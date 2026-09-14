package com.mineplus.gun.weapon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reloadable id -> {@link WeaponDefinition} index. */
public final class WeaponRegistry {

    private volatile Map<String, WeaponDefinition> byId = Map.of();

    /** Replaces the whole registry; ids are normalized to lower case. */
    public void reload(Collection<WeaponDefinition> definitions) {
        Map<String, WeaponDefinition> map = new LinkedHashMap<>();
        if (definitions != null) {
            for (WeaponDefinition definition : definitions) {
                if (definition != null) {
                    map.put(definition.id().toLowerCase(Locale.ROOT), definition);
                }
            }
        }
        this.byId = Map.copyOf(map);
    }

    public WeaponDefinition get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return byId.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public Collection<WeaponDefinition> all() {
        return byId.values();
    }

    public List<WeaponDefinition> visible() {
        List<WeaponDefinition> list = new ArrayList<>();
        for (WeaponDefinition definition : byId.values()) {
            if (!definition.hidden()) {
                list.add(definition);
            }
        }
        return list;
    }

    /** Visible weapons grouped by class, in enum order, for the shop. */
    public Map<WeaponType, List<WeaponDefinition>> byType() {
        Map<WeaponType, List<WeaponDefinition>> grouped = new LinkedHashMap<>();
        for (WeaponDefinition definition : visible()) {
            grouped.computeIfAbsent(definition.type(), key -> new ArrayList<>()).add(definition);
        }
        return grouped;
    }

    public int size() {
        return byId.size();
    }
}
