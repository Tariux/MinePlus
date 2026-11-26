package com.mineplus.machine;

import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.PluginContext;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bootstrap for the Machine module — the {@link JavaPlugin} entry point Paper
 * requires. The actual feature lives in {@link MineplusMachinePlugin}
 * (a Core {@code ModuleFeature}); this class resolves the Mineplus Core
 * context, runs the feature through the standard module lifecycle
 * (start → one coordinated reloadAll → register command) and reverses it on
 * disable.
 */
public final class MachineBootstrap extends JavaPlugin {

    private MineplusMachinePlugin feature;

    @Override
    public void onEnable() {
        Plugin core = Bukkit.getPluginManager().getPlugin("Mineplus");
        if (!(core instanceof MineplusPlugin mineplus)) {
            getLogger().severe("============================================================");
            getLogger().severe("[Machine] FATAL: Mineplus Core plugin was not found.");
            getLogger().severe("[Machine] Install the Mineplus Core jar into plugins/ first.");
            getLogger().severe("============================================================");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        PluginContext context = mineplus.getPluginContext();
        if (context == null) {
            getLogger().severe("[Machine] Mineplus Core is not initialized; retry after Core enables.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        feature = new MineplusMachinePlugin(this, context);
        feature.start();
        context.jsonInfrastructureApi().reloadAll();
        feature.registerCommand();
        getLogger().info("[Machine] mechanical power module enabled.");
    }

    @Override
    public void onDisable() {
        if (feature != null) {
            feature.stop();
            feature = null;
        }
    }
}
