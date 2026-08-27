package com.mineplus.game.juicer;

import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import com.mineplus.game.juicer.gui.JuicerGui;
import com.mineplus.game.juicer.items.CarrotJuiceItemDefinition;
import com.mineplus.game.juicer.items.MelonJuiceItemDefinition;
import com.mineplus.util.DebugLogger;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public final class JuicerFeature {

    private final MineplusPlugin plugin;
    private final PluginContext context;

    public JuicerFeature(MineplusPlugin plugin, PluginContext context) {
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
            public void onInteract(com.mineplus.infrastructure.core.multiblock.MultiBlockInstance instance, Player actor) {
                actor.sendMessage(ChatColor.GRAY + "Juicer level " + instance.level() + " ready.");
            }
        });
    }

    private void installDefaultResource(String classpathResource, String dataRelativePath, boolean overwrite) {
        File target = new File(plugin.getDataFolder(), dataRelativePath);
        if (target.exists() && !overwrite) {
            return;
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            DebugLogger.warning("Failed to create folder for " + dataRelativePath);
            return;
        }

        try (InputStream stream = plugin.getResource(classpathResource)) {
            if (stream == null) {
                DebugLogger.warning("Missing embedded resource: " + classpathResource);
                return;
            }
            Files.copy(stream, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            DebugLogger.warning("Failed to install resource " + dataRelativePath + ": " + exception.getMessage());
        }
    }
}
