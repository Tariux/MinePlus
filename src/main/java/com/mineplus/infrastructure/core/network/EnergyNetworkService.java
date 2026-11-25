package com.mineplus.infrastructure.core.network;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleEventType;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import com.mineplus.infrastructure.core.network.EnergyGrid.Balance;
import com.mineplus.infrastructure.core.network.EnergyGrid.Role;
import com.mineplus.infrastructure.model.BlockCoordinate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Bukkit-facing energy network service (mega-plan P6 / Phase C1): maintains
 * the {@link EnergyGrid} over placed multiblock instances and applies
 * balancing results back to them.
 *
 * <p>Membership: a multiblock type participates by declaring an
 * {@link EnergyProfile} (supply or demand per level). Types without a
 * profile are invisible to the network. Lifecycle listeners (PLACE /
 * REMOVE / DESTRUCTION via the {@link HookBus}) keep grid nodes in sync
 * with the registry; {@link #balanceTick()} balances dirty components and
 * pushes each consumer's result to the sink supplied by the feature (e.g.
 * writes into {@code stateData} and fires hooks).
 *
 * <p>Threading follows P6: the solve itself is pure computation, but this
 * service applies results inline on the lifecycle tick (main thread), so
 * features receive their balances on the thread that owns the instance.
 */
public final class EnergyNetworkService {

    private final MultiBlockRegistry registry;
    private final EnergyGrid grid = new EnergyGrid();
    /** Energy profile per multiblock type id, case-insensitive. */
    private final Map<String, EnergyProfile> profiles = new HashMap<>();
    /** Grid key -> instance id, so balance results resolve without a second index. */
    private final Map<Long, UUID> members = new HashMap<>();
    private BiConsumer<MultiBlockInstance, Balance> balanceSink;
    private long lastSolveNanos;
    private int lastBalanced;

    /**
     * Energy characteristics of one machine type, scaled by level.
     *
     * @param supplyPerLevel energy produced per tick at level N (0 = not a source)
     * @param demandPerLevel energy consumed per tick at level N (0 = not a consumer)
     */
    public record EnergyProfile(int supplyPerLevel, int demandPerLevel) {

        public Role role(int level) {
            if (supplyPerLevel > 0) {
                return Role.SOURCE;
            }
            if (demandPerLevel > 0) {
                return Role.CONSUMER;
            }
            return Role.CONDUIT;
        }

        public int supply(int level) {
            return supplyPerLevel * Math.max(1, level);
        }

        public int demand(int level) {
            return demandPerLevel * Math.max(1, level);
        }
    }

    /** Statistics for the last solve: node count, consumers balanced, solve time. */
    public record SolveStats(int nodes, int balanced, long solveNanos) {
    }

    public EnergyNetworkService(MultiBlockRegistry registry) {
        this.registry = registry;
    }

    /** Registers or replaces the energy profile of a machine type. */
    public void registerProfile(String typeId, EnergyProfile profile) {
        profiles.put(typeId.toLowerCase(), profile);
    }

    /**
     * Derives an energy profile from a type's per-level {@code guiOptions}:
     * keys {@code energy.supply} / {@code energy.demand} (integer per level;
     * the min level's value is the base, later levels scale by their number).
     * Types without the keys are not energized. Lets config-declared
     * machines join the network without feature code.
     */
    public void registerFromType(com.mineplus.infrastructure.core.multiblock.MultiBlockType type) {
        int supply = 0;
        int demand = 0;
        for (com.mineplus.infrastructure.core.multiblock.MultiBlockLevel level : type.levels().values()) {
            if (level.level() != type.minLevel()) {
                continue;
            }
            supply = parseInt(level.guiOptions().get("energy.supply"));
            demand = parseInt(level.guiOptions().get("energy.demand"));
        }
        if (supply > 0 || demand > 0) {
            registerProfile(type.id(), new EnergyProfile(supply, demand));
        }
    }

    private static int parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Receives every consumer balance after each solve. One sink per service. */
    public void setBalanceSink(BiConsumer<MultiBlockInstance, Balance> sink) {
        this.balanceSink = sink;
    }

    /** True when the type declares an energy profile. */
    public boolean isEnergized(String typeId) {
        return profiles.containsKey(typeId.toLowerCase());
    }

    /** True when both instances exist and share one connected network. */
    public boolean connected(UUID a, UUID b) {
        MultiBlockInstance ia = registry.getInstance(a);
        MultiBlockInstance ib = registry.getInstance(b);
        if (ia == null || ib == null) {
            return false;
        }
        return grid.connected(keyOf(ia.coordinate()), keyOf(ib.coordinate()));
    }

    public SolveStats lastSolveStats() {
        return new SolveStats(grid.size(), lastBalanced, lastSolveNanos);
    }

    /**
     * Syncs one placed instance into the grid; no-op for non-energized
     * types. Also links to already-placed energized 6-adjacent neighbours —
     * machines connect by adjacency like Create's shafts; long-haul
     * carriers are declared as conduit types by features.
     */
    public void onPlaced(MultiBlockInstance instance) {
        EnergyProfile profile = profiles.get(instance.typeId().toLowerCase());
        if (profile == null) {
            return;
        }
        int level = Math.max(1, instance.level());
        long key = keyOf(instance.coordinate());
        grid.put(key, profile.role(level), profile.supply(level), profile.demand(level));
        members.put(key, instance.id());
        BlockCoordinate at = instance.coordinate();
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : dirs) {
            long n = EnergyGrid.pack(at.x() + d[0], at.y() + d[1], at.z() + d[2]);
            if (grid.contains(n)) {
                grid.link(key, n);
            }
        }
    }

    /** Removes one instance's node from the grid; no-op when absent. */
    public void onRemoved(MultiBlockInstance instance) {
        members.remove(keyOf(instance.coordinate()));
        grid.remove(keyOf(instance.coordinate()));
    }

    /**
     * Balances all dirty components and pushes consumer balances to the
     * sink. Called from the lifecycle tick, so results are applied on the
     * thread that owns the instance.
     */
    public void balanceTick() {
        long start = System.nanoTime();
        List<Balance> balances = grid.balance();
        lastSolveNanos = System.nanoTime() - start;
        lastBalanced = balances.size();
        if (balances.isEmpty() || balanceSink == null) {
            return;
        }
        for (Balance balance : balances) {
            UUID memberId = members.get(balance.key());
            MultiBlockInstance instance = memberId == null
                    ? null : registry.getInstance(memberId);
            if (instance == null) {
                continue;
            }
            balanceSink.accept(instance, balance);
        }
    }

    /**
     * Listens to lifecycle place/remove events so grid membership follows
     * the registry without feature code.
     */
    public void attachToLifecycle(com.mineplus.infrastructure.core.events.HookBus hookBus) {
        hookBus.registerLifecycleListener(event -> {
            MultiBlockInstance instance = event.instance();
            if (instance == null) {
                return;
            }
            if (event.type() == MultiBlockLifecycleEventType.PLACE) {
                onPlaced(instance);
            } else if (event.type() == MultiBlockLifecycleEventType.REMOVE
                    || event.type() == MultiBlockLifecycleEventType.DESTRUCTION) {
                onRemoved(instance);
            }
        }, MultiBlockLifecycleEventType.PLACE,
                MultiBlockLifecycleEventType.REMOVE,
                MultiBlockLifecycleEventType.DESTRUCTION);
    }

    private long keyOf(BlockCoordinate coordinate) {
        return EnergyGrid.pack(coordinate.x(), coordinate.y(), coordinate.z());
    }
}
