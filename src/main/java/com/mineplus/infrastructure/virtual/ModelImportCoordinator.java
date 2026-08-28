package com.mineplus.infrastructure.virtual;

import com.mineplus.util.DebugLogger;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Two-phase model-folder loader adapted from FMM's {@code preflightNormalizedModelIds}
 * plus deterministic folder scan.
 *
 * <p>Phase 1 scans every {@code .bbmodel} and resolves its model key (via the
 * caller-supplied {@code keyResolver}); any key that maps to more than one file is
 * rejected before parsing, so order-dependent output can never happen. Phase 2
 * stream-parses only the surviving files in a stable (path-sorted) order, keeping
 * Mineplus's memory-safe {@link BbModelImporter} intact.
 */
public final class ModelImportCoordinator {

    private final Function<File, String> keyResolver;
    private final Logger logger;

    public ModelImportCoordinator(Function<File, String> keyResolver, Logger logger) {
        this.keyResolver = keyResolver;
        this.logger = logger;
    }

    public record ModelEntry(String key, VirtualModel model, File file) {
    }

    public record LoadResult(List<ModelEntry> entries, Set<String> rejectedIds) {
    }

    public LoadResult importFolder(File modelsFolder) {
        if (!modelsFolder.exists() && !modelsFolder.mkdirs()) {
            return new LoadResult(List.of(), Set.of());
        }

        List<File> files = new ArrayList<>();
        collect(modelsFolder, files);
        files.sort(Comparator.comparing(File::getPath)); // deterministic order

        // Phase 1: preflight collision detection (FMM preflightNormalizedModelIds).
        Map<String, List<File>> byKey = new TreeMap<>();
        for (File file : files) {
            String key = keyResolver.apply(file);
            if (key != null && !key.isEmpty()) {
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(file);
            }
        }
        Set<String> rejected = ConcurrentHashMap.newKeySet();
        byKey.forEach((key, list) -> {
            if (list.size() <= 1) {
                return;
            }
            rejected.add(key);
            String paths = list.stream()
                    .map(File::getAbsolutePath)
                    .sorted()
                    .collect(Collectors.joining(", "));
            DebugLogger.warning("[ModelImport] Rejected model-ID collision '" + key
                    + "'. These files resolve to the same ID: " + paths
                    + ". Rename so every ID is unique; nothing was loaded for this ID.");
        });

        // Phase 2: stream-parse only non-rejected files.
        Map<String, ModelEntry> loaded = new LinkedHashMap<>();
        for (File file : files) {
            String key = keyResolver.apply(file);
            if (key == null || rejected.contains(key) || loaded.containsKey(key)) {
                continue;
            }
            VirtualModel model = BbModelImporter.parse(key, file, logger);
            if (model != null && !model.cubes().isEmpty()) {
                loaded.put(key, new ModelEntry(key, model, file));
            }
        }

        if (!loaded.isEmpty()) {
            DebugLogger.info("[ModelImport] Loaded " + loaded.size()
                    + " model(s) from " + modelsFolder.getPath());
        }
        return new LoadResult(List.copyOf(loaded.values()), Set.copyOf(rejected));
    }

    private static void collect(File folder, List<File> output) {
        File[] children = folder.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, output);
            } else if (child.getName().toLowerCase(Locale.ROOT).endsWith(".bbmodel")) {
                output.add(child);
            }
        }
    }
}
