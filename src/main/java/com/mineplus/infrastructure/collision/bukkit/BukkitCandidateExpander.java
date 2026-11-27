package com.mineplus.infrastructure.collision.bukkit;

import com.mineplus.infrastructure.collision.atlas.CandidatePool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Bell;
import org.bukkit.block.data.type.BrewingStand;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.block.data.type.Candle;
import org.bukkit.block.data.type.DaylightDetector;
import org.bukkit.block.data.type.Hopper;
import org.bukkit.block.data.type.Lectern;
import org.bukkit.block.data.type.PointedDripstone;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Snow;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.block.data.type.Wall;

/**
 * Expands a {@link CandidatePool} into concrete block states using the server's
 * own block-data classes.
 *
 * <p>No shape is described here; this only enumerates orientations and
 * variants so the atlas can measure them. Properties that do not change the
 * collision (waterlogged, powered, lit, bottle contents) are pinned to one
 * value to keep the atlas small, except where the collision does change
 * (candles count, snow layers, slab/stair/trapdoor geometry, wall arms).</p>
 *
 * <p>Thread model: uses Bukkit block-data objects only; intended for the main
 * thread during startup.</p>
 */
public final class BukkitCandidateExpander {

    private static final Logger LOGGER = Logger.getLogger("Mineplus");
    private static final int MAX_STATES_PER_MATERIAL = 512;

    private final CandidatePool pool;

    public BukkitCandidateExpander(CandidatePool pool) {
        this.pool = pool == null ? CandidatePool.defaults() : pool;
    }

    /** All unique states for the pool, deterministic family/material order. */
    public List<Map.Entry<String, org.bukkit.block.data.BlockData>> expandOrdered() {
        Map<String, org.bukkit.block.data.BlockData> unique = new LinkedHashMap<>();
        for (CandidatePool.Family family : pool.families()) {
            for (String name : family.materials()) {
                if (pool.excludedMaterials().contains(name)) {
                    continue;
                }
                Material material = Material.matchMaterial(name);
                if (material == null || !material.isBlock()) {
                    LOGGER.fine("[collision] candidate material missing on this server: " + name);
                    continue;
                }
                org.bukkit.block.data.BlockData base;
                try {
                    base = material.createBlockData();
                } catch (IllegalArgumentException failure) {
                    LOGGER.fine("[collision] cannot create block data for " + name + ": "
                            + failure.getMessage());
                    continue;
                }
                List<org.bukkit.block.data.BlockData> states = expandFamily(base, family);
                if (states.size() > MAX_STATES_PER_MATERIAL) {
                    LOGGER.warning("[collision] " + name + " produced " + states.size()
                            + " states; keeping the first " + MAX_STATES_PER_MATERIAL);
                    states = states.subList(0, MAX_STATES_PER_MATERIAL);
                }
                for (org.bukkit.block.data.BlockData state : states) {
                    unique.putIfAbsent(state.getAsString(), state);
                }
            }
        }
        return new ArrayList<>(unique.entrySet());
    }

    /** All unique states for the pool, without family metadata. */
    public List<org.bukkit.block.data.BlockData> expand() {
        List<org.bukkit.block.data.BlockData> states = new ArrayList<>();
        for (Map.Entry<String, org.bukkit.block.data.BlockData> entry : expandOrdered()) {
            states.add(entry.getValue());
        }
        return states;
    }

    private List<org.bukkit.block.data.BlockData> expandFamily(
            org.bukkit.block.data.BlockData base, CandidatePool.Family family) {
        return switch (family.name()) {
            case "full", "carpets", "no_collision" -> single(base);
            case "slabs" -> product(base, List.of(slabTypes(), waterlogged()));
            case "stairs" -> product(base,
                    List.of(facings(base), half(), stairShapes(), waterlogged()));
            case "trapdoors" -> product(base,
                    List.of(facings(base), half(), openable(), power(), waterlogged()));
            case "walls" -> product(base, List.of(wallUp(), wallHeight(BlockFace.NORTH),
                    wallHeight(BlockFace.EAST), wallHeight(BlockFace.SOUTH),
                    wallHeight(BlockFace.WEST), waterlogged()));
            case "fences", "panes" -> product(base, List.of(faceSubsets(base), waterlogged()));
            case "chains" -> product(base, List.of(axes(base), waterlogged()));
            case "end_rods" -> product(base, List.of(directionalFaces(base)));
            case "candles" -> product(base, List.of(candleCounts(base), lit(), waterlogged()));
            case "snow" -> product(base, List.of(snowLayers(base)));
            case "cauldrons" -> cauldronStates(base);
            case "heads" -> product(base, List.of(rotations(base), directionalFaces(base)));
            case "utility" -> utilityStates(base);
            default -> single(base);
        };
    }

