package com.mineplus.create.rotational;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java rotational network simulation (no Bukkit imports, unit-testable).
 *
 * <p>Model space: nodes are keyed by {@code "world;x;y;z"}. Every machine
 * block that rotates (shaft, cogwheel, large cogwheel) is a node. Adjacent
 * node pairs are joined by edges; each edge is either a
 * {@link LinkType#SAME_AXIS direct} link (shafts in a line, meshed large
 * cogwheels) or a {@link LinkType#FLIP meshed} 90° link (shaft into cogwheel,
 * small cogwheel meshes) that flips the direction of rotation.</p>
 *
 * <p>A network is a connected component. Exactly one node per network is the
 * {@code source}: it provides a target RPM, a capacity (stress units) and a
 * base direction. All other nodes inherit the source's target RPM; their sign
 * is the source sign multiplied by the parity of FLIP links on their BFS path
 * from the source. Under load each node contributes stress; when a network's
 * total stress exceeds the source capacity the weakest shaft snaps and the
 * component is re-split.</p>
 *
 * <p>Speed dynamics: the actual RPM of a network lerps toward its target
 * (spin-up/spin-down) rather than jumping.</p>
 */
public final class RotationalNetworkManager {

    public enum NodeType {
        SHAFT, COGWHEEL, LARGE_COGWHEEL, CRANK, WATER_WHEEL, WINDMILL, STEAM_ENGINE
    }

    public enum LinkType {
        SAME_AXIS, FLIP
    }

    /** One rotating block. */
    public static final class Node {
        public final String key;
        public NodeType type;
        /** Stress units this node draws while the network turns. */
        public float stress;
        /** Structural strength; the lowest-strength shaft snaps on overload. */
        public float strength = Float.MAX_VALUE;
        /** Set for the single source node of a network. */
        public float sourceRpm;
        public float sourceCapacity;
        /** -1 / +1 direction as authored on the source; derived for others. */
        public int sourceSign = 1;
        /** Actual (displayed) RPM after lerp; negative = opposite base direction. */
        public float actualRpm;
        /** True while its chunk is unloaded; skipped by ticks and stress. */
        public boolean chunkUnloaded;
        /** Derived state after {@link #recompute()}: network id and sign. */
        public int networkId = -1;
        public int sign = 1;

        Node(String key, NodeType type) {
            this.key = key;
            this.type = type;
        }
    }

    private static final class Edge {
        final String a;
        final String b;
        final LinkType type;

        Edge(String a, String b, LinkType type) {
            this.a = a;
            this.b = b;
            this.type = type;
        }
    }

    private static final float LERP_FACTOR = 0.15f;

    private final Map<String, Node> nodes = new HashMap<>();
    private final Map<String, List<Edge>> adjacency = new HashMap<>();
    private int nextNetworkId = 1;

    /**
     * Places a node and links it to every orthogonal neighbour. A neighbour
     * cogwheel/small-cog pair meshes with a FLIP link; everything else links
     * SAME_AXIS. Returns the node.
     */
    public Node place(String key, NodeType type) {
        Node node = new Node(key, type);
        nodes.put(key, node);
        adjacency.put(key, new ArrayList<>());
        for (String neighbour : neighbourKeys(key)) {
            Node other = nodes.get(neighbour);
            if (other == null) {
                continue;
            }
            LinkType link = meshesWithFlip(type, other.type) ? LinkType.FLIP : LinkType.SAME_AXIS;
            addEdge(new Edge(key, neighbour, link));
        }
        return node;
    }

    /** Removes a node; its former component is re-split on the next {@link #recompute()}. */
    public Node remove(String key) {
        Node removed = nodes.remove(key);
        if (removed == null) {
            return null;
        }
        List<Edge> edges = adjacency.remove(key);
        if (edges != null) {
            for (Edge edge : edges) {
                List<Edge> otherList = adjacency.get(edge.b);
                if (otherList != null) {
                    otherList.removeIf(e -> e.b.equals(key) || e.a.equals(key));
                }
            }
        }
        recompute();
        return removed;
    }

