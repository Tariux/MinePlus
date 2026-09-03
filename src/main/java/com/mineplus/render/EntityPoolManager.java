package com.mineplus.render;

import net.minecraft.server.v1_20_R1.*;
import org.bukkit.craftbukkit.v1_20_R1.entity.CraftEntity;
import org.bukkit.craftbukkit.v1_20_R1.entity.CraftWorld;
import org.bukkit.craftbukkit.v1_20_R1.entity.CraftPlayer;
import org.bukkit.entity.*;
import org.bukkit.Location;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EntityPoolManager {

    // pooled BlockDisplay entities keyed by a UUID we assign
    private final Map<UUID, EntityHuman> blockPool = new ConcurrentHashMap<>();
    // pooled ItemDisplay entities
    private final Map<UUID, EntityHuman> itemPool = new ConcurrentHashMap<>();

    // simple limit – you can expand with chunk‑based checks
    private final int maxPerChunk = 200;

    public EntityPoolManager() {}

    /**
     * Get (or create) a BlockDisplay for the given Bukkit World at the supplied location.
     * The display is initially invisible and has no blockstate set.
     */
    public EntityHuman getBlockDisplay(World nmsWorld, Location loc) {
        UUID key = UUID.randomUUID();
        EntityHuman display;
        if (blockPool.containsKey(key)) {
            display = blockPool.get(key);
        } else {
            // create fresh
            display = NMSHandler.craftBlockDisplay(nmsWorld);
            // make it invisible until we explicitly show it
            setInvisible(display, nmsWorld);
            blockPool.put(key, display);
        }
        // teleport to location
        try {
            // CraftEntity provides getHandle() which is the NMS entity
            ((CraftEntity) display).getHandle().setPositionRotation(
                    loc.getX(), loc.getY(), loc.getZ(), 0, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return display;
    }

    /** Hide a BlockDisplay from all players in the world. */
    private static void setInvisible(EntityHuman display, World world) {
        // Bukkit wrapper – make invisible to all online players
        for (Player p : ((CraftWorld) world).getViewers()) {
            // NMS method setInvisible(Boolean, List<EntityHuman>) – we just use the built‑in
            try {
                display.getClass().getMethod("setInvisible", boolean.class).invoke(display, true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /** Return a BlockDisplay to the pool (hide it, do not delete). */
    public void releaseBlockDisplay(UUID id) {
        EntityHuman display = blockPool.get(id);
        if (display != null) {
            setInvisible(display, display.getBukkitEntity().getWorld());
            // teleport to void so it does not render
            try {
                ((CraftEntity) display).getHandle().setPositionRotation(0, 0, 0, 0, 0);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /** Get / create an ItemDisplay for a player hand. */
    public EntityHuman getItemDisplay(World nmsWorld, ItemStack itemStack) {
        UUID key = UUID.randomUUID();
        EntityHuman display;
        if (itemPool.containsKey(key)) {
            display = itemPool.get(key);
        } else {
            display = NMSHandler.craftItemDisplay(nmsWorld, itemStack);
            setInvisible(display, nmsWorld);
            itemPool.put(key, display);
        }
        return display;
    }

    public void releaseItemDisplay(UUID id) {
        EntityHuman display = itemPool.get(id);
        if (display != null) {
            setInvisible(display, display.getBukkitEntity().getWorld());
            try {
                ((CraftEntity) display).getHandle().setPositionRotation(0, 0, 0, 0, 0);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /** Optional cleanup when a chunk unloads – remove entities whose location is outside the chunk. */
    public void cleanupChunk(Chunk chunk) {
        blockPool.entrySet().removeIf(e -> {
            EntityHuman ent = e.getValue();
            Location loc = ((CraftEntity) ent).getBukkitLocation();
            return !chunk.isInside(loc);
        });
        itemPool.entrySet().removeIf(e -> {
            EntityHuman ent = e.getValue();
            Location loc = ((CraftEntity) ent).getBukkitLocation();
            return !chunk.isInside(loc);
        });
    }
}