    private static List<org.bukkit.block.data.BlockData> single(
            org.bukkit.block.data.BlockData base) {
        return List.of(base.clone());
    }

    private static List<org.bukkit.block.data.BlockData> product(
            org.bukkit.block.data.BlockData base, List<List<Consumer<org.bukkit.block.data.BlockData>>> groups) {
        List<org.bukkit.block.data.BlockData> results = new ArrayList<>();
        results.add(base.clone());
        for (List<Consumer<org.bukkit.block.data.BlockData>> group : groups) {
            if (group.isEmpty()) {
                continue;
            }
            List<org.bukkit.block.data.BlockData> next = new ArrayList<>();
            for (org.bukkit.block.data.BlockData current : results) {
                for (Consumer<org.bukkit.block.data.BlockData> mutation : group) {
                    org.bukkit.block.data.BlockData copy = current.clone();
                    mutation.accept(copy);
                    next.add(copy);
                    if (next.size() > MAX_STATES_PER_MATERIAL * 4) {
                        break;
                    }
                }
            }
            results = next;
        }
        return results;
    }

    private static List<Consumer<org.bukkit.block.data.BlockData>> waterlogged() {
        return List.of(data -> {
            if (data instanceof Waterlogged logged) {
                logged.setWaterlogged(false);
            }
        });
    }

    private static List<Consumer<org.bukkit.block.data.BlockData>> power() {
        return List.of(data -> {
            if (data instanceof Powerable powerable) {
                powerable.setPowered(false);
            }
        });
    }

    private static List<Consumer<org.bukkit.block.data.BlockData>> lit() {
        return List.of(
                data -> {
                    if (data instanceof Lightable lightable) {
                        lightable.setLit(false);
                    }
                },
                data -> {
                    if (data instanceof Lightable lightable) {
                        lightable.setLit(true);
                    }
                });
    }

    private static List<Consumer<org.bukkit.block.data.BlockData>> openable() {
        return List.of(
                data -> {
                    if (data instanceof org.bukkit.block.data.Openable openable) {
                        openable.setOpen(false);
                    }
                },
                data -> {
                    if (data instanceof org.bukkit.block.data.Openable openable) {
                        openable.setOpen(true);
                    }
                });
    }

    private static List<Consumer<org.bukkit.block.data.BlockData>> facings(
            org.bukkit.block.data.BlockData base) {
        if (!(base instanceof Directional directional)) {
            return List.of();
        }
        List<Consumer<org.bukkit.block.data.BlockData>> out = new ArrayList<>();
        for (BlockFace face : directional.getFaces()) {
            out.add(data -> {
                if (data instanceof Directional target) {
                    target.setFacing(face);
                }
            });
        }
        return out;
    }

    private static List<Consumer<org.bukkit.block.data.BlockData>> directionalFaces(
            org.bukkit.block.data.BlockData base) {
        return facings(base);
    }

    private static List<Consumer<org.bukkit.block.data.BlockData>> half() {
        List<Consumer<org.bukkit.block.data.BlockData>> out = new ArrayList<>();
        for (Bisected.Half value : Bisected.Half.values()) {
            out.add(data -> {
                if (data instanceof Bisected bisected) {
                    bisected.setHalf(value);
                }
            });
        }
        return out;
    }

