package com.mineplus.gun.input;

import com.mineplus.gun.weapon.WeaponDefinition;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Always-available input adapter built on Bukkit events.
 *
 * <p>Press is {@code PlayerInteractEvent}. Release is the Paper
 * stop-using event (registered only when that class exists) for usable
 * carriers such as {@code SPYGLASS}; item-switch / drop / sneak / quit / death
 * are hard stops and the runtime's bounded max-hold is the final guard. Reload
 * is the swap-hands key; melee reads the arm-swing animation.
 *
 * <p>The interact event is cancelled ONLY when the held item is a MineplusGun
 * weapon. Neutral carriers (no right-click action) are used so no vanilla
 * actions occur anyway, but we cancel to suppress the use animation on the
 * client. Left-click melee is handled by cancelling vanilla damage and
 * delegating to {@code onSwing} for arc-based hit detection.
 */
public class BukkitInputAdapter implements InputAdapter {

    private InputAdapter.Listener callback;
    private JavaPlugin plugin;
    private final List<org.bukkit.event.Listener> registered = new ArrayList<>();

    @Override
    public void register(JavaPlugin plugin, InputAdapter.Listener callback) {
        this.plugin = plugin;
        this.callback = callback;
        BukkitListener main = new BukkitListener();
        plugin.getServer().getPluginManager().registerEvents(main, plugin);
        registered.add(main);

        // Paper-only stop-using signal, registered only when the class is
        // actually present so a Spigot server never hits NoClassDefFoundError.
        if (paperStopUsingAvailable()) {
            try {
                org.bukkit.event.Listener stopUsing = new PaperStopUsingListener();
                plugin.getServer().getPluginManager().registerEvents(stopUsing, plugin);
                registered.add(stopUsing);
            } catch (Throwable ignored) {
                // Class vanished between the check and construction: skip.
            }
        }
    }

    @Override
    public void unregister() {
        for (org.bukkit.event.Listener listener : registered) {
            HandlerList.unregisterAll(listener);
        }
        registered.clear();
        callback = null;
        plugin = null;
    }

    @Override
    public String name() {
        return "bukkit";
    }

    private static boolean paperStopUsingAvailable() {
        try {
            Class.forName("io.papermc.paper.event.player.PlayerStopUsingItemEvent");
            return true;
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }

    private final class BukkitListener implements org.bukkit.event.Listener {

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
        public void onInteract(PlayerInteractEvent event) {
            if (event.getHand() != EquipmentSlot.HAND) {
                return;
            }
            Action action = event.getAction();
            if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
                return;
            }
            ItemStack stack = event.getItem();
            if (stack == null || stack.getType().isAir()) {
                return;
            }
            // Only cancel for our weapons; neutral carriers have no action anyway.
            if (callback != null && callback.onPress(event.getPlayer(), stack)) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
            if (!(event.getDamager() instanceof Player attacker)) {
                return;
            }
            ItemStack stack = attacker.getInventory().getItemInMainHand();
            if (stack == null || stack.getType().isAir()) {
                return;
            }
            // Cancel vanilla melee damage for all weapons; we handle melee via onSwing.
            if (callback != null && callback.onAttack(attacker, stack)) {
                event.setCancelled(true);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onAnimation(PlayerAnimationEvent event) {
            if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
                return;
            }
            if (callback != null) {
                callback.onSwing(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand());
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onSwap(PlayerSwapHandItemsEvent event) {
            event.setCancelled(true);
            if (callback != null) {
                callback.onReload(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand());
            }
        }

        @EventHandler
        public void onHeld(PlayerItemHeldEvent event) {
            if (callback != null) {
                callback.onRelease(event.getPlayer());
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onDrop(PlayerDropItemEvent event) {
            if (callback != null) {
                callback.onRelease(event.getPlayer());
            }
        }

        @EventHandler
        public void onSneak(PlayerToggleSneakEvent event) {
            if (event.isSneaking() && callback != null) {
                callback.onRelease(event.getPlayer());
            }
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            if (callback != null) {
                callback.onRelease(event.getPlayer());
            }
        }

        @EventHandler
        public void onDeath(PlayerDeathEvent event) {
            if (callback != null) {
                callback.onRelease(event.getPlayer());
            }
        }
    }

    /** Isolated so the Paper event class is only loaded when it exists. */
    private final class PaperStopUsingListener implements org.bukkit.event.Listener {

        @EventHandler(ignoreCancelled = true)
        public void onStopUsing(io.papermc.paper.event.player.PlayerStopUsingItemEvent event) {
            if (callback != null) {
                callback.onRelease(event.getPlayer());
            }
        }
    }
}
