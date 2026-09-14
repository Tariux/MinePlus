package com.mineplus.gun.feature;

import com.mineplus.gun.GunServices;
import com.mineplus.gun.ModuleFeature;
import com.mineplus.gun.shop.ShopGui;
import com.mineplus.infrastructure.PluginContext;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns the shop GUI's click handling. */
public final class ShopFeature extends ModuleFeature implements Listener {

    private final GunServices services;

    public ShopFeature(JavaPlugin plugin, PluginContext context, GunServices services) {
        super(plugin, context);
        this.services = services;
    }

    @Override
    public String id() {
        return "shop";
    }

    @Override
    protected void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof ShopGui gui) {
            gui.handleClick(event);
        }
    }

    @Override
    protected void onDisable() {
        org.bukkit.event.HandlerList.unregisterAll(this);
    }
}
