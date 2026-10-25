package com.mineplus.infrastructure.mob;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;

/**
 * Per-evaluation view of one entity. The engine creates a single reusable
 * instance per run and refreshes it before each action evaluation, so hot
 * paths allocate nothing — unlike per-evaluation snapshots. Every world write
 * funnels through {@link #trySetBlock} / {@link #tryBreak} which enforce the
 * run-scoped block edit budget.
 */
public final class BehaviorContext {

    private static final Set<Material> PLANTABLE_GROUND = EnumSet.of(Material.GRASS_BLOCK, Material.DIRT, Material.PODZOL, Material.COARSE_DIRT);
    private static final Set<Material> GRASSY_GROUND = EnumSet.of(Material.GRASS_BLOCK, Material.PODZOL, Material.MYCELIUM, Material.MOSS_BLOCK);

    private final MobBehaviorEngine engine;

    private LivingEntity entity;
    private World world;
    private Block footBlock;
    private Block groundBlock;
    private Random random;
    private BehaviorContext.EditRunGuard editGuard;

    BehaviorContext(MobBehaviorEngine engine) {
        this.engine = engine;
    }

    /** Binds the context to one entity evaluation. Package-private; engine-owned. */
    void refresh(LivingEntity entity, Random random, BehaviorContext.EditRunGuard editGuard) {
        this.entity = entity;
        this.world = entity.getWorld();
        this.footBlock = entity.getLocation().getBlock();
        this.groundBlock = footBlock.getRelative(BlockFace.DOWN);
        this.random = random;
        this.editGuard = editGuard;
    }

    /** The evaluated living entity. */
    public LivingEntity entity() {
        return entity;
    }

    /** The entity's world. */
    public World world() {
        return world;
    }

    /** Block at the entity's body position (air for an entity standing free). */
    public Block footBlock() {
        return footBlock;
    }

    /** Block directly below the entity's feet. */
    public Block groundBlock() {
        return groundBlock;
    }

    /** Biome at the entity's position. */
    public org.bukkit.block.Biome biome() {
        return footBlock.getBiome();
    }

    /** Shared per-run random source; callers must not retain it. */
    public Random random() {
        return random;
    }

    /** {@code true} when the block is air or otherwise replaceable (grass, flowers, snow layers). */
    public boolean isReplaceable(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        return type.isAir() || Tag.REPLACEABLE.isTagged(type);
    }

    /** {@code true} when the entity stands on grass-like ground (grass block, podzol, mycelium, moss). */
    public boolean onGrassyGround() {
        return GRASSY_GROUND.contains(groundBlock.getType());
    }

    /** {@code true} when the ground can host plants (grass block, dirt family). */
    public boolean onPlantableGround() {
        return PLANTABLE_GROUND.contains(groundBlock.getType()) || Tag.DIRT.isTagged(groundBlock.getType());
    }

    /** Block-light level at the entity's foot block (torches, lanterns, glowstone). */
    public int footBlockLight() {
        return footBlock.getLightFromBlocks();
    }

    /**
     * Places a block, respecting the engine's per-run edit budget. Physics
     * updates are suppressed so symbolic placements (torches, saplings) never
     * cascade into neighbor updates.
     *
     * @return {@code true} when the block changed
     */
    public boolean trySetBlock(Block target, BlockData data) {
        if (target == null || data == null) {
            return false;
        }
        if (editGuard == null || !editGuard.tryConsume(engine)) {
            return false;
        }
        target.setBlockData(data, false);
        engine.telemetry().blockEditDone();
        return true;
    }

    /**
     * Breaks a block (never dropping items unless asked) under the edit budget.
     *
     * @return {@code true} when the block changed
     */
    public boolean tryBreak(Block target, boolean dropItems) {
        if (target == null || target.getType().isAir()) {
            return false;
        }
        if (editGuard == null || !editGuard.tryConsume(engine)) {
            return false;
        }
        boolean removed = dropItems ? target.breakNaturally() : false;
        if (!dropItems) {
            target.setType(Material.AIR);
            removed = true;
        }
        if (removed) {
            engine.telemetry().blockEditDone();
        }
        return removed;
    }

