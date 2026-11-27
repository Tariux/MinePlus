package com.mineplus.test;

import com.mineplus.infrastructure.collision.atlas.AtlasKey;
import com.mineplus.infrastructure.collision.atlas.CandidatePool;
import com.mineplus.infrastructure.collision.atlas.ShapeAtlas;
import com.mineplus.infrastructure.collision.atlas.ShapeAtlasCache;
import com.mineplus.infrastructure.collision.atlas.ShapeEntry;
import com.mineplus.infrastructure.collision.atlas.ShapeFlag;
import com.mineplus.infrastructure.collision.mask.RasterMode;
import com.mineplus.infrastructure.collision.mask.VoxelMask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Plain-Java checks for masks, the atlas cache and pool hashing. */
public final class ShapeAtlasTest {

    private static final List<String> failures = new ArrayList<>();

    private ShapeAtlasTest() {
    }

    public static void main(String[] args) throws Exception {
        rasterization();
        rotationAndCombinators();
        cacheRoundTrip();
        cacheInvalidationAndCorruption();
        atlasOrdering();
        poolHashing();

        if (failures.isEmpty()) {
            System.out.println("ShapeAtlasTest: all checks passed");
            return;
        }
        System.out.println("ShapeAtlasTest: " + failures.size() + " failure(s)");
        for (String failure : failures) {
            System.out.println(" - " + failure);
        }
        System.exit(1);
    }

    private static void rasterization() {
        VoxelMask cube = VoxelMask.rasterize(
                List.of(new double[] {0, 0, 0, 1, 1, 1}), RasterMode.CONSERVATIVE);
        check("full cube has 4096 cells", cube.count() == VoxelMask.CELLS, cube);
        check("full cube is full", cube.isFull(), cube);

        VoxelMask slab = VoxelMask.rasterize(
                List.of(new double[] {0, 0, 0, 1, 0.5, 1}), RasterMode.CENTERED);
        check("bottom slab is 8 layers", slab.count() == 16 * 16 * 8, slab);
        check("slab top surface is 0.5", Math.abs(slab.topSurface() - 0.5) < 1e-9,
                slab.topSurface());

        VoxelMask plate = VoxelMask.rasterize(
                List.of(new double[] {0, 0, 0, 1, 1, 0.05}), RasterMode.CONSERVATIVE);
        check("thin plate keeps one layer", plate.count() == 16 * 16, plate);
        check("thin plate is not full", !plate.isFull(), plate);

        VoxelMask majorityPlate = VoxelMask.rasterize(
                List.of(new double[] {0, 0, 0, 1, 1, 0.02}), RasterMode.MAJORITY);
        check("majority drops a plate under half a voxel", majorityPlate.isEmpty(),
                majorityPlate);
        VoxelMask halfPlate = VoxelMask.rasterize(
                List.of(new double[] {0, 0, 0, 1, 1, 0.05}), RasterMode.MAJORITY);
        check("majority keeps a plate over half a voxel", halfPlate.count() == 16 * 16,
                halfPlate);

        VoxelMask empty = VoxelMask.rasterize(List.of(), RasterMode.CONSERVATIVE);
        check("no boxes means no collision", empty.isEmpty(), empty);
        check("empty mask bounds are null", empty.bounds() == null, empty);
        check("empty top surface is zero", empty.topSurface() == 0.0, empty);
    }

