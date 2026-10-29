package com.mineplus.gun;

import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.hud.AmmoHud;
import com.mineplus.gun.runtime.WeaponRuntime;
import com.mineplus.gun.shop.EconomyBridge;
import com.mineplus.gun.shop.ShopService;
import com.mineplus.gun.weapon.WeaponItemFactory;
import com.mineplus.gun.weapon.WeaponRegistry;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.combat.Ballistics;

/**
 * Shared, reloadable services of the module, wired once by the plugin main and
 * handed to each feature.
 *
 * <p>Only what the module <em>owns</em> is held here: its config, its weapon
 * registry and item factory, its HUD, its shop and its weapon runtime.
 * Everything that is a server-wide runtime service — the projectile engine, the
 * particle budget, held-item input routing, magazine persistence — is reached
 * through the Core's {@link PluginContext}, so the module never keeps a second
 * copy of a mechanism the Core already meters globally.
 */
public final class GunServices implements ShopService.GunServicesProvider {

    private final MineplusGunPlugin plugin;
    private final PluginContext context;

    private volatile GunConfig config;
    private volatile WeaponRegistry registry;
    private volatile WeaponItemFactory itemFactory;
    private volatile AmmoHud ammoHud;
    private volatile EconomyBridge economy;
    private volatile ShopService shopService;
    private volatile WeaponRuntime runtime;

    public GunServices(MineplusGunPlugin plugin, PluginContext context) {
        this.plugin = plugin;
        this.context = context;
    }

    public MineplusGunPlugin plugin() {
        return plugin;
    }

    public PluginContext context() {
        return context;
    }

    /** The Core's projectile engine — rounds, tracers, impacts and damage. */
    public Ballistics ballistics() {
        return context.ballistics();
    }

    public GunConfig config() {
        return config;
    }

    public void setConfig(GunConfig config) {
        this.config = config;
    }

    public WeaponRegistry registry() {
        return registry;
    }

    public void setRegistry(WeaponRegistry registry) {
        this.registry = registry;
    }

    public WeaponItemFactory itemFactory() {
        return itemFactory;
    }

    public void setItemFactory(WeaponItemFactory itemFactory) {
        this.itemFactory = itemFactory;
    }

    public AmmoHud ammoHud() {
        return ammoHud;
    }

    public void setAmmoHud(AmmoHud ammoHud) {
        this.ammoHud = ammoHud;
    }

    public EconomyBridge economy() {
        return economy;
    }

    public void setEconomy(EconomyBridge economy) {
        this.economy = economy;
    }

    public ShopService shopService() {
        return shopService;
    }

    public void setShopService(ShopService shopService) {
        this.shopService = shopService;
    }

    public WeaponRuntime runtime() {
        return runtime;
    }

    public void setRuntime(WeaponRuntime runtime) {
        this.runtime = runtime;
    }
}
