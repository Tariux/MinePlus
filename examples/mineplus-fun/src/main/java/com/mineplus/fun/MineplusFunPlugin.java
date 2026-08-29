package com.mineplus.fun;

import com.mineplus.MineplusPlugin;
import com.mineplus.fun.cannon.CannonFeature;
import com.mineplus.fun.cannon.CannonSubCommand;
import com.mineplus.fun.juicer.JuicerFeature;
import com.mineplus.fun.juicer.JuicerSubCommand;
import com.mineplus.infrastructure.PluginContext;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Example "module" plugin that turns the Mineplus Core engine into Juicer and
 * Cannon game features.
 *
 * <p>This plugin is intentionally a <em>separate</em> artifact from the Core. It depends on
 * the Core at runtime (see {@code plugin.yml -> depend: [Mineplus]}) and obtains the Core
 * API through {@link MineplusPlugin#getPluginContext()}. None of the game logic lives
 * in the Core; the Core remains a dependency-only engine.
 *
 * <p>Commands are registered dynamically through the Core's module toolkit
 * ({@code context.moduleSupport().registerCommand(...)}) — no per-command
 * {@code plugin.yml} entries or hand-written dispatch in {@code onCommand}.
 */
public final class MineplusFunPlugin extends JavaPlugin {

    private PluginContext context;
    private JuicerFeature juicerFeature;
    private CannonFeature cannonFeature;

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

        this.cannonFeature = new CannonFeature(this, context);
        this.cannonFeature.enable();

        context.moduleSupport().registerCommand(this, "juicer", new JuicerSubCommand(context));
        context.moduleSupport().registerCommand(this, "cannon", new CannonSubCommand(context));

        getLogger().info("[MineplusFun] Juicer and Cannon modules enabled on top of Mineplus Core.");
    }

    @Override
    public void onDisable() {
        if (this.cannonFeature != null) {
            this.cannonFeature.disable();
        }
        this.cannonFeature = null;
        this.juicerFeature = null;
        this.context = null;
    }
}