    private static void rotationAndCombinators() {
        VoxelMask mask = VoxelMask.empty().set(0, 0, 0, true);
        VoxelMask rotated = mask.copy().rotateY90(1);
        check("90° rotation maps (0,0,0) to (15,0,0)", rotated.get(15, 0, 0), rotated);
        check("90° rotation drops the original cell", !rotated.get(0, 0, 0), rotated);
        check("four turns restore the mask", rotated.rotateY90(3).equals(mask), rotated);
        check("mirror is an involution",
                mask.copy().mirrorX().mirrorX().equals(mask), mask);

        VoxelMask a = VoxelMask.empty().fill(0, 0, 0, 7, 15, 15, true);
        VoxelMask b = VoxelMask.empty().fill(8, 0, 0, 15, 15, 15, true);
        check("union is the whole cube", a.copy().or(b).isFull(), a);
        check("intersection is empty", a.copy().and(b).isEmpty(), a);
        check("difference keeps the left half", a.copy().andNot(b).count() == 16 * 16 * 8, a);
        check("intersection count is symmetric",
                a.intersectionCount(b) == b.intersectionCount(a), a);

        VoxelMask shell = VoxelMask.full().inset(1);
        check("inset removes the outer shell", shell.count() == 14 * 14 * 14, shell);
        VoxelMask grown = VoxelMask.empty().set(8, 8, 8, true).dilate(1);
        check("dilate grows in all directions", grown.count() == 27, grown);

        int[] bounds = VoxelMask.empty().fill(2, 3, 4, 5, 6, 7, true).bounds();
        check("bounds are inclusive", Arrays.equals(bounds, new int[] {2, 3, 4, 5, 6, 7}),
                Arrays.toString(bounds));
    }

    private static void cacheRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("mineplus-atlas");
        AtlasKey key = new AtlasKey("1.21.1", "spigot", "pool-hash", 16, RasterMode.CONSERVATIVE);
        ShapeAtlas atlas = fixtureAtlas(key);
        Path file = dir.resolve(key.cacheFileName());
        ShapeAtlasCache.save(file, atlas);
        check("cache file exists", Files.isRegularFile(file), file);

        Optional<ShapeAtlas> loaded = ShapeAtlasCache.load(file, key);
        check("cache reloads", loaded.isPresent(), loaded);
        if (loaded.isPresent()) {
            check("cache keeps entry count", loaded.get().size() == atlas.size(), loaded.get().size());
            ShapeEntry original = atlas.get("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
            ShapeEntry copy = loaded.get().get(original.state());
            check("cache keeps the state string", copy != null, original.state());
            check("cache keeps the mask", copy != null && copy.mask().equals(original.mask()), copy);
            check("cache keeps flags", copy != null && copy.flags().equals(original.flags()), copy);
            check("cache keeps invisibility", copy != null && copy.invisibleInPack(), copy);
            check("cache keeps stats",
                    loaded.get().stats().entries() == atlas.stats().entries(), loaded.get().stats());
        }

        Path second = dir.resolve("second.bin");
        ShapeAtlasCache.save(second, atlas);
        check("cache writes are deterministic",
                Arrays.equals(Files.readAllBytes(file), Files.readAllBytes(second)), second);
    }

    private static void cacheInvalidationAndCorruption() throws Exception {
        Path dir = Files.createTempDirectory("mineplus-atlas-stale");
        AtlasKey key = new AtlasKey("1.21.1", "spigot", "pool-a", 16, RasterMode.CONSERVATIVE);
        Path file = dir.resolve(key.cacheFileName());
        ShapeAtlasCache.save(file, fixtureAtlas(key));

        AtlasKey changedVersion = new AtlasKey("1.21.4", "spigot", "pool-a", 16,
                RasterMode.CONSERVATIVE);
        check("server upgrade invalidates",
                ShapeAtlasCache.load(file, changedVersion).isEmpty(), changedVersion);

        AtlasKey changedPool = new AtlasKey("1.21.1", "paper", "pool-b", 16,
                RasterMode.CONSERVATIVE);
        check("provider/pool change invalidates",
                ShapeAtlasCache.load(file, changedPool).isEmpty(), changedPool);

        AtlasKey changedMode = new AtlasKey("1.21.1", "spigot", "pool-a", 16,
                RasterMode.CENTERED);
        check("raster mode change invalidates",
                ShapeAtlasCache.load(file, changedMode).isEmpty(), changedMode);

        Path corrupt = dir.resolve(new AtlasKey("1.21.1", "spigot", "pool-a", 16,
                RasterMode.CONSERVATIVE).cacheFileName());
        Files.write(corrupt, new byte[] {1, 2, 3, 4});
        check("corrupt cache is rejected", ShapeAtlasCache.load(corrupt, key).isEmpty(), corrupt);
        check("corrupt cache is quarantined",
                Files.isRegularFile(corrupt.resolveSibling(corrupt.getFileName() + ".corrupt")),
                corrupt);
    }

