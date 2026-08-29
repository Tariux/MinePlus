package com.mineplus.fun.cannon;

import com.mineplus.fun.cannon.gui.CannonGui;
import com.mineplus.infrastructure.PluginContext;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Wires the Cannon game feature into the Mineplus Core engine.
 *
 * <p>The bbmodels and multiblock JSON are shipped inside this module's jar and
 * copied into the <em>Core's</em> data folder so the Core's existing loaders pick
 * them up. The multiblock is registered without a GUI key; the {@link CannonFireHook}
 * decides per interaction whether to mount (level 2, saddle), fire (level 1,
 * torch) or open the context menu, so a {@code reloadAll()} after installation is
 * enough to activate the feature.
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
        installDefaultResource("defaults/models/cannon-3-1-1.bbmodel", "models/cannon-3-1-1.bbmodel", true);
        installDefaultResource("defaults/models/cannon-3-1-1-bigger.bbmodel", "models/cannon-3-1-1-bigger.bbmodel", true);
        installDefaultResource("defaults/multiblocks/cannon.json", "multiblocks/cannon.json", false);

        this.mounts = new CannonMountManager(plugin);
        Bukkit.getPluginManager().registerEvents(mounts, plugin);
        Bukkit.getPluginManager().registerEvents(new CannonAimListener(context, mounts), plugin);
        Bukkit.getPluginManager().registerEvents(new CannonProjectiles(), plugin);

        context.infrastructureApi().registerGui(
                CannonKeys.GUI_KEY,
                new CannonGui(
                        plugin,
                        context.infrastructureEngine().registry(),
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

    private void installDefaultResource(String classpathResource, String dataRelativePath, boolean overwrite) {
        File target = new File(context.plugin().getDataFolder(), dataRelativePath);
        if (target.exists() && !overwrite) {
            return;
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Failed to create folder for " + dataRelativePath);
            return;
        }

        try (InputStream stream = plugin.getResource(classpathResource)) {
            if (stream == null) {
                plugin.getLogger().warning("Missing embedded resource: " + classpathResource);
                return;
            }
            Files.copy(stream, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to install resource " + dataRelativePath + ": " + exception.getMessage());
        }
    }
}
