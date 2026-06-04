package com.mineplus.infrastructure.core.api;

import com.mineplus.infrastructure.core.events.HookBus;
import com.mineplus.infrastructure.core.events.MultiBlockSignal;
import com.mineplus.infrastructure.core.gui.InfrastructureGui;
import com.mineplus.infrastructure.core.gui.InfrastructureGuiManager;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleEvent;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleManager;
import com.mineplus.infrastructure.core.multiblock.linking.MultiBlockLinkingSystem;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import com.mineplus.infrastructure.core.recipes.MachineRecipe;
import com.mineplus.infrastructure.core.recipes.RecipeManager;
import com.mineplus.infrastructure.model.BlockCoordinate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.joml.Quaternionf;

public final class MineplusInfrastructureApi implements InfrastructureApi {

    private final MultiBlockRegistry registry;
    private final MultiBlockLifecycleManager lifecycleManager;
    private final MultiBlockLinkingSystem linkingSystem;
    private final InfrastructureGuiManager guiManager;
    private final RecipeManager recipeManager;
    private final HookBus hookBus;

    public MineplusInfrastructureApi(
            MultiBlockRegistry registry,
            MultiBlockLifecycleManager lifecycleManager,
            MultiBlockLinkingSystem linkingSystem,
            InfrastructureGuiManager guiManager,
            RecipeManager recipeManager,
            HookBus hookBus
    ) {
        this.registry = registry;
        this.lifecycleManager = lifecycleManager;
        this.linkingSystem = linkingSystem;
        this.guiManager = guiManager;
        this.recipeManager = recipeManager;
        this.hookBus = hookBus;
    }

    @Override
    public void registerMultiBlock(MultiBlockType type) {
        registry.registerType(type);
    }

    @Override
    public MultiBlockInstance createMultiBlock(String typeId, Location location, UUID owner, UUID creator, Quaternionf rotation) {
        return lifecycleManager.create(typeId, location, owner, creator, rotation);
    }

    @Override
    public boolean placeMultiBlock(UUID id, Player actor) {
        return lifecycleManager.place(id, actor);
    }

    @Override
    public boolean upgradeBlock(UUID id, Player actor) {
        return lifecycleManager.upgrade(id, actor);
    }

    @Override
    public boolean removeBlock(UUID id, Player actor, boolean destroy) {
        return lifecycleManager.remove(id, actor, destroy);
    }

    @Override
    public void registerHook(String typeId, MultiBlockHook hook) {
        registry.registerHookOverride(typeId, hook);
    }

    @Override
    public void registerLifecycleListener(Consumer<MultiBlockLifecycleEvent> listener) {
        hookBus.registerLifecycleListener(listener);
    }

    @Override
    public void createRecipe(MachineRecipe recipe) {
        recipeManager.register(recipe);
    }

    @Override
    public void registerGui(String key, InfrastructureGui gui) {
        guiManager.register(key, gui);
    }

    @Override
    public boolean openGui(String key, Player player, MultiBlockInstance instance) {
        return guiManager.open(key, player, instance);
    }

    @Override
    public boolean linkBlocks(UUID from, UUID to) {
        return linkingSystem.linkTo(from, to);
    }

    @Override
    public boolean unlinkBlocks(UUID from, UUID to) {
        return linkingSystem.unlink(from, to);
    }

    @Override
    public Set<UUID> getLinkedBlocks(UUID sourceId) {
        return linkingSystem.getLinkedBlocks(sourceId);
    }

    @Override
    public void sendSignal(UUID sourceId, UUID targetId, String channel, Map<String, String> data) {
        linkingSystem.sendSignal(sourceId, targetId, channel, data);
    }

    @Override
    public MultiBlockInstance getBlock(UUID id) {
        return registry.getInstance(id);
    }

    @Override
    public MultiBlockInstance getBlockAt(Location location) {
        return registry.getByLocation(BlockCoordinate.from(location));
    }

    @Override
    public MultiBlockSignal createSignal(UUID sourceId, UUID targetId, String channel, Map<String, String> data, int hops) {
        return new MultiBlockSignal(sourceId, targetId, channel, data, hops);
    }
}
