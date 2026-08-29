package com.mineplus.fun.cannon;

import com.mineplus.fun.cannon.gui.CannonGui;
import com.mineplus.infrastructure.PluginContext;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Wires the Cannon game feature into the Mineplus Core engine.
 *
 * <p>The bbmodels and multiblock JSON are shipped inside this module's jar and
 * installed into the <em>Core's</em> data folder through the Core's module
 * toolkit ({@code context.moduleSupport()}). The multiblock is registered
 * without a GUI key; the {@link CannonFireHook} decides per interaction
 * whether to mount (level 2, saddle), fire (level 1, torch) or open the
 * context menu, so a {@code reloadAll()} after installation is enough to
 * activate the feature.
 */
public final class CannonFeature {

    private final JavaPlugin plugin;
    private final PluginContext context;
    private CannonMountManager mounts;

    public CannonFeature(JavaPlugin plugin, PluginContext context) {
        this.plugin = plugin;
        this.context = context;
    }

    public void enable() {
        var support = context.moduleSupport();
        support.installDefault(plugin, "defaults/models/cannon-3-1-1.bbmodel", "models/cannon-3-1-1.bbmodel", true);
        support.installDefault(plugin, "defaults/models/cannon-3-1-1-bigger.bbmodel", "models/cannon-3-1-1-bigger.bbmodel", true);
        support.installDefault(plugin, "defaults/multiblocks/cannon.json", "multiblocks/cannon.json", false);

        this.mounts = new CannonMountManager(plugin);
        Bukkit.getPluginManager().registerEvents(mounts, plugin);
        Bukkit.getPluginManager().registerEvents(new CannonAimListener(context, mounts), plugin);
        Bukkit.getPluginManager().registerEvents(new CannonProjectiles(), plugin);

        context.infrastructureApi().registerGui(
                CannonKeys.GUI_KEY,
                new CannonGui(
                        plugin,
                        context,
                        context.infrastructureEngine().lifecycleManager(),
                        mounts
                )
        );

        context.infrastructureApi().registerHook(CannonKeys.MACHINE_ID, new CannonFireHook(context, mounts));

        // Load the freshly installed definitions into the Core engine, then clear
        // any gunner's-seat stands orphaned by an unclean shutdown.
        context.jsonInfrastructureApi().reloadAll();
        mounts.purgeOrphanSeats();
    }

    public void disable() {
        if (mounts != null) {
            mounts.shutdown();
            mounts = null;
        }
    }
}
