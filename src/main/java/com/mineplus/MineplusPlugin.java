package com.mineplus;

import com.mineplus.config.ConfigManager;
import com.mineplus.util.DebugLogger;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.CommandRouter;
import com.mineplus.infrastructure.command.sub.ModelSubCommand;
import com.mineplus.infrastructure.command.sub.ReloadSubCommand;
import com.mineplus.infrastructure.command.sub.StatusSubCommand;
import com.mineplus.infrastructure.core.api.BasicInfrastructureApi;
import com.mineplus.infrastructure.core.api.InfrastructureApi;
import com.mineplus.infrastructure.core.api.JsonInfrastructureApi;
import com.mineplus.infrastructure.core.gui.InfrastructureGuiListener;
import com.mineplus.infrastructure.listener.InfrastructureListener;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.game.juicer.JuicerFeature;
import com.mineplus.game.juicer.JuicerSubCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MineplusPlugin extends JavaPlugin {

    private static MineplusPlugin instance;
    private PluginContext context;
    private CommandRouter commandRouter;
    private VirtualBlockManager virtualBlockManager;
    private JuicerFeature juicerFeature;
    private ConfigManager configManager;

    @Override
    public void onEnable() {
        instance = this;

        configManager = new ConfigManager(this);
        configManager.loadConfig();
        DebugLogger.init(configManager.getConfig(), getLogger());

        virtualBlockManager = new VirtualBlockManager();
        virtualBlockManager.loadModels(this);

        context = PluginContext.bootstrap(this, virtualBlockManager);
        juicerFeature = new JuicerFeature(this, context);
        juicerFeature.enable();
        context.finalizeSetup();

        registerCommand();
        registerListeners();
    }

    @Override
    public void onDisable() {
        if (context != null) {
            context.infrastructureEngine().shutdown();
        }

        if (virtualBlockManager != null) {
            virtualBlockManager.shutdown();
        }

        commandRouter = null;
        juicerFeature = null;
        context = null;
        instance = null;
        virtualBlockManager = null;
    }

    private void registerCommand() {
        PluginCommand command = getCommand("mineplus");
        if (command == null) {
            getLogger().severe("Command 'mineplus' is not defined in plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        commandRouter = new CommandRouter();
        commandRouter.register(new StatusSubCommand(context));
        commandRouter.register(new ReloadSubCommand(context));
        commandRouter.register(new ModelSubCommand(context));
        commandRouter.register(new JuicerSubCommand(context));

        command.setExecutor(commandRouter);
        command.setTabCompleter(commandRouter);
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new InfrastructureListener(context.infrastructureEngine().lifecycleManager(), virtualBlockManager),
                this
        );
        getServer().getPluginManager().registerEvents(
                new InfrastructureGuiListener(context.infrastructureEngine().guiManager()),
                this
        );
        getServer().getPluginManager().registerEvents(virtualBlockManager, this);
    }

    public static MineplusPlugin getInstance() {
        return instance;
    }

    public InfrastructureApi infrastructureApi() {
        return context == null ? null : context.infrastructureApi();
    }

    public BasicInfrastructureApi basicInfrastructureApi() {
        return context == null ? null : context.basicInfrastructureApi();
    }

    public JsonInfrastructureApi jsonInfrastructureApi() {
        return context == null ? null : context.jsonInfrastructureApi();
    }
}
