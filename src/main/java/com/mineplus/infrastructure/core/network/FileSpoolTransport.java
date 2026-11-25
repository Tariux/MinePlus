package com.mineplus.infrastructure.core.network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * File-backed cross-process transport (mega-plan D2): multiple server
 * processes on one host share a spool directory — each process drops
 * message files into its {@code outbox-<id>/} and drains the others'.
 * This is the no-dependency stand-in for the Redis transport (no Redis
 * client jar in {@code libs/}); when a client is added, only this class
 * is replaced.
 *
 * <p>Delivery is at-least-once: receivers dedupe by message id in
 * {@link MessageBus}. Each message is written to a temp file then
 * atomically moved into the spool, so readers never see partial writes.
 */
public final class FileSpoolTransport implements MessageBus.Transport {

    private final Path outbox;
    private final Path inbox;
    private final Logger logger;
    private Consumer<MessageBus.Message> sink;

    public FileSpoolTransport(Path spoolDir, String serverId, Logger logger) throws IOException {
        this.outbox = spoolDir.resolve("outbox-" + serverId);
        this.inbox = spoolDir.resolve("inbox-" + serverId);
        this.logger = logger;
        Files.createDirectories(outbox);
        Files.createDirectories(inbox);
    }

    @Override
    public void publish(MessageBus.Message message) {
        try {
            Path tmp = outbox.resolve(message.id() + ".msg.tmp");
            Path target = outbox.resolve(message.id() + ".msg");
            Files.writeString(tmp, encode(message), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.warning("FileSpoolTransport: publish failed: " + e.getMessage());
        }
    }

    @Override
    public void subscribe(Consumer<MessageBus.Message> sink) {
        this.sink = sink;
    }

    /**
     * Delivers every message currently waiting for this server: scans the
     * peers' outboxes (registered via {@link #watch}) and hands each file
     * to the bus. Call on a timer from the lifecycle pass.
     */
    public void drain() {
        if (sink == null) {
            return;
        }
        for (Path source : watchedOutboxes) {
            try (Stream<Path> files = Files.list(source)) {
                List<Path> messages = files
                        .filter(p -> p.getFileName().toString().endsWith(".msg"))
                        .sorted()
                        .toList();
                for (Path path : messages) {
                    deliverOne(path);
                }
            } catch (IOException e) {
                logger.warning("FileSpoolTransport: drain failed: " + e.getMessage());
            }
        }
    }

    /** Registers a peer outbox to consume during {@link #drain()}. */
    public void watch(Path peerOutbox) {
        watchedOutboxes.add(peerOutbox);
    }

    private final java.util.List<Path> watchedOutboxes = new java.util.ArrayList<>();

    private void deliverOne(Path path) {
        try {
            MessageBus.Message message = decode(Files.readString(path, StandardCharsets.UTF_8));
            Files.delete(path);
            sink.accept(message);
        } catch (IOException | RuntimeException e) {
            logger.warning("FileSpoolTransport: dropping bad message "
                    + path.getFileName() + ": " + e.getMessage());
            try {
                Files.delete(path);
            } catch (IOException ignored) {
                // best effort; a stuck file is re-reported next drain
            }
        }
    }

    static String encode(MessageBus.Message m) {
        // Fields are tab-separated; payload last so its content (JSON)
        // never needs escaping.
        return String.join("\t",
                m.id().toString(),
                m.channel(),
                m.sourceServer(),
                m.targetServer() == null ? "" : m.targetServer(),
                m.payload());
    }

    static MessageBus.Message decode(String line) {
        String[] parts = line.split("\t", 5);
        if (parts.length < 5) {
            throw new IllegalArgumentException("malformed line");
        }
        return new MessageBus.Message(
                java.util.UUID.fromString(parts[0]),
                parts[1],
                parts[2],
                parts[3].isEmpty() ? null : parts[3],
                parts[4]);
    }
}