    private static List<Consumer<org.bukkit.block.data.BlockData>> stairShapes() {
        List<Consumer<org.bukkit.block.data.BlockData>> out = new ArrayList<>();
        for (Stairs.Shape shape : Stairs.Shape.values()) {
            out.add(data -> {
                if (data instanceof Stairs stairs) {
                    stairs.setShape(shape);
                }
            });
        }
        return out;
    }

    private static List<Consumer<org.bukkit.block.data.BlockData>> slabTypes() {
        List<Consumer<org.bukkit.block.data.BlockData>> out = new ArrayList<>();
        for (Slab.Type type : Slab.Type.values()) {
            out.add(data -> {
                if (data instanceof Slab slab) {
                    slab.setType(type);
                }
            });
        }
        return out;
    }

    private static List<Consumer<org.bukkit.block.data.BlockData>> wallUp() {
        return List.of(
                data -> {
                    if (data instanceof Wall wall) {
                        wall.setUp(false);
                    }
                },
                data -> {
                    if (data instanceof Wall wall) {
                        wall.setUp(true);
                    }
                });
    }

    private static List<Consumer<org.bukkit.block.data.BlockData>> wallHeight(BlockFace face) {
        List<Consumer<org.bukkit.block.data.BlockData>> out = new ArrayList<>();
        for (Wall.Height height : Wall.Height.values()) {
            out.add(data -> {
                if (data instanceof Wall wall) {
                    wall.setHeight(face, height);
                }
            });
        }
        return out;
    }

    /** Every subset of the connectable faces, capped at 5 faces (32 states). */
    private static List<Consumer<org.bukkit.block.data.BlockData>> faceSubsets(
            org.bukkit.block.data.BlockData base) {
        if (!(base instanceof MultipleFacing facing)) {
            return List.of();
        }
        List<BlockFace> allowed = new ArrayList<>(facing.getAllowedFaces());
        if (allowed.size() > 5) {
            allowed = allowed.subList(0, 5);
        }
        final List<BlockFace> faces = List.copyOf(allowed);
        int combinations = 1 << faces.size();
        List<Consumer<org.bukkit.block.data.BlockData>> out = new ArrayList<>();
        for (int mask = 0; mask < combinations; mask++) {
            int bits = mask;
            out.add(data -> {
                if (data instanceof MultipleFacing target) {
                    for (int i = 0; i < faces.size(); i++) {
                        BlockFace face = faces.get(i);
                        if (target.getAllowedFaces().contains(face)) {
                            target.setFace(face, (bits & (1 << i)) != 0);
                        }
                    }
                }
            });
        }
        return out;
    }

    private static List<Consumer<org.bukkit.block.data.BlockData>> axes(
            org.bukkit.block.data.BlockData base) {
        if (!(base instanceof Orientable orientable)) {
            return List.of();
        }
        List<Consumer<org.bukkit.block.data.BlockData>> out = new ArrayList<>();
        for (Axis axis : orientable.getAxes()) {
            out.add(data -> {
                if (data instanceof Orientable target) {
                    target.setAxis(axis);
                }
            });
        }
        return out;
    }

    private static List<Consumer<org.bukkit.block.data.BlockData>> candleCounts(
            org.bukkit.block.data.BlockData base) {
        if (!(base instanceof Candle candle)) {
            return List.of();
        }
        List<Consumer<org.bukkit.block.data.BlockData>> out = new ArrayList<>();
        for (int count = Math.max(1, candle.getCandles()); count <= candle.getMaximumCandles(); count++) {
            int candles = count;
            out.add(data -> {
                if (data instanceof Candle target) {
                    target.setCandles(candles);
                }
            });
        }
        return out;
    }

    private static List<Consumer<org.bukkit.block.data.BlockData>> snowLayers(
            org.bukkit.block.data.BlockData base) {
        if (!(base instanceof Snow snow)) {
            return List.of();
        }
        List<Consumer<org.bukkit.block.data.BlockData>> out = new ArrayList<>();
        for (int layer = snow.getMinimumLayers(); layer <= snow.getMaximumLayers(); layer++) {
            int layers = layer;
            out.add(data -> {
                if (data instanceof Snow target) {
                    target.setLayers(layers);
                }
            });
        }
        return out;
    }

