package com.mineplus;

import com.mineplus.config.ConfigManager;
import com.mineplus.util.DebugLogger;
import com.mineplus.util.UpdateChecker;
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
import com.mineplus.infrastructure.virtual.display.DisplayTransport;
import com.mineplus.infrastructure.virtual.display.DisplayTransportListener;
import com.mineplus.bstats.bukkit.Metrics;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MineplusPlugin extends JavaPlugin {

    private static MineplusPlugin instance;
    private PluginContext context;
    private CommandRouter commandRouter;
    private VirtualBlockManager virtualBlockManager;
    private ConfigManager configManager;
    private com.mineplus.infrastructure.virtual.display.DisplayTransport displayTransport;

    @Override
    public void onEnable() {
        instance = this;

        configManager = new ConfigManager(this);
        configManager.loadConfig();
        DebugLogger.init(configManager.getConfig(), getLogger());
        UpdateChecker.check(this, configManager.getConfig().getUpdateCheckResourceId());
        new Metrics(this, 33702);

        virtualBlockManager = new VirtualBlockManager();
        virtualBlockManager.updateSettings(configManager.getConfig().getVirtualRendering());
        virtualBlockManager.updateTexelSettings(configManager.getConfig().getTexelBaking());
        virtualBlockManager.updateVoxelSettings(configManager.getConfig().getVoxelRendering());
        attachDisplayTransport();
        virtualBlockManager.loadModels(this);

        context = PluginContext.bootstrap(this, virtualBlockManager, configManager.getConfig().getAnimation());
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
        context = null;
        instance = null;
        virtualBlockManager = null;
        displayTransport = null;
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
        if (displayTransport != null) {
            getServer().getPluginManager().registerEvents(new DisplayTransportListener(displayTransport), this);
        }
    }

    /**
     * Starts the packet-based display transport when enabled and the runtime NMS
     * surface supports it. Failure is non-fatal: the virtual render pipeline falls
     * back to the legacy spawned-entity path and the plugin keeps working.
     */
    private void attachDisplayTransport() {
        if (!configManager.getConfig().getDisplayTransport().enabled()) {
            return;
        }
        try {
            displayTransport = DisplayTransport.start(this, configManager.getConfig().getDisplayTransport());
            virtualBlockManager.setDisplayTransport(displayTransport);
        } catch (Throwable t) {
            getLogger().warning("Display transport unavailable, using legacy entity rendering: " + t.getMessage());
            displayTransport = null;
        }
    }

    public static MineplusPlugin getInstance() {
        return instance;
    }

    /**
     * Public entry point for external modules. Returns the live {@link PluginContext}
     * so dependent plugins can register multiblocks, recipes, GUIs, hooks, and items
     * through the Core API without being part of the Core build.
     */
    public PluginContext getPluginContext() {
        return context;
    }

    /** Re-reads settings.mp.yml and applies virtual-rendering settings before a model reload. */
    public void refreshVirtualRenderingSettings() {
        if (configManager != null) {
            configManager.loadConfig();
            DebugLogger.init(configManager.getConfig(), getLogger());
        }
        if (virtualBlockManager != null && configManager != null) {
            virtualBlockManager.updateSettings(configManager.getConfig().getVirtualRendering());
            virtualBlockManager.updateTexelSettings(configManager.getConfig().getTexelBaking());
            virtualBlockManager.updateVoxelSettings(configManager.getConfig().getVoxelRendering());
        }
        if (context != null && configManager != null) {
            context.infrastructureEngine().updateAnimationSettings(configManager.getConfig().getAnimation());
        }
        // Transport enable/disable applies after a restart; log when the desired
        // state diverges from the running one so operators know why.
        if (configManager != null) {
            boolean wanted = configManager.getConfig().getDisplayTransport().enabled();
            boolean running = displayTransport != null && displayTransport.isRunning();
            if (wanted != running) {
                getLogger().info("Display transport "
                        + (running ? "stays active until restart (settings now DISABLED)" : "activates after restart (settings now ENABLED)"));
            }
        }
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

    public com.mineplus.infrastructure.core.api.AnimationApi animationApi() {
        return context == null ? null : context.animationApi();
    }
}
