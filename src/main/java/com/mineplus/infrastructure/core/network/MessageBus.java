package com.mineplus.infrastructure.core.network;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Cross-server messaging toolkit (mega-plan D2 / Phase D): channel-based
 * pub/sub with pluggable transports. Pure Java; ships with an in-memory
 * bus for tests and single-server operation. A Redis pub/sub transport
 * (D2's production target) implements the same {@link Transport} seam —
 * the toolkit stays transport-agnostic so the network layer is testable
 * without a server, and works offline the same way.
 *
 * <p>Messages are strongly-typed payloads with a channel name, an optional
 * target server (null = broadcast), a source server, and an idempotency
 * UUID so receivers can dedupe retries.
 */
public final class MessageBus {

    /** A message on the wire. Immutable; payload is JSON-ready by contract. */
    public record Message(UUID id, String channel, String sourceServer,
                          String targetServer, String payload) {

        public static Message of(String channel, String sourceServer,
                                 String targetServer, String payload) {
            return new Message(UUID.randomUUID(), channel, sourceServer, targetServer, payload);
        }
    }

    /** Seam for real transports (Redis pub/sub, plugin messaging, tests). */
    public interface Transport {
        void publish(Message message);

        void subscribe(Consumer<Message> sink);
    }

    /** In-memory bus: delivers synchronously to local subscribers. */
    public static final class InMemoryTransport implements Transport {
        private final List<Consumer<Message>> sinks = new CopyOnWriteArrayList<>();

        @Override
        public void publish(Message message) {
            for (Consumer<Message> sink : sinks) {
                sink.accept(message);
            }
        }

        @Override
        public void subscribe(Consumer<Message> sink) {
            sinks.add(sink);
        }
    }

    /** Registered listener with an optional channel filter. */
    private record Handler(String channel, Consumer<Message> handler) {
    }

    private final Transport transport;
    private final List<Handler> handlers = new CopyOnWriteArrayList<>();
    private final Map<UUID, Long> seen = new ConcurrentHashMap<>();
    private final String serverId;
    private static final long DEDUPE_TTL_MILLIS = 60_000;

    public MessageBus(Transport transport, String serverId) {
        this.transport = transport;
        this.serverId = serverId;
        transport.subscribe(this::dispatch);
    }

    public String serverId() {
        return serverId;
    }

    /** Registers a handler for one channel; returns an unsubscribe handle. */
    public Runnable on(String channel, Consumer<Message> handler) {
        Handler h = new Handler(channel, handler);
        handlers.add(h);
        return () -> handlers.remove(h);
    }

    /** Publishes to the channel: broadcast (target null) or one server. */
    public void send(String channel, String targetServer, String payload) {
        transport.publish(Message.of(channel, serverId, targetServer, payload));
    }

    private void dispatch(Message message) {
        // Never echo a message back to its source loop.
        if (message.sourceServer().equals(serverId)) {
            return;
        }
        if (message.targetServer() != null && !message.targetServer().equals(serverId)) {
            return;
        }
        if (seen.putIfAbsent(message.id(), System.currentTimeMillis()) != null) {
            return; // duplicate (transport retry)
        }
        if (seen.size() > 10_000) {
            long cutoff = System.currentTimeMillis() - DEDUPE_TTL_MILLIS;
            seen.values().removeIf(t -> t < cutoff);
        }
        for (Handler handler : handlers) {
            if (handler.channel().equals(message.channel())) {
                try {
                    handler.handler().accept(message);
                } catch (RuntimeException e) {
                    // Listener isolation: one bad handler never kills the bus.
                }
            }
        }
    }
}