    /** Directly links two existing nodes (used for axis-aligned non-adjacent joins, e.g. large cogwheels). */
    public void link(String a, String b, LinkType type) {
        if (nodes.containsKey(a) && nodes.containsKey(b)) {
            addEdge(new Edge(a, b, type));
        }
    }

    /** Recomputes network ids and rotation signs for every node (BFS from each source). */
    public void recompute() {
        nodes.values().forEach(n -> {
            n.networkId = -1;
            n.sign = n.sourceSign;
        });
        for (Node source : nodes.values()) {
            if (source.networkId != -1 || !isSource(source)) {
                continue;
            }
            int id = nextNetworkId++;
            source.networkId = id;
            source.sign = source.sourceSign;
            // BFS assigning signs by FLIP parity.
            List<String> queue = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            queue.add(source.key);
            seen.add(source.key);
            while (!queue.isEmpty()) {
                String current = queue.remove(queue.size() - 1);
                Node currentNode = nodes.get(current);
                for (Edge edge : adjacency.getOrDefault(current, List.of())) {
                    String nextKey = edge.a.equals(current) ? edge.b : edge.a;
                    if (!seen.add(nextKey)) {
                        continue;
                    }
                    Node nextNode = nodes.get(nextKey);
                    nextNode.networkId = id;
                    nextNode.sign = currentNode.sign * (edge.type == LinkType.FLIP ? -1 : 1);
                    queue.add(nextKey);
                }
            }
        }
    }

    /**
     * One simulation step (call ~4×/second from the server tick loop).
     * Networks lerp their actual RPM toward target; networks whose total
     * stress exceeds source capacity snap their weakest shaft and recompute.
     *
     * @param chunkUnloaded predicate returning true when a node's chunk is unloaded
     * @return the key of the snapped shaft, or {@code null}
     */
    public String tick(java.util.function.Predicate<Node> chunkUnloaded) {
        String snapped = null;
        Map<Integer, Float> networkTargetRpm = new HashMap<>();
        Map<Integer, Float> networkActualRpm = new HashMap<>();
        Map<Integer, Float> networkStress = new HashMap<>();
        Map<Integer, Float> networkCapacity = new HashMap<>();

        for (Node node : nodes.values()) {
            node.chunkUnloaded = chunkUnloaded.test(node);
        }
        recompute();
        for (Node node : nodes.values()) {
            if (node.chunkUnloaded) {
                continue;
            }
            if (node.networkId == -1) {
                node.actualRpm += (0f - node.actualRpm) * LERP_FACTOR;
                if (Math.abs(node.actualRpm) < 0.01f) {
                    node.actualRpm = 0f;
                }
                continue;
            }
            networkTargetRpm.merge(node.networkId, isSource(node) ? node.sourceRpm : 0f, Float::sum);
            networkCapacity.merge(node.networkId, isSource(node) ? node.sourceCapacity : 0f, Float::sum);
            networkStress.merge(node.networkId, node.stress, Float::sum);
            // Actual RPM of the network = the source's actual RPM; every node
            // in the network shares one shaft speed.
            if (isSource(node)) {
                networkActualRpm.merge(node.networkId, node.actualRpm, Float::sum);
            }
        }
        for (Map.Entry<Integer, Float> entry : networkTargetRpm.entrySet()) {
            int id = entry.getKey();
            float target = entry.getValue();
            float actual = networkActualRpm.getOrDefault(id, 0f);
            actual = actual + (target - actual) * LERP_FACTOR;
            if (Math.abs(actual) < 0.01f) {
                actual = 0f;
            }
            networkActualRpm.put(id, actual);
            if (networkStress.getOrDefault(id, 0f) > networkCapacity.getOrDefault(id, 0f)) {
                snapped = snapWeakestShaft(id);
                recompute();
                continue;
            }
            for (Node node : nodes.values()) {
                if (node.networkId == id && !node.chunkUnloaded) {
                    node.actualRpm = actual * node.sign;
                }
            }
        }
        return snapped;
    }

