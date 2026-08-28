package com.mineplus.fun.juicer;

import com.mineplus.fun.juicer.gui.JuicerGui;
import com.mineplus.fun.juicer.items.CarrotJuiceItemDefinition;
import com.mineplus.fun.juicer.items.MelonJuiceItemDefinition;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Wires the Juicer game feature into the Mineplus Core engine.
 *
 * <p>Resources (bbmodels, multiblock + recipe JSON) are shipped inside this module's jar and
 * copied into the <em>Core's</em> data folder so the Core's existing loaders pick them up.
 * A {@code reloadAll()} then loads them without restarting the server.
 */
public final class JuicerFeature {

    private final JavaPlugin plugin;
    private final PluginContext context;

    public JuicerFeature(JavaPlugin plugin, PluginContext context) {
        this.plugin = plugin;
        this.context = context;
    }

    public void enable() {
        installDefaultResource("defaults/models/juicer-machine-level-1.bbmodel", "models/juicer-machine-level-1.bbmodel", true);
        installDefaultResource("defaults/models/juicer-machine-level-2.bbmodel", "models/juicer-machine-level-2.bbmodel", true);
        installDefaultResource("defaults/multiblocks/juicer_machine.json", "multiblocks/juicer_machine.json", false);
        installDefaultResource("defaults/recipes/juicer_machine_recipes.json", "recipes/juicer_machine_recipes.json", false);

        context.itemRegistry().register(new CarrotJuiceItemDefinition());
        context.itemRegistry().register(new MelonJuiceItemDefinition());

        context.plugin().getServer().getPluginManager().registerEvents(
                new JuiceConsumeListener(context.itemRegistry()),
                plugin
        );

        context.infrastructureApi().registerGui(
                JuicerKeys.GUI_KEY,
                new JuicerGui(
                        plugin,
                        context.infrastructureEngine().registry(),
                        context.infrastructureEngine().lifecycleManager(),
                        context.infrastructureEngine().recipeManager(),
                        context.itemRegistry()
                )
        );

        context.infrastructureApi().registerHook(JuicerKeys.MACHINE_ID, new MultiBlockHook() {
            @Override
            public void onInteract(MultiBlockInstance instance, Player actor) {
                actor.sendMessage(ChatColor.GRAY + "Juicer level " + instance.level() + " ready.");
            }
        });

        // Load the freshly installed definitions into the Core engine.
        context.jsonInfrastructureApi().reloadAll();
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
