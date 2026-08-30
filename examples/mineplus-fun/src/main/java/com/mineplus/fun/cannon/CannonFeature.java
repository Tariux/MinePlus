package com.mineplus.fun.cannon;

import com.mineplus.fun.ModuleFeature;
import com.mineplus.fun.cannon.gui.CannonGui;
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
 * context menu, so the module's single coordinated reload after installation
 * is enough to activate the feature.
 */
public final class CannonFeature extends ModuleFeature {

    private CannonMountManager mounts;

    public CannonFeature(JavaPlugin plugin, com.mineplus.infrastructure.PluginContext context) {
        super(plugin, context);
    }

    @Override
    public String id() {
        return "cannon";
    }

    @Override
    protected void onEnable() {
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

        // Purge gunner's-seat stands orphaned by an unclean shutdown. The purge
        // scans for the seat PDC tag and never consults loaded instances, so it
        // does not depend on the module's coordinated reload having run yet.
        mounts.purgeOrphanSeats();
    }

    @Override
    protected void onDisable() {
        if (mounts != null) {
            mounts.shutdown();
            mounts = null;
        }
    }

    @Override
    protected com.mineplus.infrastructure.command.SubCommand command() {
        return new CannonSubCommand(context);
    }
}