    /**
     * Counts matching blocks in a small cube around the entity. Intended for
     * sparse-density checks (sapling spacing, flower clumps); the loop breaks
     * out as soon as {@code earlyExit} matches are seen.
     */
    public int countNearby(Predicate<Material> matcher, int radius, int verticalRadius, int earlyExit) {
        if (matcher == null || radius <= 0) {
            return 0;
        }
        int count = 0;
        int baseX = footBlock.getX();
        int baseY = footBlock.getY();
        int baseZ = footBlock.getZ();
        for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    if (matcher.test(world.getBlockAt(x, baseY + dy, z).getType())) {
                        count++;
                        if (count >= earlyExit) {
                            return count;
                        }
                    }
                }
            }
        }
        return count;
    }

    /** Counts block entities of one material in the given cube around the entity. */
    public int countNearby(Material material, int radius, int verticalRadius, int earlyExit) {
        return countNearby(material == null ? null : candidate -> candidate == material, radius, verticalRadius, earlyExit);
    }

    /**Whether the chunk holding the given block is loaded (safe-world-write precheck). */
    public boolean isChunkLoaded(Block block) {
        Chunk chunk = block == null ? null : block.getChunk();
        return chunk != null && chunk.isLoaded();
    }

    /**
     * Run-scoped edit budget. Created fresh by the engine per run; exhausted
     * budgets make {@link #trySetBlock} and {@link #tryBreak} no-ops until the
     * next run, which is how the engine rate-limits world changes.
     */
    interface EditRunGuard {
        /** Attempts to consume one edit slot. */
        boolean tryConsume(MobBehaviorEngine engine);
    }

    /** Condition helper factory predicates used with {@link MobProfile.Builder#condition(Predicate)}. */
    public static final class Conditions {

        private Conditions() {
        }

        /** Limits the profile to entities inside the given worlds. */
        public static Predicate<BehaviorContext> inWorld(Predicate<World> worldPredicate) {
            return context -> worldPredicate.test(context.world());
        }

        /** Requires the entity to be an adult baby-capable mob (no baby churn). */
        public static Predicate<BehaviorContext> adultOnly() {
            return context -> !(context.entity() instanceof org.bukkit.entity.Ageable ageable) || ageable.isAdult();
        }

        /** Requires block light at the foot position to be at most the given level. */
        public static Predicate<BehaviorContext> maxBlockLight(int level) {
            return context -> context.footBlockLight() <= level;
        }
    }

    /** Small API shim so bundled actions can share sapling biome mapping. */
    public static final class Planting {

        private Planting() {
        }

        /**
         * Biome-appropriate sapling for a location, or {@code null}. Mirrors
         * the reference mod's approach (name matching over biome families) at
         * Bukkit API level: {@code Tag.SAPLINGS} members only.
         */
        public static Material saplingFor(org.bukkit.block.Biome biome) {
            if (biome == null) {
                return null;
            }
            String name = biome.name();
            if (name.contains("BIRCH")) {
                return Material.BIRCH_SAPLING;
            }
            if (name.contains("TAIGA") || name.contains("GROVE") || name.contains("SNOWY")) {
                return Material.SPRUCE_SAPLING;
            }
            if (name.contains("JUNGLE")) {
                return Material.JUNGLE_SAPLING;
            }
            if (name.contains("SAVANNA")) {
                return Material.ACACIA_SAPLING;
            }
            if (name.contains("CHERRY")) {
                return Material.CHERRY_SAPLING;
            }
            if (name.contains("DARK_FOREST")) {
                return Material.DARK_OAK_SAPLING;
            }
            if (name.contains("FOREST") || name.equals("PLAINS") || name.contains("MEADOW") || name.contains("FLOWER")) {
                return Material.OAK_SAPLING;
            }
            return null;
        }

        /** Saplings share the {@link Tag#SAPLINGS} tag; useful as a density-check matcher. */
        public static boolean isSapling(Material material) {
            return material != null && Tag.SAPLINGS.isTagged(material);
        }
    }

    /**
     * Convenience view for actions that need the blocks directly around the
     * entity without doing their own block math.
     */
    public List<Block> footNeighbors() {
        return List.of(
                footBlock.getRelative(BlockFace.NORTH),
                footBlock.getRelative(BlockFace.SOUTH),
                footBlock.getRelative(BlockFace.EAST),
                footBlock.getRelative(BlockFace.WEST)
        );
    }
}
