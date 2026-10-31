package com.mineplus.infrastructure.input.net;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * The hand-off between the Netty thread and the tick: normalized gesture
 * samples in a bounded, concurrent FIFO.
 *
 * <p>{@link #offer} runs on the network thread and never touches Bukkit;
 * {@link #poll} runs on the main thread at tick start. A full queue drops the
 * sample and counts it — the event path still delivers that click, so a drop
 * costs latency, never a lost input.</p>
 */
public final class InputPacketQueue {

    public enum Gesture {
        PRIMARY,
        SECONDARY
    }

    /**
     * @param player   the player whose channel captured the packet
     * @param gesture  normalized gesture (left/primary, right/secondary)
     * @param sequence monotonic capture order
     */
    public record InputSample(UUID player, Gesture gesture, long sequence) {
    }

    private static final int DEFAULT_CAPACITY = 512;

    private final ConcurrentLinkedQueue<InputSample> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final LongAdder captured = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final AtomicInteger sequence = new AtomicInteger();
    private final int capacity;

    public InputPacketQueue() {
        this(DEFAULT_CAPACITY);
    }

    public InputPacketQueue(int capacity) {
        this.capacity = Math.max(16, capacity);
    }

    /** Enqueues one sample; {@code false} when the queue was full (counted). */
    public boolean offer(UUID player, Gesture gesture) {
        if (player == null || gesture == null) {
            return false;
        }
        if (size.incrementAndGet() > capacity) {
            size.decrementAndGet();
            dropped.increment();
            return false;
        }
        captured.increment();
        queue.add(new InputSample(player, gesture, sequence.incrementAndGet()));
        return true;
    }

    /** Returns the oldest sample or {@code null}. */
    public InputSample poll() {
        InputSample sample = queue.poll();
        if (sample != null) {
            size.decrementAndGet();
        }
        return sample;
    }

    public int size() {
        return Math.max(0, size.get());
    }

    public long captured() {
        return captured.sum();
    }

    public long dropped() {
        return dropped.sum();
    }

    public void clear() {
        queue.clear();
        size.set(0);
    }
}
