package com.mineplus.infrastructure.input;

import com.mineplus.infrastructure.combat.DamageTransactions;
import com.mineplus.infrastructure.input.net.InputChannel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import org.bukkit.scheduler.BukkitTask;

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
 * <p><b>The two triggers.</b> A modern firearm-style item needs more than one
 * gesture: primary (left mouse) to act, secondary (right mouse) to aim or
 * place, and a reload key. Both buttons are resolved into their own
 * {@link TriggerState} (so a held left button and a held right button never
 * contaminate each other) through {@link #primaryTrigger(Player)} and
 * {@link #secondaryTrigger(Player)}. Left-click is fed by the swing stream the
 * client repeats while the button is held, plus block-damage events, so an
 * automatic action keeps running while the crosshair sits on a block — the
 * one case where the vanilla client stops repeating the use packet.
 * {@code onPress}/{@code onTriggerRelease} keep their historic meaning as the
 * secondary trigger, so existing handlers are untouched.</p>
 *
 * <p><b>Threading.</b> All callbacks are Bukkit event callbacks, so they run on
 * the main thread and may touch the world directly.
 */
public final class HeldItemInput implements Listener {

    /**
     * The gap inside which a second press continues the click the client
     * already started.
     *
     * <p>A modern client answers one physical click with up to two packets:
     * a click on a block sends the "use item on block" packet, and the client
     * follows it with the "use item in hand" packet when the block did not
     * consume the interaction. Both reach the server in the same tick, so the
     * second one is a continuation of the click — not a second click. A
     * module that cannot tell them apart fires twice per click, dry-fires a
     * weapon that is already reloading and spends two rounds where the player
     * pulled one.</p>
     *
     * <p>The window is deliberately short. It has to cover the round trip
     * between the two packets, and nothing more: a wider window would swallow
     * a fast, deliberate second click, and a weapon whose rate of fire is
     * quicker than that window would silently lose shots — a 280 rpm weapon
     * needs 214 ms between rounds.</p>
     */
    private static final long CLICK_WINDOW_MILLIS = 150L;

    /**
     * How long a hold survives without a new press packet. The client stops
     * sending when the button is released, so the silence is the release
     * signal for a carrier with no vanilla use action — the server never sees
     * one.
     *
     * <p>The window must comfortably exceed the pace of a deliberate click
     * stream: a player clicking faster than this would look like silence
     * between presses, and the hold would end under their fingers. 450 ms
     * covers a fast click stream while still ending the hold promptly enough
     * that a released trigger stops an automatic weapon almost immediately
     * instead of trailing half a second of extra rounds.</p>
     *
     * <p>It must also stay above the {@link #CLICK_WINDOW_MILLIS}, or a click
     * whose second packet arrives late would restart the hold instead of
     * continuing it.</p>
     */
    private static final long RELEASE_GRACE_MILLIS = 450L;

    /**
     * The longest a single hold may last before the Core ends it anyway.
     *
     * <p>The release is inferred from silence, so a client that keeps sending
     * repeats (a stuck button, a modded client, a packet flood) would hold a
     * trigger forever. This is the Core's backstop for the input layer, so a
     * module never has to invent its own hold cap.</p>
     */
    private static final long MAX_HOLD_MILLIS = 60_000L;

    /**
     * Window inside which a gesture the fast path already delivered is not
     * delivered again by the Bukkit event. Duplicate reports of one click
     * arrive within a tick; a deliberate second click is far slower, and this
     * stays below the click window so it never swallows one.
     */
    private static final long FAST_PATH_DEDUPE_MILLIS = 75L;

    /** Ticks between two scans of the trigger states. */
    private static final long TRIGGER_SCAN_TICKS = 1L;

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
         * entity). This is the <b>secondary</b> trigger; a gun maps it to
         * aiming, a wand to its cast.
         *
         * @return {@code true} when the vanilla action must stay suppressed;
         *         {@code false} lets the carrier do its vanilla thing
         */
        boolean onPress(Player player, ItemStack held);

        /**
         * The <b>primary</b> trigger was pressed (left mouse, in air, on a
         * block, or aimed at an entity). Invoked once per new gesture;
         * whether the button is still held is read from
         * {@link #primaryTrigger(Player)}.
         *
         * @return {@code true} to keep the vanilla action suppressed
         */
        default boolean onPrimaryPress(Player player, ItemStack held) {
            return false;
        }

        /**
         * The primary gesture ended while the item stayed in the hand. Stops
         * automatic actions; a semi-automatic one simply waits for the next
         * press.
         */
        default void onPrimaryRelease(Player player) {
        }

        /**
         * The secondary gesture press. Defaults to the legacy
         * {@link #onPress(Player, ItemStack)} contract.
         */
        default boolean onSecondaryPress(Player player, ItemStack held) {
            return onPress(player, held);
        }

        /**
         * The secondary gesture ended while the item stayed in the hand.
         * Defaults to the legacy {@link #onTriggerRelease(Player)} contract.
         */
        default void onSecondaryRelease(Player player) {
            onTriggerRelease(player);
        }

        /**
         * The item left the hand by any route (slot switch, drop, quit,
         * death). Use it to persist whatever the item was counting and to
         * reset item-specific session state.
         */
        default void onRelease(Player player) {
        }

        /**
         * The activation gesture ended while the item stayed in the hand: the
         * player let go of the trigger, or the client reported the end of a
         * use gesture. Automatic fire stops here; a semi-automatic action
         * simply waits for the next press.
         *
         * <p>Distinct from {@link #onRelease(Player)}, which means the item
         * itself is gone.</p>
         */
        default void onTriggerRelease(Player player) {
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
    private final Map<UUID, TriggerState> secondaryTriggers = new ConcurrentHashMap<>();
    private final Map<UUID, TriggerState> primaryTriggers = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> fastDeliveries = new ConcurrentHashMap<>();
    private final Listener paperStopUsing;
    private volatile InputSettings inputSettings = InputSettings.defaults();
    private volatile InputChannel fastPath;
    private boolean registered;
    private BukkitTask triggerTask;

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
        startFastPath();
        return true;
    }

    /**
     * Applies the {@code INPUT} settings slice (called on enable and reload).
     * With {@code FAST_PATH: false} no channel is touched and no task exists;
     * turning it off at runtime removes the Netty handler again.
     */
    public void updateSettings(InputSettings settings) {
        this.inputSettings = settings == null ? InputSettings.defaults() : settings;
        if (!inputSettings.fastPath()) {
            stopFastPath();
        } else {
            startFastPath();
        }
    }

    /** {@code true} while the network fast path is installed and delivering. */
    public boolean fastPathActive() {
        InputChannel channel = fastPath;
        return channel != null && channel.active();
    }

    private void startFastPath() {
        if (!inputSettings.fastPath() || fastPath != null) {
            return;
        }
        InputChannel channel = new InputChannel(plugin, this::onFastInput);
        fastPath = channel.start() ? channel : null;
    }

    private void stopFastPath() {
        InputChannel channel = fastPath;
        fastPath = null;
        if (channel != null) {
            channel.shutdown();
        }
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
        stopFastPath();
        BukkitTask current = triggerTask;
        triggerTask = null;
        if (current != null) {
            current.cancel();
        }
        secondaryTriggers.clear();
        primaryTriggers.clear();
        fastDeliveries.clear();
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
    // Trigger state
    // ------------------------------------------------------------------

    /**
     * The Core-owned trigger state of one player for the <b>secondary</b>
     * (right mouse) gesture: whether it is held, and whether the last
     * activation was a fresh press or the client repeating the one it already
     * sent. Never null. This is the legacy {@code trigger(...)} accessor.
     */
    public TriggerState trigger(Player player) {
        return secondaryTrigger(player);
    }

    /** The secondary (right mouse) trigger state of one player. Never null. */
    public TriggerState secondaryTrigger(Player player) {
        return secondaryTriggers.computeIfAbsent(player.getUniqueId(), ignored -> new TriggerState());
    }

    /** The primary (left mouse) trigger state of one player. Never null. */
    public TriggerState primaryTrigger(Player player) {
        return primaryTriggers.computeIfAbsent(player.getUniqueId(), ignored -> new TriggerState());
    }

    /** Records a secondary activation; returns {@code true} for a new gesture. */
    private boolean recordPress(Player player) {
        boolean fresh = secondaryTrigger(player).press(now(), CLICK_WINDOW_MILLIS);
        armTriggerTask();
        return fresh;
    }

    /** Records a primary activation; returns {@code true} for a new gesture. */
    private boolean recordPrimaryPress(Player player) {
        boolean fresh = primaryTrigger(player).press(now(), CLICK_WINDOW_MILLIS);
        armTriggerTask();
        return fresh;
    }

    /** Ends the secondary hold and tells the owning handler the gesture ended. */
    private void recordRelease(Player player) {
        TriggerState state = secondaryTriggers.get(player.getUniqueId());
        if (state == null || !state.release(now())) {
            return;
        }
        notifyTriggerRelease(player, false);
    }

    /** Ends the primary hold and tells the owning handler the gesture ended. */
    private void recordPrimaryRelease(Player player) {
        TriggerState state = primaryTriggers.get(player.getUniqueId());
        if (state == null || !state.release(now())) {
            return;
        }
        notifyTriggerRelease(player, true);
    }

    private void recordBothReleases(Player player) {
        recordPrimaryRelease(player);
        recordRelease(player);
    }

    /**
     * One gesture read at network arrival, delivered on the main thread at tick
     * start. It goes through the same trigger fold and handler callbacks as the
     * event path; the delivery claim keeps the Bukkit event that follows in the
     * same tick from counting the click again.
     */
    private void onFastInput(Player player, boolean primary) {
        ItemStack main = player.getInventory().getItemInMainHand();
        HeldItemHandler handler = owner(player, main);
        if (handler == null) {
            return;
        }
        if (primary) {
            routePrimaryPress(player, handler, main);
            return;
        }
        recordPress(player);
        if (!claimFastDelivery(player.getUniqueId(), false)) {
            return;
        }
        try {
            handler.onSecondaryPress(player, main);
        } catch (Throwable failure) {
            logFailure("fast-path press handling", failure);
        }
    }

    /**
     * Claims one gesture delivery per player inside the dedupe window. With the
     * fast path off this is never called, so the event path is unchanged.
     */
    private boolean claimFastDelivery(UUID playerId, boolean primary) {
        long[] slot = fastDeliveries.computeIfAbsent(playerId, ignored -> new long[2]);
        int index = primary ? 0 : 1;
        long now = now();
        synchronized (slot) {
            if (slot[index] != 0L && now - slot[index] <= FAST_PATH_DEDUPE_MILLIS) {
                return false;
            }
            slot[index] = now;
            return true;
        }
    }

    /**
     * Ends every stale hold: the client stopped repeating the press, so the
     * player let go of the button. The task is armed only while somebody holds
     * a trigger and retires as soon as nobody does. Both gestures are scanned;
     * a player can legitimately hold both buttons at once.
     */
    private void scanTriggers() {
        long now = now();
        boolean anyHeld = scan(primaryTriggers, now, true);
        anyHeld |= scan(secondaryTriggers, now, false);
        if (!anyHeld) {
            BukkitTask current = triggerTask;
            triggerTask = null;
            if (current != null) {
                current.cancel();
            }
        }
    }

    private boolean scan(Map<UUID, TriggerState> states, long now, boolean primary) {
        boolean anyHeld = false;
        for (Map.Entry<UUID, TriggerState> entry : states.entrySet()) {
            TriggerState state = entry.getValue();
            if (!state.held()) {
                continue;
            }
            boolean expired = state.expire(now, RELEASE_GRACE_MILLIS);
            if (!expired && now - state.heldSince() > MAX_HOLD_MILLIS) {
                // The client never stopped repeating. Cut the hold anyway so a
                // stuck button cannot pin a trigger down forever.
                state.release(now);
                expired = true;
            }
            if (expired) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null) {
                    notifyTriggerRelease(player, primary);
                }
            }
            anyHeld |= state.held();
        }
        return anyHeld;
    }

    private void notifyTriggerRelease(Player player, boolean primary) {
        HeldItemHandler handler = owner(player, player.getInventory().getItemInMainHand());
        if (handler == null) {
            return;
        }
        try {
            if (primary) {
                handler.onPrimaryRelease(player);
            } else {
                handler.onSecondaryRelease(player);
            }
        } catch (Throwable failure) {
            logFailure(primary ? "primary release handling" : "trigger release handling", failure);
        }
    }

    private void armTriggerTask() {
        if (triggerTask != null || !plugin.isEnabled()) {
            return;
        }
        triggerTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::scanTriggers, TRIGGER_SCAN_TICKS, TRIGGER_SCAN_TICKS);
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    /**
     * The press funnel. Right-click is the secondary activation, on air, on a
     * block, or on an entity — the last one arrives through the entity-interact
     * events below, which is exactly why a module that only listens to
     * {@link PlayerInteractEvent} never hits what it aims at. Left-click is the
     * primary activation; it is fed from here and from the swing stream, which
     * is what keeps an automatic action alive while the player holds the
     * button.
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
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            routePrimaryPress(player, handler, main);
            if (handler.freezeVanillaInteractions()) {
                event.setCancelled(true);
            }
            return;
        }
        boolean isPress = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        if (!isPress) {
            if (handler.freezeVanillaInteractions()) {
                event.setCancelled(true);
            }
            return;
        }
        boolean handled = false;
        recordPress(player);
        if (!fastPathActive() || claimFastDelivery(player.getUniqueId(), false)) {
            try {
                handled = handler.onSecondaryPress(player, main);
            } catch (Throwable failure) {
                logFailure("press handling", failure);
                handled = true; // still suppress vanilla: we own this gesture
            }
        }
        if (handled || handler.freezeVanillaInteractions()) {
            event.setCancelled(true);
        }
    }

    /**
     * One primary activation, deduplicated across the three event sources that
     * can report the same physical click (left-click air/block, block damage,
     * arm swing). Only a new gesture reaches the handler; a held button is
     * read from {@link #primaryTrigger(Player)}.
     */
    private void routePrimaryPress(Player player, HeldItemHandler handler, ItemStack main) {
        if (!recordPrimaryPress(player)) {
            return;
        }
        if (fastPathActive() && !claimFastDelivery(player.getUniqueId(), true)) {
            return;
        }
        try {
            handler.onPrimaryPress(player, main);
        } catch (Throwable failure) {
            logFailure("primary press handling", failure);
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
        recordPress(player);
        if (!fastPathActive() || claimFastDelivery(player.getUniqueId(), false)) {
            try {
                handler.onSecondaryPress(player, main);
            } catch (Throwable failure) {
                logFailure("aimed press handling", failure);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        ItemStack main = player.getInventory().getItemInMainHand();
        HeldItemHandler handler = owner(player, main);
        if (handler == null) {
            return;
        }
        // Mining a block is the one left-button hold where the client stops
        // repeating the use packet; the damage stream keeps the primary
        // gesture alive so automatic actions do not die on a wall.
        routePrimaryPress(player, handler, main);
        if (handler.freezeVanillaInteractions()) {
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
        // A left-click on an entity is a primary activation too. The swing
        // event normally arrives first and this press is deduplicated by the
        // click window; when it does not (synthetic attacks, some forks), this
        // is the only report the handler ever gets.
        routePrimaryPress(attacker, handler, main);
        try {
            if (handler.suppressVanillaMelee(attacker, event)) {
                event.setCancelled(true);
            }
        } catch (Throwable failure) {
            logFailure("melee suppression", failure);
            event.setCancelled(true);
        }
    }

    /**
     * The client's swing stream: one arm swing per left click, repeated while
     * the button is held. This is the primary press source, and the vanilla
     * punch is hidden while an owned item is in hand.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack main = player.getInventory().getItemInMainHand();
        HeldItemHandler handler = owner(player, main);
        if (handler == null) {
            return;
        }
        routePrimaryPress(player, handler, main);
        if (handler.freezeVanillaInteractions()) {
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
            // Sneaking lowers the weapon; it does not remove it from the hand.
            recordBothReleases(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onQuit(PlayerQuitEvent event) {
        releaseAll(event.getPlayer());
        // The player is gone: its trigger states are dead weight in the maps.
        primaryTriggers.remove(event.getPlayer().getUniqueId());
        secondaryTriggers.remove(event.getPlayer().getUniqueId());
        fastDeliveries.remove(event.getPlayer().getUniqueId());
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
            // Closing a menu ends the trigger, not the weapon session.
            recordBothReleases(player);
        }
    }

    private void releaseAll(Player player) {
        long now = now();
        TriggerState primary = primaryTriggers.get(player.getUniqueId());
        if (primary != null) {
            // The item is gone: the hold ends with it. The handler learns
            // about it through onRelease, which is the stronger signal.
            primary.release(now);
        }
        TriggerState secondary = secondaryTriggers.get(player.getUniqueId());
        if (secondary != null) {
            secondary.release(now);
        }
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
            // Stop-using ends the trigger gesture; the item is still in hand,
            // so this must not invoke the stronger item-release callback.
            recordRelease(player);
        }
    }
}