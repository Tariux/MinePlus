package com.mineplus.infrastructure.core.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Logistics network (mega-plan P6 / Phase C3): item routing over a node
 * graph. Pure computation like the other grids — no Bukkit types, safe
 * off-thread. One route request walks BFS from the origin and delivers to
 * the nearest node that can accept the item (storage with free capacity),
 * or the nearest node holding a requested item for extraction.
 */
public final class LogisticsGrid {

    private final Map<Long, LogisticsNode> nodes = new HashMap<>();
    private final Map<Long, List<Long>> adjacency = new HashMap<>();
    private final Map<Long, Long> parent = new HashMap<>();
    private final Map<Long, Integer> rank = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();

    /** Behaviour of a logistics block. */
    public enum Mode {
        /** Stores items: capacity in stacks. */
        STORAGE,
        /** Requests items of a filter type. */
        REQUESTER,
        /** Pure pipe. */
        PIPE
    }

    public static final class LogisticsNode {
        final long key;
        final Mode mode;
        final int capacity;
        int used;
        String filter;   // requester: wanted item id; storage: optional whitelist

        LogisticsNode(long key, Mode mode, int capacity, int used, String filter) {
            this.key = key;
            this.mode = mode;
            this.capacity = capacity;
            this.used = used;
            this.filter = filter;
        }

        public long key() {
            return key;
        }

        public Mode mode() {
            return mode;
        }

        public int capacity() {
            return capacity;
        }

        public int used() {
            return used;
        }

        public String filter() {
            return filter;
        }
    }

    /** Result of one route: destination and accepted amount. */
    public record Delivery(long from, long to, String itemId, int amount) {
    }

    public LogisticsNode put(long key, Mode mode, int capacity, int used, String filter) {
        LogisticsNode previous = nodes.put(key, new LogisticsNode(key, mode, capacity, used, filter));
        parent.put(key, key);
        rank.putIfAbsent(key, 0);
        adjacency.computeIfAbsent(key, k -> new ArrayList<>());
        dirty.add(key);
        return previous;
    }

    public void remove(long key) {
        if (nodes.remove(key) == null) {
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
            for (long n : neighbours) {
                if (nodes.containsKey(n)) {
                    rebuild(find(n));
                    dirty.add(n);
                }
            }
        }
        parent.remove(key);
        rank.remove(key);
    }

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
        dirty.add(a);
        dirty.add(b);
    }

    public void unlink(long a, long b) {
        List<Long> aEdges = adjacency.get(a);
        if (aEdges == null || !aEdges.remove(Long.valueOf(b))) {
            return;
        }
        adjacency.get(b).remove(Long.valueOf(a));
        long oldRoot = find(a);
        for (long key : nodes.keySet()) {
            if (find(key) == oldRoot) {
                parent.put(key, key);
                rank.put(key, 0);
            }
        }
        flood(a);
        flood(b);
        dirty.add(a);
        dirty.add(b);
    }

    public boolean connected(long a, long b) {
        return nodes.containsKey(a) && nodes.containsKey(b) && find(a) == find(b);
    }

    public LogisticsNode node(long key) {
        return nodes.get(key);
    }

    public int size() {
        return nodes.size();
    }

    public boolean isDirty(long key) {
        return dirty.contains(key) && nodes.containsKey(key);
    }

    /**
     * Inserts {@code amount} of {@code itemId} at {@code origin}, routed by
     * BFS to the nearest STORAGE node with free capacity (respecting an
     * optional filter whitelist). Fills the origin first when it is itself
     * storage. Returns the delivery, possibly partial; null when nothing
     * accepted.
     */
    public Delivery insert(long origin, String itemId, int amount) {
        LogisticsNode source = nodes.get(origin);
        if (source == null || amount <= 0) {
            return null;
        }
        if (source.mode == Mode.STORAGE && accepts(source, itemId)) {
            int taken = Math.min(amount, source.capacity - source.used);
            if (taken > 0) {
                source.used += taken;
                return new Delivery(origin, origin, itemId, taken);
            }
        }
        ArrayDeque<Long> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(origin);
        visited.add(origin);
        while (!queue.isEmpty()) {
            long current = queue.poll();
            for (long n : adjacency.getOrDefault(current, List.of())) {
                if (!visited.add(n)) {
                    continue;
                }
                LogisticsNode node = nodes.get(n);
                if (node.mode == Mode.STORAGE && accepts(node, itemId)) {
                    int taken = Math.min(amount, node.capacity - node.used);
                    if (taken > 0) {
                        node.used += taken;
                        return new Delivery(origin, n, itemId, taken);
                    }
                }
                queue.add(n);
            }
        }
        return null;
    }

    /**
     * Extracts up to {@code amount} of {@code itemId} for a requester at
     * {@code origin}: BFS to the nearest storage holding it.
     */
    public Delivery extract(long origin, String itemId, int amount) {
        ArrayDeque<Long> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(origin);
        visited.add(origin);
        while (!queue.isEmpty()) {
            long current = queue.poll();
            for (long n : adjacency.getOrDefault(current, List.of())) {
                if (!visited.add(n)) {
                    continue;
                }
                LogisticsNode node = nodes.get(n);
                if (node.mode == Mode.STORAGE && node.used > 0
                        && (node.filter == null || node.filter.equals(itemId))) {
                    int given = Math.min(amount, node.used);
                    node.used -= given;
                    return new Delivery(n, origin, itemId, given);
                }
                queue.add(n);
            }
        }
        return null;
    }

    private boolean accepts(LogisticsNode node, String itemId) {
        return node.used < node.capacity
                && (node.filter == null || node.filter.equals(itemId));
    }

    private void rebuild(long root) {
        for (long key : nodes.keySet()) {
            if (find(key) == root) {
                parent.put(key, key);
                rank.put(key, 0);
            }
        }
        flood(root);
    }

    private void flood(long seed) {
        ArrayDeque<Long> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
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
        long current = key;
        while (parent.get(current) != root) {
            long next = parent.get(current);
            parent.put(current, root);
            current = next;
        }
        return root;
    }
}
