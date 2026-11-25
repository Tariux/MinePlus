package com.mineplus.infrastructure.core.research;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * File-backed persistence for {@link ResearchTree} progress (mega-plan
 * C4): one properties-style line per owner, written atomically on save.
 * SQLite is the production store via the persistence engine; this plain
 * store covers single-file setups and tests without a datasource. The
 * format is the same either way: {@code <owner>\t<node1,node2,...>}.
 */
public final class ResearchProgressStore {

    private final Path file;
    private final Logger logger;

    public ResearchProgressStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** Loads all owners' unlocked node ids; missing file = empty. */
    public Map<String, Set<String>> load() {
        Map<String, Set<String>> progress = new HashMap<>();
        if (!Files.exists(file)) {
            return progress;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", 2);
                if (parts.length != 2 || parts[0].isBlank()) {
                    continue;
                }
                Set<String> nodes = new HashSet<>();
                for (String node : parts[1].split(",")) {
                    if (!node.isBlank()) {
                        nodes.add(node.trim());
                    }
                }
                progress.put(parts[0], nodes);
            }
        } catch (IOException | RuntimeException e) {
            logger.warning("ResearchProgressStore: load failed: " + e.getMessage());
        }
        return progress;
    }

    /** Replaces the file with the given progress atomically. */
    public void save(Map<String, Set<String>> progress) {
        try {
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, Set<String>> entry : progress.entrySet()) {
                out.append(entry.getKey()).append('\t')
                        .append(String.join(",", entry.getValue())).append('\n');
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, out.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.warning("ResearchProgressStore: save failed: " + e.getMessage());
        }
    }
}
