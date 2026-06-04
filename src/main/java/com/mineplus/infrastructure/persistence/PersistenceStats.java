package com.mineplus.infrastructure.persistence;

public record PersistenceStats(
        boolean enabled,
        long flushCount,
        long loadedRows,
        long writtenRows,
        long lastFlushDurationMs,
        long lastErrorAt,
        String lastError
) {

    public static PersistenceStats disabled() {
        return new PersistenceStats(false, 0L, 0L, 0L, 0L, 0L, "driver-unavailable");
    }
}