    /** Removes the weakest-strength shaft of the network and returns its key. */
    private String snapWeakestShaft(int networkId) {
        Node weakest = null;
        for (Node node : nodes.values()) {
            if (node.networkId == networkId && node.type == NodeType.SHAFT
                    && (weakest == null || node.strength < weakest.strength)) {
                weakest = node;
            }
        }
        if (weakest == null) {
            // No shaft: fall back to the weakest node of any type.
            for (Node node : nodes.values()) {
                if (node.networkId == networkId && (weakest == null || node.strength < weakest.strength)) {
                    weakest = node;
                }
            }
        }
        if (weakest == null) {
            return null;
        }
        remove(weakest.key);
        return weakest.key;
    }

    /**
     * Serialized snapshot, one node per line. Fields are '|'-separated (the
     * key itself contains ';' from {@code "world;x;y;z"}):
     * {@code key|type|stress|strength|sourceRpm|sourceCapacity|sourceSign|actualRpm}.
     */
    public String save() {
        StringBuilder builder = new StringBuilder();
        for (Node node : nodes.values()) {
            builder.append(node.key).append('|')
                    .append(node.type.name()).append('|')
                    .append(node.stress).append('|')
                    .append(node.strength == Float.MAX_VALUE ? "inf" : Float.toString(node.strength)).append('|')
                    .append(node.sourceRpm).append('|')
                    .append(node.sourceCapacity).append('|')
                    .append(node.sourceSign).append('|')
                    .append(node.actualRpm)
                    .append('\n');
        }
        return builder.toString();
    }

    /** Restores a snapshot written by {@link #save()}; adjacency is rebuilt from orthogonal neighbours. */
    public void load(String snapshot) {
        nodes.clear();
        adjacency.clear();
        nextNetworkId = 1;
        for (String line : snapshot.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|");
            if (parts.length < 8) {
                continue;
            }
            Node node = place(parts[0], NodeType.valueOf(parts[1]));
            node.stress = Float.parseFloat(parts[2]);
            node.strength = parts[3].equals("inf") ? Float.MAX_VALUE : Float.parseFloat(parts[3]);
            node.sourceRpm = Float.parseFloat(parts[4]);
            node.sourceCapacity = Float.parseFloat(parts[5]);
            node.sourceSign = Integer.parseInt(parts[6]);
            node.actualRpm = Float.parseFloat(parts[7]);
        }
        recompute();
    }

    public Node node(String key) {
        return nodes.get(key);
    }

    public int size() {
        return nodes.size();
    }

    /** Snapshot list of all nodes, for commands and diagnostics. */
    public java.util.List<Node> all() {
        return new java.util.ArrayList<>(nodes.values());
    }

    private boolean isSource(Node node) {
        return node.sourceRpm != 0f || node.sourceCapacity != 0f;
    }

    private void addEdge(Edge edge) {
        adjacency.computeIfAbsent(edge.a, k -> new ArrayList<>()).add(edge);
        adjacency.computeIfAbsent(edge.b, k -> new ArrayList<>()).add(edge);
    }

    /** Orthogonal neighbour keys of {@code "world;x;y;z"}. */
    static List<String> neighbourKeys(String key) {
        String[] parts = key.split(";");
        String world = parts[0];
        int x = Integer.parseInt(parts[1]);
        int y = Integer.parseInt(parts[2]);
        int z = Integer.parseInt(parts[3]);
        return List.of(
                world + ";" + (x + 1) + ";" + y + ";" + z,
                world + ";" + (x - 1) + ";" + y + ";" + z,
                world + ";" + x + ";" + (y + 1) + ";" + z,
                world + ";" + x + ";" + (y - 1) + ";" + z,
                world + ";" + x + ";" + y + ";" + (z + 1),
                world + ";" + x + ";" + y + ";" + (z - 1));
    }

    /** True when the two types mesh as cogwheels (direction flips). */
    static boolean meshesWithFlip(NodeType a, NodeType b) {
        boolean aCog = a == NodeType.COGWHEEL || a == NodeType.LARGE_COGWHEEL;
        boolean bCog = b == NodeType.COGWHEEL || b == NodeType.LARGE_COGWHEEL;
        return aCog && bCog;
    }
}
