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
import com.mineplus.pack.MineplusPackApi;
import com.mineplus.pack.PackApi;
import com.mineplus.pack.PackSettings;
import com.mineplus.pack.PackSystem;

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
    private final PackSystem packSystem;
    private final PackApi packApi;

    private PluginContext(
            MineplusPlugin plugin,
            ItemRegistry itemRegistry,
            VirtualBlockManager virtualBlockManager,
            InfrastructureEngine infrastructureEngine,
            InfrastructureApi infrastructureApi,
            BasicInfrastructureApi basicInfrastructureApi,
            JsonInfrastructureApi jsonInfrastructureApi,
            AnimationApi animationApi,
            ModuleSupport moduleSupport,
            PackSystem packSystem,
            PackApi packApi
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
        this.packSystem = packSystem;
        this.packApi = packApi;
    }

    public static PluginContext bootstrap(MineplusPlugin plugin, VirtualBlockManager virtualBlockManager) {
        return bootstrap(plugin, virtualBlockManager, AnimationSettings.defaults());
    }

    public static PluginContext bootstrap(
            MineplusPlugin plugin,
            VirtualBlockManager virtualBlockManager,
            AnimationSettings animationSettings
    ) {
        return bootstrap(plugin, virtualBlockManager, animationSettings, PackSettings.defaults());
    }

    /**
     * @param packSettings pack subsystem settings; when enabled, the subsystem
     *                     starts here (renderer injection happens before the
     *                     engine's restore pass in {@link #finalizeSetup()})
     */
    public static PluginContext bootstrap(
            MineplusPlugin plugin,
            VirtualBlockManager virtualBlockManager,
            AnimationSettings animationSettings,
            PackSettings packSettings
    ) {
        ItemRegistry itemRegistry = new ItemRegistry(plugin);
        InfrastructureEngine infrastructureEngine = new InfrastructureEngine(
                plugin, virtualBlockManager, itemRegistry, animationSettings);

        PackSystem packSystem = null;
        if (packSettings != null && packSettings.enabled()) {
            // Start failures are isolated inside PackSystem; virtual rendering
            // is never affected.
            packSystem = PackSystem.start(plugin, virtualBlockManager, itemRegistry,
                    infrastructureEngine.renderingManager(), packSettings);
        }
        PackApi packApi = packSystem != null && packSystem.isRunning()
                ? new MineplusPackApi(packSystem)
                : PackApi.disabled(itemRegistry);

        return new PluginContext(
                plugin,
                itemRegistry,
                virtualBlockManager,
                infrastructureEngine,
                infrastructureEngine.api(),
                infrastructureEngine.basicApi(),
                infrastructureEngine.jsonApi(),
                infrastructureEngine.animationApi(),
                new ModuleSupport(plugin, infrastructureEngine.registry(), virtualBlockManager),
                packSystem,
                packApi
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

    /**
     * Resource pack API — never null. When the subsystem is disabled this is
     * a safe fallback: item identity still registers, presentation falls back
     * to the vanilla backing material, nothing compiles or pushes.
     */
    public PackApi packApi() {
        return packApi;
    }

    /** The running pack subsystem, or null when disabled/unavailable. */
    public PackSystem packSystem() {
        return packSystem;
    }

    /** Module toolkit: resource installation, looked-at resolution, command registration. */
    public ModuleSupport moduleSupport() {
        return moduleSupport;
    }
}
