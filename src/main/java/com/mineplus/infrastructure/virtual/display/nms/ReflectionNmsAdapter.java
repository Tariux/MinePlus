package com.mineplus.infrastructure.virtual.display.nms;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reflection-based {@link NmsAdapter} probing both mapping worlds at runtime:
 * Mojang-mapped runtimes (Paper 1.20.5+) resolve readable names, Spigot-mapped
 * runtimes resolve legacy packet names and obfuscated members through structural
 * probes (return types, parameter shapes) and semantic probes (packDirty clears
 * the flags it reports).
 *
 * <p>Everything is resolved once in the constructor into cached
 * {@link MethodHandle}s / {@link Constructor}s; hot paths only pay for
 * {@code invoke}. No NMS type is referenced at compile time.</p>
 */
final class ReflectionNmsAdapter implements NmsAdapter {

    private final String versionTag;

    private final MethodHandle craftPlayerGetHandle;
    private final MethodHandle entityGetEntityData;
    private final MethodHandle entityGetType;
    private final MethodHandle entityMoveTo;
    private final MethodHandle packDirty;
    private final MethodHandle nonDefaultValues;
    private final MethodHandle asNmsCopy;
    private final MethodHandle sendPacket;

    private final Constructor<?> addEntityCtor;
    private final Constructor<?> metadataCtor;
    private final Constructor<?> teleportCtor;
    private final Constructor<?> rotCtor;
    private final Constructor<?> destroyCtor;
    private final Constructor<?> mountCtor;
    private final Constructor<?> equipmentCtor;
    private final Constructor<?> bundleCtor;
    private final Constructor<?> friendlyBufCtor;
    private final Constructor<?> pairCtorClass;

    private final MethodHandle bufWriteVarInt;
    private final MethodHandle bufWriteVarIntArray;

    private final Object vec3Zero;
    private final Class<?> enumItemSlotClass;
    private final Field connectionField;

