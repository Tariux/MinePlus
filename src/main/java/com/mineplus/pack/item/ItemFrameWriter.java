package com.mineplus.pack.item;

import com.mineplus.util.DebugLogger;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Drives a framed item ({@link ItemFrames}) through its states at runtime by
 * writing the stack's {@code custom_model_data} value — the single number the
 * generated {@code range_dispatch} reads.
 *
 * <p><b>Two numbers, one state.</b> The modern item model system dispatches
 * on the {@code custom_model_data} component's <em>float list</em>
 * ({@code property: minecraft:custom_model_data}, {@code index: 0}), while the
 * legacy representation dispatches on the component's legacy integer. A frame
 * therefore writes both: the integer for the legacy host-file predicates, and
 * the float for the modern range dispatch. Writing only the integer leaves
 * the modern dispatch at zero, and the item renders its resting model forever
 * — a framed item that never animates.</p>
 *
 * <p>The float is written through the {@code custom_model_data} component API
 * when the running server exposes it (1.21.2+); the whole seam is reflective,
 * so one jar compiles and loads on every server version. On older servers the
 * integer alone is written, the modern dispatch does not exist there, and the
 * item keeps rendering its bare backing material — the graceful degradation
 * every pack item already applies.</p>
 *
 * <p>With {@code PACK.APPEARANCE_MODE: SELECT} the same state is additionally
 * written to the component's <em>string list</em> — the channel
 * {@code minecraft:select} reads — and that list becomes the value the writer
 * reads back as current. The float channel stays in place for every definition
 * that dispatches on it.</p>
 *
 * <p><b>The reflective seam is probed, not assumed.</b> {@code ItemMeta}
 * declares the accessor as {@code int getCustomModelData()} but the setter as
 * {@code void setCustomModelData(Integer)}; a lookup that asks for the boxed
 * return type finds nothing and silently disables the whole frame pipeline.
 * Both accessors are therefore probed against every signature the API has
 * used, and a state that fails to land is reported once instead of leaving a
 * gun that never moves its pose.</p>
 *
 * <p>Each state write triggers one slot update on the held item, so callers
 * should only write when a frame actually changes: {@link #apply} returns
 * false without touching the stack when the value already matches (it also
 * reads the current value first, so repeated calls are cheap and safe).</p>
 */
public final class ItemFrameWriter {

    private static final MethodHandle READ = resolveRead();
    private static final MethodHandle WRITE = resolveWrite();

    /** The {@code custom_model_data} component type, when the server has one. */
    private static final Class<?> COMPONENT_TYPE = resolveComponentType();
    private static final Method GET_COMPONENT = resolveItemMetaMethod("getCustomModelDataComponent");
    private static final Method SET_COMPONENT = resolveItemMetaSetter();
    private static final Method GET_FLOATS = COMPONENT_TYPE == null ? null
            : findMethod(COMPONENT_TYPE, "getFloats");
    private static final Method SET_FLOATS = COMPONENT_TYPE == null ? null
            : findMethod(COMPONENT_TYPE, "setFloats", List.class);
    private static final Method GET_STRINGS = COMPONENT_TYPE == null ? null
            : findMethod(COMPONENT_TYPE, "getStrings");
    private static final Method SET_STRINGS = COMPONENT_TYPE == null ? null
            : findMethod(COMPONENT_TYPE, "setStrings", List.class);

    /**
     * True when the pack's item definitions dispatch on
     * {@code custom_model_data.strings[0]} ({@code PACK.APPEARANCE_MODE:
     * SELECT}). Set once by the pack subsystem; the default float channel is
     * untouched when off.
     */
    private static volatile boolean STRING_STATES;

    /** The one-shot report of a seam that refuses the frame state. */
    private static final AtomicBoolean SEAM_REPORTED = new AtomicBoolean();

    private ItemFrameWriter() {
    }

    /** True when this server can carry custom-model-data values (1.14+). */
    public static boolean isSupported() {
        return READ != null || WRITE != null;
    }

    /**
     * True when the server exposes the {@code custom_model_data} component
     * API, so the modern range dispatch can see the frame state.
     */
    public static boolean supportsFloatState() {
        return COMPONENT_TYPE != null && GET_COMPONENT != null && SET_COMPONENT != null
                && GET_FLOATS != null && SET_FLOATS != null;
    }

    /**
     * True when the server can write the {@code custom_model_data} string
     * list — the channel {@code minecraft:select} reads.
     */
    public static boolean supportsStringState() {
        return COMPONENT_TYPE != null && GET_COMPONENT != null && SET_COMPONENT != null
                && GET_STRINGS != null && SET_STRINGS != null;
    }

    /**
     * Selects the definition's state channel. When enabled (the pack compiled
     * {@code SELECT} definitions), frame states are read from and written to
     * {@code custom_model_data.strings[0]}; otherwise the existing float
     * channel is used unchanged. A server without the string component keeps
     * the float channel even when enabled.
     */
    public static void setStringStates(boolean enabled) {
        STRING_STATES = enabled && supportsStringState();
    }

    /** True when frame states currently travel on the string channel. */
    public static boolean usesStringStates() {
        return STRING_STATES;
    }

    /**
     * The stack value the client renders right now — the custom-model-data
     * number, not the frame state index. {@code 0} when nothing is set.
     */
    public static int read(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !isSupported()) {
            return 0;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return 0;
        }
        return readCurrent(meta);
    }

    /**
     * Renders {@code frame} on the stack, once and only when it differs from
     * the current state. Values at or below zero select the resting state.
     * Returns true when the stack's meta changed (the caller should expect a
     * slot update and usually writes the stack back into the inventory).
     */
    @SuppressWarnings("deprecation")
    public static boolean apply(ItemStack stack, int frame) {
        if (stack == null || stack.getType().isAir() || !isSupported() || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        int state = Math.max(0, frame);
        if (readCurrent(meta) == state) {
            return false;
        }
        if (!writeState(meta, state)) {
            return false;
        }
        stack.setItemMeta(meta);
        reportSeam(meta, state);
        return true;
    }

    /**
     * The state the client renders right now. The modern dispatch reads the
     * component's float list, so that list is the source of truth wherever the
     * component API exists; the legacy integer is the fallback for servers
     * that have no component API at all.
     */
    private static int readCurrent(ItemMeta meta) {
        if (STRING_STATES) {
            Integer stringState = readStringState(meta);
            if (stringState != null) {
                return stringState;
            }
        }
        Float value = readFloatState(meta);
        if (value != null) {
            return Math.round(value);
        }
        return readLegacyState(meta);
    }

    /**
     * The state stored on the string channel, or {@code null} when the server
     * has no string component (the caller then reads the float channel).
     */
    private static Integer readStringState(ItemMeta meta) {
        if (!supportsStringState()) {
            return null;
        }
        try {
            Object component = GET_COMPONENT.invoke(meta);
            if (component == null) {
                return 0;
            }
            Object strings = GET_STRINGS.invoke(component);
            if (strings instanceof List<?> list && !list.isEmpty() && list.get(0) != null) {
                return Integer.parseInt(list.get(0).toString());
            }
            return 0;
        } catch (Throwable failure) {
            return 0;
        }
    }

    /**
     * The first float of the component's float list, or {@code null} when the
     * server has no component API (the caller then reads the legacy integer).
     */
    private static Float readFloatState(ItemMeta meta) {
        if (!supportsFloatState()) {
            return null;
        }
        try {
            Object component = GET_COMPONENT.invoke(meta);
            if (component == null) {
                return 0.0f;
            }
            Object floats = GET_FLOATS.invoke(component);
            if (floats instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Number number) {
                return number.floatValue();
            }
            return 0.0f;
        } catch (Throwable failure) {
            return 0.0f;
        }
    }

    /** The legacy custom-model-data integer of the stack. */
    private static int readLegacyState(ItemMeta meta) {
        if (READ == null) {
            return 0;
        }
        try {
            Object current = READ.invoke(meta);
            return current instanceof Number number ? number.intValue() : 0;
        } catch (Throwable failure) {
            return 0;
        }
    }

    /**
     * Writes one state into both representations. The legacy integer goes
     * first: on 1.21.4+ it lands in the component's float list as well, so
     * the component write afterwards is the authoritative one and neither
     * path can undo the other.
     */
    private static boolean writeState(ItemMeta meta, int state) {
        boolean written = writeLegacyState(meta, state);
        if (STRING_STATES && writeStringState(meta, state)) {
            written = true;
        }
        if (writeFloatState(meta, state)) {
            written = true;
        }
        return written;
    }

    /**
     * Writes the frame state into the component's string list — the value a
     * {@code minecraft:select} definition dispatches on. The number is
     * serialized as its decimal string, matching the case entries the writer
     * emits.
     */
    private static boolean writeStringState(ItemMeta meta, int state) {
        if (!supportsStringState()) {
            return false;
        }
        try {
            Object component = GET_COMPONENT.invoke(meta);
            if (component == null) {
                return false;
            }
            SET_STRINGS.invoke(component, List.of(Integer.toString(state)));
            SET_COMPONENT.invoke(meta, component);
            return true;
        } catch (Throwable failure) {
            DebugLogger.warning("[ItemFrameWriter] Could not write the frame string state: " + failure);
            return false;
        }
    }

    /** The legacy integer the host-file predicates read. */
    @SuppressWarnings("deprecation")
    private static boolean writeLegacyState(ItemMeta meta, int state) {
        if (WRITE == null) {
            return false;
        }
        try {
            WRITE.invoke(meta, state);
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * Writes the frame state into the component's float list — the number the
     * modern range dispatch reads. The legacy integer is written by the caller
     * first, so the component always exists by the time it is read here.
     */
    private static boolean writeFloatState(ItemMeta meta, int state) {
        if (!supportsFloatState()) {
            return false;
        }
        try {
            Object component = GET_COMPONENT.invoke(meta);
            if (component == null) {
                DebugLogger.warning("[ItemFrameWriter] The custom_model_data component is absent;"
                        + " the modern frame dispatch cannot see the state.");
                return false;
            }
            SET_FLOATS.invoke(component, List.of((float) state));
            SET_COMPONENT.invoke(meta, component);
            return true;
        } catch (Throwable failure) {
            DebugLogger.warning("[ItemFrameWriter] Could not write the frame float: " + failure);
            return false;
        }
    }

    /**
     * Reports a state that did not land. A frame pipeline that cannot write
     * is invisible: the item simply never changes pose. One clear console
     * line turns that silence into a diagnosable problem.
     */
    private static void reportSeam(ItemMeta meta, int state) {
        if (readCurrent(meta) == state) {
            return;
        }
        if (SEAM_REPORTED.compareAndSet(false, true)) {
            DebugLogger.warning("[ItemFrameWriter] This server does not expose the custom-model-data"
                    + " value the frame dispatch reads; framed items will keep their resting pose.");
        }
    }

    /**
     * The accessor seam. The API has declared the getter with a primitive
     * return type, so both spellings are probed: a lookup that asks for the
     * wrong one finds nothing and would silently disable every frame.
     */
    private static MethodHandle resolveRead() {
        for (MethodType type : List.of(MethodType.methodType(int.class),
                MethodType.methodType(Integer.class))) {
            MethodHandle handle = findVirtual("getCustomModelData", type);
            if (handle != null) {
                return handle.asType(MethodType.methodType(Object.class, ItemMeta.class));
            }
        }
        return null;
    }

    /** The writer seam; the setter has always taken a boxed value. */
    private static MethodHandle resolveWrite() {
        for (MethodType type : List.of(MethodType.methodType(void.class, Integer.class),
                MethodType.methodType(void.class, int.class))) {
            MethodHandle handle = findVirtual("setCustomModelData", type);
            if (handle != null) {
                return handle.asType(MethodType.methodType(void.class, ItemMeta.class, int.class));
            }
        }
        return null;
    }

    /** A virtual accessor of {@link ItemMeta}, or {@code null} when absent. */
    private static MethodHandle findVirtual(String name, MethodType type) {
        try {
            return MethodHandles.lookup().findVirtual(ItemMeta.class, name, type);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    /** The {@code custom_model_data} component interface, when the server has one. */
    private static Class<?> resolveComponentType() {
        try {
            return Class.forName("org.bukkit.inventory.meta.components.CustomModelDataComponent");
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }

    /** A no-argument {@link ItemMeta} method by name, or {@code null}. */
    private static Method resolveItemMetaMethod(String name) {
        try {
            return ItemMeta.class.getMethod(name);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    /** {@code ItemMeta#setCustomModelDataComponent(X)}, whatever X is on this server. */
    private static Method resolveItemMetaSetter() {
        for (Method method : ItemMeta.class.getMethods()) {
            if ("setCustomModelDataComponent".equals(method.getName()) && method.getParameterCount() == 1) {
                return method;
            }
        }
        return null;
    }

    /** A method of the component type by name and parameter types, or {@code null}. */
    private static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }
}
