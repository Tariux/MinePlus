package com.mineplus.gun;

import com.mineplus.MineplusPlugin;
import com.mineplus.gun.command.GunSubCommand;
import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.config.WeaponConfigLoader;
import com.mineplus.gun.feature.CombatFeature;
import com.mineplus.gun.feature.HudFeature;
import com.mineplus.gun.feature.ShopFeature;
import com.mineplus.gun.feature.WeaponFeature;
import com.mineplus.gun.fx.ParticleFx;
import com.mineplus.gun.fx.SoundFx;
import com.mineplus.gun.hud.AmmoHud;
import com.mineplus.gun.shop.EconomyBridge;
import com.mineplus.gun.shop.ShopService;
import com.mineplus.gun.weapon.WeaponItemFactory;
import com.mineplus.gun.weapon.WeaponRegistry;
import com.mineplus.infrastructure.PluginContext;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MineplusGun — the combat/weapons module on top of the Mineplus Core.
 *
 * <p>Bootstrap mirrors the Core's other modules: build the shared services,
 * start every feature (install assets + register items/listeners, no reload),
 * perform exactly one coordinated {@code reloadAll()}, then register
 * {@code /mpgun} and {@code /mineplus gun}. Teardown runs in reverse.
 */
public final class MineplusGunPlugin extends JavaPlugin {

    private PluginContext context;
    private GunServices services;
    private final List<ModuleFeature> features = new ArrayList<>();
    private WeaponFeature weaponFeature;

    @Override
    public void onEnable() {
        Plugin core = Bukkit.getPluginManager().getPlugin("Mineplus");
        if (!(core instanceof MineplusPlugin mineplus)) {
            getLogger().severe("============================================================");
            getLogger().severe("[MineplusGun] FATAL: Mineplus Core plugin was not found.");
            getLogger().severe("[MineplusGun] Install 'Mineplus.jar' (Core) into your plugins/ folder first.");
            getLogger().severe("============================================================");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.context = mineplus.getPluginContext();
        if (this.context == null) {
            getLogger().severe("[MineplusGun] Mineplus Core is not initialized; retry after Core enables.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        GunConfig config = WeaponConfigLoader.load(getConfig());

        services = new GunServices(this, context);
        services.setConfig(config);

        WeaponRegistry registry = new WeaponRegistry();
        registry.reload(config.weapons());
        services.setRegistry(registry);
        services.setItemFactory(new WeaponItemFactory(this, context.itemRegistry(), context.packApi()));
        services.setParticleFx(new ParticleFx(config.ballistics().maxParticlesPerTick()));
        services.setSoundFx(new SoundFx());
        services.setAmmoHud(new AmmoHud(config));

        EconomyBridge economy = new EconomyBridge();
        services.setEconomy(economy);
        services.setShopService(new ShopService(services, economy));

        weaponFeature = new WeaponFeature(this, context, services);
        features.add(weaponFeature);
        features.add(new CombatFeature(this, context, services));
        features.add(new HudFeature(this, context, services));
        features.add(new ShopFeature(this, context, services));

        for (ModuleFeature feature : features) {
            feature.start();
        }

        // One coordinated load of everything the features just installed.
        context.jsonInfrastructureApi().reloadAll();

        registerCommands();

        getLogger().info("[MineplusGun] " + features.size() + " feature(s) enabled with "
                + registry.size() + " weapon(s)"
                + (economy.available() ? " (Vault economy active)." : " (no Vault; shop delivers free)."));
    }

    private void registerCommands() {
        GunSubCommand gunCommand = new GunSubCommand("gun", this, services, weaponFeature::reload);
        GunSubCommand rootCommand = new GunSubCommand("mpgun", this, services, weaponFeature::reload);

        // /mineplus gun via the new Core routing hook.
        context.moduleSupport().registerCoreSubCommand(gunCommand);
        // /mpgun as a module-owned top-level command (no plugin.yml entry).
        context.moduleSupport().registerCommand(this, "mpgun", rootCommand);
    }

    @Override
    public void onDisable() {
        for (int i = features.size() - 1; i >= 0; i--) {
            features.get(i).stop();
        }
        features.clear();
        weaponFeature = null;
        services = null;
        context = null;
    }

    /** The live module services (for API consumers), or null while disabled. */
    public GunServices services() {
        return services;
    }
}
