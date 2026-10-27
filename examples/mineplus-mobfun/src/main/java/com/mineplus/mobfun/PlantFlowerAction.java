package com.mineplus.mobfun;

import com.mineplus.infrastructure.fx.ParticleFx;
import com.mineplus.infrastructure.fx.Sounds;
import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import java.util.List;
import java.util.Random;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * MobFun-authored behavior: the mob plants an occasional wildflower on grassy
 * ground (the Florist Bee showcase). This class exists to demonstrate that
 * {@code BehaviorAction} is fully open to modules — it is implemented here,
 * outside the Core, and only talks to the public {@link BehaviorContext}
 * helpers and the Core's shared services, exactly like the Core's bundled
 * actions do.
 *
 * <p>Gates: air at the foot position, grass-like ground, no flower within the
 * spacing radius. Swamp-family biomes get blue orchids; everything else draws
 * from the common plains palette.
 */
public final class PlantFlowerAction implements BehaviorAction {

    private static final int SPACING_CHECK_RADIUS = 3;
    private static final List<Material> COMMON_FLOWERS = List.of(
            Material.POPPY, Material.DANDELION, Material.CORNFLOWER,
            Material.AZURE_BLUET, Material.OXEYE_DAISY, Material.ALLIUM);

    private final ParticleFx fx;

    public PlantFlowerAction(ParticleFx fx) {
        this.fx = fx;
    }

    @Override
    public String id() {
        return "plant_flower";
    }

    @Override
    public boolean execute(BehaviorContext context) {
        Block foot = context.footBlock();
        if (!foot.getType().isAir() || !foot.getChunk().isLoaded()) {
            return false;
        }
        if (!context.onGrassyGround()) {
            return false;
        }
        Material flower = flowerFor(context.biome(), context.random());
        if (flower == null) {
            return false;
        }
        if (context.countNearby(Tag.FLOWERS::isTagged, SPACING_CHECK_RADIUS, 1, 1) > 0) {
            return false;
        }
        BlockData data = Bukkit.createBlockData(flower);
        if (!context.trySetBlock(foot, data)) {
            return false;
        }
        Location center = foot.getLocation().add(0.5, 0.7, 0.5);
        fx.burst(ParticleFx.Tier.AMBIENT, center, Particle.HAPPY_VILLAGER, 5, 0.2, 0.2, 0.2, 0.0);
        Sounds.play(center, Sound.BLOCK_GRASS_PLACE.name(), 0.6f, 1.6f);
        return true;
    }

    /**
     * Biome-flavored flower choice: {@code null} keeps unsupported biomes bare,
     * mirroring how the Core's sapling mapping behaves.
     */
    private static Material flowerFor(Biome biome, Random random) {
        if (biome == null) {
            return null;
        }
        String name = biome.name();
        if (name.contains("SWAMP") || name.contains("MANGROVE")) {
            return Material.BLUE_ORCHID;
        }
        if (name.contains("CHERRY") || name.contains("SAKURA")) {
            return Material.PINK_TULIP;
        }
        if (name.contains("DESERT") || name.contains("BADLANDS") || name.contains("BEACH")
                || name.contains("OCEAN") || name.contains("DEEP") || name.contains("RIVER")
                || name.contains("END") || name.contains("NETHER") || name.contains("CAVE")
                || name.contains("DRIPSTONE") || name.contains("STONY") || name.contains("JAGGED")
                || name.contains("FROZEN_PEAKS") || name.contains("SNOWY_SLOPES")
                || name.contains("WINDSWEPT_GRAVELLY") || name.contains("ERUPT")) {
            return null;
        }
        return COMMON_FLOWERS.get(random.nextInt(COMMON_FLOWERS.size()));
    }
}
