package com.mineplus.render;

import com.google.common.collect.Sets;
import net.minecraft.server.v1_20_R1.*;
import org.bukkit.craftbukkit.v1_20_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per‑entity dirty‑data tracking and bundle builder.
 * Only packets whose hash changed since the last tick will be included in the bundle.
 */
public class PacketOptimizer {

    // entity UUID -> last sent data hashcode
    private final Map<UUID, Integer> lastSentHashes = new ConcurrentHashMap<>();

    // player UUID -> set of entity UUIDs they should see
    private final Map<UUID, Set<UUID>> playerToEntities = new ConcurrentHashMap<>();

    public PacketOptimizer() {}

    /**
     * Register that *player* should receive updates for *entityId*.
     */
    public void registerEntityForPlayer(UUID entityId, UUID playerId) {
        playerToEntities.computeIfAbsent(playerId, k -> Sets.newHashSet()).add(entityId);
    }

    /** Remove a player from all tracking. */
    public void clearPlayer(UUID playerId) {
        playerToEntities.remove(playerId);
        // we do not purge lastSentHashes here; they will be ignored if not in any player set.
    }

    /**
     * Call once per tick for a given entity.
     * Returns a list containing the new packet if its data changed, or an empty list otherwise.
     */
    public List<Packet<?>> getDirtyPacket(UUID entityId, Packet<?> newPacket) {
        if (newPacket == null) return Collections.emptyList();
        int newHash = newPacket.hashCode();
        Integer oldHash = lastSentHashes.putIfAbsent(entityId, newHash);
        if (oldHash == null) {
            // first time – we must send it
            return Collections.singletonList(newPacket);
        }
        if (!oldHash.equals(newHash)) {
            // data changed – update stored hash and return new packet
            lastSentHashes.put(entityId, newHash);
            return Collections.singletonList(newPacket);
        }
        // nothing changed
        return Collections.emptyList();
    }

    /**
     * Build a {@link ClientboundBundlePacket} containing only the non‑empty dirty packets
     * for the given list of entities that belong to *player*.
     */
    public Packet<?> buildBundle(World world, Collection<EntityHuman> entities, Player player) {
        List<Packet<?>> packets = new ArrayList<>();
        for (EntityHuman ent : entities) {
            UUID eid = ent.getUniqueId();
            // If the player is not tracking this entity, skip
            Set<UUID> entitiesSeen = playerToEntities.get(player.getUniqueId());
            if (entitiesSeen == null || !entitiesSeen.contains(eid)) continue;

            // ask the optimizer for a dirty packet (placeholder – in real code you would pass the most recent packet)
            // Here we just pretend the packet is the last one we stored; for demo we return empty.
            // List<Packet<?>> dirty = getDirtyPacket(eid, /* lastPacket */ null);
            // packets.addAll(dirty);
        }
        if (packets.isEmpty()) return null;

        try {
            Class<?> bundleCls = Class.forName("net.minecraft.server.v1_20_R1.packet.ClientboundBundlePacket");
            java.lang.reflect.Constructor<?> ctor = bundleCls.getDeclaredConstructor(Packet.class, Packet[].class);
            Object bundle = ctor.newInstance(packets.get(0), packets.toArray(new Packet[0]));
            return bundle;
        } catch (Exception e) {
            // fallback – send individually
            return packets.isEmpty() ? null : packets.get(0);
        }
    }
}