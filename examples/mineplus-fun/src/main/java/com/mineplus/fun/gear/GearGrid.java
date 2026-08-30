package com.mineplus.fun.gear;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.core.api.AnimationApi;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.model.BlockCoordinate;
import com.mineplus.infrastructure.virtual.animation.AnimationPlayback;
import com.mineplus.infrastructure.virtual.animation.AnimationState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/**
 * Redstone-driven gear grid: evaluates which gears should rotate.
 *
 * <p>Activation semantics (the two feature rules):
 * <ol>
 *   <li>A gear adjacent to redstone power (torch, lever, wire, redstone block,
 *       repeater, ...) rotates.</li>
 *   <li>A gear adjacent (face-sharing, Manhattan distance 1) to a rotating gear
 *       rotates too — the "interlocking train". Reachability is computed per
 *       evaluation by flood-fill from the redstone-powered seeds, so a train
 *       only stays active while it still traces back to a power source and
 *       never self-sustains through a cycle.</li>
 * </ol>
 *
 * <p>Adjacency is <b>cached</b>: gear anchors are immutable, so the gear id-set is a
 * complete topology fingerprint — the O(n²) pairwise graph is rebuilt only when a
 * gear is placed or removed, and the periodic/redstone evaluations pay O(n).
 *
 * <p>Animation control goes through the Core's {@link AnimationApi}: newly
 * activated gears start the {@code rotate_gear} loop at the current animation
 * time of an already-spinning neighbour (phase sync), or at 0 when the whole
 * train starts together. Deactivated gears stop and return to rest. All gears
 * play the same clip at the same rate, so a train stays synchronized.
 */
final class GearGrid {

    private static final BlockFace[] ADJACENT_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private final PluginContext context;

    /** Cached gear snapshot of the last evaluation (empty = never evaluated). */
    private List<MultiBlockInstance> cachedGears = List.of();
    /** Cached face-adjacency graph of {@link #cachedGears}. */
    private Map<UUID, List<MultiBlockInstance>> adjacency = Map.of();

    GearGrid(PluginContext context) {
        this.context = context;
    }

    void evaluate() {
        List<MultiBlockInstance> gears = collectGears();
        if (gears.isEmpty()) {
            cachedGears = List.of();
            adjacency = Map.of();
            return;
        }
        if (topologyChanged(gears)) {
            cachedGears = gears;
            adjacency = buildAdjacency(gears);
        }

        // Flood-fill the active set from the redstone-powered seeds.
        Set<UUID> active = new HashSet<>();
        Deque<MultiBlockInstance> queue = new ArrayDeque<>();
        for (MultiBlockInstance gear : gears) {
            if (isRedstonePowered(gear) && active.add(gear.id())) {
                queue.add(gear);
            }
        }
        while (!queue.isEmpty()) {
            MultiBlockInstance current = queue.poll();
            for (MultiBlockInstance neighbor : adjacency.getOrDefault(current.id(), List.of())) {
                if (active.add(neighbor.id())) {
                    queue.add(neighbor);
                }
            }
        }

        AnimationApi animation = context.animationApi();
        for (MultiBlockInstance gear : gears) {
            boolean spinning = animation.getAnimationState(gear.id(), GearKeys.ANIMATION_ROTATE) != null;
            if (active.contains(gear.id())) {
                if (!spinning && gear.renderedModelId() != null) {
                    animation.playAnimation(gear.id(), GearKeys.ANIMATION_ROTATE,
                            new AnimationPlayback(1.0f, null, neighbourPhase(gear, animation)));
                }
            } else if (spinning) {
                animation.stopAnimation(gear.id(), GearKeys.ANIMATION_ROTATE);
            }
        }
    }

    /**
     * True when the gear id-set differs from the cached snapshot. Gear anchors
     * and ids are immutable once placed, so an unchanged id-set means unchanged
     * topology and the cached adjacency graph stays valid.
     */
    private boolean topologyChanged(List<MultiBlockInstance> gears) {
        if (gears.size() != cachedGears.size()) {
            return true;
        }
        Set<UUID> cachedIds = new HashSet<>();
        for (MultiBlockInstance gear : cachedGears) {
            cachedIds.add(gear.id());
        }
        for (MultiBlockInstance gear : gears) {
            if (!cachedIds.contains(gear.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Current animation time of an already-spinning neighbour, so a gear
     * joining a running train meshes in phase with it; 0 when no neighbour is
     * spinning yet (whole train starting together).
     */
    private float neighbourPhase(MultiBlockInstance gear, AnimationApi animation) {
        for (MultiBlockInstance neighbor : adjacency.getOrDefault(gear.id(), List.of())) {
            AnimationState state = animation.getAnimationState(neighbor.id(), GearKeys.ANIMATION_ROTATE);
            if (state != null && !state.paused()) {
                return state.time();
            }
        }
        return 0.0f;
    }

    private List<MultiBlockInstance> collectGears() {
        List<MultiBlockInstance> gears = new ArrayList<>();
        for (MultiBlockInstance instance : context.basicInfrastructureApi().getLoadedInstances()) {
            if (GearKeys.MACHINE_ID.equals(instance.typeId())) {
                gears.add(instance);
            }
        }
        return gears;
    }

    private Map<UUID, List<MultiBlockInstance>> buildAdjacency(List<MultiBlockInstance> gears) {
        Map<UUID, List<MultiBlockInstance>> graph = new HashMap<>();
        for (int i = 0; i < gears.size(); i++) {
            for (int j = i + 1; j < gears.size(); j++) {
                if (isAdjacent(gears.get(i).coordinate(), gears.get(j).coordinate())) {
                    graph.computeIfAbsent(gears.get(i).id(), key -> new ArrayList<>()).add(gears.get(j));
                    graph.computeIfAbsent(gears.get(j).id(), key -> new ArrayList<>()).add(gears.get(i));
                }
            }
        }
        return graph;
    }

    /** Face-sharing anchors in the same world (the gear occupies one block). */
    private static boolean isAdjacent(BlockCoordinate a, BlockCoordinate b) {
        if (!a.worldName().equals(b.worldName())) {
            return false;
        }
        int dx = Math.abs(a.x() - b.x());
        int dy = Math.abs(a.y() - b.y());
        int dz = Math.abs(a.z() - b.z());
        return dx + dy + dz == 1;
    }

    /**
     * True when the gear's anchor position receives redstone power. The
     * anchor is a barrier block, so direct source neighbours (redstone block,
     * torches) are also checked explicitly — {@code isBlockPowered} covers
     * levers, wire, and repeaters pointing into the position.
     */
    private static boolean isRedstonePowered(MultiBlockInstance instance) {
        BlockCoordinate coordinate = instance.coordinate();
        World world = Bukkit.getWorld(coordinate.worldName());
        if (world == null) {
            return false;
        }
        Block anchor = world.getBlockAt(coordinate.x(), coordinate.y(), coordinate.z());
        if (anchor.isBlockPowered() || anchor.isBlockIndirectlyPowered()) {
            return true;
        }
        for (BlockFace face : ADJACENT_FACES) {
            Material type = anchor.getRelative(face).getType();
            if (type == Material.REDSTONE_BLOCK
                    || type == Material.REDSTONE_TORCH
                    || type == Material.REDSTONE_WALL_TORCH) {
                return true;
            }
        }
        return false;
    }
}
