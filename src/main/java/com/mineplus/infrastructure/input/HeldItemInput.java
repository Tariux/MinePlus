package com.mineplus.infrastructure.input;

import com.mineplus.infrastructure.combat.DamageTransactions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * The Core's held-item input service: turns "the player is holding one of my
 * items" into clean gameplay callbacks, and freezes the vanilla behavior that
 * item would otherwise trigger.
 *
 * <p>A module that owns a held item (a gun, a tool, a wand) needs four things
 * that Bukkit does not give it as a package: a right-click press that also
 * arrives when the crosshair is on an entity, the suppression of the vanilla
 * action behind the carrier item (place, mine, trade, ride, draw), a release
 * signal for every way the item can leave the hand, and a melee suppression
 * that does not eat the module's own damage. Re-implementing that pair by pair
 * per module is where held-item gameplay historically breaks: the press that
 * never reaches the shooter because an aimed entity routes through a different
 * event, and the hit that silently deals nothing because the module's own
 * melee-suppression listener cancelled it. This service gets both right once.
 *
 * <p><b>Routing.</b> Handlers are consulted in registration order; the first
 * whose {@link HeldItemHandler#owns(ItemStack)} matches the main-hand stack
 * claims the interaction. A stack is owned by one handler only, so two modules
 * can never both act on one right-click.
 *
 * <p><b>Threading.</b> All callbacks are Bukkit event callbacks, so they run on
 * the main thread and may touch the world directly.
 */
public final class HeldItemInput implements Listener {

    /**
     * The gameplay surface a module exposes for the items it owns.
     */
    public interface HeldItemHandler {

        /** {@code true} when {@code stack} is one of this handler's items. */
        boolean owns(ItemStack stack);

        /**
         * {@code true} when {@code stack} is one of this handler's items
         * <em>for this holder</em>. The default ignores the holder; a module
         * that switches its item off per world, per permission or per game
         * state overrides it and returns {@code false} there — which makes the
         * item fully vanilla for that player, because nothing is frozen and no
         * press is routed. Doing it in {@link #owns(ItemStack)} alone would
         * freeze the carrier's vanilla behavior while still refusing to act.
         */
        default boolean owns(ItemStack stack, Player holder) {
            return owns(stack);
        }

        /**
         * A press arrived (right-click, in air or on a block, or aimed at an
         * entity).
         *
         * @return {@code true} when the vanilla action must stay suppressed;
         *         {@code false} lets the carrier do its vanilla thing
         */
        boolean onPress(Player player, ItemStack held);

        /**
         * The item left the hand by any route (slot switch, drop, sneak, quit,
         * death, inventory close). Use it to stop hold-to-fire state.
         */
        default void onRelease(Player player) {
        }

        /**
         * The player pressed the swap-hands key while this item was held.
         *
         * @return {@code true} to consume the swap (a reload, typically)
         */
        default boolean onSwapHands(Player player, ItemStack held) {
            return false;
        }

        /**
         * Whether the vanilla melee swing of this item should be cancelled.
         * Defaults to {@code true} — a module that computes its own damage does
         * not want a free punch stacked on top of it. Never consulted for damage
         * the module applied itself: that is the caller's own hit, and
         * cancelling it would zero it out.
         */
        default boolean suppressVanillaMelee(Player attacker, EntityDamageByEntityEvent event) {
            return true;
        }

        /**
         * Whether every vanilla block/entity interaction should be frozen while
         * this item is in the main hand. Default {@code true}: a gun in hand must
         * not place a torch, mine, open a door, or start a trade.
         */
        default boolean freezeVanillaInteractions() {
            return true;
        }
    }

    private final Plugin plugin;
    private final List<HeldItemHandler> handlers = new CopyOnWriteArrayList<>();
    private final Listener paperStopUsing;
    private boolean registered;

    public HeldItemInput(Plugin plugin) {
        this.plugin = plugin;
        this.paperStopUsing = paperStopUsingAvailable() ? new PaperStopUsingSeam() : null;
    }

    /** Probed before the seam is ever constructed, so Spigot never links the class. */
    private static boolean paperStopUsingAvailable() {
        try {
            Class.forName("io.papermc.paper.event.player.PlayerStopUsingItemEvent");
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** Attaches a handler; returns {@code false} when it was already attached. */
    public boolean addHandler(HeldItemHandler handler) {
        if (handler == null || handlers.contains(handler)) {
            return false;
        }
        handlers.add(handler);
        ensureRegistered();
        return true;
    }

    /** Detaches a handler. The service stops listening when none remain. */
    public boolean removeHandler(HeldItemHandler handler) {
        boolean removed = handlers.remove(handler);
        if (handlers.isEmpty()) {
            shutdown();
        }
        return removed;
    }

    public int handlerCount() {
        return handlers.size();
    }

    /** Unregisters every listener this service installed. Idempotent. */
    public void shutdown() {
        if (!registered) {
            return;
        }
        registered = false;
        HandlerList.unregisterAll(this);
        Listener seam = paperStopUsing;
        if (seam != null) {
            HandlerList.unregisterAll(seam);
        }
        handlers.clear();
    }

    private void ensureRegistered() {
        if (registered) {
            return;
        }
        PluginManager manager = plugin.getServer().getPluginManager();
        manager.registerEvents(this, plugin);
        if (paperStopUsing != null) {
            manager.registerEvents(paperStopUsing, plugin);
        }
        registered = true;
    }

    /** The first handler that owns the held stack for this holder, or {@code null}. */
    private HeldItemHandler owner(Player holder, ItemStack stack) {
        if (holder == null || stack == null || stack.getType().isAir()) {
            return null;
        }
        for (HeldItemHandler handler : handlers) {
            try {
                if (handler.owns(stack, holder)) {
                    return handler;
                }
            } catch (Throwable broken) {
                // A handler that throws must not blind the whole input layer.
                logFailure("ownership check", broken);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    /**
     * The press funnel. Right-click is the activation, on air, on a block, or
     * on an entity — the last one arrives through the entity-interact events
     * below, which is exactly why a module that only listens to
     * {@link PlayerInteractEvent} never hits what it aims at.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack main = player.getInventory().getItemInMainHand();
        HeldItemHandler handler = owner(player, main);
        if (handler == null) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            // Off-hand use is frozen too: a torch in the off hand must not
            // place on a right-click while the main hand holds our item.
            if (handler.freezeVanillaInteractions()) {
                event.setCancelled(true);
            }
            return;
        }
        Action action = event.getAction();
        boolean isPress = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        if (!isPress) {
            if (handler.freezeVanillaInteractions()) {
                event.setCancelled(true);
            }
            return;
        }
        boolean handled = false;
        try {
            handled = handler.onPress(player, main);
        } catch (Throwable failure) {
            logFailure("press handling", failure);
            handled = true; // still suppress vanilla: we own this gesture
        }
        if (handled || handler.freezeVanillaInteractions()) {
            event.setCancelled(true);
        }
    }

    /** An aimed-at entity press is a press; the freeze applies to mounting and trading. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        routeEntityPress(event.getPlayer(), event.getHand(), event::setCancelled);
    }

    /** Same semantics for entity-attachment interactions (stands, boats, seats). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        routeEntityPress(event.getPlayer(), event.getHand(), event::setCancelled);
    }

    private void routeEntityPress(Player player, EquipmentSlot hand, CancelSink cancel) {
        ItemStack main = player.getInventory().getItemInMainHand();
        HeldItemHandler handler = owner(player, main);
        if (handler == null) {
            return;
        }
        if (hand != EquipmentSlot.HAND) {
            cancel.set(handler.freezeVanillaInteractions());
            return;
        }
        cancel.set(true);
        try {
            handler.onPress(player, main);
        } catch (Throwable failure) {
            logFailure("aimed press handling", failure);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockDamage(BlockDamageEvent event) {
        HeldItemHandler handler = owner(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand());
        if (handler != null && handler.freezeVanillaInteractions()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        HeldItemHandler handler = owner(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand());
        if (handler != null && handler.freezeVanillaInteractions()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        HeldItemHandler handler = owner(event.getPlayer(), event.getItemInHand());
        if (handler != null && handler.freezeVanillaInteractions()) {
            event.setCancelled(true);
        }
    }

    /**
     * Vanilla melee suppression — with the self-cancel trap closed. While a
     * module is applying its own computed damage, the event that call produces
     * is that module's hit landing, not a punch, and cancelling it would make
     * every simulated shot deal nothing.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (DamageTransactions.inProgress()) {
            return;
        }
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        ItemStack main = attacker.getInventory().getItemInMainHand();
        HeldItemHandler handler = owner(attacker, main);
        if (handler == null) {
            return;
        }
        try {
            if (handler.suppressVanillaMelee(attacker, event)) {
                event.setCancelled(true);
            }
        } catch (Throwable failure) {
            logFailure("melee suppression", failure);
            event.setCancelled(true);
        }
    }

    /** A held item that owns the hand never shows the vanilla arm punch. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        HeldItemHandler handler = owner(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand());
        if (handler != null && handler.freezeVanillaInteractions()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack main = player.getInventory().getItemInMainHand();
        HeldItemHandler handler = owner(player, main);
        if (handler == null) {
            return;
        }
        try {
            if (handler.onSwapHands(player, main)) {
                event.setCancelled(true);
            }
        } catch (Throwable failure) {
            logFailure("swap handling", failure);
            event.setCancelled(true);
        }
    }

    // Every route an item takes out of the hand ends in a release.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onItemHeld(PlayerItemHeldEvent event) {
        releaseAll(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        releaseAll(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()) {
            releaseAll(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onQuit(PlayerQuitEvent event) {
        releaseAll(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getEntity() instanceof Player player) {
            releaseAll(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            releaseAll(player);
        }
    }

    private void releaseAll(Player player) {
        for (HeldItemHandler handler : handlers) {
            try {
                handler.onRelease(player);
            } catch (Throwable failure) {
                logFailure("release handling", failure);
            }
        }
    }

    private void logFailure(String what, Throwable failure) {
        plugin.getLogger().warning("[input] held-item " + what + " failed: " + failure);
    }

    /** Lets both entity-interact events share one routing path. */
    private interface CancelSink {
        void set(boolean cancelled);
    }

    /**
     * The Paper-only stop-using signal, in its own class so the missing event
     * type is only ever linked on a server that has it.
     */
    private final class PaperStopUsingSeam implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
        public void onStopUsing(io.papermc.paper.event.player.PlayerStopUsingItemEvent event) {
            Player player = event.getPlayer();
            ItemStack main = player.getInventory().getItemInMainHand();
            HeldItemHandler handler = owner(player, main);
            if (handler == null) {
                return;
            }
            try {
                handler.onRelease(player);
            } catch (Throwable failure) {
                logFailure("stop-using release", failure);
            }
        }
    }
}
