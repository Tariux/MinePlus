package com.mineplus.infrastructure.core.block;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Holds the valid block definitions, keyed by their {@code namespace:path} id. */
public final class BlockRegistry {

    private final Map<String, BlockDefinition> definitions = new LinkedHashMap<>();

    public synchronized void clear() {
        definitions.clear();
    }

    /**
     * Registers a definition.
     *
     * @return the definition already using this id, or {@code null} when the
     *         id was free and the definition is now registered
     */
    public synchronized BlockDefinition register(BlockDefinition definition) {
        String key = normalize(definition.id());
        BlockDefinition existing = definitions.putIfAbsent(key, definition);
        return existing;
    }

    public synchronized BlockDefinition get(String id) {
        return definitions.get(normalize(id));
    }

    public synchronized boolean contains(String id) {
        return definitions.containsKey(normalize(id));
    }

    public synchronized Collection<BlockDefinition> all() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(definitions.values()));
    }

    public synchronized int size() {
        return definitions.size();
    }

    private static String normalize(String id) {
        if (id == null) {
            return "";
        }
        String trimmed = id.trim().toLowerCase(Locale.ROOT);
        return trimmed.indexOf(':') < 0 ? "mineplus:" + trimmed : trimmed;
    }
}
