package com.mineplus.infrastructure.virtual;

import com.mineplus.infrastructure.virtual.animation.AnimationClip;
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
        List<File> animationFiles = new ArrayList<>();
        collect(modelsFolder, files, animationFiles);
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

        // Phase 3: merge sibling animation files (`<stem>.anim.bbmodel` /
        // `<stem>.animations.bbmodel`) into their target model. Animators are
        // keyed by bone name, so the merge addresses the target's bone graph
        // directly; clips whose animators resolve to nothing are reported.
        if (!animationFiles.isEmpty()) {
            animationFiles.sort(Comparator.comparing(File::getPath));
            for (File animationFile : animationFiles) {
                ModelEntry target = siblingTarget(animationFile, loaded);
                if (target == null) {
                    DebugLogger.warning("[ModelImport] Animation file '"
                            + animationFile.getName() + "' has no sibling model '"
                            + stemOf(animationFile) + ".bbmodel'; skipped.");
                    continue;
                }
                VirtualModel parsed = BbModelImporter.parse(
                        target.key() + "#anim", animationFile, logger);
                if (parsed == null || parsed.animations().isEmpty()) {
                    continue;
                }
                List<AnimationClip> usable = new ArrayList<>();
                List<AnimationClip> unresolved = new ArrayList<>();
                for (AnimationClip clip : parsed.animations()) {
                    if (resolvesAgainst(clip, target.model())) {
                        usable.add(clip);
                    } else {
                        unresolved.add(clip);
                    }
                }
                for (AnimationClip clip : unresolved) {
                    DebugLogger.warning("[ModelImport] Animation file '"
                            + animationFile.getName() + "': clip '" + clip.name()
                            + "' animates no bone of model '" + target.key()
                            + "'; check the outliner group names.");
                }
                if (!usable.isEmpty()) {
                    loaded.put(target.key(), new ModelEntry(target.key(),
                            target.model().withAnimations(usable), target.file()));
                    DebugLogger.info("[ModelImport] Merged " + usable.size()
                            + " clip(s) from '" + animationFile.getName()
                            + "' into model '" + target.key() + "'.");
                }
            }
        }

        if (!loaded.isEmpty()) {
            DebugLogger.info("[ModelImport] Loaded " + loaded.size()
                    + " model(s) from " + modelsFolder.getPath());
        }
        return new LoadResult(List.copyOf(loaded.values()), Set.copyOf(rejected));
    }

    /** True when at least one of the clip's animators addresses the model's bones by name. */
    private static boolean resolvesAgainst(AnimationClip clip, VirtualModel target) {
        for (String bone : clip.animators().keySet()) {
            if (target.boneIndex(bone) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** The loaded model a sibling animation file belongs to (same directory, same stem). */
    private static ModelEntry siblingTarget(File animationFile, Map<String, ModelEntry> loaded) {
        String stem = stemOf(animationFile);
        File parent = animationFile.getParentFile();
        for (ModelEntry entry : loaded.values()) {
            if (entry.file() != null
                    && entry.file().getParentFile() != null
                    && entry.file().getParentFile().equals(parent)
                    && stemOf(entry.file()).equalsIgnoreCase(stem)) {
                return entry;
            }
        }
        return null;
    }

    /** The bbmodel stem with any {@code .anim} / {@code .animations} suffix removed. */
    private static String stemOf(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".bbmodel")) {
            name = name.substring(0, name.length() - ".bbmodel".length());
        }
        if (name.endsWith(".animations")) {
            name = name.substring(0, name.length() - ".animations".length());
        }
        if (name.endsWith(".anim")) {
            name = name.substring(0, name.length() - ".anim".length());
        }
        return name;
    }

    private static void collect(File folder, List<File> output, List<File> animationOutput) {
        File[] children = folder.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, output, animationOutput);
            } else {
                String name = child.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".anim.bbmodel") || name.endsWith(".animations.bbmodel")) {
                    animationOutput.add(child);
                } else if (name.endsWith(".bbmodel")) {
                    output.add(child);
                }
            }
        }
    }
}
