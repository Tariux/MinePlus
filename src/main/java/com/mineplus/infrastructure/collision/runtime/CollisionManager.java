package com.mineplus.infrastructure.collision.runtime;

import com.mineplus.infrastructure.collision.backend.BackendContext;
import com.mineplus.infrastructure.collision.backend.BackendRegistry;
import com.mineplus.infrastructure.collision.backend.CollisionBackend;
import com.mineplus.infrastructure.collision.event.ModelCollisionBuiltEvent;
import com.mineplus.infrastructure.collision.event.ModelCollisionInteractEvent;
import com.mineplus.infrastructure.collision.event.ModelCollisionRemovedEvent;
import com.mineplus.infrastructure.collision.fit.FitResult;
import com.mineplus.util.SchedulerCompat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;

/**
 * Owns the live lifetime of every model collision: applying fitted parts,
 * persisting them, protecting them, restoring ghosts after a player (re)enters
 * the area, and cleaning everything up on disable.
 *
 * <p>All Bukkit access happens on the owning thread. Fitting and atlas work stay
 * elsewhere; this class only materialises finished {@link FitResult}s.
 * Technical parts are applied through a per-tick budget so one large model
 * cannot stall the server.
 */
public final class CollisionManager implements Listener {

    /** Upper bound of parts applied per tick by the queue. */
    public static final int DEFAULT_PARTS_PER_TICK = 256;

    private final Plugin plugin;
    private final CollisionStore store;
    private final BackendRegistry registry;
    private final Consumer<String> warn;
    private final int partsPerTick;

    private final Map<String, PlacedFurniture> byBlock = new HashMap<>();
    private final Map<UUID, PlacedPart> byEntity = new HashMap<>();
    private final Map<UUID, PlacedFurniture> entityOwner = new HashMap<>();
    private final Set<UUID> technicalEntities = new HashSet<>();
    private final Deque<Runnable> pending = new ArrayDeque<>();

    private SchedulerCompat.Handle drainHandle;
    private boolean enabled;

    public CollisionManager(Plugin plugin, CollisionStore store, BackendRegistry registry,
            Consumer<String> warn, int partsPerTick) {
        this.plugin = plugin;
        this.store = store;
        this.registry = registry;
        this.warn = warn == null ? message -> {
        } : warn;
        this.partsPerTick = Math.max(16, partsPerTick);
    }

    // ------------------------------------------------------------- lifecycle

    /** Loads persisted state, reconciles it against the world and starts listening. */
    public void enable() {
        if (enabled) {
            return;
        }
        enabled = true;
        store.load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(new CollisionProtection(this), plugin);
        reconcile();
    }

    /** Removes every technical part and clears the store; safe to call twice. */
    public void disable() {
        if (!enabled) {
            return;
        }
        enabled = false;
        for (PlacedFurniture furniture : new ArrayList<>(store.furniture())) {
            removeParts(furniture);
            store.remove(furniture.id());
        }
        byBlock.clear();
        technicalEntities.clear();
        pending.clear();
        if (drainHandle != null) {
            drainHandle.cancel();
            drainHandle = null;
        }
        store.save();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public CollisionStore store() {
        return store;
    }

    // ------------------------------------------------------------- placement

    /**
     * Materialises a finished fit as a model instance. Fires
     * {@link ModelCollisionBuiltEvent}; a cancelled event leaves nothing behind.
     *
     * @return the stored instance, or {@code null} when the world is missing,
     *         the event was cancelled or no part could be applied
     */
    public PlacedFurniture place(String modelKey, FitResult fit, Location origin) {
        if (fit == null || origin == null || origin.getWorld() == null) {
            return null;
        }
        PlacedFurniture furniture = new PlacedFurniture(UUID.randomUUID(), modelKey,
                origin.getWorld().getName(), origin.getBlockX(), origin.getBlockY(),
                origin.getBlockZ(), fit.yaw(), fit.atlasKey(), fit.profileName(),
                fit.resolution());
        for (int y = 0; y < fit.sizeY(); y++) {
            for (int z = 0; z < fit.sizeZ(); z++) {
                for (int x = 0; x < fit.sizeX(); x++) {
                    var cell = fit.cell(x, y, z);
                    if (cell == null || !cell.backend().placesSomething()) {
                        continue;
                    }
                    PlacedPart part = new PlacedPart(cell.backend(), x, y, z, cell.state());
                    furniture.add(part);
                }
            }
        }
        if (furniture.parts().isEmpty()) {
            return null;
        }
        ModelCollisionBuiltEvent event = new ModelCollisionBuiltEvent(furniture);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return null;
        }
        store.add(furniture);
        index(furniture);
        enqueueAll(furniture);
        store.save();
        return furniture;
    }

