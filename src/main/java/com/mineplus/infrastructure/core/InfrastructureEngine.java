package com.mineplus.infrastructure.core;

import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.core.api.InfrastructureApi;
import com.mineplus.infrastructure.core.api.JsonInfrastructureApi;
import com.mineplus.infrastructure.core.api.BasicInfrastructureApi;
import com.mineplus.infrastructure.core.api.MineplusBasicInfrastructureApi;
import com.mineplus.infrastructure.core.api.MineplusInfrastructureApi;
import com.mineplus.infrastructure.core.api.MineplusJsonInfrastructureApi;
import com.mineplus.infrastructure.core.config.MultiBlockConfigLoader;
import com.mineplus.infrastructure.core.config.RecipeConfigLoader;
import com.mineplus.infrastructure.core.events.HookBus;
import com.mineplus.infrastructure.core.gui.InfrastructureGuiManager;
import com.mineplus.infrastructure.core.items.InfrastructureItemManager;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleManager;
import com.mineplus.infrastructure.core.multiblock.linking.MultiBlockLinkingSystem;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import com.mineplus.infrastructure.core.multiblock.render.ModelRenderingManager;
import com.mineplus.infrastructure.core.multiblock.upgrade.UpgradeManager;
import com.mineplus.infrastructure.core.recipes.RecipeManager;
import com.mineplus.infrastructure.core.storage.MultiBlockStorageEngine;
import com.mineplus.infrastructure.registry.ItemRegistry;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import java.util.Set;

public final class InfrastructureEngine {

    private final MultiBlockRegistry registry;
    private final HookBus hookBus;
    private final InfrastructureGuiManager guiManager;
    private final InfrastructureItemManager itemManager;
    private final RecipeManager recipeManager;
    private final MultiBlockStorageEngine storageEngine;
    private final ModelRenderingManager renderingManager;
    private final UpgradeManager upgradeManager;
    private final MultiBlockLifecycleManager lifecycleManager;
    private final MultiBlockLinkingSystem linkingSystem;
    private final InfrastructureApi api;
    private final BasicInfrastructureApi basicApi;
    private final JsonInfrastructureApi jsonApi;
    private final MultiBlockConfigLoader configLoader;
    private final RecipeConfigLoader recipeLoader;
    private final MineplusPlugin plugin;
    private int tickTaskId;

    public InfrastructureEngine(MineplusPlugin plugin, VirtualBlockManager virtualBlockManager, ItemRegistry itemRegistry) {
        this.plugin = plugin;
        this.registry = new MultiBlockRegistry();
        this.hookBus = new HookBus();
        this.guiManager = new InfrastructureGuiManager();
        this.itemManager = new InfrastructureItemManager(itemRegistry);
        this.recipeManager = new RecipeManager();
        this.storageEngine = new MultiBlockStorageEngine(plugin);
        this.renderingManager = new ModelRenderingManager(virtualBlockManager);
        this.upgradeManager = new UpgradeManager(itemManager);
        this.lifecycleManager = new MultiBlockLifecycleManager(
                plugin,
                registry,
                renderingManager,
                storageEngine,
                guiManager,
                upgradeManager,
                hookBus
        );
        this.linkingSystem = new MultiBlockLinkingSystem(registry);
        this.api = new MineplusInfrastructureApi(
                registry,
                lifecycleManager,
                linkingSystem,
                guiManager,
                recipeManager,
                hookBus
        );
        this.basicApi = new MineplusBasicInfrastructureApi(registry, lifecycleManager);
        this.jsonApi = new MineplusJsonInfrastructureApi(this);
        this.configLoader = new MultiBlockConfigLoader(plugin, registry);
        this.recipeLoader = new RecipeConfigLoader(plugin, recipeManager);
        this.tickTaskId = -1;
    }

    public void initialize() {
        reloadMultiBlocks();
        reloadRecipes();
        lifecycleManager.restorePersistedInstances();
        if (tickTaskId == -1) {
            tickTaskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                    plugin,
                    lifecycleManager::tick,
                    20L,
                    20L
            );
        }
    }

    public void shutdown() {
        if (tickTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(tickTaskId);
            tickTaskId = -1;
        }
        lifecycleManager.saveNow();
    }

    public InfrastructureApi api() {
        return api;
    }

    public BasicInfrastructureApi basicApi() {
        return basicApi;
    }

    public JsonInfrastructureApi jsonApi() {
        return jsonApi;
    }

    public MultiBlockRegistry registry() {
        return registry;
    }

    public MultiBlockLifecycleManager lifecycleManager() {
        return lifecycleManager;
    }

    public MultiBlockLinkingSystem linkingSystem() {
        return linkingSystem;
    }

    public RecipeManager recipeManager() {
        return recipeManager;
    }

    public InfrastructureGuiManager guiManager() {
        return guiManager;
    }

    public void reloadAll() {
        reloadModelDefinitions();
        reloadMultiBlocks();
        reloadRecipes();
    }

    public void reloadModelDefinitions() {
        renderingManager.virtualBlockManager().reloadModelDefinitions();
        lifecycleManager.reloadModels();
    }

    public void reloadMultiBlocks() {
        configLoader.loadAll();
        lifecycleManager.pruneUnknownTypes();
    }

    public void reloadRecipes() {
        recipeLoader.loadAll();
    }

    public Set<String> loadedModelKeys() {
        return renderingManager.virtualBlockManager().getAvailableModels();
    }
}
