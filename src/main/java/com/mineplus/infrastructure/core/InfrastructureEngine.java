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
import com.mineplus.infrastructure.persistence.PersistenceConfig;
import com.mineplus.infrastructure.persistence.PersistenceFacade;
import com.mineplus.infrastructure.persistence.snapshot.MultiBlockSnapshot;
import com.mineplus.infrastructure.registry.ItemRegistry;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.util.DebugLogger;
import java.io.File;
import java.util.List;
import java.util.Set;

public final class InfrastructureEngine {

    private final MultiBlockRegistry registry;
    private final HookBus hookBus;
    private final InfrastructureGuiManager guiManager;
    private final InfrastructureItemManager itemManager;
    private final RecipeManager recipeManager;
    private final PersistenceFacade persistenceFacade;
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
    private final java.util.logging.Logger logger;
    private int tickTaskId;

    public InfrastructureEngine(MineplusPlugin plugin, VirtualBlockManager virtualBlockManager, ItemRegistry itemRegistry) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.registry = new MultiBlockRegistry();
        this.hookBus = new HookBus(plugin.getLogger());
        this.guiManager = new InfrastructureGuiManager();
        this.itemManager = new InfrastructureItemManager(itemRegistry);
        this.recipeManager = new RecipeManager();
        this.persistenceFacade = new PersistenceFacade(PersistenceConfig.defaults(plugin.getDataFolder()), plugin.getLogger());
        this.persistenceFacade.initialize();
        this.renderingManager = new ModelRenderingManager(virtualBlockManager);
        this.upgradeManager = new UpgradeManager(itemManager);
        this.linkingSystem = new MultiBlockLinkingSystem(registry);
        this.lifecycleManager = new MultiBlockLifecycleManager(
                plugin,
                registry,
                renderingManager,
                persistenceFacade,
                guiManager,
                upgradeManager,
                hookBus,
                linkingSystem
        );
        virtualBlockManager.setLifecycleManager(lifecycleManager);
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
        DebugLogger.info("InfrastructureEngine: Initializing infrastructure...");
        DebugLogger.info("InfrastructureEngine: Loading types from configs.");
        reloadMultiBlocks();
        DebugLogger.info("InfrastructureEngine: Types and multiblocks loaded.");
        reloadRecipes();
        DebugLogger.info("InfrastructureEngine: Recipes loaded.");
        migrateFromJsonIfPresent();
        DebugLogger.info("InfrastructureEngine: Migration check complete.");
        int restored = lifecycleManager.restorePersistedInstances();
        DebugLogger.info("InfrastructureEngine: Restored " + restored + " instances from persistence.");
        if (tickTaskId == -1) {
            tickTaskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                    plugin,
                    lifecycleManager::tick,
                    20L,
                    20L
            );
            DebugLogger.info("InfrastructureEngine: Scheduled lifecycle tick task (ID: " + tickTaskId + ").");
        }
        lifecycleManager.startHeartbeat();
        DebugLogger.info("InfrastructureEngine: Heartbeat started.");
    }

    public void shutdown() {
        DebugLogger.info("InfrastructureEngine: Shutting down. Saving persistent data...");
        if (tickTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(tickTaskId);
            tickTaskId = -1;
            DebugLogger.info("InfrastructureEngine: Tick task cancelled.");
        }
        lifecycleManager.stopHeartbeat();
        DebugLogger.info("InfrastructureEngine: Heartbeat stopped.");
        lifecycleManager.saveNow();
        DebugLogger.info("InfrastructureEngine: Persistent data saved.");
        persistenceFacade.shutdown(5000);
        DebugLogger.info("InfrastructureEngine: Persistence facade shutdown complete.");
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

    private void migrateFromJsonIfPresent() {
        File jsonFile = new File(plugin.getDataFolder(), "multiblocks.json");
        if (!jsonFile.exists()) {
            return;
        }

        List<MultiBlockSnapshot> existing = persistenceFacade.loadAllMultiBlocks();
        if (!existing.isEmpty()) {
            jsonFile.renameTo(new File(plugin.getDataFolder(), "multiblocks.json.migrated"));
            return;
        }

        MultiBlockStorageEngine legacy = new MultiBlockStorageEngine(plugin);
        List<MultiBlockSnapshot> snapshots = legacy.load().stream()
                .map(MultiBlockSnapshot::from)
                .toList();
        persistenceFacade.enqueueFullReplace(snapshots);
        persistenceFacade.flushNow();

        if (jsonFile.delete()) {
            DebugLogger.info("Migrated legacy multiblocks.json to SQLite persistence.");
        } else {
            DebugLogger.warning("Failed to delete legacy multiblocks.json after migration.");
        }
    }

    public void reloadAll() {
        reloadModelDefinitions();
        reloadMultiBlocks();
        reloadRecipes();
        lifecycleManager.reconcile();
        List<MultiBlockSnapshot> snapshots = registry.getInstances().stream()
                .map(MultiBlockSnapshot::from)
                .toList();
        persistenceFacade.enqueueFullReplace(snapshots);
        persistenceFacade.flushNow();
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