    /** Applies a stored instance again (reconciliation path). */
    public void applyStored(PlacedFurniture furniture) {
        index(furniture);
        enqueueAll(furniture);
    }

    public boolean remove(UUID id) {
        PlacedFurniture furniture = store.byId(id);
        if (furniture == null) {
            return false;
        }
        return remove(furniture);
    }

    public boolean remove(PlacedFurniture furniture) {
        removeParts(furniture);
        store.remove(furniture.id());
        unindex(furniture);
        Bukkit.getPluginManager().callEvent(new ModelCollisionRemovedEvent(furniture));
        store.save();
        return true;
    }

    public PlacedFurniture furnitureAt(Block block) {
        return byBlock.get(blockKey(block));
    }

    public boolean isTechnical(Block block) {
        return byBlock.containsKey(blockKey(block));
    }

    public boolean isTechnicalEntity(UUID id) {
        return technicalEntities.contains(id);
    }

    // ---------------------------------------------------------- reconciliation

    /**
     * Compares the store with the world: drops records whose world is gone,
     * removes entities that no longer exist, and re-places missing blocks when
     * their chunk is loaded.
     */
    public void reconcile() {
        int repaired = 0;
        int dropped = 0;
        for (PlacedFurniture furniture : new ArrayList<>(store.furniture())) {
            World world = Bukkit.getWorld(furniture.world());
            if (world == null) {
                warn.accept("collision: dropping " + furniture.modelKey() + " in missing world "
                        + furniture.world());
                store.remove(furniture.id());
                dropped++;
                continue;
            }
            index(furniture);
            for (PlacedPart part : furniture.parts()) {
                if (part.entityId() != null) {
                    Entity entity = Bukkit.getEntity(part.entityId());
                    if (entity == null) {
                        part.setEntityId(null);
                        repaired++;
                    } else {
                        technicalEntities.add(part.entityId());
                        byEntity.put(part.entityId(), part);
                        entityOwner.put(part.entityId(), furniture);
                    }
                }
            }
            enqueueAll(furniture);
        }
        if (repaired > 0 || dropped > 0) {
            store.markDirty();
            store.save();
            warn.accept("collision: reconciliation repaired " + repaired + " part(s), dropped "
                    + dropped + " model(s)");
        }
    }

    // -------------------------------------------------------------- listeners

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        SchedulerCompat.runLater(plugin, 20L, () -> refreshGhosts(event.getPlayer()));
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        SchedulerCompat.runLater(plugin, 5L, () -> refreshGhosts(event.getPlayer()));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        SchedulerCompat.runLater(plugin, 10L, () -> refreshGhosts(event.getPlayer()));
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // Entities load with their chunk; nothing to do unless a fit was queued
        // while the chunk was unloaded.
        if (!pending.isEmpty()) {
            ensureDrain();
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        reconcile();
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        UUID id = event.getRightClicked().getUniqueId();
        PlacedFurniture furniture = entityOwner.get(id);
        if (furniture == null) {
            return;
        }
        ModelCollisionInteractEvent interact = new ModelCollisionInteractEvent(
                event.getPlayer(), event.getRightClicked(), furniture, byEntity.get(id),
                event.getRightClicked().getLocation());
        Bukkit.getPluginManager().callEvent(interact);
        if (interact.isCancelled()) {
            event.setCancelled(true);
        }
    }

