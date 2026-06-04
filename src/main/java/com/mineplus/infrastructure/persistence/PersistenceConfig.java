package com.mineplus.infrastructure.persistence;

import java.io.File;
import java.util.Objects;

public record PersistenceConfig(
        File databaseFile,
        String tablePrefix,
        int busyTimeoutMs,
        int flushIntervalTicks
) {

    public PersistenceConfig {
        Objects.requireNonNull(databaseFile, "databaseFile");
        tablePrefix = sanitizePrefix(tablePrefix);
        busyTimeoutMs = Math.max(0, busyTimeoutMs);
        flushIntervalTicks = Math.max(1, flushIntervalTicks);
    }

    public static PersistenceConfig defaults(File dataFolder) {
        return new PersistenceConfig(new File(dataFolder, "infrastructure.db"), "infra_", 5000, 20);
    }

    private static String sanitizePrefix(String value) {
        if (value == null || value.isBlank()) {
            return "infra_";
        }
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
