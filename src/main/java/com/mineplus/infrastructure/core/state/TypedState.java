package com.mineplus.infrastructure.core.state;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import java.util.Map;

/**
 * Typed view over an instance's persisted {@code stateData} map.
 *
 * <p>{@code stateData} is a {@code Map<String, String>} because the snapshot
 * layer serializes it verbatim; reading numeric fields through raw string
 * parsing (with malformed-value guards) was repeated boilerplate in every
 * feature. This class centralizes parsing and formatting so module stores
 * reduce to one-liner accessors. Values written here are persisted by the
 * Core's write-behind cycle exactly like raw {@code stateData} writes.
 */
public final class TypedState {

    private final Map<String, String> stateData;

    private TypedState(Map<String, String> stateData) {
        this.stateData = stateData;
    }

    /** A typed view over the instance's mutable (persisted) state map. */
    public static TypedState of(MultiBlockInstance instance) {
        return new TypedState(instance.mutableStateData());
    }

    public String getString(String key, String fallback) {
        String raw = stateData.get(key);
        return raw == null || raw.isBlank() ? fallback : raw;
    }

    public void setString(String key, String value) {
        stateData.put(key, String.valueOf(value));
    }

    public int getInt(String key, int fallback) {
        return parse(key, fallback, Integer::parseInt);
    }

    public void setInt(String key, int value) {
        stateData.put(key, String.valueOf(value));
    }

    public long getLong(String key, long fallback) {
        return parse(key, fallback, Long::parseLong);
    }

    public void setLong(String key, long value) {
        stateData.put(key, String.valueOf(value));
    }

    public double getDouble(String key, double fallback) {
        return parse(key, fallback, Double::parseDouble);
    }

    public void setDouble(String key, double value) {
        stateData.put(key, String.valueOf(value));
    }

    public boolean getBoolean(String key, boolean fallback) {
        String raw = stateData.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    public void setBoolean(String key, boolean value) {
        stateData.put(key, String.valueOf(value));
    }

    /** Removes a key from the persisted state. */
    public void clear(String key) {
        stateData.remove(key);
    }

    private <T> T parse(String key, T fallback, java.util.function.Function<String, T> parser) {
        String raw = stateData.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return parser.apply(raw.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