    ReflectionNmsAdapter() {
        boolean mojang = probe("net.minecraft.network.protocol.game.ClientboundAddEntityPacket");
        this.versionTag = serverVersion() + (mojang ? " (mojang-mapped)" : " (spigot-mapped)");

        MethodHandles.Lookup lookup = MethodHandles.lookup();

        try {
            Class<?> craftPlayerClass = resolveCraftClass("entity.CraftPlayer");
            this.craftPlayerGetHandle = lookup.unreflect(method(craftPlayerClass, "getHandle"));

            Class<?> entityClass = cls("net.minecraft.world.entity.Entity");
            Class<?> entityTypesClass = mojang ? cls("net.minecraft.world.entity.EntityType") : cls("net.minecraft.world.entity.EntityTypes");
            Class<?> vec3Class = mojang ? cls("net.minecraft.world.phys.Vec3") : cls("net.minecraft.world.phys.Vec3D");
            Class<?> dataWatcherClass = mojang ? cls("net.minecraft.network.syncher.SynchedEntityData") : cls("net.minecraft.network.syncher.DataWatcher");

            this.entityGetEntityData = lookup.unreflect(findMethodByReturnType(entityClass, dataWatcherClass));
            this.entityGetType = lookup.unreflect(findMethodByReturnType(entityClass, entityTypesClass));
            this.entityMoveTo = lookup.unreflect(findMoveTo(entityClass));

            Method[] watcher = resolveDataWatcherMethods(dataWatcherClass, findMethodByReturnType(entityClass, dataWatcherClass));
            this.nonDefaultValues = lookup.unreflect(watcher[0]);
            this.packDirty = lookup.unreflect(watcher[1]);

            Class<?> addPacket = mojang ? cls("net.minecraft.network.protocol.game.ClientboundAddEntityPacket") : cls("net.minecraft.network.protocol.game.PacketPlayOutSpawnEntity");
            this.addEntityCtor = findCtorByShape(addPacket, int.class, UUID.class, double.class, double.class, double.class, float.class, float.class, entityTypesClass, int.class, vec3Class, double.class);

            Class<?> metaPacket = mojang ? cls("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket") : cls("net.minecraft.network.protocol.game.PacketPlayOutEntityMetadata");
            this.metadataCtor = findCtorByShape(metaPacket, int.class, List.class);

            Class<?> teleportPacket = mojang ? cls("net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket") : cls("net.minecraft.network.protocol.game.PacketPlayOutEntityTeleport");
            this.teleportCtor = findCtorByShape(teleportPacket, entityClass);

            Class<?> rotPacket = mojang ? cls("net.minecraft.network.protocol.game.ClientboundMoveEntityPacket$Rot") : cls("net.minecraft.network.protocol.game.PacketPlayOutEntity$PacketPlayOutEntityLook");
            this.rotCtor = findCtorByShape(rotPacket, int.class, byte.class, byte.class, boolean.class);

            Class<?> destroyPacket = mojang ? cls("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket") : cls("net.minecraft.network.protocol.game.PacketPlayOutEntityDestroy");
            this.destroyCtor = findCtorByShape(destroyPacket, int[].class);

            Class<?> mountPacket = mojang ? cls("net.minecraft.network.protocol.game.ClientboundSetPassengersPacket") : cls("net.minecraft.network.protocol.game.PacketPlayOutMount");
            this.mountCtor = findMountCtor(mountPacket, mojang);

            Class<?> equipPacket = mojang ? cls("net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket") : cls("net.minecraft.network.protocol.game.PacketPlayOutEntityEquipment");
            this.equipmentCtor = findCtorByShape(equipPacket, int.class, List.class);

            Class<?> bundlePacket = cls("net.minecraft.network.protocol.game.ClientboundBundlePacket");
            this.bundleCtor = findBundleCtor(bundlePacket);

            Class<?> friendlyBufClass = mojang ? cls("net.minecraft.network.FriendlyByteBuf") : cls("net.minecraft.network.PacketDataSerializer");
            Class<?> byteBufClass = cls("io.netty.buffer.ByteBuf");
            this.friendlyBufCtor = accessible(newCtor(friendlyBufClass, byteBufClass));

            this.bufWriteVarInt = lookup.unreflect(mojang ? method(friendlyBufClass, "writeVarInt", int.class) : method(friendlyBufClass, "c", int.class));
            this.bufWriteVarIntArray = lookup.unreflect(mojang ? method(friendlyBufClass, "writeVarIntArray", int[].class) : method(friendlyBufClass, "a", int[].class));

            this.vec3Zero = vec3Ctor(vec3Class).newInstance(0.0, 0.0, 0.0);
            this.pairCtorClass = accessible(newCtor(cls("com.mojang.datafixers.util.Pair"), Object.class, Object.class));
            this.enumItemSlotClass = mojang ? cls("net.minecraft.world.entity.EquipmentSlot") : cls("net.minecraft.world.entity.EnumItemSlot");

            Class<?> craftItemStackClass = resolveCraftClass("inventory.CraftItemStack");
            this.asNmsCopy = lookup.unreflect(method(craftItemStackClass, "asNMSCopy", ItemStack.class));

            this.sendPacket = lookup.unreflect(findSendPacketMethod());
            this.connectionField = findConnectionField();

        } catch (Throwable t) {
            throw new IllegalStateException("Failed to initialize MethodHandles for NMS adapter", t);
        }
    }

    @Override
    public String version() { return versionTag; }

    @Override
    public Object spawnPacket(Entity entity) {
        try {
            Object handle = handle(entity);
            var loc = entity.getLocation();
            return addEntityCtor.newInstance(entity.getEntityId(), entity.getUniqueId(), loc.getX(), loc.getY(), loc.getZ(),
                    loc.getPitch(), loc.getYaw(), entityGetType.invoke(handle), 0, vec3Zero, (double) loc.getYaw());
        } catch (Throwable t) { throw new IllegalStateException(t); }
    }

    @Override
    public Object metadataPacket(Entity entity, boolean onlyDirty) {
        try {
            Object watcher = entityGetEntityData.invoke(handle(entity));
            @SuppressWarnings("unchecked")
            List<Object> values = (List<Object>) (onlyDirty ? packDirty.invoke(watcher) : nonDefaultValues.invoke(watcher));
            if (values == null || values.isEmpty()) return null;
            return metadataCtor.newInstance(entity.getEntityId(), values);
        } catch (Throwable t) { throw new IllegalStateException(t); }
    }

    @Override
    public Object teleportPacket(Entity entity) {
        try { return teleportCtor.newInstance(handle(entity)); }
        catch (Throwable t) { throw new IllegalStateException(t); }
    }

    @Override
    public Object rotationPacket(Entity entity) {
        var loc = entity.getLocation();
        try { return rotCtor.newInstance(entity.getEntityId(), packAngle(loc.getYaw()), packAngle(loc.getPitch()), true); }
        catch (Throwable t) { throw new IllegalStateException(t); }
    }

