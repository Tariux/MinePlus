package com.mineplus.fun;

import com.mineplus.MineplusPlugin;
import com.mineplus.fun.juicer.JuicerFeature;
import com.mineplus.fun.juicer.JuicerSubCommand;
import com.mineplus.infrastructure.PluginContext;
import java.util.Collections;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Example "module" plugin that turns the Mineplus Core engine into a Juicer game feature.
 *
 * <p>This plugin is intentionally a <em>separate</em> artifact from the Core. It depends on
 * the Core at runtime (see {@code plugin.yml -> depend: [Mineplus]}) and obtains the Core
 * API through {@link MineplusPlugin#getPluginContext()}. None of the juicer game logic lives
 * in the Core; the Core remains a dependency-only engine.
 */
public final class MineplusFunPlugin extends JavaPlugin {

    private PluginContext context;
    private JuicerFeature juicerFeature;

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

        this.juicerFeature = new JuicerFeature(this, context);
        this.juicerFeature.enable();
        getLogger().info("[MineplusFun] Juicer module enabled on top of Mineplus Core.");
    }

    @Override
    public void onDisable() {
        this.juicerFeature = null;
        this.context = null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("juicer") && context != null) {
            return new JuicerSubCommand(context).execute(sender, label, args);
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("juicer") && context != null) {
            return new JuicerSubCommand(context).tabComplete(sender, args);
        }
        return Collections.emptyList();
    }
}
