package com.mineplus.gun.diag;

import com.mineplus.util.DebugLogger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.Plugin;

/**
 * The fire-chain trace helper. Every step of the press-to-shot flow reports
 * here so a silent no-op on a live server is visible in {@code latest.log}:
 *
 * <ul>
 *   <li>{@link #trace} — unconditional (plain INFO), throttled to one line
 *       per key per two-second window, so holding the trigger streams a
 *       readable chain instead of a wall of repeats.</li>
 *   <li>{@link #debug} — the exhaustive per-repeat stream, gated behind the
 *       Core's {@code ADDITIONAL_DEBUG_LOGS}.</li>
 *   <li>Anomalies (broken stamps, registry misses) are logged directly by
 *       the caller — always, never throttled.</li>
 * </ul>
 */
public final class PressTrace {

    private static final long WINDOW_MILLIS = 2000L;

    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();

    private PressTrace() {
    }

    /** Unconditional, window-throttled trace line ({@code key} is arbitrary, usually {@code <player>:<topic>}). */
    public static void trace(Plugin plugin, String key, String message) {
        long now = System.currentTimeMillis();
        Long last = LAST.get(key);
        if (last != null && now - last < WINDOW_MILLIS) {
            return;
        }
        LAST.put(key, now);
        plugin.getLogger().info(message);
    }

    /** Per-player topic key helper. */
    public static String key(UUID playerId, String topic) {
        return playerId + ":" + topic;
    }

    /** Exhaustive repeat-level trace, only when the Core debug logging is on. */
    public static void debug(String message) {
        DebugLogger.debug(message);
    }
}
