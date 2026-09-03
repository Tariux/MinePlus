package com.mineplus.render;

import net.minecraft.server.v1_20_R1.*;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_20_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Renders a custom item in the player's hand using an ItemDisplay entity.
 * The entity always faces the player and is offset to look like a giant weapon.
 */
public class HandItemRenderer {

    private final EntityPoolManager pool = new EntityPoolManager();
    private final PacketOptimizer optimizer = new PacketOptimizer();

    /** Call when the player should see a custom item in hand.
     *  *displayItem* is the visual ItemStack (e.g. a STICK) that will be shown scaled up.
     */
    public void setHandItem(Player player, ItemStack displayItem) {
        UUID playerId = player.getUniqueId();

        // 1️⃣ Get (or create) an ItemDisplay entity
        World world = player.getWorld().getHandle();
        EntityHuman itemDisplay = pool.getItemDisplay(world, displayItem);

        // 2️⃣ Position the display in the hand.
        //    We offset from the player's eyes/hand location.
        try {
            EntityHuman playerNMS = ((CraftPlayer) player).getHandle();
            double offsetX = 0.5; // right
            double offsetY = 0.25; // up
            double offsetZ = -0.5; // forward (into the screen)
            itemDisplay.setPositionRotation(
                    playerNMS.locX + offsetX,
                    playerNMS.locY + offsetY,
                    playerNMS.locZ + offsetZ,
                    playerNMS.yaw,
                    playerNMS.pitch
            );
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 3️⃣ Make it visible ONLY to this player.
        //    We hide it from everyone else, then show it for the player later via optimizer.
        setInvisibleToAll(itemDisplay, world);

        // 4️⃣ Register with optimizer so we can send dirty metadata when the item changes.
        optimizer.registerEntityForPlayer(itemDisplay.getUniqueId(), playerId);

        // 5️⃣ Send the initial "set item" packet.
        Packet<?> initPacket = buildInitialItemPacket(itemDisplay, displayItem);
        List<Packet<?>> dirty = optimizer.getDirtyPacket(itemDisplay.getUniqueId(), initPacket);
        // In production you would bundle these for the player:
        // NMSHandler.sendBundle(player.getConnection(), dirty.toArray(new Packet[0]));

        // store a reference for later cleanup (you may keep a map in your main plugin)
        player.setMetadata("hand-item-renderer", new FixedMetadataValue(Bukkit.getPluginManager().getPlugin("MinePlus"), itemDisplay.getUniqueId()));
    }

    /** Build the initial PacketPlayOutEntityMetadata (or similar) that tells the client
     *  which vanilla item to use and how to scale/rotate it.
     *  For brevity we return null – you must implement the exact NMS packet construction. */
    private Packet<?> buildInitialItemPacket(EntityHuman display, ItemStack item) {
        try {
            Class<?> metadataCls = Class.forName("net.minecraft.server.v1_20_R1.PacketPlayOutEntityMetadata");
            // Constructor: PacketPlayOutEntityMetadata(int entityId, int[] watchers, boolean)
            // We'll just return a stub; real code must craft the watcher array containing the item NBT.
            return null;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /** Remove the hand display when the player switches slots or leaves. */
    public void clearHandItem(Player player) {
        UUID uuid = (UUID) player.getMetadata("hand-item-renderer").get(0).value();
        EntityHuman display = pool.getItemDisplay(player.getWorld().getHandle(), null);
        // In a real app you would keep a map from player -> entity UUID; here we just die.
        if (display != null) {
            pool.releaseItemDisplay(display.getUniqueId());
            display.die();
        }
        player.removeMetadata("hand-item-renderer", Bukkit.getPluginManager().getPlugin("MinePlus"));
    }

    private static void setInvisibleToAll(EntityHuman display, World world) {
        // make invisible to all players via NMS
        try {
            display.getClass().getMethod("setInvisible", boolean.class).invoke(display, true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}