    @Override
    public Object removePacket(int... entityIds) {
        try { return destroyCtor.newInstance((Object) entityIds); }
        catch (Throwable t) { throw new IllegalStateException(t); }
    }

    @Override
    public Object passengersPacket(int vehicleId, int... passengerIds) {
        Object buf = newBuffer();
        try {
            bufWriteVarInt.invoke(buf, vehicleId);
            bufWriteVarIntArray.invoke(buf, passengerIds);
            return mountCtor.newInstance(buf);
        } catch (Throwable t) { throw new IllegalStateException(t); }
    }

    @Override
    public Object equipmentPacket(int entityId, EquipmentSlot slot, ItemStack stack) {
        try {
            Object nmsSlot = enumItemSlotClass.getEnumConstants()[slot.ordinal()];
            Object nmsItem = asNmsCopy.invoke(stack == null ? new ItemStack(Material.AIR) : stack);
            Object pair = pairCtorClass.newInstance(nmsSlot, nmsItem);
            return equipmentCtor.newInstance(entityId, List.of(pair));
        } catch (Throwable t) { throw new IllegalStateException(t); }
    }

    @Override
    public Object bundle(List<Object> packets) {
        try { return bundleCtor.newInstance((Object) new ArrayList<>(packets)); }
        catch (Throwable t) { throw new IllegalStateException(t); }
    }

    @Override
    public void send(Player player, Object packet) {
        if (packet == null || player == null) return;
        try {
            Object handle = craftPlayerGetHandle.invoke(player);
            Object connection = connectionField.get(handle);
            sendPacket.invoke(connection, packet);
        } catch (Throwable t) { throw new IllegalStateException(t); }
    }

    @Override
    public void setPositionAndRotation(Entity entity, double x, double y, double z, float yaw, float pitch) {
        try { entityMoveTo.invoke(handle(entity), x, y, z, yaw, pitch); }
        catch (Throwable t) { throw new IllegalStateException(t); }
    }

    private Object handle(Entity entity) throws Throwable {
        MethodHandle getHandle = getHandleCache.computeIfAbsent(entity.getClass(), c -> {
            try { return MethodHandles.lookup().unreflect(findGetHandle(c.asSubclass(Entity.class))); }
            catch (IllegalAccessException e) { throw new RuntimeException(e); }
        });
        return getHandle.invoke(entity);
    }
    private static final java.util.Map<Class<?>, MethodHandle> getHandleCache = new java.util.concurrent.ConcurrentHashMap<>();

    // Re-use rest of the reflection utilities (findGetHandle, resolveCraftClass, etc.) unchanged
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
        if (world == null) throw fail("cannot probe DataWatcher methods before worlds load");
        BlockDisplay scratch = world.createEntity(world.getSpawnLocation(), BlockDisplay.class);
        scratch.setBlock(Material.STONE.createBlockData());

