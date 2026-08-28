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

    public void cleanupLinksFor(UUID removedId) {
        for (MultiBlockInstance instance : registry.getInstances()) {
            instance.mutableLinkedBlocks().remove(removedId);
        }
    }

    /**
     * Automatically links an instance to every other instance within a cube around
     * it (Chebyshev distance {@code <= radius}). Enables pipe-network style
     * scenarios: a newly placed pump auto-connects to adjacent filters without
     * any plugin knowing individual instance UUIDs.
     *
     * <p>Links are directed from the anchor to each discovered neighbor (the
     * same semantics as {@link #linkTo(UUID, UUID)}). Existing links are
     * preserved; this method only adds.
     *
     * @param sourceId the instance to link from (the network joiner)
     * @param radius   the Chebyshev radius to search (typically 1 for adjacency)
     * @return the number of new links created
     */
    public int autoLinkNeighbors(UUID sourceId, int radius) {
        MultiBlockInstance source = registry.getInstance(sourceId);
        if (source == null) {
            return 0;
        }
        int created = 0;
        for (MultiBlockInstance neighbor : registry.getNearby(source.coordinate(), radius)) {
            if (source.mutableLinkedBlocks().add(neighbor.id())) {
                created++;
            }
        }
        return created;
    }

    private void propagateSignal(MultiBlockSignal initial) {
        Deque<MultiBlockSignal> queue = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        // Routed ledger (FMM ProjectileTarget pattern): prevents the same
        // (source, target, channel) pair from being delivered twice within one
        // propagation, even when graph cycles would otherwise re-visit a target
        // through a different path after the targetId guard has released it.
        Set<SignalRoute> routed = new HashSet<>();
        queue.add(initial);

        while (!queue.isEmpty()) {
            MultiBlockSignal signal = queue.poll();
            if (signal.hopCount() > MAX_SIGNAL_HOPS || !visited.add(signal.targetId())) {
                continue;
            }
            if (!routed.add(new SignalRoute(signal.sourceId(), signal.targetId(), signal.channel()))) {
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

    /** Identity of a single signal delivery, used by the routede-ledger dedupe. */
    private record SignalRoute(UUID source, UUID target, String channel) {
    }
}
