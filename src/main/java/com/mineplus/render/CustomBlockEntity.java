package com.mineplus.render;

import com.mineplus.core.customblock.CustomBlock; // assume this exists in your core
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Wrapper around a MinePlusCore CustomBlock that also spawns the corresponding
 * BlockDisplay entities to visualise the structure.
 */
public class CustomBlockEntity {

    private final CustomBlock coreBlock;
    private final Location anchor; // the block where the structure was placed

    // maps display UUID -> NMS EntityHuman
    private final Map<UUID, EntityHuman> displays = new HashMap<>();

    // the list of vanilla BlockStates used for each part (pre‑computed)
    private final List<BlockState> displayBlockStates;

    private final PacketOptimizer optimizer = new PacketOptimizer();
    private final EntityPoolManager pool = new EntityPoolManager();

    public CustomBlockEntity(CustomBlock coreBlock, Location anchor) {
        this.coreBlock = coreBlock;
        this.anchor = anchor;
        // Build a static list of vanilla BlockStates that will be used for each part.
        // For demonstration we just use STONE for legs and OAK_PLANKS for top.
        this.displayBlockStates = initDisplayBlockStates();
    }

    /** Called when the player places the custom block structure. */
    public void onPlace(Player placer, Block blockClicked) {
        World world = blockClicked.getWorld().getHandle();

        // register this entity with all players that should see it (nearby)
        // In a real server you would check distance and add to optimizer per player
        for (Player p : world.getPlayers()) {
            optimizer.registerEntityForPlayer(UUID.randomUUID(), p.getUniqueId()); // placeholder
        }

        // rebuild / spawn displays
        rebuildDisplays(world, placer);

        // send initial spawn packets (handled by your main loop)
        sendInitialPackets();
    }

    /** Called when the structure is broken. */
    public void onBreak(Player breaker) {
        // release all displays back to the pool
        for (EntityHuman d : new ArrayList<>(displays.values())) {
            pool.releaseBlockDisplay(d.getUniqueId());
            d.die(); // NMS will remove it from the world
            displays.remove(d.getUniqueId());
        }
        optimizer.clearPlayer(breaker.getUniqueId());
    }

    /** Re‑create the display entities whenever the structure changes. */
    private void rebuildDisplays(World world, Player player) {
        // clean up old ones
        for (EntityHuman d : new ArrayList<>(displays.values())) {
            pool.releaseBlockDisplay(d.getUniqueId());
            d.die();
            displays.remove(d.getUniqueId());
        }

        // Determine size from coreBlock metadata (example getters)
        int sizeX = coreBlock.getSizeX();
        int sizeY = coreBlock.getSizeY();
        int sizeZ = coreBlock.getSizeZ();

        // create a grid of BlockDisplay entities
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    Location partLoc = anchor.add(x, y, z);
                    BlockState vanillaState = pickVanillaState(x, y, z, sizeX, sizeY, sizeZ);
                    EntityHuman disp = pool.getBlockDisplay(world, partLoc);

                    // set the blockstate via NMS
                    try {
                        NMSHandler.setBlockState(disp, vanillaState);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    // make visible to players (but still no collision)
                    try {
                        disp.getClass().getMethod("setInvisible", boolean.class).invoke(display -> false);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    displays.put(disp.getUniqueId(), disp);

                    // register with optimizer for this player (placeholder)
                    optimizer.registerEntityForPlayer(disp.getUniqueId(), player.getUniqueId());
                }
            }
        }
    }

    /** Pick a vanilla BlockState depending on the part’s role. */
    private BlockState pickVanillaState(int x, int y, int z, int sizeX, int sizeY, int sizeZ) {
        // Very simple example – topmost layer uses OAK_PLANKS, rest uses STONE
        if (y == sizeY - 1) {
            return org.bukkit.Material.OAK_PLANKS.createBlockState();
        } else {
            return org.bukkit.Material.STONE.createBlockState();
        }
    }

    /** Send initial spawn packets to all nearby players. */
    private void sendInitialPackets() {
        // In a real implementation you would collect the metadata packets for each display,
        // bundle them per player using NMSHandler.sendBundle, and send them.
        // Here we just log.
        getLogger().info("Sent initial BlockDisplay spawn packets for structure at " + anchor);
    }

    // -----------------------------------------------------------------
    // LOD handling – called from RenderListener each tick
    // -----------------------------------------------------------------
    /** Update LOD for a specific viewer. If the viewer is far away the displays are hidden. */
    public void updateLOD(Player viewer, World world) {
        Location viewerLoc = viewer.getLocation();
        for (Map.Entry<UUID, EntityHuman> entry : displays.entrySet()) {
            EntityHuman disp = entry.getValue();
            Location dispLoc = ((CraftEntity) disp).getBukkitLocation();
            double distSq = viewerLoc.distanceSquared(dispLoc);

            if (distSq > 400) { // >20 blocks
                // hide from this viewer
                try {
                    disp.getClass().getMethod("setInvisible", boolean.class).invoke(disp, true);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                optimizer.clearPlayer(viewer.getUniqueId());
            } else {
                // show and ensure invisible flag is off
                try {
                    disp.getClass().getMethod("setInvisible", boolean.class).invoke(disp, false);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                // optionally send a dirty position packet; the optimizer will compare hashes
                Packet<?> posPacket = NMSHandler.buildPositionPacket(disp);
                optimizer.getDirtyPacket(disp.getUniqueId(), posPacket);
            }
        }
    }

    // -----------------------------------------------------------------
    // Helper logger – delegate to plugin if needed
    // -----------------------------------------------------------------
    private org.bukkit.Bukkit getLogger() {
        // This is a placeholder; in your main plugin you would pass the instance.
        return org.bukkit.Bukkit.getLogger();
    }
}