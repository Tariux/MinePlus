package com.mineplus.render;

import net.minecraft.server.v1_20_R1.*;

import org.bukkit.craftbukkit.v1_20_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Version‑abstracted NMS helpers.
 * Adjust the Class.forName strings and method signatures for other Spigot versions.
 */
public final class NMSHandler {

    // -----------------------------------------------------------------
    // BlockDisplay
    // -----------------------------------------------------------------
    /**
     * Create a BlockDisplay entity attached to the given World.
     * The BlockState may be null; callers should set it later via setBlockState.
     */
    public static EntityHuman craftBlockDisplay(World world) {
        try {
            Class<?> displayCls = Class.forName("net.minecraft.server.v1_20_R1.entity.block.BlockDisplay");
            EntityHuman display = (EntityHuman) displayCls.getDeclaredConstructor(World.class).newInstance(world);
            return display;
        } catch (Exception e) {
            throw new RuntimeException("Failed to craft BlockDisplay", e);
        }
    }

    /** Set the BlockState on a BlockDisplay via NMS "a" method (or setBlockState). */
    public static void setBlockState(EntityHuman display, BlockState state) {
        try {
            // Many versions expose a method named "a" that takes BlockState
            Method m = display.getClass().getMethod("a", BlockState.class);
            m.invoke(display, state);
        } catch (Exception e) {
            // fallback via reflection of DataWatcher if needed
            throw new RuntimeException("Could not set blockstate", e);
        }
    }

    // -----------------------------------------------------------------
    // ItemDisplay
    // -----------------------------------------------------------------
    public static EntityHuman craftItemDisplay(World world, ItemStack item) {
        try {
            Class<?> displayCls = Class.forName("net.minecraft.server.v1_20_R1.entity.item.ItemDisplay");
            EntityHuman display = (EntityHuman) displayCls.getDeclaredConstructor(World.class).newInstance(world);
            // set the displayed item
            Method setItem = display.getClass().getMethod("setItem", ItemStack.class);
            setItem.invoke(display, item);
            return display;
        } catch (Exception e) {
            throw new RuntimeException("Failed to craft ItemDisplay", e);
        }
    }

    // -----------------------------------------------------------------
    // Bundle packet
    // -----------------------------------------------------------------
    /**
     * Send a ClientboundBundlePacket containing the given packets.
     * If the class/method is not available, falls back to sending each packet individually.
     */
    public static void sendBundle(Connection connection, Packet<?>... packets) {
        try {
            Class<?> bundleCls = Class.forName("net.minecraft.server.v1_20_R1.packet.ClientboundBundlePacket");
            // Constructor: ClientboundBundlePacket(Packet, Packet[])
            java.lang.reflect.Constructor<?> ctor = bundleCls.getDeclaredConstructor(Packet.class, Packet[].class);
            Object bundle = ctor.newInstance(packets[0], (Object) packets);
            connection.sendPacket(bundle);
        } catch (Exception e) {
            // fallback
            for (Packet<?> p : packets) {
                connection.sendPacket(p);
            }
        }
    }

    // -----------------------------------------------------------------
    // Connection getter
    // -----------------------------------------------------------------
    public static Connection getConnection(EntityHuman player) {
        try {
            Object handle = player.getBukkitEntity().getClass().getMethod("getHandle").invoke(player.getBukkitEntity());
            return (Connection) handle.getClass().getField("connection").get(handle);
        } catch (Exception e) {
            throw new RuntimeException("Could not get connection", e);
        }
    }

    // -----------------------------------------------------------------
    // Packet utilities (position / metadata) – placeholders
    // -----------------------------------------------------------------
    /**
     * Build a PacketPlayOutEntityMetadata packet for a BlockDisplay.
     * The dataWatcher is populated with the blockstate (implementation‑specific).
     */
    public static Packet<?> buildEntityMetadataPacket(EntityHuman display, BlockState state) {
        try {
            Class<?> metadataCls = Class.forName("net.minecraft.server.v1_20_R1.PacketPlayOutEntityMetadata");
            // Constructor: PacketPlayOutEntityMetadata(int entityId, int[] watchers, boolean flag)
            java.lang.reflect.Constructor<?> ctor = metadataCls.getDeclaredConstructor(int.class, int[].class, boolean.class);
            // We'll just return a stub; real impl must craft the int[] watcher entries.
            return null; // placeholder – replace with actual watcher building
        } catch (Exception e) {
            throw new RuntimeException("Could not build metadata packet", e);
        }
    }

    /**
     * Build a simple position‑only packet (PacketPlayOutEntityPosition) for LOD culling.
     */
    public static Packet<?> buildPositionPacket(EntityHuman display) {
        try {
            Class<?> posCls = Class.forName("net.minecraft.server.v1_20_R1.PacketPlayOutEntityPosition");
            java.lang.reflect.Constructor<?> ctor = posCls.getDeclaredConstructor(int.class);
            return ctor.newInstance(display.getId());
        } catch (Exception e) {
            throw new RuntimeException("Could not build position packet", e);
        }
    }
}