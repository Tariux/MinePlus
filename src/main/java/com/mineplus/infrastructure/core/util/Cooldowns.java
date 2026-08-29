package com.mineplus.infrastructure.core.util;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Self-pruning, per-key cooldown tracker for feature interactions.
 *
 * <p>Replaces the hand-rolled {@code Map<UUID, Long>} + currentTimeMillis
 * pattern that leaked entries when instances vanished without cleanup. The
 * canonical use is the interact-pair dedupe: the Core's hook API does not
 * expose the {@code EquipmentSlot}, so a ~1s cooldown keyed by instance id is
 * the standard way to collapse the near-simultaneous main/off-hand
 * {@code PlayerInteractEvent} pair into one action.
 */
public final class Cooldowns {

    private final Map<UUID, Long> lastUsedAt = new HashMap<>();

    /**
     * Returns {@code true} and records the current time when the cooldown for
     * {@code key} has elapsed; returns {@code false} without side effects
     * while the cooldown is still running.
     */
    public synchronized boolean tryAcquire(UUID key, long cooldownMillis) {
        long now = System.currentTimeMillis();
        Long last = lastUsedAt.get(key);
        if (last != null && now - last < cooldownMillis) {
            return false;
        }
        lastUsedAt.put(key, now);
        return true;
    }

    /** Returns {@code true} when the cooldown for {@code key} has elapsed, without recording a use. */
    public synchronized boolean isReady(UUID key, long cooldownMillis) {
        Long last = lastUsedAt.get(key);
        return last == null || System.currentTimeMillis() - last >= cooldownMillis;
    }

    /** Drops the cooldown entry for a key (e.g. when its instance is destroyed). */
    public synchronized void remove(UUID key) {
        lastUsedAt.remove(key);
    }

    /** Removes entries older than {@code maxAgeMillis}, bounding memory for long-lived features. */
    public synchronized void prune(long maxAgeMillis) {
        long threshold = System.currentTimeMillis() - maxAgeMillis;
        Iterator<Map.Entry<UUID, Long>> iterator = lastUsedAt.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() < threshold) {
                iterator.remove();
            }
        }
    }
}
