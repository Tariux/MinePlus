package com.mineplus.infrastructure;

import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.core.InfrastructureEngine;
import com.mineplus.infrastructure.core.api.AnimationApi;
import com.mineplus.infrastructure.core.api.BasicInfrastructureApi;
import com.mineplus.infrastructure.core.api.InfrastructureApi;
import com.mineplus.infrastructure.core.api.JsonInfrastructureApi;
import com.mineplus.infrastructure.module.ModuleSupport;
import com.mineplus.infrastructure.registry.ItemRegistry;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.animation.AnimationSettings;

public final class PluginContext {

    private final MineplusPlugin plugin;
    private final ItemRegistry itemRegistry;
    private final VirtualBlockManager virtualBlockManager;
    private final InfrastructureEngine infrastructureEngine;
    private final InfrastructureApi infrastructureApi;
    private final BasicInfrastructureApi basicInfrastructureApi;
    private final JsonInfrastructureApi jsonInfrastructureApi;
    private final AnimationApi animationApi;
    private final ModuleSupport moduleSupport;

    private PluginContext(
            MineplusPlugin plugin,
            ItemRegistry itemRegistry,
            VirtualBlockManager virtualBlockManager,
            InfrastructureEngine infrastructureEngine,
            InfrastructureApi infrastructureApi,
            BasicInfrastructureApi basicInfrastructureApi,
            JsonInfrastructureApi jsonInfrastructureApi,
            AnimationApi animationApi,
            ModuleSupport moduleSupport
    ) {
        this.plugin = plugin;
        this.itemRegistry = itemRegistry;
        this.virtualBlockManager = virtualBlockManager;
        this.infrastructureEngine = infrastructureEngine;
        this.infrastructureApi = infrastructureApi;
        this.basicInfrastructureApi = basicInfrastructureApi;
        this.jsonInfrastructureApi = jsonInfrastructureApi;
        this.animationApi = animationApi;
        this.moduleSupport = moduleSupport;
    }

    public static PluginContext bootstrap(MineplusPlugin plugin, VirtualBlockManager virtualBlockManager) {
        return bootstrap(plugin, virtualBlockManager, AnimationSettings.defaults());
    }

    public static PluginContext bootstrap(
            MineplusPlugin plugin,
            VirtualBlockManager virtualBlockManager,
            AnimationSettings animationSettings
    ) {
        ItemRegistry itemRegistry = new ItemRegistry(plugin);
        InfrastructureEngine infrastructureEngine = new InfrastructureEngine(
                plugin, virtualBlockManager, itemRegistry, animationSettings);

        return new PluginContext(
                plugin,
                itemRegistry,
                virtualBlockManager,
                infrastructureEngine,
                infrastructureEngine.api(),
                infrastructureEngine.basicApi(),
                infrastructureEngine.jsonApi(),
                infrastructureEngine.animationApi(),
                new ModuleSupport(plugin, infrastructureEngine.registry(), virtualBlockManager)
        );
    }

    public void finalizeSetup() {
        infrastructureEngine.initialize();
    }

    public MineplusPlugin plugin() {
        return plugin;
    }

    public ItemRegistry itemRegistry() {
        return itemRegistry;
    }

    public InfrastructureEngine infrastructureEngine() {
        return infrastructureEngine;
    }

    public VirtualBlockManager virtualBlockManager() {
        return virtualBlockManager;
    }

    public InfrastructureApi infrastructureApi() {
        return infrastructureApi;
    }

    public BasicInfrastructureApi basicInfrastructureApi() {
        return basicInfrastructureApi;
    }

    public JsonInfrastructureApi jsonInfrastructureApi() {
        return jsonInfrastructureApi;
    }

    /** Selector-based animation control (play/stop/pause/trigger/enable by clip or bone). */
    public AnimationApi animationApi() {
        return animationApi;
    }

    /** Module toolkit: resource installation, looked-at resolution, command registration. */
    public ModuleSupport moduleSupport() {
        return moduleSupport;
    }
}
