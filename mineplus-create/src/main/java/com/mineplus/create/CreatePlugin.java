package com.mineplus.create;

import com.mineplus.MineplusPlugin;
import com.mineplus.create.admin.AdminFeature;
import com.mineplus.create.electric.ElectricFeature;
import com.mineplus.create.journal.JournalFeature;
import com.mineplus.create.machines.MachinesFeature;
import com.mineplus.create.ores.OreFeature;
import com.mineplus.create.rotational.RotationalNetworkFeature;
import com.mineplus.create.steam.SteamFeature;
import com.mineplus.create.towns.TownsFeature;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.module.ModuleFeature;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bootstrap for the Mineplus Create module.
 *
 * <p>This plugin is a separate artifact from the Core. It depends on the Core
 * at runtime ({@code plugin.yml -> depend: [Mineplus]}) and obtains the Core
 * API through {@link MineplusPlugin#getPluginContext()}. One feature = one
 * {@link ModuleFeature}: features start first, then one coordinated
 * {@code reloadAll()}, then command registration. Teardown stops features in
 * reverse enable order.</p>
 */
public final class CreatePlugin extends JavaPlugin {

    private PluginContext context;
    private final List<ModuleFeature> features = new ArrayList<>();

    @Override
    public void onEnable() {
        org.bukkit.plugin.Plugin core = Bukkit.getPluginManager().getPlugin("Mineplus");
        if (!(core instanceof MineplusPlugin mineplus)) {
            getLogger().severe("[MineplusCreate] FATAL: Mineplus Core plugin was not found.");
            getLogger().severe("[MineplusCreate] Install 'Mineplus.jar' (Core) into plugins/ first, then restart.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.context = mineplus.getPluginContext();
        if (this.context == null) {
            getLogger().severe("[MineplusCreate] Mineplus Core is not initialized. Disable MineplusCreate and retry after Core enables.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Enable order matters: rotational networks first, machines attach to
        // it, steam and electric build on machines, ores are independent.
        RotationalNetworkFeature rotationalNetworks = new RotationalNetworkFeature(this, context);
        TownsFeature towns = new TownsFeature(this, context);
        MachinesFeature machines = new MachinesFeature(this, context, rotationalNetworks, towns);
        JournalFeature journal = new JournalFeature(this, context);
        SteamFeature steam = new SteamFeature(this, context, rotationalNetworks);
        ElectricFeature electric = new ElectricFeature(this, context, rotationalNetworks);
        OreFeature ores = new OreFeature(this, context);
        AdminFeature admin = new AdminFeature(this, context);
        features.add(rotationalNetworks);
        features.add(machines);
        features.add(journal);
        features.add(steam);
        features.add(electric);
        features.add(towns);
        features.add(ores);
        features.add(admin);

        for (ModuleFeature feature : features) {
            feature.start();
        }

        // One coordinated load of everything the features installed — never
        // reloadAll() from inside a feature.
        context.jsonInfrastructureApi().reloadAll();

        for (ModuleFeature feature : features) {
            feature.registerCommand();
        }

        getLogger().info("[MineplusCreate] " + features.size()
                + " feature(s) (RotationalNetwork) enabled on top of Mineplus Core.");
    }

    public RotationalNetworkFeature rotational() {
        for (ModuleFeature feature : features) {
            if (feature instanceof RotationalNetworkFeature rotational) {
                return rotational;
            }
        }
        throw new IllegalStateException("Rotational feature not enabled");
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
