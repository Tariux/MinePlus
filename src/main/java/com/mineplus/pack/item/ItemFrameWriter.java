package com.mineplus.pack.item;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Drives a framed item ({@link ItemFrames}) through its states at runtime by
 * writing the stack's {@code custom_model_data} value — the single integer
 * the generated {@code range_dispatch} reads.
 *
 * <p>Writes use the integer custom-model-data accessor resolved reflectively,
 * so this class compiles and loads on every server version: on 1.21.4+ the
 * value lands in the component's float list (exactly what the dispatch
 * reads); on older servers the dispatch never existed, the value is inert,
 * and the item keeps rendering its bare backing material — the graceful
 * degradation every pack item already applies.</p>
 *
 * <p>Each state write triggers one slot update on the held item, so callers
 * should only write when a frame actually changes: {@link #apply} returns
 * false without touching the stack when the value already matches (it also
 * reads the current value first, so repeated calls are cheap and safe).</p>
 */
public final class ItemFrameWriter {

    private static final MethodHandle READ = resolveRead();
    private static final MethodHandle WRITE = resolveWrite();

    private ItemFrameWriter() {
    }

    /** True when this server can carry custom-model-data values (1.14+). */
    public static boolean isSupported() {
        return READ != null && WRITE != null;
    }

    /** The currently rendered state ({@code 0} — idle — when none is set). */
    public static int read(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || READ == null) {
            return 0;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return 0;
        }
        try {
            Object value = READ.invoke(meta);
            return value instanceof Integer integer ? integer : 0;
        } catch (Throwable failure) {
            return 0;
        }
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
        try {
            WRITE.invoke(meta, state);
        } catch (Throwable failure) {
            return false;
        }
        stack.setItemMeta(meta);
        return true;
    }

    private static int readCurrent(ItemMeta meta) {
        try {
            Object value = READ.invoke(meta);
            return value instanceof Integer integer ? integer : 0;
        } catch (Throwable failure) {
            return 0;
        }
    }

    private static MethodHandle resolveRead() {
        try {
            return MethodHandles.lookup()
                    .findVirtual(ItemMeta.class, "getCustomModelData",
                            MethodType.methodType(Integer.class))
                    .asType(MethodType.methodType(Object.class, ItemMeta.class));
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private static MethodHandle resolveWrite() {
        try {
            return MethodHandles.lookup()
                    .findVirtual(ItemMeta.class, "setCustomModelData",
                            MethodType.methodType(void.class, Integer.class))
                    .asType(MethodType.methodType(void.class, ItemMeta.class, int.class));
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }
}
