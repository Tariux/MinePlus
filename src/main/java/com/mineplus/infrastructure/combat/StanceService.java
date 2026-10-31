package com.mineplus.infrastructure.combat;

import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.display.DisplayTransport;
import com.mineplus.infrastructure.virtual.display.DisplayTransportSettings;
import com.mineplus.util.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Observer-only equipment "stance" channel.
 *
 * <p>A module asks for one observer to see an entity in a different stance
 * ({@code apply}) by naming the stance and, optionally, the equipment stacks
 * that stance implies. The service sends that observer a
 * {@code ClientboundSetEquipmentPacket} through the display transport's packet
 * optimizer — it never touches the target's real equipment or inventory, and two
 * observers can hold different stances of the same entity at the same time.</p>
 *
 * <p>The last sent map per (observer, target) suppresses duplicate packets; a
 * stance changed, an inventory close, a join/respawn/world change, a target's
 * held-item change and a target death invalidate or resend it. Everything here
 * is main-thread state (player/entity reads), and the service is entirely inert
 * when {@code DISPLAY_TRANSPORT.STANCES.ENABLED} is false or the packet
 * transport is not running.</p>
 */
public final class StanceService implements Listener {

    private static final class AppliedStance {
        String stanceId;
        final EnumMap<EquipmentSlot, ItemStack> override = new EnumMap<>(EquipmentSlot.class);
        final EnumMap<EquipmentSlot, ItemStack> sent = new EnumMap<>(EquipmentSlot.class);
    }

    private final JavaPlugin plugin;
    private final VirtualBlockManager virtualBlockManager;
    private final DisplayTransportSettings settings;
    private final boolean enabled;

    /** observer -> target -> applied stance. */
    private final Map<UUID, Map<UUID, AppliedStance>> byObserver = new HashMap<>();
    /** target -> observers that track it. */
    private final Map<UUID, Set<UUID>> observersByTarget = new HashMap<>();
    private final Set<String> registeredStances = new TreeSet<>();

    public StanceService(JavaPlugin plugin, VirtualBlockManager virtualBlockManager,
                         DisplayTransportSettings settings) {
        this.plugin = plugin;
        this.virtualBlockManager = virtualBlockManager;
        this.settings = settings == null ? DisplayTransportSettings.defaults() : settings;
        DisplayTransport transport = transport();
        this.enabled = this.settings.stancesEnabled() && transport != null && transport.isRunning();
    }

    /** {@code true} when the transport is running and the stance channel is enabled. */
    public boolean enabled() {
        return enabled && transportRunning();
    }

