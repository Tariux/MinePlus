package com.mineplus.gun.feature;

import com.mineplus.gun.GunServices;
import com.mineplus.gun.runtime.GunRuntime;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.input.HeldItemInput;
import com.mineplus.infrastructure.module.ModuleFeature;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns the shooting runtime: the fire-flow state machine and its lifecycle.
 *
 * <p>Input is not installed here — the module registers a
 * {@link HeldItemInput.HeldItemHandler} with the Core's shared routing service,
 * which already owns the press funnel, the vanilla freeze, the aimed-entity
 * press and the melee arbitration for every held-item module on the server. The
 * one thing this feature still listens for directly is the quit: the magazine
 * must be persisted while the player's inventory is still readable.
 */
public final class CombatFeature extends ModuleFeature implements Listener {

    private final GunServices services;

    private GunRuntime runtime;
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
        runtime = new GunRuntime(plugin, services);
        services.setRuntime(runtime);
        handler = runtime;
        context.heldItemInput().addHandler(runtime);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        runtime.start();

        plugin.getLogger().info("[MineplusGun] Combat runtime started (input="
                + "core held-item routing, handlers=" + context.heldItemInput().handlerCount() + ").");
    }

    /**
     * Persists the quitting player's live magazine before their inventory is
     * saved and drops the session (the tick loop only skips offline players, so
     * without this the session would linger).
     */
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
