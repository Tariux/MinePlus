package com.mineplus.gun.feature;

import com.mineplus.gun.GunServices;
import com.mineplus.gun.runtime.WeaponRuntime;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.input.HeldItemInput;
import com.mineplus.infrastructure.module.ModuleFeature;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns the weapon runtime: the fire-flow state machine and its lifecycle.
 *
 * <p>Input is not installed here — the module registers a
 * {@link HeldItemInput.HeldItemHandler} with the Core's shared routing service,
 * which already owns the press funnel, the vanilla freeze and the primary
 * (scope) gesture for every held-item module on the server. This feature only
 * tells the runtime when the hand's contents changed, because a session binds
 * to one stack: the Core persists the old magazine on release, and the runtime
 * re-reads the new stack one tick later so the equip pose and HUD are right.</p>
 *
 * <p>The quit listener is deliberately direct: the magazine must be persisted
 * while the player's inventory is still readable, and the tick loop only skips
 * offline players, so without it the session would linger.</p>
 */
public final class CombatFeature extends ModuleFeature implements Listener {

    private final GunServices services;

    private WeaponRuntime runtime;
    private HeldItemInput.HeldItemHandler handler;

    public CombatFeature(JavaPlugin plugin, PluginContext context, GunServices services) {
        super(plugin, context);
        this.services = services;
    }

    @Override
    public String id() {
        return "combat";
    }

    @Override
    protected void onEnable() {
        runtime = new WeaponRuntime(services.plugin(), services);
        services.setRuntime(runtime);
        handler = runtime;
        context.heldItemInput().addHandler(runtime);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        runtime.start();

        // Players already online when the module (re)loads need their HUD and
        // resting pose; one deferred pass, never a per-tick scan.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (runtime == null) {
                return;
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                runtime.refresh(player);
            }
        });

        plugin.getLogger().info("[MineplusGun] Combat runtime started (input=core held-item routing, handlers="
                + context.heldItemInput().handlerCount() + ").");
    }

    /**
     * A different slot is now in hand. The Core releases the old stack (which
     * flushes its magazine); the new stack's session opens next tick, after
     * every listener has finished with the event.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onItemHeld(PlayerItemHeldEvent event) {
        refreshLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refreshLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        refreshLater(event.getPlayer());
    }

    private void refreshLater(Player player) {
        WeaponRuntime active = runtime;
        if (active == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (runtime == active && player.isOnline()) {
                active.refresh(player);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (runtime != null) {
            runtime.clear(event.getPlayer());
        }
    }

    @Override
    protected void onDisable() {
        HeldItemInput.HeldItemHandler attached = handler;
        if (attached != null) {
            context.heldItemInput().removeHandler(attached);
            handler = null;
        }
        if (runtime != null) {
            runtime.stop();
            runtime = null;
        }
        services.setRuntime(null);
        HandlerList.unregisterAll(this);
    }
}
