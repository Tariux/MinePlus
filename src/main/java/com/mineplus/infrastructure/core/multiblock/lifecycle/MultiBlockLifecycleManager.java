package com.mineplus.infrastructure.core.multiblock.lifecycle;

import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.core.events.HookBus;
import com.mineplus.infrastructure.core.events.MultiBlockSignal;
import com.mineplus.infrastructure.core.gui.InfrastructureGuiManager;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import com.mineplus.infrastructure.core.multiblock.render.ModelRenderingManager;
import com.mineplus.infrastructure.core.multiblock.upgrade.UpgradeManager;
import com.mineplus.infrastructure.persistence.PersistenceFacade;
import com.mineplus.infrastructure.persistence.snapshot.MultiBlockSnapshot;
import com.mineplus.infrastructure.model.BlockCoordinate;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.joml.Quaternionf;

public final class MultiBlockLifecycleManager {

    private final MineplusPlugin plugin;
    private final MultiBlockRegistry registry;
    private final ModelRenderingManager renderingManager;
    private final PersistenceFacade persistence;
    private final InfrastructureGuiManager guiManager;
    private final UpgradeManager upgradeManager;
    private final HookBus hookBus;

    public MultiBlockLifecycleManager(
            MineplusPlugin plugin,
            MultiBlockRegistry registry,
            ModelRenderingManager renderingManager,
            PersistenceFacade persistence,
            InfrastructureGuiManager guiManager,
            UpgradeManager upgradeManager,
            HookBus hookBus
    ) {
        this.plugin = plugin;
        this.registry = registry;
        this.renderingManager = renderingManager;
        this.persistence = persistence;
        this.guiManager = guiManager;
        this.upgradeManager = upgradeManager;
        this.hookBus = hookBus;
    }

    public void restorePersistedInstances() {
        registry.clearInstances();
        for (MultiBlockSnapshot snapshot : persistence.loadAllMultiBlocks()) {
            MultiBlockInstance instance = snapshot.toInstance();
            MultiBlockType type = registry.getType(instance.typeId());
            if (type == null) {
                continue;
            }

            UUID modelId = renderingManager.render(type, instance, plugin.getDataFolder());
            instance.setRenderedModelId(modelId);
            registry.addInstance(instance);
            fire(MultiBlockLifecycleEventType.MODEL_RELOAD, type, instance, null, null);
        }
    }

    public MultiBlockInstance create(String typeId, Location location, UUID owner, UUID creator, Quaternionf rotation) {
        MultiBlockType type = registry.getType(typeId);
        if (type == null || location.getWorld() == null) {
            return null;
        }

        BlockCoordinate coordinate = BlockCoordinate.from(location);
        if (registry.hasAt(coordinate)) {
            return null;
        }

        MultiBlockInstance instance = new MultiBlockInstance(
                UUID.randomUUID(),
                type.id(),
                coordinate,
                owner,
                creator,
                System.currentTimeMillis(),
                0L,
                type.minLevel(),
                rotation == null ? new Quaternionf() : rotation,
                null,
                Map.of(),
                Map.of(),
                java.util.Set.of()
        );

        registry.addInstance(instance);
        fire(MultiBlockLifecycleEventType.CREATE, type, instance, null, null);
        saveAsync();
        return instance;
    }

    public boolean place(UUID id, Player actor) {
        MultiBlockInstance instance = registry.getInstance(id);
        if (instance == null) {
            return false;
        }

        MultiBlockType type = registry.getType(instance.typeId());
        if (type == null) {
            return false;
        }

        UUID modelId = renderingManager.render(type, instance, plugin.getDataFolder());
        instance.setRenderedModelId(modelId);
        instance.setPlacedAt(System.currentTimeMillis());

        fire(MultiBlockLifecycleEventType.PLACE, type, instance, actor, null);
        fire(MultiBlockLifecycleEventType.ACTIVATE, type, instance, actor, null);
        saveAsync();
        return true;
    }

