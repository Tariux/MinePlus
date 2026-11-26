package com.mineplus.infrastructure.render;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loud, once-per-model record of every render that fell back from the
 * resourcepack to the texel/virtual engine. Unlike {@code DebugLogger}, the
 * warning is always written, so a model that fails to compile or bind is named
 * in the server log instead of silently rendering as texels.
 */
public final class PackFallbackNotice {

    private static final Logger LOGGER = Logger.getLogger("MinePlus");
    private static final Map<String, String> REPORTED = new ConcurrentHashMap<>();

    private PackFallbackNotice() {
    }

    /**
     * Records a pack-to-virtual fallback for {@code modelKey}; warns the first
     * time each model falls back.
     *
     * @return true when this call wrote the warning
     */
    public static boolean report(String modelKey, String reason) {
        String key = modelKey == null || modelKey.isBlank() ? "<unknown>" : modelKey;
        String why = reason == null || reason.isBlank() ? "unknown reason" : reason;
        if (REPORTED.putIfAbsent(key, why) != null) {
            return false;
        }
        LOGGER.log(Level.WARNING, "[MinePlus] Model '" + key + "' is rendering with the texel/virtual"
                + " fallback instead of the resourcepack: " + why + ".");
        return true;
    }

    /** Models that fell back since the last {@link #clear()}, with the first reason seen. */
    public static Map<String, String> reported() {
        return Map.copyOf(REPORTED);
    }

    /** Forgets reported models (reload path) so a repeat failure warns again. */
    public static void clear() {
        REPORTED.clear();
    }
}