    private void refreshGhosts(Player player) {
        for (PlacedFurniture furniture : store.furniture()) {
            if (!furniture.world().equals(player.getWorld().getName())) {
                continue;
            }
            BackendContext context = new BackendContext(player.getWorld(), furniture.originX(),
                    furniture.originY(), furniture.originZ(), warn);
            registry.get(com.mineplus.infrastructure.collision.fit.BackendType.GHOST_BLOCK)
                    .refresh(context, furniture, List.of(player));
        }
    }

    // ---------------------------------------------------------------- helpers

    private void enqueueAll(PlacedFurniture furniture) {
        World world = Bukkit.getWorld(furniture.world());
        if (world == null) {
            return;
        }
        BackendContext context = new BackendContext(world, furniture.originX(), furniture.originY(),
                furniture.originZ(), warn);
        for (PlacedPart part : furniture.parts()) {
            pending.add(() -> applyPart(context, furniture, part));
        }
        ensureDrain();
    }

    private void applyPart(BackendContext context, PlacedFurniture furniture, PlacedPart part) {
        if (context.world() == null) {
            return;
        }
        Location location = context.blockLocation(furniture, part);
        if (!context.world().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return;
        }
        CollisionBackend backend = registry.get(part.backend());
        try {
            backend.apply(context, furniture, part);
            if (part.entityId() != null) {
                technicalEntities.add(part.entityId());
                byEntity.put(part.entityId(), part);
                entityOwner.put(part.entityId(), furniture);
            }
        } catch (RuntimeException failure) {
            warn.accept("collision: " + part.backend() + " failed at "
                    + location.getBlockX() + "," + location.getBlockY() + ","
                    + location.getBlockZ() + ": " + failure.getMessage());
        }
    }

    private void removeParts(PlacedFurniture furniture) {
        World world = Bukkit.getWorld(furniture.world());
        if (world == null) {
            return;
        }
        BackendContext context = new BackendContext(world, furniture.originX(), furniture.originY(),
                furniture.originZ(), warn);
        // Removal is not budgeted: leaving technical blocks behind is worse than
        // one slow tick during shutdown.
        for (PlacedPart part : furniture.parts()) {
            try {
                registry.get(part.backend()).remove(context, furniture, part);
            } catch (RuntimeException failure) {
                warn.accept("collision: cannot remove " + part.backend() + ": "
                        + failure.getMessage());
            }
            if (part.entityId() != null) {
                technicalEntities.remove(part.entityId());
                byEntity.remove(part.entityId());
                entityOwner.remove(part.entityId());
            }
        }
    }

    private void ensureDrain() {
        if (drainHandle != null || pending.isEmpty()) {
            return;
        }
        drainHandle = SchedulerCompat.repeat(plugin, 1L, 1L, this::drain);
    }

    private void drain() {
        int applied = 0;
        while (applied < partsPerTick && !pending.isEmpty()) {
            pending.poll().run();
            applied++;
        }
        if (pending.isEmpty() && drainHandle != null) {
            drainHandle.cancel();
            drainHandle = null;
        }
    }

    private void index(PlacedFurniture furniture) {
        World world = Bukkit.getWorld(furniture.world());
        if (world == null) {
            return;
        }
        for (PlacedPart part : furniture.parts()) {
            if (part.backend() == com.mineplus.infrastructure.collision.fit.BackendType.REAL_BLOCK) {
                byBlock.put(blockKey(world.getName(), (int) Math.floor(furniture.originX())
                        + part.cellX(), (int) Math.floor(furniture.originY()) + part.cellY(),
                        (int) Math.floor(furniture.originZ()) + part.cellZ()), furniture);
            }
        }
    }

    private void unindex(PlacedFurniture furniture) {
        byBlock.values().removeIf(entry -> entry.id().equals(furniture.id()));
    }

    private static String blockKey(Block block) {
        return blockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private static String blockKey(String world, int x, int y, int z) {
        return world + '|' + x + '|' + y + '|' + z;
    }
}
