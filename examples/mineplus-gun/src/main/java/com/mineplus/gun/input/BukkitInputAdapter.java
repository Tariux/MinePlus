package com.mineplus.gun.input;

import com.mineplus.gun.diag.PressTrace;
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
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
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
 * <p>While a gun is in the main hand <b>every</b> vanilla interaction is frozen:
 * right/left-clicking blocks (placing, mining, opening containers, trampling),
 * using an off-hand item, interacting with entities, and the arm-swing
 * animation. Right-click is the single trigger (fire / scope / melee / throw);
 * left-click is inert, because driving fire from the arm swing would replay the
 * client's arm-punch animation on every shot.
 *
 * <p>Swapping hands reloads; item-switch / drop / sneak / quit are hard stops,
 * and the runtime's bounded max-hold is the final guard. The weapons' carriers
 * are neutral materials with no use action, so even a missed cancel can never
 * produce a vanilla action (fire ignition, block placement, camera zoom).
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

    /** True when the stack belongs to this module (never throws). */
    private boolean isWeapon(ItemStack stack) {
        InputAdapter.Listener listener = callback;
        if (listener == null || stack == null || stack.getType().isAir()) {
            return false;
        }
        try {
            return listener.isWeapon(stack);
        } catch (Throwable failure) {
            report("weapon check", failure);
            return false;
        }
    }

    private void report(String action, Throwable failure) {
        JavaPlugin owner = plugin;
        if (owner != null) {
            owner.getLogger().warning("[MineplusGun] Input " + action + " failed: " + failure);
        }
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

        /**
         * Freezes every vanilla block interaction while a gun is held: mining,
         * placing, opening containers, farmland trampling and off-hand item
         * use. Right-click on the main hand additionally reaches the runtime.
         */
        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
        public void onInteract(PlayerInteractEvent event) {
            Player player = event.getPlayer();
            ItemStack main = player.getInventory().getItemInMainHand();
            PressTrace.debug("[MineplusGun] interact received: player=" + player.getName() + " action="
                    + event.getAction() + " hand=" + event.getHand() + " item=" + main.getType()
                    + " cancelledBefore=" + event.isCancelled());
            if (!isWeapon(main)) {
                return;
            }
            PressTrace.trace(plugin, com.mineplus.gun.diag.PressTrace.key(player.getUniqueId(), "receipt"),
                    "[MineplusGun] press received: " + player.getName() + " action=" + event.getAction()
                            + " hand=" + event.getHand() + " cancelledBefore=" + event.isCancelled());
            if (event.getHand() != EquipmentSlot.HAND) {
                // A gun in the main hand freezes off-hand use too (a torch or
                // block in the off hand must not place on a right-click).
                event.setCancelled(true);
                return;
            }
            event.setCancelled(true);
            Action action = event.getAction();
            if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
                // Left-click / physical (farmland trample): nothing beyond the freeze.
                return;
            }
            try {
                if (callback != null && !callback.onPress(player, main)) {
                    event.setCancelled(false);
                }
            } catch (Throwable failure) {
                report("press handling", failure);
                // Keep the vanilla action suppressed even when we failed.
                event.setCancelled(true);
            }
        }

        /**
         * A gun in hand freezes entity interaction (mounting, trading, feeding)
         * — and the interaction packet is the SHOT: aiming at a mob and
         * right-clicking routes here instead of PlayerInteractEvent, so the
         * press must still reach the runtime or aimed targets take nothing.
         */
        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
        public void onInteractEntity(PlayerInteractEntityEvent event) {
            Player player = event.getPlayer();
            ItemStack main = player.getInventory().getItemInMainHand();
            boolean weapon = isWeapon(main);
            if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
                if (weapon) {
                    event.setCancelled(true);
                }
                return;
            }
            if (!weapon) {
                return;
            }
            event.setCancelled(true);
            PressTrace.trace(plugin, com.mineplus.gun.diag.PressTrace.key(player.getUniqueId(), "receipt"),
                    "[MineplusGun] aimed-entity press: " + player.getName() + " -> "
                            + event.getRightClicked().getType());
            try {
                if (callback != null) {
                    callback.onPress(player, main);
                }
            } catch (Throwable failure) {
                report("entity press handling", failure);
            }
        }

        /** Same freeze-and-fire semantics for entity-specific interactions (armor stands, seats). */
        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
        public void onInteractAtEntity(org.bukkit.event.player.PlayerInteractAtEntityEvent event) {
            Player player = event.getPlayer();
            ItemStack main = player.getInventory().getItemInMainHand();
            boolean weapon = isWeapon(main);
            if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
                if (weapon) {
                    event.setCancelled(true);
                }
                return;
            }
            if (!weapon) {
                return;
            }
            event.setCancelled(true);
            try {
                if (callback != null) {
                    callback.onPress(player, main);
                }
            } catch (Throwable failure) {
                report("entity press handling", failure);
            }
        }

        /** A gun in hand never mines; left-click is shooting only. */
        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
        public void onBlockDamage(BlockDamageEvent event) {
            if (isWeapon(event.getPlayer().getInventory().getItemInMainHand())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
        public void onBlockBreak(BlockBreakEvent event) {
            if (isWeapon(event.getPlayer().getInventory().getItemInMainHand())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
        public void onBlockPlace(BlockPlaceEvent event) {
            if (isWeapon(event.getPlayer().getInventory().getItemInMainHand())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
            if (!(event.getDamager() instanceof Player attacker)) {
                return;
            }
            ItemStack stack = attacker.getInventory().getItemInMainHand();
            if (!isWeapon(stack)) {
                return;
            }
            // Cancel vanilla melee damage for every weapon; firearms replace it
            // with the simulated round and melee with the arc sweep.
            try {
                if (callback.onAttack(attacker, stack)) {
                    event.setCancelled(true);
                }
            } catch (Throwable failure) {
                report("attack handling", failure);
                event.setCancelled(true);
            }
        }

        /**
         * A held gun never shows the vanilla arm punch: the swing is swallowed
         * for our weapons (the local client's own prediction cannot be stopped
         * server-side, which is why firing lives on right-click, not on the
         * swing).
         */
        @EventHandler(ignoreCancelled = true)
        public void onAnimation(PlayerAnimationEvent event) {
            if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
                return;
            }
            if (isWeapon(event.getPlayer().getInventory().getItemInMainHand())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onSwap(PlayerSwapHandItemsEvent event) {
            ItemStack stack = event.getPlayer().getInventory().getItemInMainHand();
            if (!isWeapon(stack)) {
                return;
            }
            event.setCancelled(true);
            try {
                callback.onReload(event.getPlayer(), stack);
            } catch (Throwable failure) {
                report("reload handling", failure);
            }
        }

        @EventHandler
        public void onHeld(PlayerItemHeldEvent event) {
            if (callback != null) {
                try {
                    callback.onRelease(event.getPlayer());
                } catch (Throwable failure) {
                    report("release handling", failure);
                }
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onDrop(PlayerDropItemEvent event) {
            if (callback != null) {
                try {
                    callback.onRelease(event.getPlayer());
                } catch (Throwable failure) {
                    report("release handling", failure);
                }
            }
        }

        @EventHandler
        public void onSneak(PlayerToggleSneakEvent event) {
            if (event.isSneaking() && callback != null) {
                try {
                    callback.onRelease(event.getPlayer());
                } catch (Throwable failure) {
                    report("release handling", failure);
                }
            }
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            if (callback != null) {
                try {
                    callback.onRelease(event.getPlayer());
                } catch (Throwable failure) {
                    report("release handling", failure);
                }
            }
        }

        @EventHandler
        public void onDeath(PlayerDeathEvent event) {
            if (callback != null) {
                try {
                    callback.onRelease(event.getPlayer());
                } catch (Throwable failure) {
                    report("release handling", failure);
                }
            }
        }
    }

    /** Isolated so the Paper event class is only loaded when it exists. */
    private final class PaperStopUsingListener implements org.bukkit.event.Listener {

        @EventHandler(ignoreCancelled = true)
        public void onStopUsing(io.papermc.paper.event.player.PlayerStopUsingItemEvent event) {
            if (callback != null) {
                try {
                    callback.onRelease(event.getPlayer());
                } catch (Throwable failure) {
                    report("release handling", failure);
                }
            }
        }
    }
}