    public boolean interact(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return false;
        }

        MultiBlockInstance instance = registry.getByLocation(BlockCoordinate.from(event.getClickedBlock()));
        boolean handled = interact(event.getPlayer(), instance);
        if (handled) {
            event.setCancelled(true);
        }
        return handled;
    }

    public boolean interact(Player player, MultiBlockInstance instance) {
        if (instance == null) {
            return false;
        }

        MultiBlockType type = registry.getType(instance.typeId());
        if (type == null) {
            return false;
        }

        fire(MultiBlockLifecycleEventType.INTERACT, type, instance, player, null);
        type.hook().onInteract(instance, player);

        if (!type.guiKey().isBlank()) {
            guiManager.open(type.guiKey(), player, instance);
        }

        saveAsync();
        return true;
    }

    public boolean upgrade(UUID instanceId, Player player) {
        MultiBlockInstance instance = registry.getInstance(instanceId);
        if (instance == null) {
            return false;
        }

        MultiBlockType type = registry.getType(instance.typeId());
        if (type == null || !upgradeManager.canUpgrade(type, instance, player)) {
            return false;
        }

        int oldLevel = instance.level();
        if (!upgradeManager.consumeUpgradeCost(type, instance, player)) {
            return false;
        }

        instance.setLevel(oldLevel + 1);
        UUID swappedModel = renderingManager.swapModel(type, instance, plugin.getDataFolder());
        instance.setRenderedModelId(swappedModel);

        fire(MultiBlockLifecycleEventType.UPGRADE, type, instance, player, null);
        type.hook().onUpgrade(instance, oldLevel, instance.level(), player);
        saveAsync();
        return true;
    }

    public boolean remove(UUID instanceId, Player actor, boolean destroy) {
        MultiBlockInstance instance = registry.getInstance(instanceId);
        if (instance == null) {
            return false;
        }

        MultiBlockType type = registry.getType(instance.typeId());
        if (type == null) {
            return false;
        }

        renderingManager.remove(instance);
        registry.removeInstance(instanceId);

        if (destroy) {
            fire(MultiBlockLifecycleEventType.DESTRUCTION, type, instance, actor, null);
            type.hook().onBreak(instance, actor);
        } else {
            fire(MultiBlockLifecycleEventType.REMOVE, type, instance, actor, null);
            type.hook().onRemove(instance, actor);
        }
        saveAsync();
        return true;
    }

    public void tick() {
        for (MultiBlockInstance instance : registry.getInstances()) {
            MultiBlockType type = registry.getType(instance.typeId());
            if (type == null) {
                continue;
            }
            fire(MultiBlockLifecycleEventType.TICK, type, instance, null, null);
            type.hook().onTick(instance);
        }
    }

    public void craft(UUID instanceId, Player actor) {
        MultiBlockInstance instance = registry.getInstance(instanceId);
        if (instance == null) {
            return;
        }
        MultiBlockType type = registry.getType(instance.typeId());
        if (type == null) {
            return;
        }
        fire(MultiBlockLifecycleEventType.CRAFT, type, instance, actor, null);
        type.hook().onCraft(instance, actor);
    }

    public void use(UUID instanceId, Player actor) {
        MultiBlockInstance instance = registry.getInstance(instanceId);
        if (instance == null) {
            return;
        }
        MultiBlockType type = registry.getType(instance.typeId());
        if (type == null) {
            return;
        }
        fire(MultiBlockLifecycleEventType.USAGE, type, instance, actor, null);
    }

    public void handleSignal(UUID targetId, MultiBlockSignal signal) {
        MultiBlockInstance instance = registry.getInstance(targetId);
        if (instance == null) {
            return;
        }
        MultiBlockType type = registry.getType(instance.typeId());
        if (type == null) {
            return;
        }
        fire(MultiBlockLifecycleEventType.USAGE, type, instance, null, signal);
        type.hook().onSignal(instance, signal);
    }

    public MultiBlockInstance findByLocation(org.bukkit.block.Block block) {
        return registry.getByLocation(BlockCoordinate.from(block));
    }

    public void saveNow() {
        persistence.enqueueMultiBlockReplace(registry.getInstances().stream().map(MultiBlockSnapshot::from).toList());
        persistence.flushNow();
    }

    private void saveAsync() {
        persistence.enqueueMultiBlockReplace(registry.getInstances().stream().map(MultiBlockSnapshot::from).toList());
    }

    public void reloadModels() {
        for (MultiBlockInstance instance : registry.getInstances()) {
            MultiBlockType type = registry.getType(instance.typeId());
            if (type == null) {
                continue;
            }
            UUID modelId = renderingManager.swapModel(type, instance, plugin.getDataFolder());
            instance.setRenderedModelId(modelId);
            fire(MultiBlockLifecycleEventType.MODEL_RELOAD, type, instance, null, null);
            type.hook().onModelReload(instance);
        }
        saveAsync();
    }

    public boolean reloadModel(UUID instanceId) {
        MultiBlockInstance instance = registry.getInstance(instanceId);
        if (instance == null) {
            return false;
        }

        MultiBlockType type = registry.getType(instance.typeId());
        if (type == null) {
            return false;
        }

        UUID modelId = renderingManager.swapModel(type, instance, plugin.getDataFolder());
        instance.setRenderedModelId(modelId);
        fire(MultiBlockLifecycleEventType.MODEL_RELOAD, type, instance, null, null);
        type.hook().onModelReload(instance);
        saveAsync();
        return true;
    }

    public boolean setLevel(UUID instanceId, int level) {
        MultiBlockInstance instance = registry.getInstance(instanceId);
        if (instance == null) {
            return false;
        }

        MultiBlockType type = registry.getType(instance.typeId());
        if (type == null || type.level(level) == null) {
            return false;
        }

        int previousLevel = instance.level();
        instance.setLevel(level);
        UUID modelId = renderingManager.swapModel(type, instance, plugin.getDataFolder());
        instance.setRenderedModelId(modelId);
        fire(MultiBlockLifecycleEventType.MODEL_RELOAD, type, instance, null, null);
        if (previousLevel != level) {
            fire(MultiBlockLifecycleEventType.UPGRADE, type, instance, null, null);
        }
        saveAsync();
        return true;
    }

    public int pruneUnknownTypes() {
        Set<UUID> idsToRemove = registry.getInstances().stream()
                .filter(instance -> registry.getType(instance.typeId()) == null)
                .map(MultiBlockInstance::id)
                .collect(java.util.stream.Collectors.toSet());

        for (UUID id : idsToRemove) {
            MultiBlockInstance removed = registry.removeInstance(id);
            if (removed != null && removed.renderedModelId() != null) {
                renderingManager.remove(removed);
            }
        }

        if (!idsToRemove.isEmpty()) {
            saveAsync();
        }
        return idsToRemove.size();
    }

    public MultiBlockInstance findByRenderedModelId(UUID renderedModelId) {
        if (renderedModelId == null) {
            return null;
        }
        return registry.getInstances().stream()
                .filter(instance -> Objects.equals(renderedModelId, instance.renderedModelId()))
                .findFirst()
                .orElse(null);
    }

    public Location toLocation(MultiBlockInstance instance) {
        World world = Bukkit.getWorld(instance.coordinate().worldName());
        if (world == null) {
            return null;
        }
        return new Location(world, instance.coordinate().x(), instance.coordinate().y(), instance.coordinate().z());
    }

    private void fire(
            MultiBlockLifecycleEventType type,
            MultiBlockType definition,
            MultiBlockInstance instance,
            Player actor,
            MultiBlockSignal signal
    ) {
        hookBus.publish(new MultiBlockLifecycleEvent(type, definition, instance, actor, signal));
    }
}
