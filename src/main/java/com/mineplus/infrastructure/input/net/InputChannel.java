package com.mineplus.infrastructure.input.net;

import com.mineplus.util.DebugLogger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Optional inbound input fast path: reads swing/use/interact packets when they
 * leave the network, normalizes them into gestures and hands them to the tick
 * loop instead of waiting for the next Bukkit event.
 *
 * <p><b>Nothing is enabled by default.</b> With {@code INPUT.FAST_PATH: false}
 * this class is never constructed, no reflection runs and no channel is
 * touched. When enabled it locates the player's Netty channel reflectively
 * (structural field walk from the NMS handle, resolved once), appends exactly
 * one handler named {@value #HANDLER_NAME} and leaves every other handler in
 * the pipeline untouched. The handler is removed on quit and on disable.</p>
 *
 * <p><b>Threading.</b> The Netty handler only reads the packet's class name and
 * enqueues a sample into a bounded {@link InputPacketQueue}; it never calls
 * Bukkit, never touches a world and never invokes gameplay code. One main-thread
 * task drains the queue at tick start and hands each sample to the sink, which
 * routes it through the same trigger fold the event path uses.</p>
 *
 * <p><b>Failure is contained.</b> Any reflection or pipeline failure logs once,
 * removes what was installed and permanently disables the fast path for the
 * session; the normal event path keeps working untouched.</p>
 */
public final class InputChannel implements Listener {

    public static final String HANDLER_NAME = "mineplus_input";

    /** Main-thread samples handled per tick; the queue itself is bounded far lower. */
    private static final int DRAIN_PER_TICK = 512;

    private static final Set<String> PRIMARY_PACKETS = Set.of(
            "ServerboundSwingPacket",
            "PacketPlayInArmAnimation");

    private static final Set<String> SECONDARY_PACKETS = Set.of(
            "ServerboundUseItemPacket",
            "ServerboundUseItemOnPacket",
            "ServerboundInteractPacket",
            "PacketPlayInUseItem",
            "PacketPlayInBlockPlace",
            "PacketPlayInUseEntity");

    /** Receives one normalized gesture on the main thread at tick start. */
    public interface Sink {
        void onInput(Player player, boolean primary);
    }

    private final Plugin plugin;
    private final Sink sink;
    private final InputPacketQueue queue = new InputPacketQueue();
    private final Map<UUID, Object> handlers = new ConcurrentHashMap<>();
    private final Map<Class<?>, Optional<Method>> handReaders = new ConcurrentHashMap<>();
    private final LongAdder delivered = new LongAdder();

    private Method craftPlayerGetHandle;
    private Field[] channelPath;
    private Method channelPipeline;
    private Method pipelineGet;
    private Method pipelineAddLast;
    private Method pipelineRemove;
    private Method contextFireRead;

    private volatile boolean active;
    private boolean failed;
    private boolean registered;
    private BukkitTask tickTask;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask;

    public InputChannel(Plugin plugin, Sink sink) {
        this.plugin = plugin;
        this.sink = sink;
    }

    /** Probes the runtime and starts the tick drain; {@code false} = event path only. */
    public boolean start() {
        if (active) {
            return true;
        }
        if (failed || plugin == null || sink == null || !plugin.isEnabled()) {
            return false;
        }
        try {
            resolve();
            active = true;
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            registered = true;
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                attach(player);
            }
            startDrain();
            return active;
        } catch (Throwable failure) {
            disable(failure);
            return false;
        }
    }

    public boolean active() {
        return active;
    }

    public long captured() {
        return queue.captured();
    }

    public long dropped() {
        return queue.dropped();
    }

    public long delivered() {
        return delivered.sum();
    }

    public int attachedChannels() {
        return handlers.size();
    }

    /** Removes every handler this channel installed and stops the drain. Idempotent. */
    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (foliaTask != null) {
            foliaTask.cancel();
            foliaTask = null;
        }
        if (registered) {
            registered = false;
            HandlerList.unregisterAll(this);
        }
        if (active) {
            for (UUID id : new ArrayList<>(handlers.keySet())) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    detach(player);
                }
            }
        }
        handlers.clear();
        queue.clear();
        active = false;
    }

    // ------------------------------------------------------------------ pipeline

    private void resolve() throws Exception {
        Class<?> craftPlayer = resolveCraftClass("entity.CraftPlayer");
        this.craftPlayerGetHandle = craftPlayer.getMethod("getHandle");
        this.channelPath = findChannelPath(craftPlayerGetHandle.getReturnType());

        Class<?> channelClass = Class.forName("io.netty.channel.Channel");
        this.channelPipeline = channelClass.getMethod("pipeline");
        Class<?> pipelineClass = Class.forName("io.netty.channel.ChannelPipeline");
        this.pipelineGet = pipelineClass.getMethod("get", String.class);
        Class<?> handlerClass = Class.forName("io.netty.channel.ChannelHandler");
        this.pipelineAddLast = pipelineClass.getMethod("addLast", String.class, handlerClass);
        this.pipelineRemove = pipelineClass.getMethod("remove", handlerClass);
        Class<?> contextClass = Class.forName("io.netty.channel.ChannelHandlerContext");
        this.contextFireRead = contextClass.getMethod("fireChannelRead", Object.class);

        // The proxy handler is constructed from this interface; fail here, not
        // on a player's join thread, if the runtime does not have it.
        Class.forName("io.netty.channel.ChannelInboundHandler");
    }

    private void startDrain() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            foliaTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> drain(), 1L, 1L);
        } catch (ClassNotFoundException spigot) {
            tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::drain, 1L, 1L);
        }
    }

    private void attach(Player player) {
        if (!active || player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        if (handlers.containsKey(id)) {
            return;
        }
        try {
            Object channel = channelOf(player);
            if (channel == null) {
                return;
            }
            Object pipeline = channelPipeline.invoke(channel);
            Object existing = pipelineGet.invoke(pipeline, HANDLER_NAME);
            if (existing != null) {
                // Same name can only be ours (left over from a hard reload with
                // the same JVM); replace it so we own the sink again.
                pipelineRemove.invoke(pipeline, existing);
            }
            Object handler = newHandler(id);
            pipelineAddLast.invoke(pipeline, HANDLER_NAME, handler);
            handlers.put(id, handler);
        } catch (Throwable failure) {
            disable(failure);
        }
    }

    private void detach(Player player) {
        Object handler = handlers.remove(player.getUniqueId());
        if (handler == null) {
            return;
        }
        try {
            Object channel = channelOf(player);
            if (channel == null) {
                return;
            }
            Object pipeline = channelPipeline.invoke(channel);
            pipelineRemove.invoke(pipeline, handler);
        } catch (Throwable ignored) {
            // The channel is already closed on most quit paths; the pipeline
            // dies with it.
        }
    }

    private Object channelOf(Player player) throws Exception {
        Object node = craftPlayerGetHandle.invoke(player);
        for (Field field : channelPath) {
            if (node == null) {
                return null;
            }
            node = field.get(node);
        }
        return node;
    }

    /**
     * Walks the NMS handle's field graph (bounded depth) for a field typed as
     * the Netty {@code Channel}. Handles both mojang- and spigot-mapped
     * runtimes because it matches types, never names.
     */
    private static Field[] findChannelPath(Class<?> handle) throws Exception {
        Class<?> channelClass = Class.forName("io.netty.channel.Channel");
        List<Field> path = new ArrayList<>();
        if (search(handle, channelClass, path, new HashSet<>(), 0)) {
            return path.toArray(new Field[0]);
        }
        throw new IllegalStateException("no Netty channel reachable from " + handle.getName());
    }

    private static boolean search(Class<?> type, Class<?> target, List<Field> path,
                                  Set<Class<?>> visited, int depth) {
        if (depth > 3 || !visited.add(type)) {
            return false;
        }
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (target.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    path.add(field);
                    return true;
                }
            }
        }
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> fieldType = field.getType();
                if (fieldType.isPrimitive() || fieldType.isArray()
                        || fieldType.getName().startsWith("java.")
                        || fieldType.getName().startsWith("io.netty.")) {
                    continue;
                }
                field.setAccessible(true);
                path.add(field);
                if (search(fieldType, target, path, visited, depth + 1)) {
                    return true;
                }
                path.remove(path.size() - 1);
            }
        }
        return false;
    }

    private Object newHandler(UUID player) throws Exception {
        Class<?> inbound = Class.forName("io.netty.channel.ChannelInboundHandler");
        ClassLoader loader = inbound.getClassLoader() != null
                ? inbound.getClassLoader() : InputChannel.class.getClassLoader();
        return Proxy.newProxyInstance(loader, new Class<?>[]{inbound},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("channelRead".equals(name)) {
                        Object context = args != null && args.length > 0 ? args[0] : null;
                        Object message = args != null && args.length > 1 ? args[1] : null;
                        capture(player, message);
                        if (context != null) {
                            try {
                                contextFireRead.invoke(context, message);
                            } catch (Throwable broken) {
                                logOnce("[input] could not forward a packet past the fast-path handler: " + broken);
                            }
                        }
                        return null;
                    }
                    if ("equals".equals(name)) {
                        return proxy == args[0];
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("toString".equals(name)) {
                        return "MineplusInputHandler[" + player + "]";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private void capture(UUID player, Object message) {
        if (message == null) {
            return;
        }
        for (Class<?> type = message.getClass(); type != null; type = type.getSuperclass()) {
            String simple = type.getSimpleName();
            if (PRIMARY_PACKETS.contains(simple)) {
                queue.offer(player, InputPacketQueue.Gesture.PRIMARY);
                return;
            }
            if (SECONDARY_PACKETS.contains(simple)) {
                if (!offHand(type, message)) {
                    queue.offer(player, InputPacketQueue.Gesture.SECONDARY);
                }
                return;
            }
        }
    }

    /**
     * An off-hand use must not read as a press for the main-hand item; the
     * event path ignores it too. The hand accessor is obfuscated on spigot
     * mappings, so it is found structurally (a no-arg method returning an enum
     * with an {@code OFF_HAND} constant) and cached.
     */
    private boolean offHand(Class<?> packetType, Object message) {
        Optional<Method> reader = handReaders.computeIfAbsent(packetType, InputChannel::findHandReader);
        if (reader.isEmpty()) {
            return false;
        }
        try {
            Object hand = reader.get().invoke(message);
            return hand instanceof Enum<?> value && "OFF_HAND".equals(value.name());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Optional<Method> findHandReader(Class<?> packetType) {
        for (Method method : packetType.getMethods()) {
            if (method.getParameterCount() != 0 || !Enum.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            for (Object constant : method.getReturnType().getEnumConstants()) {
                if (constant instanceof Enum<?> value && "OFF_HAND".equals(value.name())) {
                    return Optional.of(method);
                }
            }
        }
        return Optional.empty();
    }

    private void drain() {
        if (!active) {
            return;
        }
        for (int i = 0; i < DRAIN_PER_TICK; i++) {
            InputPacketQueue.InputSample sample = queue.poll();
            if (sample == null) {
                return;
            }
            Player player = Bukkit.getPlayer(sample.player());
            if (player == null || !player.isOnline()) {
                continue;
            }
            try {
                sink.onInput(player, sample.gesture() == InputPacketQueue.Gesture.PRIMARY);
                delivered.increment();
            } catch (Throwable failure) {
                logOnce("[input] fast-path delivery failed: " + failure);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        attach(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        detach(event.getPlayer());
    }

    // ------------------------------------------------------------------ failure

    private void disable(Throwable failure) {
        if (failed) {
            return;
        }
        failed = true;
        try {
            shutdown();
        } catch (Throwable ignored) {
            // Already tearing down; the event path stays authoritative.
        }
        plugin.getLogger().warning("[input] fast-path disabled for this session: " + failure);
    }

    private void logOnce(String message) {
        if (failureReported.compareAndSet(false, true)) {
            DebugLogger.warning(message);
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean failureReported =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static Class<?> resolveCraftClass(String suffix) throws ClassNotFoundException {
        try {
            return Class.forName("org.bukkit.craftbukkit." + suffix);
        } catch (ClassNotFoundException direct) {
            String versioned = Bukkit.getServer().getClass().getPackage().getName();
            return Class.forName(versioned + "." + suffix);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0d;
    }
}
