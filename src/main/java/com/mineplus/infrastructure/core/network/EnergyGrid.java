package com.mineplus.infrastructure.core.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Incremental network graph for Phase C energy/kinetic/logistics networks
 * (mega-plan P6). Pure computation over plain data: no Bukkit types, so a
 * solve is safe to run off-thread with results applied on the owning region
 * thread (P1/P6).
 *
 * <p>Nodes are block positions packed into a long (P2-compatible: the active
 * set tick only touches nodes in loaded chunks). Connectivity is a
 * union-find over adjacencies declared by {@link #link}. Removal cannot
 * un-merge a union-find set, so {@link #unlink} rebuilds the affected
 * component from its stored adjacency edges — amortized cheap because the
 * rebuild touches only that component, not the whole graph.
 *
 * <p>{@link #balance()} implements Create's speed/stress model flattened to a
 * server-side equivalent: per connected component, total supply feeds total
 * demand; if supply is short, every consumer receives its demand scaled by
 * the same saturation factor (fair shortfall, no per-tick BFS over all
 * machines — the pitfall called out in 03 §4). Only dirty components are
 * recomputed.
 */
public final class EnergyGrid {

    /** Role of a node in its network. */
    public enum Role {
        /** Produces energy: carries a positive supply. */
        SOURCE,
        /** Consumes energy: carries a positive demand. */
        CONSUMER,
        /** Pure carrier (cable/shaft); contributes nothing. */
        CONDUIT
    }

    /** Immutable node record. Supply/demand are per-tick amounts. */
    public static final class Node {
        final long key;
        final Role role;
        final int supply;
        final int demand;

        Node(long key, Role role, int supply, int demand) {
            this.key = key;
            this.role = role;
            this.supply = supply;
            this.demand = demand;
        }

        public long key() {
            return key;
        }

        public Role role() {
            return role;
        }

        public int supply() {
            return supply;
        }

        public int demand() {
            return demand;
        }
    }

    /** Result of balancing one node: how much of its demand was met. */
    public record Balance(long key, int demand, int received) {
    }

    // Minecraft-style long packing: x at bits 0-25, z at 26-51, y at 52-63.
    private static final long X_MASK = 0x3FFFFFFL;   // 26 bits
    private static final long Z_MASK = 0x3FFFFFFL;   // 26 bits
    private static final long Y_MASK = 0xFFFL;       // 12 bits, y 0..4095

    private final Map<Long, Node> nodes = new HashMap<>();
    private final Map<Long, List<Long>> adjacency = new HashMap<>();
    private final Map<Long, Long> parent = new HashMap<>();
    private final Map<Long, Integer> rank = new HashMap<>();
    private final java.util.Set<Long> dirty = new java.util.HashSet<>();

    /** Packs world block coordinates into a node key. */
    public static long pack(int x, int y, int z) {
        return ((long) (x + 30_000_000) & X_MASK)
                | (((long) (z + 30_000_000) & Z_MASK) << 26)
                | (((long) y & Y_MASK) << 52);
    }

    /** Unpacks the x coordinate from a node key. */
    public static int unpackX(long key) {
        return (int) (key & X_MASK) - 30_000_000;
    }

    /** Unpacks the y coordinate from a node key. */
    public static int unpackY(long key) {
        return (int) ((key >>> 52) & Y_MASK);
    }

    /** Unpacks the z coordinate from a node key. */
    public static int unpackZ(long key) {
        return (int) ((key >>> 26) & Z_MASK) - 30_000_000;
    }

    /**
     * Adds or replaces a node. Marks its component dirty so the next
     * {@link #balance()} recomputes it. Returns the previous node, if any.
     */
    public Node put(long key, Role role, int supply, int demand) {
        Node previous = nodes.put(key, new Node(key, role, supply, demand));
        if (previous == null) {
            parent.put(key, key);
            rank.put(key, 0);
        }
        adjacency.computeIfAbsent(key, k -> new ArrayList<>());
        markComponentDirty(key);
        return previous;
    }

    /** Removes a node and its edges, rebuilding the split component(s). */
    public void remove(long key) {
        Node removed = nodes.remove(key);
        if (removed == null) {
            return;
        }
        List<Long> neighbours = adjacency.remove(key);
        if (neighbours != null) {
            for (long n : neighbours) {
                List<Long> back = adjacency.get(n);
                if (back != null) {
                    back.remove(Long.valueOf(key));
                }
            }
        }
        parent.remove(key);
        rank.remove(key);
        // Rebuild every former neighbour's component from scratch; the union
        // of their components before removal may have been one set.
        if (neighbours != null) {
            for (long n : neighbours) {
                if (nodes.containsKey(n)) {
                    rebuildComponent(n);
                    markComponentDirty(n);
                }
            }
        }
    }

    /** Declares a bidirectional adjacency between two existing nodes. */
    public void link(long a, long b) {
        if (a == b || !nodes.containsKey(a) || !nodes.containsKey(b)) {
            return;
        }
        List<Long> aEdges = adjacency.computeIfAbsent(a, k -> new ArrayList<>());
        if (!aEdges.contains(b)) {
            aEdges.add(b);
        }
        List<Long> bEdges = adjacency.computeIfAbsent(b, k -> new ArrayList<>());
        if (!bEdges.contains(a)) {
            bEdges.add(a);
        }
        union(a, b);
        markComponentDirty(a);
    }

    /** Removes one adjacency, rebuilding the component if the edge was a bridge. */
    public void unlink(long a, long b) {
        List<Long> aEdges = adjacency.get(a);
        if (aEdges == null || !aEdges.remove(Long.valueOf(b))) {
            return;
        }
        adjacency.get(b).remove(Long.valueOf(a));
        long oldRoot = find(a);
        // Reset every former member to self-rooted, then re-union each side
        // of the split independently; both endpoints may root their own half.
        for (long key : nodes.keySet()) {
            if (find(key) == oldRoot) {
                parent.put(key, key);
                rank.put(key, 0);
            }
        }
        floodUnion(a);
        floodUnion(b);
        markComponentDirty(a);
        markComponentDirty(b);
    }

    public boolean contains(long key) {
        return nodes.containsKey(key);
    }

    public Node node(long key) {
        return nodes.get(key);
    }

    /** True when the two nodes share a component. */
    public boolean connected(long a, long b) {
        return nodes.containsKey(a) && nodes.containsKey(b) && find(a) == find(b);
    }

    public int size() {
        return nodes.size();
    }

    /**
     * Balances every dirty component and clears the dirty flags. Pure
     * computation: call from any thread, apply the returned balances on the
     * owning region thread.
     */
    public List<Balance> balance() {
        List<Balance> results = new ArrayList<>();
        java.util.Set<Long> roots = new java.util.HashSet<>();
        for (long key : dirty) {
            if (nodes.containsKey(key)) {
                roots.add(find(key));
            }
        }
        for (long root : roots) {
            balanceComponent(root, results);
        }
        dirty.clear();
        return results;
    }

    public boolean isDirty(long key) {
        return dirty.contains(key) && nodes.containsKey(key);
    }

    private void balanceComponent(long root, List<Balance> results) {
        long supply = 0;
        long demand = 0;
        List<Long> members = componentMembers(root);
        for (long key : members) {
            Node node = nodes.get(key);
            if (node.role == Role.SOURCE) {
                supply += node.supply;
            } else if (node.role == Role.CONSUMER) {
                demand += node.demand;
            }
        }
        double saturation = demand == 0 ? 1.0 : Math.min(1.0, (double) supply / demand);
        for (long key : members) {
            Node node = nodes.get(key);
            if (node.role == Role.CONSUMER) {
                int received = (int) Math.round(node.demand * saturation);
                results.add(new Balance(key, node.demand, received));
            }
        }
    }

    private List<Long> componentMembers(long root) {
        List<Long> members = new ArrayList<>();
        for (long key : nodes.keySet()) {
            if (find(key) == root) {
                members.add(key);
            }
        }
        return members;
    }

    private void rebuildComponent(long seed) {
        long oldRoot = find(seed);
        for (long key : nodes.keySet()) {
            if (find(key) == oldRoot) {
                parent.put(key, key);
                rank.put(key, 0);
            }
        }
        floodUnion(seed);
    }

    private void floodUnion(long seed) {
        // Flood the seed's true component over stored adjacency edges,
        // re-unioning members as they are reached.
        ArrayDeque<Long> queue = new ArrayDeque<>();
        java.util.Set<Long> visited = new java.util.HashSet<>();
        queue.add(seed);
        visited.add(seed);
        while (!queue.isEmpty()) {
            long current = queue.poll();
            for (long n : adjacency.getOrDefault(current, List.of())) {
                union(current, n);
                if (visited.add(n)) {
                    queue.add(n);
                }
            }
        }
    }

    private void markComponentDirty(long key) {
        dirty.add(key);
    }

    private void union(long a, long b) {
        long ra = find(a);
        long rb = find(b);
        if (ra == rb) {
            return;
        }
        int rankA = rank.getOrDefault(ra, 0);
        int rankB = rank.getOrDefault(rb, 0);
        if (rankA < rankB) {
            parent.put(ra, rb);
        } else if (rankA > rankB) {
            parent.put(rb, ra);
        } else {
            parent.put(rb, ra);
            rank.put(ra, rankA + 1);
        }
    }

    private long find(long key) {
        long root = key;
        Long p = parent.get(root);
        while (p != null && p != root) {
            root = p;
            p = parent.get(root);
        }
        if (p == null) {
            return key;
        }
        // Path compression.
        long current = key;
        while (parent.get(current) != root) {
            long next = parent.get(current);
            parent.put(current, root);
            current = next;
        }
        return root;
    }
}
