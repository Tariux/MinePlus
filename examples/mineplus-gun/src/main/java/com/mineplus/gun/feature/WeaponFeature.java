package com.mineplus.gun.feature;

import com.mineplus.gun.GunServices;
import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.config.WeaponConfigLoader;
import com.mineplus.gun.runtime.WeaponRuntime;
import com.mineplus.gun.weapon.WeaponAssets;
import com.mineplus.gun.weapon.WeaponDefinition;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.module.ModuleFeature;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns content: installs the shipped gun assets, registers every weapon item
 * with the pack, and re-runs the whole content pipeline on reload.
 *
 * <p>Presentation is discovered, not declared: the module jar ships
 * {@code defaults/guns/<id>/} folders whose model file names say what they are
 * ({@code <id>_idle}, {@code <id>_primed}, {@code <id>_loading_N}, …).
 * {@link WeaponAssets} mirrors those files into the Core's pack staging area and
 * {@code WeaponFrames} classifies them into Core frame spec. The config only
 * has to name the weapon; adding a gun is dropping a folder in the jar.</p>
 *
 * <p>The main class installs assets before the first config parse (discovery
 * reads the staging folder). This feature registers the parsed weapons as pack
 * items; the module's single {@code reloadAll()} call then compiles the pack
 * with the assets and items together.</p>
 */
public final class WeaponFeature extends ModuleFeature {

    private final GunServices services;

    public WeaponFeature(JavaPlugin plugin, PluginContext context, GunServices services) {
        super(plugin, context);
        this.services = services;
    }

    @Override
    public String id() {
        return "content";
    }

    @Override
    protected void onEnable() {
        registerItems();
    }

    /** Re-reads config.yml and re-runs install → parse → register → recompile. */
    public void reload() {
        plugin.reloadConfig();
        WeaponAssets.install(plugin, context);
        WeaponAssets.register(plugin, context);

        GunConfig config = WeaponConfigLoader.load(plugin.getConfig(), context.moduleSupport().dataFile(""));
        services.setConfig(config);
        services.registry().reload(config.weapons());
        services.ammoHud().setConfig(config);

        registerItems();

        // One coordinated recompile for the whole module: assets, items and
        // the frame dispatch the pack derives from them.
        context.jsonInfrastructureApi().reloadAll();

        WeaponRuntime runtime = services.runtime();
        if (runtime != null) {
            runtime.refreshAll();
        }
    }

    /**
     * Registers every configured weapon as a Core pack item — the
     * {@code ContentItem} already carries identity, carrier, display and
     * presentation; the pack layer derives the definition.
     */
    public void registerItems() {
        int registered = 0;
        for (WeaponDefinition weapon : services.config().weapons()) {
            var definition = weapon.base().toPackItem();
            if (definition == null) {
                plugin.getLogger().warning("[MineplusGun] Weapon '" + weapon.id()
                        + "' has no model and will render on its vanilla carrier.");
                continue;
            }
            context.packApi().registerItem(definition);
            registered++;
        }
        plugin.getLogger().info("[MineplusGun] Registered " + registered + " weapon item(s).");
    }
}
