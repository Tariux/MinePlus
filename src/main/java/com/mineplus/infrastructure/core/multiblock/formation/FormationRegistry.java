package com.mineplus.infrastructure.core.multiblock.formation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of {@link FormationPattern}s. Modules register patterns at startup;
 * the {@link FormationManager} consults them when a player places or breaks a
 * block that could complete or break a formation.
 */
public final class FormationRegistry {

    private final Map<String, FormationPattern> patterns = new LinkedHashMap<>();

    /** Registers a pattern; a duplicate id replaces the old one. */
    public void register(FormationPattern pattern) {
        patterns.put(pattern.id(), pattern);
    }

    public FormationPattern get(String id) {
        return patterns.get(id);
    }

    public void unregister(String id) {
        patterns.remove(id);
    }

    /** Read-only view of all registered patterns, keyed by id. */
    public Map<String, FormationPattern> patternsView() {
        return Collections.unmodifiableMap(patterns);
    }
}
