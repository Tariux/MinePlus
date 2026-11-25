package com.mineplus.infrastructure.core.research;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Research tree (mega-plan C4 / Phase C): Slimefun-style unlock graph.
 * Pure computation — nodes, prerequisite edges, unlock costs, and a
 * progress store with cycle detection at definition time.
 *
 * <p>Features declare nodes with prerequisites and a cost in any currency
 * the host maps (item, XP, custom). {@link #unlock} validates the whole
 * chain: a node is unlockable only when every prerequisite is already
 * unlocked for that owner and the budget covers the cost.
 */
public final class ResearchTree {

    /** One researchable node. */
    public static final class Node {
        private final String id;
        private final List<String> prerequisites;
        private final int cost;

        Node(String id, List<String> prerequisites, int cost) {
            this.id = id;
            this.prerequisites = prerequisites;
            this.cost = cost;
        }

        public String id() {
            return id;
        }

        public List<String> prerequisites() {
            return Collections.unmodifiableList(prerequisites);
        }

        public int cost() {
            return cost;
        }
    }

    /** Unlock rejected: unknown node, missing prerequisite, or budget. */
    public static final class UnlockException extends IllegalStateException {
        public UnlockException(String message) {
            super(message);
        }
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    /** Unlocked node ids per owner (player or team UUID string). */
    private final Map<String, Set<String>> progress = new HashMap<>();

    /**
     * Defines a node. Throws on unknown prerequisites (definition order is
     * the author's problem, not the player's) and on cycles.
     */
    public Node define(String id, int cost, String... prerequisites) {
        for (String prereq : prerequisites) {
            if (!nodes.containsKey(prereq)) {
                throw new IllegalArgumentException("Unknown prerequisite '" + prereq + "' for " + id);
            }
        }
        Node node = new Node(id, List.of(prerequisites), cost);
        nodes.put(id, node);
        ensureAcyclic(id);
        return node;
    }

    public Node node(String id) {
        return nodes.get(id);
    }

    public List<Node> nodes() {
        return List.copyOf(nodes.values());
    }

    public boolean isUnlocked(String ownerId, String nodeId) {
        Set<String> unlocked = progress.get(ownerId);
        return unlocked != null && unlocked.contains(nodeId);
    }

    /** True when every prerequisite of the node is unlocked for the owner. */
    public boolean prerequisitesMet(String ownerId, String nodeId) {
        Node node = nodes.get(nodeId);
        if (node == null) {
            return false;
        }
        for (String prereq : node.prerequisites) {
            if (!isUnlocked(ownerId, prereq)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Unlocks the node if prerequisites are met and the budget covers the
     * cost; deducts from the budget via the mutable int box. Owner starts
     * with no unlocks, so root nodes (no prerequisites) are always
     * affordable in terms of the graph.
     */
    public void unlock(String ownerId, String nodeId, int[] budget) {
        Node node = nodes.get(nodeId);
        if (node == null) {
            throw new UnlockException("No such research: " + nodeId);
        }
        if (isUnlocked(ownerId, nodeId)) {
            return; // idempotent
        }
        if (!prerequisitesMet(ownerId, nodeId)) {
            throw new UnlockException("Prerequisites not met for " + nodeId);
        }
        if (budget.length < 1 || budget[0] < node.cost) {
            throw new UnlockException("Insufficient funds for " + nodeId
                    + " (need " + node.cost + ")");
        }
        budget[0] -= node.cost;
        progress.computeIfAbsent(ownerId, k -> new HashSet<>()).add(nodeId);
    }

    /** Resets one owner's progress (debug/reset command). */
    public void resetOwner(String ownerId) {
        progress.remove(ownerId);
    }

    /**
     * Restores a progress snapshot (e.g. from {@code ResearchProgressStore}
     * at startup). Unknown node ids are kept but ignored until defined.
     */
    public void restore(Map<String, Set<String>> snapshot) {
        for (Map.Entry<String, Set<String>> entry : snapshot.entrySet()) {
            progress.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
    }

    /** Convenience for adapters: currently unlockable nodes for a GUI. */
    public List<Node> availableFor(String ownerId) {
        List<Node> result = new ArrayList<>();
        for (Node node : nodes.values()) {
            if (!isUnlocked(ownerId, node.id) && prerequisitesMet(ownerId, node.id)) {
                result.add(node);
            }
        }
        return result;
    }

    private void ensureAcyclic(String newId) {
        // BFS from the new node's prerequisites must never reach newId.
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.addAll(nodes.get(newId).prerequisites);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(newId)) {
                throw new IllegalArgumentException("Research cycle through " + newId);
            }
            if (!visited.add(current)) {
                continue;
            }
            queue.addAll(nodes.get(current).prerequisites);
        }
    }
}
