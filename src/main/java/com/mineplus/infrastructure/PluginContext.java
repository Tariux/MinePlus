package com.mineplus.infrastructure;

import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.core.InfrastructureEngine;
import com.mineplus.infrastructure.core.api.BasicInfrastructureApi;
import com.mineplus.infrastructure.core.api.InfrastructureApi;
import com.mineplus.infrastructure.core.api.JsonInfrastructureApi;
import com.mineplus.infrastructure.module.ModuleSupport;
import com.mineplus.infrastructure.registry.ItemRegistry;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;

public final class PluginContext {

    private final MineplusPlugin plugin;
    private final ItemRegistry itemRegistry;
    private final VirtualBlockManager virtualBlockManager;
    private final InfrastructureEngine infrastructureEngine;
    private final InfrastructureApi infrastructureApi;
    private final BasicInfrastructureApi basicInfrastructureApi;
    private final JsonInfrastructureApi jsonInfrastructureApi;
    private final ModuleSupport moduleSupport;

    private PluginContext(
            MineplusPlugin plugin,
            ItemRegistry itemRegistry,
            VirtualBlockManager virtualBlockManager,
            InfrastructureEngine infrastructureEngine,
            InfrastructureApi infrastructureApi,
            BasicInfrastructureApi basicInfrastructureApi,
            JsonInfrastructureApi jsonInfrastructureApi,
            ModuleSupport moduleSupport
    ) {
        this.plugin = plugin;
        this.itemRegistry = itemRegistry;
        this.virtualBlockManager = virtualBlockManager;
        this.infrastructureEngine = infrastructureEngine;
        this.infrastructureApi = infrastructureApi;
        this.basicInfrastructureApi = basicInfrastructureApi;
        this.jsonInfrastructureApi = jsonInfrastructureApi;
        this.moduleSupport = moduleSupport;
    }

    public static PluginContext bootstrap(MineplusPlugin plugin, VirtualBlockManager virtualBlockManager) {
        ItemRegistry itemRegistry = new ItemRegistry(plugin);
        InfrastructureEngine infrastructureEngine = new InfrastructureEngine(plugin, virtualBlockManager, itemRegistry);

        return new PluginContext(
                plugin,
                itemRegistry,
                virtualBlockManager,
                infrastructureEngine,
                infrastructureEngine.api(),
                infrastructureEngine.basicApi(),
                infrastructureEngine.jsonApi(),
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

    /** Module toolkit: resource installation, looked-at resolution, command registration. */
    public ModuleSupport moduleSupport() {
        return moduleSupport;
    }
}
