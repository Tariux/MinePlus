package com.mineplus.infrastructure.render;

import com.mineplus.util.DebugLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-loud-warning bridge for pack-to-virtual fallbacks (machine root rule:
 * pack-first always — when a model falls back to the texel engine, that must
 * be logged loudly with the model named).
 *
 * <p>The first fallback for a model warns; repeats for the same model stay
 * silent so a broken model cannot flood the log. {@link #clear} resets the
 * state (reload path) so a repeat failure warns again.</p>
 */
public final class PackFallbackNotice {

    private static final String PLACEHOLDER = "<unknown>";
    private static final Map<String, String> REPORTED = new ConcurrentHashMap<>();

    private PackFallbackNotice() {
    }

    /**
     * Reports a fallback. Returns true the first time a model is seen (a
     * warning is emitted); repeats for the same model return false and stay
     * silent.
     *
     * @param model  the model or level id; blank/null collapses to {@code <unknown>}
     * @param reason why the pack render was not used
     * @return true if this is the first report for the model
     */
    public static boolean report(String model, String reason) {
        String key = model == null || model.isBlank() ? PLACEHOLDER : model.trim();
        String resolvedReason = reason == null || reason.isBlank() ? "unknown reason" : reason;
        String previous = REPORTED.putIfAbsent(key, resolvedReason);
        if (previous != null) {
            return false;
        }
        DebugLogger.warning("[PackFallback] " + key
                + " is rendering on the virtual axis (pack-first rule): " + resolvedReason);
        return true;
    }

    /** Reasons for the fallbacks reported so far, keyed by model id. */
    public static Map<String, String> reported() {
        return Map.copyOf(REPORTED);
    }

    /** Forgets all reported fallbacks (reload path). */
    public static void clear() {
        REPORTED.clear();
    }
}
