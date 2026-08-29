package com.mineplus.fun.juicer;

import com.mineplus.fun.juicer.gui.JuicerGui;
import com.mineplus.fun.juicer.items.CarrotJuiceItemDefinition;
import com.mineplus.fun.juicer.items.MelonJuiceItemDefinition;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Wires the Juicer game feature into the Mineplus Core engine.
 *
 * <p>Resources (bbmodels, multiblock + recipe JSON) are shipped inside this module's jar and
 * installed into the <em>Core's</em> data folder through the Core's module toolkit
 * ({@code context.moduleSupport()}); a {@code reloadAll()} then loads them without
 * restarting the server.
 */
public final class JuicerFeature {

    private final JavaPlugin plugin;
    private final PluginContext context;

    public JuicerFeature(JavaPlugin plugin, PluginContext context) {
        this.plugin = plugin;
        this.context = context;
    }

    public void enable() {
        var support = context.moduleSupport();
        support.installDefault(plugin, "defaults/models/juicer-machine-level-1.bbmodel", "models/juicer-machine-level-1.bbmodel", true);
        support.installDefault(plugin, "defaults/models/juicer-machine-level-2.bbmodel", "models/juicer-machine-level-2.bbmodel", true);
        support.installDefault(plugin, "defaults/multiblocks/juicer_machine.json", "multiblocks/juicer_machine.json", false);
        support.installDefault(plugin, "defaults/recipes/juicer_machine_recipes.json", "recipes/juicer_machine_recipes.json", false);

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
}
