package com.mineplus.infrastructure.mob;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * A bounded, engine-granted allowance for working on the world from outside the
 * behavior loop: block edits and entity spawns, each with its own budget.
 *
 * <p>A long-running scenario does not finish inside one evaluation: a villager
 * that raises a tower for a golem lays it block by block over scheduled ticks
 * and spawns the golem at the end, and a crew building a camp writes after the
 * engine run that started it. Those writes cannot go through {@link BehaviorContext}
 * — the context is a reusable per-run object, and a task holding it would be
 * writing under whatever budget the newest evaluation happens to own.
 *
 * <p>So the Core hands out a session instead: it carries its own counters, is
 * capped when granted, and every write or summon through it is one the engine
 * has accounted for. A module that never opens one cannot silently write the
 * world or populate it outside the mob subsystem's limits.
 *
 * <p>Thread model: main thread only, like the rest of the subsystem.
 */
public final class MobEditSession {

    private final AtomicInteger remaining;
    private final AtomicInteger remainingSpawns;
    private final BehaviorTelemetry telemetry;
    private int used;
    private int spawned;

    MobEditSession(int editBudget, int spawnBudget, BehaviorTelemetry telemetry) {
        this.remaining = new AtomicInteger(Math.max(0, editBudget));
        this.remainingSpawns = new AtomicInteger(Math.max(0, spawnBudget));
        this.telemetry = telemetry;
    }

    /** Places a block with physics suppressed; {@code false} when the session is spent. */
    public boolean setBlock(Block target, BlockData data) {
        if (target == null || data == null || !tryConsume()) {
            return false;
        }
        target.setBlockData(data, false);
        telemetry.blockEditDone();
        return true;
    }

    /** Places a block; {@code false} when the session is spent. */
    public boolean setBlock(Block target, Material material) {
        if (target == null || material == null || !tryConsume()) {
            return false;
        }
        target.setType(material, false);
        telemetry.blockEditDone();
        return true;
    }

    /** Removes a block; {@code false} when the session is spent. */
    public boolean breakBlock(Block target, boolean dropItems) {
        if (target == null || target.getType().isAir() || !tryConsume()) {
            return false;
        }
        if (dropItems) {
            target.breakNaturally();
        } else {
            target.setType(Material.AIR, false);
        }
        telemetry.blockEditDone();
        return true;
    }

    /**
     * Spawns a living entity under the session's spawn budget — the summon half
     * of a scenario that finishes after the evaluation that started it (the
     * golem at the top of a column built over scheduled ticks).
     *
     * <p>Reason {@link CreatureSpawnEvent.SpawnReason#CUSTOM}, same as
     * {@link BehaviorContext#trySpawn}: the server's own cleanup can tell the
     * plugin's mobs from the world's. The configurator runs before the entity
     * enters the world, so tagging it cannot leak a second entity-join packet.
     *
     * @return the entity, or {@code null} when the budget is spent or the world
     *         refused the spawn
     */
    public LivingEntity spawnMob(Location at, Class<? extends LivingEntity> type,
                                 Consumer<? super LivingEntity> configurator) {
        if (at == null || at.getWorld() == null || type == null || !tryConsumeSpawn()) {
            return null;
        }
        Consumer<? super LivingEntity> configure = configurator == null ? unneeded -> { } : configurator;
        try {
            LivingEntity born = at.getWorld().spawn(at, type,
                    CreatureSpawnEvent.SpawnReason.CUSTOM, false, configure);
            spawned++;
            telemetry.entitySpawnDone();
            return born;
        } catch (Throwable refused) {
            return null;
        }
    }

    /** Edits consumed so far — a builder loop uses this to know it is finished. */
    public int used() {
        return used;
    }

    /** Edits still available. */
    public int remaining() {
        return Math.max(0, remaining.get());
    }

    /** {@code true} when another edit would be granted. */
    public boolean hasBudget() {
        return remaining.get() > 0;
    }

    /** Entities spawned through this session so far. */
    public int spawned() {
        return spawned;
    }

    /** {@code true} when another spawn would be granted. */
    public boolean hasSpawnBudget() {
        return remainingSpawns.get() > 0;
    }

    private boolean tryConsume() {
        if (remaining.getAndUpdate(value -> value > 0 ? value - 1 : 0) <= 0) {
            return false;
        }
        used++;
        return true;
    }

    private boolean tryConsumeSpawn() {
        if (remainingSpawns.getAndUpdate(value -> value > 0 ? value - 1 : 0) <= 0) {
            return false;
        }
        return true;
    }
}
