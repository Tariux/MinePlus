package com.mineplus.infrastructure.virtual.display.pool;

import com.mineplus.infrastructure.virtual.display.nms.NmsAdapter;
import com.mineplus.infrastructure.virtual.display.packet.PacketOptimizer;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
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

public final class EntityPoolManager {

    private final NmsAdapter nms;
    private final PacketOptimizer packets;
    private final int maxIdlePerWorld;

    private final Map<UUID, Deque<PooledDisplay>> idleBlocks = new HashMap<>();
    private final Map<UUID, Deque<PooledDisplay>> idleItems  = new HashMap<>();
    private final Int2ObjectOpenHashMap<PooledDisplay> byEntityId = new Int2ObjectOpenHashMap<>();
    private final Map<UUID, PooledDisplay> byUniqueId = new HashMap<>();

    private long created, reused, destroyed;

    public EntityPoolManager(NmsAdapter nms, PacketOptimizer packets, int maxIdlePerWorld) {
        this.nms = nms;
        this.packets = packets;
        this.maxIdlePerWorld = maxIdlePerWorld;
    }

    public PooledDisplay acquireBlock(World world) {
        Deque<PooledDisplay> pool = idleBlocks.computeIfAbsent(world.getUID(), k -> new ArrayDeque<>());
        PooledDisplay d = pool.pollFirst();
        if (d == null) {
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

    private static void configureDefaults(Display e) {
        e.setPersistent(false);
        e.setViewRange(1.0f);
        e.setShadowRadius(0f);
        e.setTeleportDuration(1);
        e.setInterpolationDuration(0);
    }

    public void release(PooledDisplay d) {
        if (!d.isInUse()) return;
        d.setInUse(false);
        d.reset();

        Object invisible = nms.metadataPacket(d.entity(), true);
        if (invisible != null) {
            for (UUID id : d.knownClients()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) packets.queue(p, invisible);
            }
        }

        Map<UUID, Deque<PooledDisplay>> pools = d.entity() instanceof BlockDisplay ? idleBlocks : idleItems;
        Deque<PooledDisplay> pool = pools.computeIfAbsent(d.entity().getWorld().getUID(), k -> new ArrayDeque<>());
        if (pool.size() >= maxIdlePerWorld) {
            destroy(d);
        } else {
            pool.addFirst(d);
        }
    }

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

    public void forgetPlayer(UUID player) {
        for (PooledDisplay d : byEntityId.values()) d.forget(player);
    }

    public PooledDisplay byEntityId(int id) {
        return byEntityId.get(id);
    }

    public PooledDisplay byUniqueId(UUID entityUniqueId) {
        return byUniqueId.get(entityUniqueId);
    }

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
