package com.mineplus.gun;

import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.fx.ParticleFx;
import com.mineplus.gun.fx.SoundFx;
import com.mineplus.gun.hud.AmmoHud;
import com.mineplus.gun.runtime.GunRuntime;
import com.mineplus.gun.shop.EconomyBridge;
import com.mineplus.gun.shop.ShopService;
import com.mineplus.gun.weapon.WeaponItemFactory;
import com.mineplus.gun.weapon.WeaponRegistry;
import com.mineplus.infrastructure.PluginContext;

/**
 * Shared, reloadable services of the module, wired once by the plugin main and
 * handed to each feature. Keeping the graph in one place lets every feature
 * stay small and lets reload swap the config/registry without re-wiring.
 */
public final class GunServices implements ShopService.GunServicesProvider {

    private final MineplusGunPlugin plugin;
    private final PluginContext context;

    private volatile GunConfig config;
    private volatile WeaponRegistry registry;
    private volatile WeaponItemFactory itemFactory;
    private volatile ParticleFx particleFx;
    private volatile SoundFx soundFx;
    private volatile AmmoHud ammoHud;
    private volatile EconomyBridge economy;
    private volatile ShopService shopService;
    private volatile GunRuntime runtime;

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

    public ParticleFx particleFx() {
        return particleFx;
    }

    public void setParticleFx(ParticleFx particleFx) {
        this.particleFx = particleFx;
    }

    public SoundFx soundFx() {
        return soundFx;
    }

    public void setSoundFx(SoundFx soundFx) {
        this.soundFx = soundFx;
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

    public GunRuntime runtime() {
        return runtime;
    }

    public void setRuntime(GunRuntime runtime) {
        this.runtime = runtime;
    }
}