        Method getHandle = findGetHandle(scratch.getClass());
        Method nonDefault = null, pack = null;
        try {
            Object watcher = getEntityData.invoke(getHandle.invoke(scratch));
            for (Method m : dataWatcherClass.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || !List.class.isAssignableFrom(m.getReturnType())) continue;
                accessible(m);
                try {
                    Object first = m.invoke(watcher);
                    Object second = m.invoke(watcher);
                    if (first != null && !((List<?>) first).isEmpty() && second != null && !((List<?>) second).isEmpty()) nonDefault = m;
                    else if (first != null && !((List<?>) first).isEmpty() && (second == null || ((List<?>) second).isEmpty())) pack = m;
                } catch (ReflectiveOperationException ignored) {}
            }
        } catch (ReflectiveOperationException e) { throw fail("DataWatcher probe failed: " + e.getMessage()); }
        if (nonDefault == null || pack == null) throw fail("could not identify DataWatcher semantics");
        return new Method[]{nonDefault, pack};
    }

    private static Method findGetHandle(Class<? extends Entity> craftClass) {
        for (Class<?> c = craftClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) if (m.getName().equals("getHandle") && m.getParameterCount() == 0) return accessible(m);
        }
        throw fail("getHandle not found");
    }

    private Method findSendPacketMethod() {
        Class<?> connectionClass = connectionCandidate();
        try { return accessible(connectionClass.getMethod("sendPacket", packetClass())); }
        catch (NoSuchMethodException e) { throw fail("sendPacket not found"); }
    }

    private Field findConnectionField() {
        // Walk up from the ServerPlayer handle class looking for the packet-listener
        // field. Works on versioned (spigot) and unversioned (paper 1.20.5+) packages.
        Class<?> handleClass = craftPlayerGetHandle.type().returnType();
        for (Class<?> c = handleClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.getType().getMethod("sendPacket", packetClass());
                    return accessible(f);
                } catch (NoSuchMethodException ignored) {}
            }
        }
        throw fail("connection field not found");
    }

    private Class<?> connectionCandidate() { return findConnectionField().getType(); }
    private static Class<?> packetClassCache;
    private static Class<?> packetClass() {
        if (packetClassCache == null) try { packetClassCache = Class.forName("net.minecraft.network.protocol.Packet"); } catch (ClassNotFoundException e) { throw fail("Packet not found"); }
        return packetClassCache;
    }

    private Constructor<?> findMountCtor(Class<?> mountPacket, boolean mojang) {
        Class<?> bufClass = mojang
                ? cls("net.minecraft.network.FriendlyByteBuf")
                : cls("net.minecraft.network.PacketDataSerializer");
        for (Constructor<?> c : mountPacket.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 1 && bufClass.equals(p[0])) return accessible(c);
        }
        // 1.21.2+ mojang: codec-only packet, decode through the stream codec buffer
        Class<?> entityClass = cls("net.minecraft.world.entity.Entity");
        for (Constructor<?> c : mountPacket.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 1 && !p[0].equals(entityClass)) return accessible(c);
        }
        throw fail("mount packet constructor not found");
    }
    private Method findMethodByReturnType(Class<?> owner, Class<?> returnType) {
        for (Method m : owner.getDeclaredMethods()) if (m.getParameterCount() == 0 && m.getReturnType() == returnType) return accessible(m);
        throw fail("no zero-arg method");
    }
    private static Method findMoveTo(Class<?> owner) {
        for (Method m : owner.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 5 && p[0] == double.class && p[1] == double.class && p[2] == double.class
                    && p[3] == float.class && p[4] == float.class) return accessible(m);
        }
        throw fail("moveTo not found");
    }
    private Constructor<?> findCtorByShape(Class<?> owner, Class<?>... params) {
        for (Constructor<?> c : owner.getDeclaredConstructors()) {
            if (java.util.Arrays.equals(c.getParameterTypes(), params)) return accessible(c);
        }
        throw fail("constructor shape not found");
    }
    private Constructor<?> findBundleCtor(Class<?> bundlePacket) {
        for (Constructor<?> c : bundlePacket.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 1 && Iterable.class.isAssignableFrom(p[0])) return accessible(c);
        }
        throw fail("bundle ctor not found");
    }
    private Constructor<?> vec3Ctor(Class<?> vec3Class) {
        try { return accessible(vec3Class.getDeclaredConstructor(double.class, double.class, double.class)); }
        catch (NoSuchMethodException e) { throw fail("Vec3 ctor not found"); }
    }
    private Object newBuffer() {
        try {
            return friendlyBufCtor.newInstance(Class.forName("io.netty.buffer.Unpooled").getMethod("buffer").invoke(null));
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private Class<?> resolveCraftClass(String suffix) {
        try { return Class.forName("org.bukkit.craftbukkit." + suffix); }
        catch (ClassNotFoundException direct) {
            String versioned = Bukkit.getServer().getClass().getPackage().getName();
            try { return Class.forName(versioned + "." + suffix); }
            catch (ClassNotFoundException e) { throw fail("craft class not found"); }
        }
    }
    private static String serverVersion() { return Bukkit.getVersion(); }
    private static byte packAngle(float angle) { return (byte) (int) Math.floor(angle * 256.0f / 360.0f); }
    private static boolean probe(String className) { try { Class.forName(className); return true; } catch (ClassNotFoundException e) { return false; } }
    private static Class<?> cls(String name) { try { return Class.forName(name); } catch (ClassNotFoundException e) { throw fail(name); } }
    private static Method method(Class<?> owner, String name, Class<?>... params) { try { return accessible(owner.getMethod(name, params)); } catch (NoSuchMethodException e) { throw fail(name); } }
    private static Constructor<?> newCtor(Class<?> owner, Class<?>... params) { try { return owner.getDeclaredConstructor(params); } catch (NoSuchMethodException e) { throw fail("ctor"); } }
    @SuppressWarnings("unchecked") private static <T extends java.lang.reflect.AccessibleObject> T accessible(T object) { object.setAccessible(true); return object; }
    private static IllegalStateException fail(String message) { return new IllegalStateException("[Mineplus] NMS Failure: " + message); }
}
