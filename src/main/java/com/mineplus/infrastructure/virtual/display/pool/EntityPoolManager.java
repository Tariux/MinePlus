package com.mineplus.infrastructure.virtual.display.pool;

import com.mineplus.infrastructure.virtual.display.nms.NmsAdapter;
import com.mineplus.infrastructure.virtual.display.packet.PacketOptimizer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reusable-entity pattern for the display transport. Display entities are expensive
 * to (re)create client-side (new render state, new interpolation state, entity id
 * churn); the pool keeps them alive and only changes what they show.
 */
public final class EntityPoolManager {

    private final NmsAdapter nms;
    private final PacketOptimizer packets;
    private final int maxIdlePerWorld;

    private final Map<UUID, Deque<PooledDisplay>> idleBlocks = new HashMap<>();
    private final Map<UUID, Deque<PooledDisplay>> idleItems  = new HashMap<>();
    private final Map<Integer, PooledDisplay> byEntityId = new HashMap<>();
    private final Map<UUID, PooledDisplay> byUniqueId = new HashMap<>();

    private long created, reused, destroyed;

    public EntityPoolManager(NmsAdapter nms, PacketOptimizer packets, int maxIdlePerWorld) {
        this.nms = nms;
        this.packets = packets;
        this.maxIdlePerWorld = maxIdlePerWorld;
    }

    // ------------------------------------------------------------------ acquire

    public PooledDisplay acquireBlock(World world) {
        Deque<PooledDisplay> pool = idleBlocks.computeIfAbsent(world.getUID(), k -> new ArrayDeque<>());
        PooledDisplay d = pool.pollFirst();
        if (d == null) {
            // Real entity object, real entity id, real SynchedEntityData - but never spawned or ticked.
            BlockDisplay entity = world.createEntity(new Location(world, 0, -512, 0), BlockDisplay.class);
            configureDefaults(entity);
            d = new PooledDisplay(entity);
            byEntityId.put(d.id(), d);
            byUniqueId.put(entity.getUniqueId(), d);
            created++;
        } else {
            reused++;
        }
        d.setInUse(true);
        return d;
    }

    public PooledDisplay acquireItem(World world) {
        Deque<PooledDisplay> pool = idleItems.computeIfAbsent(world.getUID(), k -> new ArrayDeque<>());
        PooledDisplay d = pool.pollFirst();
        if (d == null) {
            // Real entity object, real entity id, real SynchedEntityData - but never spawned or ticked.
            ItemDisplay entity = world.createEntity(new Location(world, 0, -512, 0), ItemDisplay.class);
            configureDefaults(entity);
            d = new PooledDisplay(entity);
            byEntityId.put(d.id(), d);
            byUniqueId.put(entity.getUniqueId(), d);
            created++;
        } else {
            reused++;
        }
        d.setInUse(true);
        return d;
    }

    private static void configureDefaults(org.bukkit.entity.Display e) {
        e.setPersistent(false);
        e.setViewRange(1.0f);          // client culling distance factor; LOD is done server-side anyway
        e.setShadowRadius(0f);
        e.setTeleportDuration(1);      // smooth 1-tick position/rotation blending
        e.setInterpolationDuration(0);
    }

    // ------------------------------------------------------------------ release

    /**
     * Hands the display back. Clients that still know the id receive a metadata delta that
     * turns it invisible (AIR / no item, identity transform) - NO remove packet, so the next
     * acquire can reuse the id for those clients with a cheap teleport + metadata.
     */
    public void release(PooledDisplay d) {
        if (!d.isInUse()) return;
        d.setInUse(false);
        d.reset();

        Object invisible = nms.metadataPacket(d.entity(), true);   // dirty delta contains the AIR change
        if (invisible != null) {
            for (UUID id : d.knownClients()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) packets.queue(p, invisible);
            }
        }

        Map<UUID, Deque<PooledDisplay>> pools = d.entity() instanceof BlockDisplay ? idleBlocks : idleItems;
        Deque<PooledDisplay> pool = pools.computeIfAbsent(d.entity().getWorld().getUID(), k -> new ArrayDeque<>());
        if (pool.size() >= maxIdlePerWorld) {
            destroy(d);                // over budget: really remove it everywhere
        } else {
            pool.addFirst(d);          // LIFO: hot entity, still in the clients' memory
        }
    }

    /** Sends real remove packets for the display to every client that knows it. */
    public void destroy(PooledDisplay d) {
        Object remove = nms.removePacket(d.id());
        for (UUID id : new ArrayList<>(d.knownClients())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) packets.queue(p, remove);
            d.forget(id);
        }
        byEntityId.remove(d.id());
        byUniqueId.remove(d.entity().getUniqueId());
        destroyed++;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Player left: their client state is gone, drop them from every known-set (no packets). */
    public void forgetPlayer(UUID player) {
        for (PooledDisplay d : byEntityId.values()) d.forget(player);
    }

    public PooledDisplay byEntityId(int id) {
        return byEntityId.get(id);
    }

    /** UUID-keyed lookup: matches the entity ids the spawn path records in bindings. */
    public PooledDisplay byUniqueId(UUID entityUniqueId) {
        return byUniqueId.get(entityUniqueId);
    }

    /** Removes every virtual entity from every client (plugin disable / reload). */
    public void shutdown() {
        List<PooledDisplay> all = new ArrayList<>(byEntityId.values());
        for (PooledDisplay d : all) destroy(d);
        idleBlocks.clear();
        idleItems.clear();
        byEntityId.clear();
        byUniqueId.clear();
    }

    public record Stats(long created, long reused, long destroyed, int live) {}

    public Stats stats() {
        return new Stats(created, reused, destroyed, byEntityId.size());
    }
}
