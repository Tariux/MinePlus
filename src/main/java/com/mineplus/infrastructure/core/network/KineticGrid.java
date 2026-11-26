package com.mineplus.infrastructure.core.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kinetic network (mega-plan P6 / Phase C2): Create-style rotational
 * networks. Like {@link EnergyGrid} this is pure computation over packed
 * long keys — no Bukkit types, thread-safe to solve off-thread.
 *
 * <p>Model: each node has a rotation direction (+1 / -1 / 0) and the
 * network's angular velocity is set by its sources. Stress (torque demand)
 * from consumers is summed per network and compared against the sources'
 * combined capacity; over-stressed networks run at reduced velocity
 * (Create's "insufficient stress" behaviour, flattened server-side).
 * Gearboxes and belt crossings can flip direction; shafts propagate it.
 */
public final class KineticGrid {

    /** Minimum network speed before machines visibly slow down (RPM-ish). */
    public static final float BASE_SPEED = 32.0f;

    private final Map<Long, KineticNode> nodes = new HashMap<>();
    private final Map<Long, List<Long>> adjacency = new HashMap<>();
    private final Map<Long, Long> parent = new HashMap<>();
    private final Map<Long, Integer> rank = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();
    /** Undirected edge keys whose traversal flips rotation sign. */
    private final Set<Long> flips = new HashSet<>();

    /** Kind of kinetic block: drives the network, loads it, or just carries rotation. */
    public enum Kind {
        /** Source of rotation: capacity in stress units, speed in RPM-ish units. */
        SOURCE,
        /** Consumer: torque demand in stress units, runs at network speed. */
        CONSUMER,
        /** Pure carrier (shaft/cog). */
        SHAFT
    }

    public static final class KineticNode {
        final long key;
        final Kind kind;
        final float speed;      // sources only
        final float capacity;   // sources only
        final float stress;     // consumers only
        int direction = 1;      // +1 / -1 relative to network base, mutable

        KineticNode(long key, Kind kind, float speed, float capacity, float stress) {
            this.key = key;
            this.kind = kind;
            this.speed = speed;
            this.capacity = capacity;
            this.stress = stress;
        }

        public long key() {
            return key;
        }

        public Kind kind() {
            return kind;
        }

        public float speed() {
            return speed;
        }

        public float capacity() {
            return capacity;
        }

        public float stress() {
            return stress;
        }

        public int direction() {
            return direction;
        }

        public void setDirection(int direction) {
            this.direction = direction;
        }
    }

    /** Resolved rotation state of one node after a solve. */
    public record Rotation(long key, float speed, int direction, boolean overStressed) {
    }

    public KineticNode put(long key, Kind kind, float speed, float capacity, float stress) {
        KineticNode previous = nodes.put(key, new KineticNode(key, kind, speed, capacity, stress));
        if (previous == null) {
            // Only a brand-new node starts as its own component. Re-putting an
            // existing node (dynamic SU, brake state) must keep its union-find
            // root, or the node silently splits off its network.
            parent.put(key, key);
            rank.putIfAbsent(key, 0);
            adjacency.computeIfAbsent(key, k -> new ArrayList<>());
        }
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

    /** Links two nodes; {@code flip} reverses rotation across the joint (belt crossing). */
    public void link(long a, long b, boolean flip) {
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
        if (flip) {
            flips.add(edgeKey(a, b));
        }
        dirty.add(a);
        dirty.add(b);
    }

    public void unlink(long a, long b) {
        List<Long> aEdges = adjacency.get(a);
        if (aEdges == null || !aEdges.remove(Long.valueOf(b))) {
            return;
        }
        adjacency.get(b).remove(Long.valueOf(a));
        flips.remove(edgeKey(a, b));
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

    public KineticNode node(long key) {
        return nodes.get(key);
    }

    public int size() {
        return nodes.size();
    }

    public boolean isDirty(long key) {
        return dirty.contains(key) && nodes.containsKey(key);
    }

    /**
     * Solves every dirty network: picks the fastest source's speed, checks
     * stress against capacity, and returns one {@link Rotation} per source
     * and consumer in the touched networks.
     */
    public List<Rotation> solve() {
        List<Rotation> results = new ArrayList<>();
        Set<Long> roots = new HashSet<>();
        for (long key : dirty) {
            if (nodes.containsKey(key)) {
                roots.add(find(key));
            }
        }
        for (long root : roots) {
            solveComponent(root, results);
        }
        dirty.clear();
        return results;
    }

    /**
     * The whole connected component containing {@code key}, including shafts
     * (which {@link #solve()} intentionally leaves out of its rotations).
     * Empty when the key is not in the grid.
     */
    public List<KineticNode> networkMembers(long key) {
        KineticNode node = nodes.get(key);
        if (node == null) {
            return List.of();
        }
        long root = find(key);
        List<KineticNode> members = new ArrayList<>();
        for (long k : nodes.keySet()) {
            if (find(k) == root) {
                members.add(nodes.get(k));
            }
        }
        return members;
    }

    private void solveComponent(long root, List<Rotation> results) {
        float speed = 0;
        float capacity = 0;
        float stress = 0;
        List<Long> members = new ArrayList<>();
        for (long key : nodes.keySet()) {
            if (find(key) != root) {
                continue;
            }
            members.add(key);
            KineticNode node = nodes.get(key);
            if (node.kind == Kind.SOURCE && node.capacity > 0) {
                // A source with no capacity (a dry water wheel) must not turn
                // the network: it contributes neither speed nor direction.
                speed = Math.max(speed, node.speed);
                capacity += node.capacity;
            } else if (node.kind == Kind.CONSUMER) {
                stress += node.stress;
            }
        }
        boolean overStressed = stress > capacity;
        float factor = overStressed && capacity > 0 ? capacity / stress : 1.0f;
        float netSpeed = speed * factor;
        // Direction propagation: BFS from every source (nearest wins);
        // flip-edges reverse sign across belt crossings / mirrored gearboxes.
        Map<Long, Integer> direction = new HashMap<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        for (long key : members) {
            KineticNode node = nodes.get(key);
            if (node.kind == Kind.SOURCE && node.capacity > 0) {
                direction.put(key, node.direction);
                queue.add(new long[]{key, node.direction});
            }
        }
        while (!queue.isEmpty()) {
            long[] current = queue.poll();
            for (long n : adjacency.getOrDefault(current[0], List.of())) {
                if (direction.containsKey(n)) {
                    continue;
                }
                int sign = flips.contains(edgeKey(current[0], n)) ? -1 : 1;
                long propagated = current[1] * sign;
                direction.put(n, (int) propagated);
                queue.add(new long[]{n, propagated});
            }
        }
        for (long key : members) {
            KineticNode node = nodes.get(key);
            node.direction = direction.getOrDefault(key, node.direction);
            if (node.kind != Kind.SHAFT) {
                results.add(new Rotation(key, netSpeed, node.direction, overStressed));
            }
        }
    }

    /** Canonical undirected key for a flip-edge (min, max). */
    private static long edgeKey(long a, long b) {
        return a < b ? (a << 1) ^ b : (b << 1) ^ a;
    }

    private void rebuild(long root) {
        for (long key : nodes.keySet()) {
            if (find(key) == root) {
                parent.put(key, key);
                rank.put(key, 0);
            }
        }
        flood(root == find(root) ? root : root);
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
