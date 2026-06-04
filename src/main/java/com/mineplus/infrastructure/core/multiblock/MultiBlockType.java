package com.mineplus.infrastructure.core.multiblock;

import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MultiBlockType {

    private final String id;
    private final String displayName;
    private final Map<Integer, MultiBlockLevel> levels;
    private final MultiBlockHook hook;
    private final String guiKey;

    public MultiBlockType(
            String id,
            String displayName,
            Map<Integer, MultiBlockLevel> levels,
            MultiBlockHook hook,
            String guiKey
    ) {
        this.id = id;
        this.displayName = displayName;
        this.levels = Collections.unmodifiableMap(new LinkedHashMap<>(levels));
        this.hook = hook;
        this.guiKey = guiKey == null ? "" : guiKey;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Map<Integer, MultiBlockLevel> levels() {
        return levels;
    }

    public MultiBlockLevel level(int level) {
        return levels.get(level);
    }

    public int minLevel() {
        return levels.keySet().stream().min(Integer::compareTo).orElse(1);
    }

    public int maxLevel() {
        return levels.keySet().stream().max(Integer::compareTo).orElse(1);
    }

    public MultiBlockHook hook() {
        return hook;
    }

    public String guiKey() {
        return guiKey;
    }
}
