package com.mineplus.infrastructure.core.multiblock.linking;

import com.mineplus.infrastructure.core.events.MultiBlockSignal;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MultiBlockLinkingSystem {

    private static final int MAX_SIGNAL_HOPS = 16;

    private final MultiBlockRegistry registry;

    public MultiBlockLinkingSystem(MultiBlockRegistry registry) {
        this.registry = registry;
    }

    public boolean linkTo(UUID sourceId, UUID targetId) {
        MultiBlockInstance source = registry.getInstance(sourceId);
        MultiBlockInstance target = registry.getInstance(targetId);
        if (source == null || target == null || sourceId.equals(targetId)) {
            return false;
        }

        source.mutableLinkedBlocks().add(targetId);
        return true;
    }

    public boolean unlink(UUID sourceId, UUID targetId) {
        MultiBlockInstance source = registry.getInstance(sourceId);
        if (source == null) {
            return false;
        }
        return source.mutableLinkedBlocks().remove(targetId);
    }

    public Set<UUID> getLinkedBlocks(UUID sourceId) {
        MultiBlockInstance source = registry.getInstance(sourceId);
        if (source == null) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(source.mutableLinkedBlocks()));
    }

    public void sendSignal(UUID sourceId, UUID targetId, String channel, Map<String, String> payload) {
        MultiBlockInstance target = registry.getInstance(targetId);
        if (target == null) {
            return;
        }

        MultiBlockSignal initial = new MultiBlockSignal(sourceId, targetId, channel, payload, 0);
        propagateSignal(initial);
    }

    private void spawnSignalParticle(MultiBlockInstance from, MultiBlockInstance to) {
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(from.coordinate().worldName());
        if (world == null) return;

        org.bukkit.Location start = new org.bukkit.Location(world, from.coordinate().x() + 0.5, from.coordinate().y() + 0.5, from.coordinate().z() + 0.5);
        org.bukkit.Location end = new org.bukkit.Location(world, to.coordinate().x() + 0.5, to.coordinate().y() + 0.5, to.coordinate().z() + 0.5);

        org.bukkit.util.Vector direction = end.toVector().subtract(start.toVector());
        double distance = direction.length();
        direction.normalize();

        org.bukkit.Location current = start.clone();
        for (double i = 0; i < distance; i += 0.5) {
            current.add(direction.clone().multiply(0.5));
            world.spawnParticle(org.bukkit.Particle.END_ROD, current, 1, 0, 0, 0, 0);
        }
    }

    private void propagateSignal(MultiBlockSignal initial) {
        Deque<MultiBlockSignal> queue = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        queue.add(initial);

        while (!queue.isEmpty()) {
            MultiBlockSignal signal = queue.poll();
            if (signal.hopCount() > MAX_SIGNAL_HOPS || !visited.add(signal.targetId())) {
                continue;
            }

            MultiBlockInstance target = registry.getInstance(signal.targetId());
            if (target == null) {
                continue;
            }

            var type = registry.getType(target.typeId());
            if (type != null) {
                MultiBlockHook hook = type.hook();
                hook.onSignal(target, signal);
            }

            for (UUID nextTarget : target.linkedBlocks()) {
                MultiBlockInstance nextInstance = registry.getInstance(nextTarget);
                if (nextInstance != null) {
                    spawnSignalParticle(target, nextInstance);
                }

                queue.add(new MultiBlockSignal(
                        signal.sourceId(),
                        nextTarget,
                        signal.channel(),
                        signal.payload(),
                        signal.hopCount() + 1
                ));
            }
        }
    }
}
