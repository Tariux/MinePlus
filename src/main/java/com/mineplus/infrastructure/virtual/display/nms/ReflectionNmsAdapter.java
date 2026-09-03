package com.mineplus.infrastructure.virtual.display.nms;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reflection-based {@link NmsAdapter} probing both mapping worlds at runtime:
 * <ul>
 *   <li>Mojang-mapped runtimes (Paper 1.20.5+): readable names
 *       ({@code ClientboundAddEntityPacket}, {@code SynchedEntityData#packDirty}).</li>
 *   <li>Spigot-mapped runtimes (Spigot 1.20.5+/1.21.x): legacy packet names
 *       ({@code PacketPlayOutSpawnEntity}, {@code DataWatcher}) and obfuscated
 *       members, resolved through structural probes (return types, parameter shapes)
 *       and semantic probes (packDirty clears the flags it reports).</li>
 * </ul>
 *
 * <p>Everything is resolved once in the constructor and cached; hot paths only pay
 * for {@code Method.invoke}. No NMS type is referenced at compile time.</p>
 */
final class ReflectionNmsAdapter implements NmsAdapter {

    private final String versionTag;

    private final Class<?> craftPlayerClass;
    private final Method craftPlayerGetHandle;

    private final Class<?> entityClass;
    private final Method entityGetEntityData;
    private final Method entityGetType;
    private final Method entityMoveTo;

    private final Method packDirty;
    private final Method nonDefaultValues;

    private final Constructor<?> addEntityCtor;
    private final Constructor<?> metadataCtor;
    private final Constructor<?> teleportCtor;
    private final Constructor<?> rotCtor;
    private final Constructor<?> destroyCtor;
    private final Constructor<?> mountCtor;
    private final Constructor<?> equipmentCtor;
    private final Constructor<?> bundleCtor;

    private final Class<?> friendlyBufClass;
    private final Constructor<?> friendlyBufCtor;
    private final Method bufWriteVarInt;
    private final Method bufWriteVarIntArray;

    private final Object vec3Zero;
    private final Class<?> entityTypesClass;
    private final Class<?> vec3Class;

    private final Class<?> enumItemSlotClass;
    private final Constructor<?> pairCtorClass;
    private final Method asNmsCopy;

    private final Method sendPacket;
    private final Field connectionField;

    ReflectionNmsAdapter() {
        boolean mojang = probe("net.minecraft.network.protocol.game.ClientboundAddEntityPacket");
        this.versionTag = serverVersion() + (mojang ? " (mojang-mapped)" : " (spigot-mapped)");

        this.craftPlayerClass = resolveCraftClass("entity.CraftPlayer");
        this.craftPlayerGetHandle = method(craftPlayerClass, "getHandle");

        this.entityClass = cls("net.minecraft.world.entity.Entity");
        this.entityTypesClass = mojang
                ? cls("net.minecraft.world.entity.EntityType")
                : cls("net.minecraft.world.entity.EntityTypes");
        this.vec3Class = mojang
                ? cls("net.minecraft.world.phys.Vec3")
                : cls("net.minecraft.world.phys.Vec3D");
        Class<?> dataWatcherClass = mojang
                ? cls("net.minecraft.network.syncher.SynchedEntityData")
                : cls("net.minecraft.network.syncher.DataWatcher");

        this.entityGetEntityData = findMethodByReturnType(entityClass, dataWatcherClass);
        this.entityGetType = findMethodByReturnType(entityClass, entityTypesClass);
        this.entityMoveTo = findMoveTo(entityClass);

        Method[] watcher = resolveDataWatcherMethods(dataWatcherClass, entityGetEntityData);
        this.nonDefaultValues = watcher[0];
        this.packDirty = watcher[1];

        Class<?> addPacket = mojang
                ? cls("net.minecraft.network.protocol.game.ClientboundAddEntityPacket")
                : cls("net.minecraft.network.protocol.game.PacketPlayOutSpawnEntity");
        this.addEntityCtor = findCtorByShape(addPacket, int.class, UUID.class, double.class, double.class,
                double.class, float.class, float.class, entityTypesClass, int.class, vec3Class, double.class);

        Class<?> metaPacket = mojang
                ? cls("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket")
                : cls("net.minecraft.network.protocol.game.PacketPlayOutEntityMetadata");
        this.metadataCtor = findCtorByShape(metaPacket, int.class, List.class);

        Class<?> teleportPacket = mojang
                ? cls("net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket")
                : cls("net.minecraft.network.protocol.game.PacketPlayOutEntityTeleport");
        this.teleportCtor = findCtorByShape(teleportPacket, entityClass);

        Class<?> rotPacket = mojang
                ? cls("net.minecraft.network.protocol.game.ClientboundMoveEntityPacket$Rot")
                : cls("net.minecraft.network.protocol.game.PacketPlayOutEntity$PacketPlayOutEntityLook");
        this.rotCtor = findCtorByShape(rotPacket, int.class, byte.class, byte.class, boolean.class);

        Class<?> destroyPacket = mojang
                ? cls("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket")
                : cls("net.minecraft.network.protocol.game.PacketPlayOutEntityDestroy");
        this.destroyCtor = findCtorByShape(destroyPacket, int[].class);

        Class<?> mountPacket = mojang
                ? cls("net.minecraft.network.protocol.game.ClientboundSetPassengersPacket")
                : cls("net.minecraft.network.protocol.game.PacketPlayOutMount");
        this.mountCtor = findMountCtor(mountPacket, mojang);

        Class<?> equipPacket = mojang
                ? cls("net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket")
                : cls("net.minecraft.network.protocol.game.PacketPlayOutEntityEquipment");
        this.equipmentCtor = findCtorByShape(equipPacket, int.class, List.class);

        Class<?> bundlePacket = cls("net.minecraft.network.protocol.game.ClientboundBundlePacket");
        this.bundleCtor = findBundleCtor(bundlePacket);

        this.friendlyBufClass = mojang
                ? cls("net.minecraft.network.FriendlyByteBuf")
                : cls("net.minecraft.network.PacketDataSerializer");
        Class<?> byteBufClass = cls("io.netty.buffer.ByteBuf");
        this.friendlyBufCtor = accessible(newCtor(friendlyBufClass, byteBufClass));
        this.bufWriteVarInt = mojang
                ? method(friendlyBufClass, "writeVarInt", int.class)
                : method(friendlyBufClass, "c", int.class);
        this.bufWriteVarIntArray = mojang
                ? method(friendlyBufClass, "writeVarIntArray", int[].class)
                : method(friendlyBufClass, "a", int[].class);

        this.vec3Zero = newVec3(0.0, 0.0, 0.0);

        this.pairCtorClass = accessible(newCtor(cls("com.mojang.datafixers.util.Pair"), Object.class, Object.class));
        this.enumItemSlotClass = mojang
                ? cls("net.minecraft.world.entity.EquipmentSlot")
                : cls("net.minecraft.world.entity.EnumItemSlot");

        Class<?> craftItemStackClass = resolveCraftClass("inventory.CraftItemStack");
        this.asNmsCopy = method(craftItemStackClass, "asNMSCopy", ItemStack.class);

        this.sendPacket = findSendPacketMethod();
        this.connectionField = findConnectionField();
    }

    // ------------------------------------------------------------------ interface

    @Override
    public String version() {
        return versionTag;
    }

    @Override
    public Object spawnPacket(Entity entity) {
        Object handle = handle(entity);
        var loc = entity.getLocation();
        try {
            return addEntityCtor.newInstance(
                    entity.getEntityId(),
                    entity.getUniqueId(),
                    loc.getX(), loc.getY(), loc.getZ(),
                    loc.getPitch(), loc.getYaw(),
                    entityGetType.invoke(handle),
                    0,
                    vec3Zero,
                    (double) loc.getYaw());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("spawnPacket failed", e);
        }
    }

    @Override
    public Object metadataPacket(Entity entity, boolean onlyDirty) {
        Object handle = handle(entity);
        try {
            Object watcher = entityGetEntityData.invoke(handle);
            @SuppressWarnings("unchecked")
            List<Object> values = (List<Object>) (onlyDirty
                    ? packDirty.invoke(watcher)
                    : nonDefaultValues.invoke(watcher));
            if (values == null || values.isEmpty()) {
                return null;
            }
            return metadataCtor.newInstance(entity.getEntityId(), values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("metadataPacket failed", e);
        }
    }

    @Override
    public Object teleportPacket(Entity entity) {
        try {
            return teleportCtor.newInstance(handle(entity));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("teleportPacket failed", e);
        }
    }

    @Override
    public Object rotationPacket(Entity entity) {
        var loc = entity.getLocation();
        try {
            return rotCtor.newInstance(
                    entity.getEntityId(),
                    packAngle(loc.getYaw()),
                    packAngle(loc.getPitch()),
                    true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("rotationPacket failed", e);
        }
    }

    @Override
    public Object removePacket(int... entityIds) {
        try {
            return destroyCtor.newInstance((Object) entityIds);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("removePacket failed", e);
        }
    }

    @Override
    public Object passengersPacket(int vehicleId, int... passengerIds) {
        Object buf = newBuffer();
        try {
            bufWriteVarInt.invoke(buf, vehicleId);
            bufWriteVarIntArray.invoke(buf, passengerIds);
            return mountCtor.newInstance(buf);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("passengersPacket failed", e);
        } finally {
            release(buf);
        }
    }

    @Override
    public Object equipmentPacket(int entityId, EquipmentSlot slot, ItemStack stack) {
        try {
            Object nmsSlot = enumItemSlotClass.getEnumConstants()[slot.ordinal()];
            Object nmsItem = asNmsCopy.invoke(null, stack == null ? new ItemStack(Material.AIR) : stack);
            Object pair = pairCtorClass.newInstance(nmsSlot, nmsItem);
            return equipmentCtor.newInstance(entityId, List.of(pair));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("equipmentPacket failed", e);
        }
    }

    @Override
    public Object bundle(List<Object> packets) {
        try {
            return bundleCtor.newInstance((Object) new ArrayList<>(packets));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("bundle failed", e);
        }
    }

    @Override
    public void send(Player player, Object packet) {
        if (packet == null || player == null) {
            return;
        }
        try {
            Object handle = craftPlayerGetHandle.invoke(player);
            Object connection = connectionField.get(handle);
            sendPacket.invoke(connection, packet);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("send failed", e);
        }
    }

    @Override
    public void setPositionAndRotation(Entity entity, double x, double y, double z, float yaw, float pitch) {
        try {
            entityMoveTo.invoke(handle(entity), x, y, z, yaw, pitch);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("setPositionAndRotation failed", e);
        }
    }

    // ------------------------------------------------------------------ data watcher probe

    /**
     * Distinguishes {@code packDirty} from {@code getNonDefaultValues} without trusting
     * obfuscated names: getNonDefaultValues is idempotent and returns the same non-empty
     * list on repeated calls, while packDirty clears the flags and returns null/empty on
     * the second call. Probed once against a scratch BlockDisplay carrying dirty data.
     */
    private static Method[] resolveDataWatcherMethods(Class<?> dataWatcherClass, Method getEntityData) {
        try {
            return new Method[]{
                    accessible(dataWatcherClass.getDeclaredMethod("getNonDefaultValues")),
                    accessible(dataWatcherClass.getDeclaredMethod("packDirty"))
            };
        } catch (NoSuchMethodException mojangNamesAbsent) {
            // spigot-mapped: semantic probe below
        }

        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (world == null) {
            throw fail("cannot probe DataWatcher methods before worlds load");
        }
        BlockDisplay scratch = world.createEntity(world.getSpawnLocation(), BlockDisplay.class);
        scratch.setBlock(Material.STONE.createBlockData());

        Method getHandle = findGetHandle(scratch.getClass());
        Method nonDefault = null;
        Method pack = null;
        try {
            Object watcher = getEntityData.invoke(getHandle.invoke(scratch));
            for (Method m : dataWatcherClass.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || !List.class.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                accessible(m);
                try {
                    Object first = m.invoke(watcher);
                    Object second = m.invoke(watcher);
                    if (first != null && !((List<?>) first).isEmpty()
                            && second != null && !((List<?>) second).isEmpty()) {
                        nonDefault = m;                                    // idempotent non-empty
                    } else if (first != null && !((List<?>) first).isEmpty()
                            && (second == null || ((List<?>) second).isEmpty())) {
                        pack = m;                                           // cleared by the first call
                    }
                } catch (ReflectiveOperationException ignored) {
                    // not one of the two serializers
                }
            }
        } catch (ReflectiveOperationException e) {
            throw fail("DataWatcher probe failed: " + e.getMessage());
        }
        if (nonDefault == null || pack == null) {
            throw fail("could not identify DataWatcher packDirty/getNonDefaultValues by semantics");
        }
        return new Method[]{nonDefault, pack};
    }

    // ------------------------------------------------------------------ helpers

    private Object handle(Entity entity) {
        try {
            Method getHandle = getHandleCache.computeIfAbsent(entity.getClass(),
                    c -> findGetHandle(c.asSubclass(Entity.class)));
            return getHandle.invoke(entity);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("getHandle failed for " + entity.getClass(), e);
        }
    }

    private static final java.util.Map<Class<?>, Method> getHandleCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static Method findGetHandle(Class<? extends Entity> craftClass) {
        for (Class<?> c = craftClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("getHandle") && m.getParameterCount() == 0) {
                    return accessible(m);
                }
            }
        }
        throw fail("getHandle not found on " + craftClass.getName());
    }

    private Method findSendPacketMethod() {
        Class<?> connectionClass = connectionCandidate();
        try {
            return accessible(connectionClass.getMethod("sendPacket", packetClass()));
        } catch (NoSuchMethodException e) {
            throw fail("sendPacket(Packet) not found on " + connectionClass.getName());
        }
    }

    private Field findConnectionField() {
        Class<?> handleClass = craftPlayerGetHandle.getReturnType();
        for (Class<?> c = handleClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (hasSendPacket(f.getType())) {
                    try {
                        return accessible(f);
                    } catch (Exception ignored) {
                        // next candidate
                    }
                }
            }
        }
        throw fail("connection field not found on " + handleClass.getName());
    }

    private static boolean hasSendPacket(Class<?> type) {
        try {
            type.getMethod("sendPacket", packetClass());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private Class<?> connectionCandidate() {
        for (Class<?> c = craftPlayerGetHandle.getReturnType(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("sendPacket") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].getName().endsWith("Packet")) {
                    return c;
                }
            }
        }
        return craftPlayerGetHandle.getReturnType();
    }

    private static Class<?> packetClassCache;

    private static Class<?> packetClass() {
        if (packetClassCache == null) {
            try {
                packetClassCache = Class.forName("net.minecraft.network.protocol.Packet");
            } catch (ClassNotFoundException e) {
                throw fail("net.minecraft.network.protocol.Packet not found");
            }
        }
        return packetClassCache;
    }

    private Constructor<?> findMountCtor(Class<?> mountPacket, boolean mojang) {
        for (Constructor<?> c : mountPacket.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 1 && friendlyBuf(mojang).equals(p[0])) {
                return accessible(c);
            }
        }
        // 1.21.2+ mojang: codec-only packet, decode through the stream codec buffer
        for (Constructor<?> c : mountPacket.getDeclaredConstructors()) {
            if (c.getParameterTypes().length == 1
                    && !c.getParameterTypes()[0].equals(entityClass)) {
                return accessible(c);
            }
        }
        throw fail("mount packet buffer constructor not found");
    }

    private Class<?> friendlyBuf(boolean mojang) {
        return mojang
                ? cls("net.minecraft.network.FriendlyByteBuf")
                : cls("net.minecraft.network.PacketDataSerializer");
    }

    private Method findMethodByReturnType(Class<?> owner, Class<?> returnType) {
        for (Method m : owner.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == returnType) {
                return accessible(m);
            }
        }
        throw fail("no zero-arg method on " + owner.getSimpleName() + " returning " + returnType.getSimpleName());
    }

    private static Method findMoveTo(Class<?> owner) {
        for (Method m : owner.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 5 && p[0] == double.class && p[1] == double.class && p[2] == double.class
                    && p[3] == float.class && p[4] == float.class) {
                return accessible(m);
            }
        }
        throw fail("Entity.moveTo equivalent not found");
    }

    private Constructor<?> findCtorByShape(Class<?> owner, Class<?>... params) {
        for (Constructor<?> c : owner.getDeclaredConstructors()) {
            Class<?>[] shape = c.getParameterTypes();
            if (shape.length != params.length) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < shape.length; i++) {
                if (!shape[i].equals(params[i])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return accessible(c);
            }
        }
        throw fail("constructor " + owner.getSimpleName() + "(" + simpleNames(params) + ") not found");
    }

    private Constructor<?> findBundleCtor(Class<?> bundlePacket) {
        for (Constructor<?> c : bundlePacket.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 1 && Iterable.class.isAssignableFrom(p[0])) {
                return accessible(c);
            }
        }
        throw fail("bundle packet constructor not found");
    }

    private Object newVec3(double x, double y, double z) {
        try {
            return vec3Ctor().newInstance(x, y, z);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Vec3 construction failed", e);
        }
    }

    private Constructor<?> vec3Ctor() {
        try {
            return accessible(vec3Class.getDeclaredConstructor(double.class, double.class, double.class));
        } catch (NoSuchMethodException e) {
            throw fail("Vec3 ctor not found");
        }
    }

    private Object newBuffer() {
        try {
            Class<?> unpooled = Class.forName("io.netty.buffer.Unpooled");
            Object buf = unpooled.getMethod("buffer").invoke(null);
            return friendlyBufCtor.newInstance(buf);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("buffer creation failed", e);
        }
    }

    private static void release(Object buf) {
        try {
            buf.getClass().getMethod("release").invoke(buf);
        } catch (ReflectiveOperationException ignored) {
            // released by GC if the method is absent - safe
        }
    }

    private Class<?> resolveCraftClass(String suffix) {
        Exception direct;
        try {
            return Class.forName("org.bukkit.craftbukkit." + suffix);
        } catch (ClassNotFoundException first) {
            direct = first;
        }
        String versioned = Bukkit.getServer().getClass().getPackage().getName();
        try {
            return Class.forName(versioned + "." + suffix);
        } catch (ClassNotFoundException e) {
            throw fail("craft class " + suffix + " not found: " + direct.getMessage());
        }
    }

    private static String serverVersion() {
        try {
            return Bukkit.getMinecraftVersion();
        } catch (Throwable missingPaperApi) {
            String pkg = Bukkit.getServer().getClass().getPackage().getName();
            int idx = pkg.lastIndexOf('.');
            return idx < 0 ? "unknown" : pkg.substring(idx + 1);
        }
    }

    private static byte packAngle(float angle) {
        return (byte) (int) Math.floor(angle * 256.0f / 360.0f);
    }

    private static boolean probe(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static Class<?> cls(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw fail("class not found: " + name);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... params) {
        try {
            return accessible(owner.getMethod(name, params));
        } catch (NoSuchMethodException e) {
            throw fail("method " + owner.getSimpleName() + "#" + name + " not found");
        }
    }

    private static Constructor<?> newCtor(Class<?> owner, Class<?>... params) {
        try {
            return owner.getDeclaredConstructor(params);
        } catch (NoSuchMethodException e) {
            throw fail("constructor " + owner.getSimpleName() + "(" + simpleNames(params) + ") not found");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends java.lang.reflect.AccessibleObject> T accessible(T object) {
        object.setAccessible(true);
        return object;
    }

    private static String simpleNames(Class<?>... params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(params[i].getSimpleName());
        }
        return sb.toString();
    }

    private static IllegalStateException fail(String message) {
        return new IllegalStateException("[Mineplus] display transport: " + message
                + " - this server build is unsupported");
    }
}
