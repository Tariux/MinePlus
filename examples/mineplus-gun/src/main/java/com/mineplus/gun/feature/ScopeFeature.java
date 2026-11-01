package com.mineplus.gun.feature;

import com.mineplus.gun.GunServices;
import com.mineplus.gun.runtime.WeaponRuntime;
import com.mineplus.gun.scope.ScopeGui;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.module.ModuleFeature;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns the scope overlay: its clicks are the scoped trigger, and leaving the
 * screen is what unscopes. The scope state itself stays in the weapon runtime —
 * this feature is only the container's event plumbing.
 */
public final class ScopeFeature extends ModuleFeature implements Listener {

    private final GunServices services;

    public ScopeFeature(JavaPlugin plugin, PluginContext context, GunServices services) {
        super(plugin, context);
        this.services = services;
    }

    @Override
    public String id() {
        return "scope";
    }

    @Override
    protected void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ScopeGui gui) {
            gui.handleClick(event);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ScopeGui) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof ScopeGui
                && event.getPlayer() instanceof Player player) {
            WeaponRuntime runtime = services.runtime();
            if (runtime != null) {
                runtime.onScopeOverlayClosed(player);
            }
        }
    }

    @Override
    protected void onDisable() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ScopeGui) {
                player.closeInventory();
            }
        }
        HandlerList.unregisterAll(this);
    }
}
