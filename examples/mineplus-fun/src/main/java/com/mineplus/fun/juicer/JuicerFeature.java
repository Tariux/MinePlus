package com.mineplus.fun.juicer;

import com.mineplus.fun.ModuleFeature;
import com.mineplus.fun.juicer.gui.JuicerGui;
import com.mineplus.fun.juicer.items.CarrotJuiceItemDefinition;
import com.mineplus.fun.juicer.items.MelonJuiceItemDefinition;
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
 * ({@code context.moduleSupport()}); the module's single coordinated reload loads
 * them without restarting the server.
 */
public final class JuicerFeature extends ModuleFeature {

    public JuicerFeature(JavaPlugin plugin, com.mineplus.infrastructure.PluginContext context) {
        super(plugin, context);
    }

    @Override
    public String id() {
        return "juicer";
    }

    @Override
    protected void onEnable() {
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
    }

    @Override
    protected com.mineplus.infrastructure.command.SubCommand command() {
        return new JuicerSubCommand(context);
    }
}