    private static List<Consumer<org.bukkit.block.data.BlockData>> rotations(
            org.bukkit.block.data.BlockData base) {
        if (!(base instanceof Rotatable rotatable)) {
            return List.of();
        }
        List<Consumer<org.bukkit.block.data.BlockData>> out = new ArrayList<>();
        for (BlockFace face : BlockFace.values()) {
            if (face == rotatable.getRotation()) {
                continue;
            }
            out.add(data -> {
                if (data instanceof Rotatable target) {
                    try {
                        target.setRotation(face);
                    } catch (IllegalArgumentException notSupported) {
                        // Not a 16-step rotation face; skip it.
                    }
                }
            });
        }
        out.add(data -> {
            if (data instanceof Rotatable target) {
                target.setRotation(rotatable.getRotation());
            }
        });
        return out;
    }

    private List<org.bukkit.block.data.BlockData> cauldronStates(
            org.bukkit.block.data.BlockData base) {
        List<org.bukkit.block.data.BlockData> out = new ArrayList<>();
        out.add(base.clone());
        if (base instanceof Levelled levelled) {
            for (int level = 1; level <= levelled.getMaximumLevel(); level++) {
                org.bukkit.block.data.BlockData copy = base.clone();
                ((Levelled) copy).setLevel(level);
                out.add(copy);
            }
        }
        return out;
    }

    private List<org.bukkit.block.data.BlockData> utilityStates(
            org.bukkit.block.data.BlockData base) {
        if (base instanceof Hopper) {
            return product(base, List.of(facings(base), power()));
        }
        if (base instanceof BrewingStand brewing) {
            List<Consumer<org.bukkit.block.data.BlockData>> bottles = new ArrayList<>();
            int max = brewing.getMaximumBottles();
            for (int mask = 0; mask < (1 << max); mask++) {
                int bits = mask;
                bottles.add(data -> {
                    if (data instanceof BrewingStand target) {
                        for (int i = 0; i < max; i++) {
                            target.setBottle(i, (bits & (1 << i)) != 0);
                        }
                    }
                });
            }
            return product(base, List.of(bottles));
        }
        if (base instanceof Lectern lectern) {
            return product(base, List.of(facings(base), power()));
        }
        if (base instanceof Campfire campfire) {
            return product(base, List.of(facings(base), lit(),
                    List.of(data -> {
                        if (data instanceof Campfire target) {
                            target.setSignalFire(false);
                        }
                    })));
        }
        if (base instanceof Bell) {
            List<Consumer<org.bukkit.block.data.BlockData>> attachments = new ArrayList<>();
            for (Bell.Attachment attachment : Bell.Attachment.values()) {
                attachments.add(data -> {
                    if (data instanceof Bell target) {
                        target.setAttachment(attachment);
                    }
                });
            }
            return product(base, List.of(attachments, facings(base), power()));
        }
        if (base instanceof DaylightDetector) {
            return product(base, List.of(List.of(
                    data -> {
                        if (data instanceof DaylightDetector target) {
                            target.setInverted(false);
                        }
                    },
                    data -> {
                        if (data instanceof DaylightDetector target) {
                            target.setInverted(true);
                        }
                    })));
        }
        if (base instanceof PointedDripstone dripstone) {
            List<Consumer<org.bukkit.block.data.BlockData>> thickness = new ArrayList<>();
            for (PointedDripstone.Thickness value : PointedDripstone.Thickness.values()) {
                thickness.add(data -> {
                    if (data instanceof PointedDripstone target) {
                        target.setThickness(value);
                    }
                });
            }
            List<Consumer<org.bukkit.block.data.BlockData>> directions = new ArrayList<>();
            for (BlockFace face : dripstone.getVerticalDirections()) {
                directions.add(data -> {
                    if (data instanceof PointedDripstone target) {
                        target.setVerticalDirection(face);
                    }
                });
            }
            return product(base, List.of(thickness, directions, waterlogged()));
        }
        return single(base);
    }

    /** Unused import guard for servers without Bell (kept for readability). */
    static String providerName() {
        return Bukkit.getName();
    }
}
