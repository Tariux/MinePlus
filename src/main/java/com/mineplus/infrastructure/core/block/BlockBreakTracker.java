package com.mineplus.infrastructure.core.block;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side mining progress per player and block instance. Progress is
 * accumulated from damage events, capped per event so a paused miner does not
 * get credit for the gap, and dropped after {@link #TIMEOUT_MS} without input.
 */
public final class BlockBreakTracker {

    public static final long TIMEOUT_MS = 400L;

    public record State(double fraction, int stage, boolean complete) {
    }

    private record Key(UUID player, UUID instance) {
    }

    private static final class Entry {
        double progress;
        long lastMillis;

        Entry(long lastMillis) {
            this.lastMillis = lastMillis;
        }
    }

    private final Map<Key, Entry> entries = new HashMap<>();

    public State damage(UUID player, UUID instance, double hardness, double speed, long nowMillis) {
        Key key = new Key(player, instance);
        Entry entry = entries.get(key);
        if (entry == null) {
            entry = new Entry(nowMillis);
            entries.put(key, entry);
        }
        long delta = nowMillis - entry.lastMillis;
        if (delta < 0 || delta > TIMEOUT_MS) {
            entry.progress = 0.0;
            delta = 0L;
        }
        entry.lastMillis = nowMillis;
        double seconds = Math.max(delta, 40L) / 1000.0;
        double needed = Math.max(0.05, hardness);
        entry.progress = Math.min(1.0, entry.progress
                + seconds * Math.max(0.0, speed) / needed);
        int stage = (int) Math.min(9, Math.floor(entry.progress * 10.0));
        return new State(entry.progress, stage, entry.progress >= 1.0);
    }

    public void reset(UUID player, UUID instance) {
        entries.remove(new Key(player, instance));
    }

    public void forgetPlayer(UUID player) {
        entries.keySet().removeIf(key -> key.player().equals(player));
    }

    public double progress(UUID player, UUID instance) {
        Entry entry = entries.get(new Key(player, instance));
        return entry == null ? 0.0 : entry.progress;
    }
}