    private static void atlasOrdering() {
        AtlasKey key = new AtlasKey("1.21.1", "spigot", "pool", 16, RasterMode.CONSERVATIVE);
        ShapeEntry z = entry("minecraft:z_block", "Z_BLOCK", 0);
        ShapeEntry a = entry("minecraft:a_block", "A_BLOCK", 0);
        ShapeAtlas atlas = new ShapeAtlas(key, List.of(z, a));
        check("entries are sorted by state",
                atlas.entries().get(0).state().equals("minecraft:a_block"), atlas.entries());
        check("query keeps order",
                atlas.query(entry -> true).size() == 2, atlas.size());
        check("get is exact", atlas.get("minecraft:a_block") == a, atlas.get("minecraft:a_block"));
        check("unknown state is null", atlas.get("minecraft:missing") == null, "null expected");
        boolean duplicateRejected = false;
        try {
            new ShapeAtlas(key, List.of(a, a));
        } catch (IllegalArgumentException expected) {
            duplicateRejected = true;
        }
        check("duplicate states are rejected", duplicateRejected, "no exception");
    }

    private static void poolHashing() {
        CandidatePool base = CandidatePool.defaults();
        check("default pool has families", base.families().size() >= 15, base.families().size());
        check("default pool is deterministic",
                base.hash().equals(CandidatePool.defaults().hash()), base.hash());
        CandidatePool extra = CandidatePool.defaults(List.of("amethyst_block"), List.of(), Set.of());
        check("extra material changes the hash", !extra.hash().equals(base.hash()), extra.hash());
        CandidatePool invisible = CandidatePool.defaults(List.of(),
                List.of("minecraft:oak_stairs"), Set.of());
        check("invisible state list changes the hash",
                !invisible.hash().equals(base.hash()), invisible.hash());
        check("invisible state listing matches prefixes",
                invisible.isListedInvisible("oak_stairs[facing=north]"), invisible);
        check("unlisted state is not invisible",
                !invisible.isListedInvisible("stone"), "stone");
        check("barrier is not listed as pack-invisible by default",
                !base.isListedInvisible("barrier"), "barrier");
        boolean requiresBarrier = base.families().stream()
                .anyMatch(family -> family.materials().contains("barrier"));
        check("default pool contains barrier", requiresBarrier, base.families());
    }

    private static ShapeAtlas fixtureAtlas(AtlasKey key) {
        ShapeEntry slab = entry("minecraft:oak_slab[type=bottom,waterlogged=false]", "OAK_SLAB", 1);
        ShapeEntry stairs = new ShapeEntry(
                "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
                "OAK_STAIRS", VoxelMask.empty().fill(0, 0, 0, 15, 7, 15, true), 2048,
                new double[] {0, 0, 0, 1, 0.5, 1}, true, true, false,
                EnumSet.of(ShapeFlag.NO_COLLISION), true, true, 2);
        ShapeEntry air = new ShapeEntry("minecraft:air", "AIR", VoxelMask.empty(), 0,
                new double[6], false, false, false, EnumSet.of(ShapeFlag.NO_COLLISION),
                true, false, 0);
        return new ShapeAtlas(key, List.of(slab, stairs, air));
    }

    private static ShapeEntry entry(String state, String material, int cost) {
        return new ShapeEntry(state, material, VoxelMask.empty().fill(0, 0, 0, 15, 7, 15, true),
                2048, new double[] {0, 0, 0, 1, 0.5, 1}, true, true, false,
                EnumSet.noneOf(ShapeFlag.class), true, false, cost);
    }

    private static void check(String name, boolean condition, Object detail) {
        if (!condition) {
            failures.add(name + " (got: " + detail + ")");
        }
    }
}
