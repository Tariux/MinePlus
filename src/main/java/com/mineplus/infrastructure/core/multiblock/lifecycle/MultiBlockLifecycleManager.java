package com.mineplus.infrastructure.core.multiblock.lifecycle;

import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.core.events.HookBus;
import com.mineplus.infrastructure.core.events.MultiBlockSignal;
import com.mineplus.infrastructure.core.gui.InfrastructureGuiManager;
import com.mineplus.infrastructure.core.multiblock.EntityStatus;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.core.multiblock.linking.MultiBlockLinkingSystem;
import com.mineplus.infrastructure.core.multiblock.progress.MachineProcessManager;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import com.mineplus.infrastructure.core.multiblock.render.ModelRenderingManager;
import com.mineplus.infrastructure.core.multiblock.upgrade.UpgradeManager;
import com.mineplus.infrastructure.model.BlockCoordinate;
import com.mineplus.infrastructure.persistence.PersistenceFacade;
import com.mineplus.infrastructure.persistence.snapshot.MultiBlockSnapshot;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.util.DebugLogger;
import java.util.List;
import java.util.Map;
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

    /** Interval of the repeating lifecycle tick task, in ticks. */
    private static final int TICK_INTERVAL_TICKS = 20;

    private final MineplusPlugin plugin;
    private final MultiBlockRegistry registry;
    private final ModelRenderingManager renderingManager;
    private final PersistenceFacade persistenceFacade;
    private final InfrastructureGuiManager guiManager;
    private final UpgradeManager upgradeManager;
    private final HookBus hookBus;
    private final MultiBlockLinkingSystem linkingSystem;
    private MachineProcessManager processManager;
    private int heartbeatTaskId;

    public MultiBlockLifecycleManager(
            MineplusPlugin plugin,
            MultiBlockRegistry registry,
            ModelRenderingManager renderingManager,
            PersistenceFacade persistenceFacade,
            InfrastructureGuiManager guiManager,
            UpgradeManager upgradeManager,
            HookBus hookBus,
            MultiBlockLinkingSystem linkingSystem
    ) {
        this.plugin = plugin;
        this.registry = registry;
        this.renderingManager = renderingManager;
        this.persistenceFacade = persistenceFacade;
        this.guiManager = guiManager;
        this.upgradeManager = upgradeManager;
        this.hookBus = hookBus;
        this.linkingSystem = linkingSystem;
        this.processManager = null;
        this.heartbeatTaskId = -1;
    }

    /**
     * Constructor with an explicit process manager, enabling timed crafting
     * processes ({@link MachineProcessManager}). The process manager requires
     * the recipe registry, which the base constructor does not have access to.
     *
     * @param processManager the timed-process engine, or {@code null} to disable processes
     */
    public MultiBlockLifecycleManager(
            MineplusPlugin plugin,
            MultiBlockRegistry registry,
            ModelRenderingManager renderingManager,
            PersistenceFacade persistenceFacade,
            InfrastructureGuiManager guiManager,
            UpgradeManager upgradeManager,
            HookBus hookBus,
            MultiBlockLinkingSystem linkingSystem,
            MachineProcessManager processManager
    ) {
        this(plugin, registry, renderingManager, persistenceFacade, guiManager, upgradeManager, hookBus, linkingSystem);
        if (processManager != null) {
            this.processManager = processManager;
        }
    }

    /**
     * Binds (or replaces) the timed-process engine. Processes already encoded in
     * restored instances' stateData resume automatically on the next tick.
     * When no process manager is bound, timed processes are disabled and
     * {@link #tick()} skips process advancement entirely.
     *
     * @param processManager the engine to bind; must not be null
     */
    public void setProcessManager(MachineProcessManager processManager) {
        java.util.Objects.requireNonNull(processManager, "processManager");
        this.processManager = processManager;
    }

    /**
     * @return the bound timed-process engine, or {@code null} if timed processes are disabled
     */
    public MachineProcessManager processManager() {
        return processManager;
    }

    public int restorePersistedInstances() {
        DebugLogger.info("MultiBlockLifecycleManager: Loading instances from persistence...");
        registry.clearInstances();
        int loaded = 0;
        int corrupted = 0;
        for (MultiBlockSnapshot snapshot : persistenceFacade.loadAllMultiBlocks()) {
            MultiBlockInstance instance = snapshot.toInstance();
            MultiBlockType type = registry.getType(instance.typeId());
            if (type == null) {
                DebugLogger.warning("MultiBlockLifecycleManager: Instance " + instance.id() + " has unknown type '" + instance.typeId() + "' — marking CORRUPTED.");
                instance.setStatus(EntityStatus.CORRUPTED);
                corrupted++;
            }
            registry.addInstance(instance);
            loaded++;
        }
        DebugLogger.info("MultiBlockLifecycleManager: Loaded " + loaded + " instances (" + corrupted + " corrupted, type missing).");
        reconcile();
        return loaded;
    }

    public void reconcile() {
        int checked = 0;
        int corrupted = 0;
        int deferred = 0;
        int rendered = 0;
        for (MultiBlockInstance instance : new java.util.ArrayList<>(registry.getInstances())) {
            checked++;
            MultiBlockType type = registry.getType(instance.typeId());

            if (type == null) {
                DebugLogger.warning("reconcile: Instance " + instance.id() + " has no type '" + instance.typeId() + "' — mark CORRUPTED.");
                EntityStateMachine.transition(instance, EntityStatus.CORRUPTED);
            }

            EntityStatus status = instance.status() == null ? EntityStatus.CREATED : instance.status();
            if (status == EntityStatus.CORRUPTED) {
                cleanupCorrupted(instance);
                corrupted++;
                continue;
            }

            if (!EntityStateMachine.validateWorldLoaded(instance)) {
                deferred++;
                continue;
            }

            if (status == EntityStatus.ACTIVE) {
                UUID modelId = renderingManager.virtualBlockManager().restoreForState(
                        instance.coordinate(), instance.modelKey(), instance.rotation());
                if (modelId == null && type != null) {
                    DebugLogger.warning("reconcile: restoreForState returned null for instance " + instance.id() + ", attempting render().");
                    modelId = renderingManager.render(type, instance, plugin.getDataFolder());
                }
                if (modelId != null) {
                    rendered++;
                } else {
                    DebugLogger.warning("reconcile: Failed to render model for instance " + instance.id() + " — modelId is null.");
                }
                registry.bindRenderedModelId(instance.id(), modelId);
                instance.setLastValidatedAt(System.currentTimeMillis());
                fire(MultiBlockLifecycleEventType.MODEL_RELOAD, type, instance, null, null);
            }
        }
        persistInstances();
        DebugLogger.info("reconcile: checked=" + checked + " corrupted=" + corrupted + " deferred=" + deferred + " rendered=" + rendered);
    }

    private void cleanupCorrupted(MultiBlockInstance instance) {
        renderingManager.remove(instance);
        instance.mutableLinkedBlocks().clear();
        registry.removeInstance(instance.id());
        EntityStateMachine.transition(instance, EntityStatus.REMOVED);
    }

    public MultiBlockInstance create(String typeId, Location location, UUID owner, UUID creator, Quaternionf rotation) {
        MultiBlockType type = registry.getType(typeId);
        if (type == null) {
            DebugLogger.warning("create: Unknown multiblock type '" + typeId + "' at " + location + ".");
            return null;
        }
        if (location.getWorld() == null) {
            DebugLogger.warning("create: World not loaded for location " + location + ".");
            return null;
        }

        BlockCoordinate coordinate = BlockCoordinate.from(location);
        if (registry.hasAt(coordinate)) {
            DebugLogger.warning("create: Position already occupied at " + coordinate + ".");
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
                EntityStatus.CREATED,
                0L,
                0L,
                null,
                Map.of(),
                Map.of(),
                java.util.Set.of()
        );

        registry.addInstance(instance);
        fire(MultiBlockLifecycleEventType.CREATE, type, instance, null, null);
        persistInstances();
        DebugLogger.info("create: Created multiblock instance " + instance.id() + " of type '" + typeId + "' at " + coordinate + ".");
        return instance;
    }

    public boolean place(UUID id, Player actor) {
        MultiBlockInstance instance = registry.getInstance(id);
        if (instance == null) {
            DebugLogger.warning("place: Instance not found for id " + id + ".");
            return false;
        }

        MultiBlockType type = registry.getType(instance.typeId());
        if (type == null) {
            DebugLogger.warning("place: Type not found for instance " + id + " (typeId='" + instance.typeId() + "').");
            return false;
        }

        EntityStatus current = instance.status() == null ? EntityStatus.CREATED : instance.status();
        if (current != EntityStatus.CREATED) {
            DebugLogger.warning("place: Instance " + id + " is not in CREATED state (current=" + current + ").");
            return false;
        }

        // Spawn-area policy: creative/admin placements clear the target area first;
        // standard players require it to be empty (no spawning inside cactus, grass,
        // or other existing blocks).
        boolean fullAccess = actor == null
                || actor.isOp()
                || actor.getGameMode() == org.bukkit.GameMode.CREATIVE;
        VirtualBlockManager.SpawnAreaResult area = renderingManager.prepareArea(
                type, instance, plugin.getDataFolder(), fullAccess);
        if (area == VirtualBlockManager.SpawnAreaResult.BLOCKED) {
            if (actor != null) {
                actor.sendMessage(org.bukkit.ChatColor.RED
                        + "Insufficient space to place this structure — the target area is not clear.");
            }
            DebugLogger.info("place: Aborted placement of instance " + id + ": spawn area not clear.");
            return false;
        }
        if (area == VirtualBlockManager.SpawnAreaResult.CLEARED) {
            DebugLogger.info("place: Cleared spawn area for instance " + id + " (full-access placement).");
        }

        if (!EntityStateMachine.transition(instance, EntityStatus.PLACED)) {
            DebugLogger.warning("place: Failed to transition instance " + id + " to PLACED.");
            return false;
        }

        UUID modelId = renderingManager.render(type, instance, plugin.getDataFolder());
        if (modelId == null) {
            DebugLogger.severe("place: render() returned null for instance " + id + ". Rolling back to CREATED.");
            EntityStateMachine.transition(instance, EntityStatus.CREATED);
            persistInstances();
            return false;
        }
        registry.bindRenderedModelId(instance.id(), modelId);
        instance.setPlacedAt(System.currentTimeMillis());
        instance.setLastValidatedAt(System.currentTimeMillis());

        fire(MultiBlockLifecycleEventType.PLACE, type, instance, actor, null);
        instance.setStatus(EntityStatus.ACTIVE);
        fire(MultiBlockLifecycleEventType.ACTIVATE, type, instance, actor, null);
        persistInstances();
        DebugLogger.info("place: Placed and activated instance " + id + " (modelKey='" + instance.modelKey() + "').");
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

        persistInstances();
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

        EntityStatus current = instance.status() == null ? EntityStatus.CREATED : instance.status();
        if (current != EntityStatus.ACTIVE) {
            return false;
        }

        int oldLevel = instance.level();
        if (!upgradeManager.consumeUpgradeCost(type, instance, player)) {
            return false;
        }

        instance.setLevel(oldLevel + 1);
        UUID swappedModel = renderingManager.swapModel(type, instance, plugin.getDataFolder());
        if (swappedModel == null) {
            instance.setLevel(oldLevel);
            return false;
        }
        registry.bindRenderedModelId(instance.id(), swappedModel);

        fire(MultiBlockLifecycleEventType.UPGRADE, type, instance, player, null);
        type.hook().onUpgrade(instance, oldLevel, instance.level(), player);
        persistInstances();
        return true;
    }

    public boolean remove(UUID instanceId, Player actor, boolean destroy) {
        MultiBlockInstance instance = registry.getInstance(instanceId);
        if (instance == null) {
            return false;
        }

        if (destroy) {
            if (!EntityStateMachine.transition(instance, EntityStatus.BROKEN)) {
                if (!EntityStateMachine.transition(instance, EntityStatus.REMOVED)) {
                    return false;
                }
            }
        } else {
            if (!EntityStateMachine.transition(instance, EntityStatus.REMOVED)) {
                return false;
            }
        }
        if (instance.status() != EntityStatus.REMOVED) {
            EntityStateMachine.transition(instance, EntityStatus.REMOVED);
        }

        MultiBlockType type = registry.getType(instance.typeId());
        renderingManager.remove(instance);
        registry.removeInstance(instanceId);
        if (linkingSystem != null) {
            linkingSystem.cleanupLinksFor(instanceId);
        }

        if (type != null) {
            if (destroy) {
                fire(MultiBlockLifecycleEventType.DESTRUCTION, type, instance, actor, null);
                type.hook().onBreak(instance, actor);
            } else {
                fire(MultiBlockLifecycleEventType.REMOVE, type, instance, actor, null);
                type.hook().onRemove(instance, actor);
            }
        }
        persistInstances();
        DebugLogger.info("remove: Removed instance " + instanceId + " (destroy=" + destroy + ").");
        return true;
    }

    /**
     * Repeating lifecycle tick (runs every {@link #TICK_INTERVAL_TICKS} ticks).
     *
     * <p>Chunk-awareness (vanilla parity): instances whose containing chunk is
     * currently unloaded are fully skipped — no heartbeat update, no deferred
     * render, no hook dispatch, no process advancement. Their state (including
     * running processes encoded in stateData) is retained and resumes when the
     * chunk loads again, mirroring how vanilla tile entities behave in unloaded
     * chunks.
     */
    public void tick() {
        long now = System.currentTimeMillis();
        if (processManager != null) {
            processManager.advanceAll(TICK_INTERVAL_TICKS);
        }
        for (MultiBlockInstance instance : registry.getInstances()) {
            MultiBlockType type = registry.getType(instance.typeId());
            if (type == null) {
                continue;
            }
            EntityStatus status = instance.status() == null ? EntityStatus.CREATED : instance.status();
            if (status == EntityStatus.ACTIVE) {
                if (!EntityStateMachine.validateChunkLoaded(instance)) {
                    continue;
                }
                instance.setLastHeartbeat(now);

                if (instance.renderedModelId() == null && instance.modelKey() != null && !instance.modelKey().isBlank()) {
                    if (EntityStateMachine.validateWorldLoaded(instance)) {
                        UUID modelId = renderingManager.render(type, instance, plugin.getDataFolder());
                        if (modelId != null) {
                            registry.bindRenderedModelId(instance.id(), modelId);
                            DebugLogger.info("tick: Deferred render succeeded for instance " + instance.id() + ".");
                            fire(MultiBlockLifecycleEventType.MODEL_RELOAD, type, instance, null, null);
                        } else {
                            DebugLogger.warning("tick: Deferred render FAILED for instance " + instance.id() + " (render returned null).");
                        }
                    }
                }
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

    public MultiBlockRegistry registry() {
        return registry;
    }

    /**
     * Saves all live instances durably, blocking until the SQLite write completes.
     * Intended for shutdown and admin-triggered operations; regular gameplay paths
     * use {@link #persistInstances()} (write-behind) instead.
     */
    public void saveNow() {
        persistInstances();
        persistenceFacade.flushNow();
    }

    public void startHeartbeat() {
        if (heartbeatTaskId == -1) {
            heartbeatTaskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                    plugin,
                    this::flushHeartbeats,
                    100L,
                    100L
            );
        }
    }

    public void stopHeartbeat() {
        if (heartbeatTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(heartbeatTaskId);
            heartbeatTaskId = -1;
        }
    }

    /**
     * Periodic heartbeat flush. Chunk-aware, matching {@link #tick()}: instances in
     * unloaded chunks are skipped, so a server whose machines all sit in unloaded
     * chunks stages nothing and the persistence layer stays idle. The heartbeat
     * timestamp is only ever written and persisted (never used for decisions), so
     * pausing it for unloaded machines has no behavioral side effects.
     */
    public void flushHeartbeats() {
        long now = System.currentTimeMillis();
        boolean needsFlush = false;
        for (MultiBlockInstance instance : registry.getInstances()) {
            if (instance.status() == EntityStatus.ACTIVE) {
                if (!EntityStateMachine.validateChunkLoaded(instance)) {
                    continue;
                }
                instance.setLastHeartbeat(now);
                needsFlush = true;
            }
        }
        if (needsFlush) {
            persistInstances();
        }
    }

    /**
     * Stages the current state of all instances for asynchronous persistence.
     * Snapshots are captured on the main thread (instances are main-thread state)
     * but no SQLite I/O happens here; the {@link PersistenceFacade} write-behind
     * cycle performs the actual flush off-thread. All lifecycle hot paths
     * (create/place/interact/upgrade/remove/tick/heartbeat) call this.
     */
    private void persistInstances() {
        List<MultiBlockSnapshot> snapshots = registry.getInstances().stream()
                .map(MultiBlockSnapshot::from)
                .toList();
        persistenceFacade.enqueueFullReplace(snapshots);
        DebugLogger.info("persistInstances: Staged " + snapshots.size() + " instances for async persistence.");
    }

    public void reloadModels() {
        for (MultiBlockInstance instance : registry.getInstances()) {
            MultiBlockType type = registry.getType(instance.typeId());
            if (type == null) {
                continue;
            }
            EntityStatus current = instance.status() == null ? EntityStatus.CREATED : instance.status();
            if (current != EntityStatus.ACTIVE && current != EntityStatus.PLACED) {
                continue;
            }
            UUID modelId = renderingManager.swapModel(type, instance, plugin.getDataFolder());
            registry.bindRenderedModelId(instance.id(), modelId);
            fire(MultiBlockLifecycleEventType.MODEL_RELOAD, type, instance, null, null);
            type.hook().onModelReload(instance);
        }
        persistInstances();
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

        EntityStatus current = instance.status() == null ? EntityStatus.CREATED : instance.status();
        if (current != EntityStatus.ACTIVE && current != EntityStatus.PLACED) {
            return false;
        }

        UUID modelId = renderingManager.swapModel(type, instance, plugin.getDataFolder());
        registry.bindRenderedModelId(instance.id(), modelId);
        fire(MultiBlockLifecycleEventType.MODEL_RELOAD, type, instance, null, null);
        type.hook().onModelReload(instance);
        persistInstances();
        return modelId != null;
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

        EntityStatus current = instance.status() == null ? EntityStatus.CREATED : instance.status();
        if (current != EntityStatus.ACTIVE && current != EntityStatus.PLACED) {
            return false;
        }

        int previousLevel = instance.level();
        instance.setLevel(level);
        UUID modelId = renderingManager.swapModel(type, instance, plugin.getDataFolder());
        registry.bindRenderedModelId(instance.id(), modelId);
        fire(MultiBlockLifecycleEventType.MODEL_RELOAD, type, instance, null, null);
        if (previousLevel != level) {
            fire(MultiBlockLifecycleEventType.UPGRADE, type, instance, null, null);
        }
        persistInstances();
        return modelId != null;
    }

    public int pruneUnknownTypes() {
        Set<UUID> idsToRemove = registry.getInstances().stream()
                .filter(instance -> registry.getType(instance.typeId()) == null)
                .map(MultiBlockInstance::id)
                .collect(java.util.stream.Collectors.toSet());

        for (UUID id : idsToRemove) {
            MultiBlockInstance removed = registry.removeInstance(id);
            if (removed != null) {
                EntityStateMachine.transition(removed, EntityStatus.REMOVED);
                removed.mutableLinkedBlocks().clear();
                if (linkingSystem != null) {
                    linkingSystem.cleanupLinksFor(id);
                }
                if (removed.renderedModelId() != null) {
                    renderingManager.remove(removed);
                }
            }
        }

        if (!idsToRemove.isEmpty()) {
            persistInstances();
        }
        return idsToRemove.size();
    }

    public MultiBlockInstance findByRenderedModelId(UUID renderedModelId) {
        if (renderedModelId == null) {
            return null;
        }
        return registry.getInstanceByRenderedModelId(renderedModelId);
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
