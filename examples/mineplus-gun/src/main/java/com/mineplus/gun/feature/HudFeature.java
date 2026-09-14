package com.mineplus.gun.feature;

import com.mineplus.gun.GunServices;
import com.mineplus.gun.ModuleFeature;
import com.mineplus.infrastructure.PluginContext;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns the ammo HUD lifecycle: clears a player's HUD/boss bar on quit and
 * exposes the HUD service to the command tree.
 */
public final class HudFeature extends ModuleFeature implements Listener {

    private final GunServices services;

    public HudFeature(JavaPlugin plugin, PluginContext context, GunServices services) {
        super(plugin, context);
        this.services = services;
    }

    @Override
    public String id() {
        return "hud";
    }

    @Override
    protected void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (services.ammoHud() != null) {
            services.ammoHud().clear(event.getPlayer().getUniqueId());
        }
    }

    @Override
    protected void onDisable() {
        if (services.ammoHud() != null) {
            services.ammoHud().clearAll();
        }
        org.bukkit.event.HandlerList.unregisterAll(this);
    }
}
