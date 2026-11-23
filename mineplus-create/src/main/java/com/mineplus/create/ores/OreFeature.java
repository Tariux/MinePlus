package com.mineplus.create.ores;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.module.ModuleFeature;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Random;

/**
 * Phase 4: zinc / bauxite / aluminum ore generation.
 *
 * <p>Config-gated via {@code modules.create.ores.enabled} in the module's
 * config.yml (default true). Veins only generate in wilderness chunks — chunks
 * within {@link #SPAWN_RADIUS} of world spawn are excluded; settlement/
 * claim-based exclusion lands with the claim system. The virtual ore blocks
 * themselves render through the Core's virtual block system; the populator
 * only plans and applies stone replacements.</p>
 */
public final class OreFeature extends ModuleFeature {

    /** Chunks this close to spawn never generate ore. */
    private static final int SPAWN_RADIUS_CHUNKS = 8;

    private boolean enabled = true;

    public OreFeature(JavaPlugin plugin, PluginContext context) {
        super(plugin, context);
    }

    @Override
    public String id() {
        return "ores";
    }

    @Override
    protected void onEnable() {
        this.enabled = plugin.getConfig().getBoolean("modules.create.ores.enabled", true);
        if (!enabled) {
            return;
        }
        for (World world : plugin.getServer().getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            world.getPopulators().add(new BlockPopulator() {
                @Override
                public void populate(org.bukkit.World populatorWorld, Random random,
                                     org.bukkit.Chunk source) {
                    // Here we plan the veins and replace stone so the blocks
                    // persist in the real world; the virtual-block pass
                    // retextures them client-side.
                    int chunkDistance = Math.max(
                            Math.abs(source.getX() - (populatorWorld.getSpawnLocation().getBlockX() >> 4)),
                            Math.abs(source.getZ() - (populatorWorld.getSpawnLocation().getBlockZ() >> 4)));
                    boolean wilderness = chunkDistance > SPAWN_RADIUS_CHUNKS;
                    List<OrePlan.Vein> veins = OrePlan.plan(source.getX(), source.getZ(), random, wilderness);
                    for (OrePlan.Vein vein : veins) {
                        apply(populatorWorld, vein, random);
                    }
                }
            });
        }
    }

    private void apply(World world, OrePlan.Vein vein, Random random) {
        for (int i = 0; i < vein.size; i++) {
            int dx = random.nextInt(3) - 1;
            int dy = random.nextInt(3) - 1;
            int dz = random.nextInt(3) - 1;
            Block block = world.getBlockAt(vein.x + dx, vein.y + dy, vein.z + dz);
            if (block.getType() == Material.STONE) {
                // Material mapping: the module registers custom ore items, the
                // physical placeholder block is a vanilla ore lookalike until
                // the virtual block pass retextures it client-side.
                block.setType(oreMaterial(vein.ore));
            }
        }
    }

    private static Material oreMaterial(String ore) {
        return switch (ore) {
            case OrePlan.ZINC -> Material.DEEPSLATE_IRON_ORE;
            case OrePlan.BAUXITE -> Material.DEEPSLATE_COPPER_ORE;
            default -> Material.DEEPSLATE_REDSTONE_ORE;
        };
    }
}
