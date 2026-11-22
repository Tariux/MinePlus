package com.mineplus.infrastructure.mob;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mob AI 2.0: short-term memory and player senses for behavior actions. An
 * instance lives on the behavior engine's per-mob state — actions reach it
 * through {@link BehaviorContext#memory()} — and everything decays on its own,
 * so a mob that stops seeing a player eventually forgets them without any
 * module bookkeeping.
 *
 * <p>Budgets: one map per mob, entries expire after
 * {@link #ENTRY_TTL_TICKS}, and the whole class is allocation-light on the
 * hot path (no per-tick allocations beyond the boxed Long keys the map
 * already needs).
 */
public final class MobMemory {

    /** How long a remembered entity stays remembered without a refresher. */
    public static final long ENTRY_TTL_TICKS = 20L * 60;

    /** How long the mob "remembers being hurt" (aggro memory). */
    public static final long HURT_MEMORY_TICKS = 20L * 30;

    /** One remembered entity: where they were last seen and when. */
    public record Memory(long tick, Location lastSeen) { }

    private static final int MAX_ENTRIES = 32;

    private final Map<UUID, Memory> seen = new ConcurrentHashMap<>();
    private volatile long lastHurtTick = Long.MIN_VALUE;
    private volatile UUID lastHurtBy = null;

    /** Records that the mob senses this entity right now. */
    public void sense(LivingEntity entity, long tick) {
        if (entity == null) {
            return;
        }
        if (seen.size() >= MAX_ENTRIES && !seen.containsKey(entity.getUniqueId())) {
            evictOldest();
        }
        seen.put(entity.getUniqueId(), new Memory(tick, entity.getLocation()));
    }

    /** The last time (game tick) this entity was sensed, or {@code Long.MIN_VALUE}. */
    public long lastSeenTick(UUID entityId) {
        Memory m = seen.get(entityId);
        return m == null ? Long.MIN_VALUE : m.tick;
    }

    /** Where the entity was last sensed, or {@code null} if forgotten. */
    public Location lastSeen(UUID entityId) {
        Memory m = seen.get(entityId);
        return m == null ? null : m.lastSeen().clone();
    }

    /** Whether the entity was sensed within {@code withinTicks}. */
    public boolean remembers(UUID entityId, long nowTick, long withinTicks) {
        long t = lastSeenTick(entityId);
        return t != Long.MIN_VALUE && nowTick - t <= withinTicks;
    }

    /** The nearest currently-remembered player to {@code from}, or {@code null}. */
    public Player nearestRememberedPlayer(Location from, long nowTick, long withinTicks) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        Iterator<Map.Entry<UUID, Memory>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Memory> entry = it.next();
            if (nowTick - entry.getValue().tick() > withinTicks) {
                it.remove();
                continue;
            }
            if (entry.getValue().lastSeen() == null
                    || !entry.getValue().lastSeen().getWorld().equals(from.getWorld())) {
                continue;
            }
            Player p = org.bukkit.Bukkit.getPlayer(entry.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            double d = p.getLocation().distanceSquared(from);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** The mob was hurt by someone; behaviors can hold a grudge for a while. */
    public void recordHurt(UUID attackerId, long tick) {
        this.lastHurtTick = tick;
        this.lastHurtBy = attackerId;
    }

    /** Who hurt the mob last, or {@code null} when the memory expired. */
    public UUID lastAttacker(long nowTick) {
        if (lastHurtBy == null || nowTick - lastHurtTick > HURT_MEMORY_TICKS) {
            return null;
        }
        return lastHurtBy;
    }

    /** Ticks since the last hit, or {@code Long.MAX_VALUE} when never hurt. */
    public long ticksSinceHurt(long nowTick) {
        return lastHurtTick == Long.MIN_VALUE ? Long.MAX_VALUE : nowTick - lastHurtTick;
    }

    /** Forgets everything (on death or despawn). */
    public void clear() {
        seen.clear();
        lastHurtTick = Long.MIN_VALUE;
        lastHurtBy = null;
    }

    private void evictOldest() {
        UUID oldestId = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<UUID, Memory> e : seen.entrySet()) {
            if (e.getValue().tick() < oldest) {
                oldest = e.getValue().tick();
                oldestId = e.getKey();
            }
        }
        if (oldestId != null) {
            seen.remove(oldestId);
        }
    }
}
