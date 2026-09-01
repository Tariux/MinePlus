package com.mineplus.fun;

import com.mineplus.MineplusPlugin;
import com.mineplus.fun.cabinet.CabinetFeature;
import com.mineplus.fun.cannon.CannonFeature;
import com.mineplus.fun.gear.GearFeature;
import com.mineplus.fun.juicer.JuicerFeature;
import com.mineplus.fun.wine.WineFeature;
import com.mineplus.infrastructure.PluginContext;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Example "module" plugin that turns the Mineplus Core engine into Juicer,
 * Cannon, Gear, Wine, and Cabinet game features.
 *
 * <p>This plugin is intentionally a <em>separate</em> artifact from the Core. It depends on
 * the Core at runtime (see {@code plugin.yml -> depend: [Mineplus]}) and obtains the Core
 * API through {@link MineplusPlugin#getPluginContext()}. None of the game logic lives
 * in the Core; the Core remains a dependency-only engine.
 *
 * <p>Bootstrap order (see {@link ModuleFeature}): every feature installs its resources
 * and registers hooks/listeners/GUIs first, then <b>one</b> coordinated
 * {@code reloadAll()} loads all freshly installed definitions at once, then each
 * feature's top-level command is registered. Features are exception-isolated, so one
 * broken feature never prevents the others from booting. Teardown stops features in
 * reverse enable order.
 */
public final class MineplusFunPlugin extends JavaPlugin {

    private PluginContext context;
    private final List<ModuleFeature> features = new ArrayList<>();

    @Override
    public void onEnable() {
        org.bukkit.plugin.Plugin core = Bukkit.getPluginManager().getPlugin("Mineplus");
        if (!(core instanceof MineplusPlugin mineplus)) {
            getLogger().severe("============================================================");
            getLogger().severe("[MineplusFun] FATAL: Mineplus Core plugin was not found.");
            getLogger().severe("[MineplusFun] This module requires the Mineplus Core engine.");
            getLogger().severe("[MineplusFun] Install 'Mineplus.jar' (Core) into your plugins/");
            getLogger().severe("[MineplusFun] folder first, then restart the server.");
            getLogger().severe("============================================================");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.context = mineplus.getPluginContext();
        if (this.context == null) {
            getLogger().severe("[MineplusFun] Mineplus Core is not initialized. Disable MineplusFun and retry after Core enables.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        features.add(new JuicerFeature(this, context));
        features.add(new CannonFeature(this, context));
        features.add(new GearFeature(this, context));
        features.add(new WineFeature(this, context));
        features.add(new CabinetFeature(this, context));

        for (ModuleFeature feature : features) {
            feature.start();
        }

        // One coordinated load of everything the features just installed —
        // never reloadAll() from inside a feature.
        context.jsonInfrastructureApi().reloadAll();

        for (ModuleFeature feature : features) {
            feature.registerCommand();
        }

        getLogger().info("[MineplusFun] " + features.size()
                + " features (Juicer, Cannon, Gear, Wine, Cabinet) enabled on top of Mineplus Core.");
    }

    @Override
    public void onDisable() {
        for (int i = features.size() - 1; i >= 0; i--) {
            features.get(i).stop();
        }
        features.clear();
        this.context = null;
    }
}
