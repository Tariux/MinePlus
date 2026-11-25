package com.mineplus.infrastructure.core.pipeline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Asset pipeline v2 (mega-plan C8 / Phase C): builds the resource pack in
 * dependency order. Assets (models, textures, sounds, atlases) declare
 * dependencies on other assets; the pipeline topologically sorts them,
 * detects cycles at build time, and runs each asset's build step once —
 * so a texture referenced by five models is compiled before any of them
 * and exactly once.
 *
 * <p>Pure computation over names and a build callback; the pack
 * compiler's existing stages remain the io boundary. Incremental builds:
 * {@link #buildIncremental} rebuilds only assets whose dependencies
 * changed, using a per-asset content hash.
 */
public final class AssetPipeline {

    /** Raised when dependencies form a cycle or reference nothing. */
    public static final class PipelineException extends IllegalStateException {
        public PipelineException(String message) {
            super(message);
        }
    }

    /** One buildable asset with its dependency names. */
    public static final class Asset {
        final String name;
        final List<String> dependencies;
        final Builder builder;
        String hash = "";

        Asset(String name, List<String> dependencies, Builder builder) {
            this.name = name;
            this.dependencies = dependencies;
            this.builder = builder;
        }

        public String name() {
            return name;
        }

        public List<String> dependencies() {
            return dependencies;
        }
    }

    /** Build step: produces the asset's output (e.g. JSON text). */
    public interface Builder {
        String build(String name, Map<String, String> dependencyOutputs);
    }

    private final Map<String, Asset> assets = new HashMap<>();
    private final Map<String, String> outputs = new HashMap<>();

    public Asset define(String name, List<String> dependencies, Builder builder) {
        Asset asset = new Asset(name, List.copyOf(dependencies), builder);
        assets.put(name, asset);
        return asset;
    }

    public String output(String name) {
        return outputs.get(name);
    }

    public int size() {
        return assets.size();
    }

    /**
     * Builds every asset in dependency order. Unknown dependencies and
     * cycles throw before any build step runs.
     */
    public List<String> buildAll() {
        List<String> order = topologicalOrder();
        Map<String, String> built = new HashMap<>();
        for (String name : order) {
            Asset asset = assets.get(name);
            Map<String, String> depOutputs = new HashMap<>();
            for (String dep : asset.dependencies) {
                depOutputs.put(dep, built.get(dep));
            }
            built.put(name, asset.builder.build(name, depOutputs));
        }
        outputs.putAll(built);
        return order;
    }

    /**
     * Incremental pass: rebuilds only assets whose own inputs changed
     * (per {@code hashOf} for the asset name) or whose dependencies were
     * rebuilt. Returns the rebuilt names in build order.
     */
    public List<String> buildIncremental(java.util.function.UnaryOperator<String> hashOf) {
        List<String> order = topologicalOrder();
        List<String> rebuilt = new ArrayList<>();
        Set<String> dirty = new LinkedHashSet<>();
        for (String name : order) {
            Asset asset = assets.get(name);
            String hash = hashOf.apply(name);
            boolean selfChanged = !hash.equals(asset.hash);
            boolean depChanged = asset.dependencies.stream().anyMatch(dirty::contains);
            if (selfChanged || depChanged || !outputs.containsKey(name)) {
                dirty.add(name);
                rebuilt.add(name);
            }
        }
        Map<String, String> built = new HashMap<>(outputs);
        for (String name : rebuilt) {
            Asset asset = assets.get(name);
            Map<String, String> depOutputs = new HashMap<>();
            for (String dep : asset.dependencies) {
                depOutputs.put(dep, built.get(dep));
            }
            built.put(name, asset.builder.build(name, depOutputs));
        }
        outputs.putAll(built);
        for (String name : rebuilt) {
            assets.get(name).hash = hashOf.apply(name);
        }
        return rebuilt;
    }

    private List<String> topologicalOrder() {
        // Kahn's algorithm; cycle members never reach in-degree zero.
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (Asset asset : assets.values()) {
            inDegree.putIfAbsent(asset.name, 0);
            for (String dep : asset.dependencies) {
                if (!assets.containsKey(dep)) {
                    throw new PipelineException("Asset '" + asset.name
                            + "' depends on unknown asset '" + dep + "'");
                }
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(asset.name);
                inDegree.merge(asset.name, 1, Integer::sum);
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String name = ready.poll();
            order.add(name);
            for (String dependent : dependents.getOrDefault(name, List.of())) {
                int left = inDegree.merge(dependent, -1, Integer::sum);
                if (left == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (order.size() != assets.size()) {
            Set<String> cycle = new LinkedHashSet<>(inDegree.keySet());
            order.forEach(cycle::remove);
            throw new PipelineException("Asset dependency cycle involving: " + cycle);
        }
        return order;
    }
}