    /** Registers a stance id for content discovery ({@link #availableStances()}). */
    public void registerStance(String stanceId) {
        if (stanceId == null || stanceId.isBlank()) {
            return;
        }
        registeredStances.add(stanceId.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** Sorted snapshot of the stance ids modules registered. */
    public List<String> availableStances() {
        return List.copyOf(registeredStances);
    }

    /**
     * Makes {@code observer} see {@code target} in {@code stanceId} with the
     * given equipment stacks (missing slots keep their real item). The target's
     * own inventory/equipment is never modified.
     */
    public void apply(Player observer, Entity target, String stanceId, Map<EquipmentSlot, ItemStack> equipment) {
        if (!enabled() || observer == null || target == null) {
            return;
        }
        UUID observerId = observer.getUniqueId();
        UUID targetId = target.getUniqueId();
        if (observerId.equals(targetId)) {
            return; // the local player's own first-person hand stays untouched
        }
        AppliedStance stance = byObserver
                .computeIfAbsent(observerId, k -> new HashMap<>())
                .computeIfAbsent(targetId, k -> new AppliedStance());
        stance.stanceId = stanceId;
        stance.override.clear();
        if (equipment != null) {
            for (Map.Entry<EquipmentSlot, ItemStack> entry : equipment.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                stance.override.put(entry.getKey(), entry.getValue().clone());
            }
        }
        observersByTarget.computeIfAbsent(targetId, k -> new HashSet<>()).add(observerId);
        sync(observer, target, stance, false);
    }

    /** Drops one observer's stance for one target and restores the real equipment. */
    public void clear(Player observer, Entity target) {
        if (observer == null || target == null) {
            return;
        }
        UUID observerId = observer.getUniqueId();
        UUID targetId = target.getUniqueId();
        Map<UUID, AppliedStance> targets = byObserver.get(observerId);
        AppliedStance stance = targets == null ? null : targets.remove(targetId);
        if (targets != null && targets.isEmpty()) {
            byObserver.remove(observerId);
        }
        Set<UUID> watchers = observersByTarget.get(targetId);
        if (watchers != null) {
            watchers.remove(observerId);
            if (watchers.isEmpty()) {
                observersByTarget.remove(targetId);
            }
        }
        if (stance == null) {
            return;
        }
        // Re-send the real item for every slot the stance ever touched.
        stance.override.clear();
        sync(observer, target, stance, true);
    }

    /** Drops every observer's stance for one target. */
    public void clearAll(Entity target) {
        if (target == null) {
            return;
        }
        Set<UUID> watchers = observersByTarget.get(target.getUniqueId());
        if (watchers == null || watchers.isEmpty()) {
            return;
        }
        for (UUID observerId : new ArrayList<>(watchers)) {
            Player observer = Bukkit.getPlayer(observerId);
            if (observer != null) {
                clear(observer, target);
            } else {
                forget(observerId);
            }
        }
    }

    /** Re-sends every stance this observer holds (client state was reset). */
    public void resend(Player observer) {
        if (!enabled() || observer == null) {
            return;
        }
        Map<UUID, AppliedStance> targets = byObserver.get(observer.getUniqueId());
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, AppliedStance> entry : new ArrayList<>(targets.entrySet())) {
            Entity target = Bukkit.getEntity(entry.getKey());
            if (target == null || !target.isValid()) {
                continue;
            }
            sync(observer, target, entry.getValue(), true);
        }
    }

    /** Drops an observer's stance state (quit path). */
    public void forget(UUID observerId) {
        Map<UUID, AppliedStance> targets = byObserver.remove(observerId);
        if (targets == null) {
            return;
        }
        for (UUID targetId : targets.keySet()) {
            Set<UUID> watchers = observersByTarget.get(targetId);
            if (watchers != null) {
                watchers.remove(observerId);
                if (watchers.isEmpty()) {
                    observersByTarget.remove(targetId);
                }
            }
        }
    }

    /** Clears all state; called on Core shutdown. */
    public void shutdown() {
        byObserver.clear();
        observersByTarget.clear();
        registeredStances.clear();
    }

    // ------------------------------------------------------------------ sending

    private void sync(Player observer, Entity target, AppliedStance stance, boolean force) {
        if (!observer.isOnline() || target.getWorld() != observer.getWorld()) {
            return;
        }
        DisplayTransport transport = transport();
        if (transport == null || !transport.isRunning()) {
            return;
        }
        org.bukkit.inventory.EntityEquipment equipment =
                target instanceof org.bukkit.entity.LivingEntity living ? living.getEquipment() : null;
        int entityId = target.getEntityId();
        int queued = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack override = stance.override.get(slot);
            ItemStack touched = stance.sent.get(slot);
            if (override == null && touched == null) {
                continue; // never part of this stance: leave the vanilla view alone
            }
            ItemStack wanted = override != null ? override
                    : equipment == null ? air() : equipment.getItem(slot);
            if (wanted == null) {
                wanted = air();
            }
            if (!force && wanted.equals(touched)) {
                continue;
            }
            try {
                transport.packets().queue(observer,
                        transport.nms().equipmentPacket(entityId, slot, wanted));
                queued++;
                stance.sent.put(slot, wanted.clone());
            } catch (Throwable failure) {
                // A slot the runtime cannot encode (older/newer slot enum) must
                // never break the caller's gameplay action.
                DebugLogger.debug("StanceService: equipment packet skipped for "
                        + slot + ": " + failure.getMessage());
            }
        }
        if (queued > 0) {
            transport.requestFlush();
        }
    }

    private static ItemStack air() {
        return new ItemStack(Material.AIR);
    }

    private DisplayTransport transport() {
        return virtualBlockManager == null ? null : virtualBlockManager.displayTransport();
    }

    private boolean transportRunning() {
        DisplayTransport transport = transport();
        return transport != null && transport.isRunning();
    }

    /** Re-sends the real (non-overridden) slots after a target's equipment changed. */
    private void syncTargetEquipment(Player target) {
        Set<UUID> watchers = observersByTarget.get(target.getUniqueId());
        if (watchers == null || watchers.isEmpty()) {
            return;
        }
        for (UUID observerId : new ArrayList<>(watchers)) {
            Player observer = Bukkit.getPlayer(observerId);
            Map<UUID, AppliedStance> targets = byObserver.get(observerId);
            AppliedStance stance = targets == null ? null : targets.get(target.getUniqueId());
            if (observer != null && stance != null) {
                sync(observer, target, stance, false);
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle hooks

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        resend(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!enabled()) {
            return;
        }
        // The respawn packet resets the client's equipment view; resend once the
        // respawn has been committed, on both sides of the channel.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            resend(player);
            syncTargetEquipment(player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        resend(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (enabled()) {
            syncTargetEquipment(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!enabled() || !settings.stanceResendOnInventoryClose()) {
            return;
        }
        if (event.getPlayer() instanceof Player player) {
            // Closing an inventory re-syncs the client's own view and may have
            // changed the player's equipment (the target side).
            resend(player);
            syncTargetEquipment(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        clearAll(event.getEntity());
    }

    @SuppressWarnings("removal") // Bukkit's event still fires on both Paper and Spigot
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent event) {
        clearAll(event.getEntity());
    }
}
