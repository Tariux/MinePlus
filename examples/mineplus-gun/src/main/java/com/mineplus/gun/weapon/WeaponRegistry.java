package com.mineplus.gun.weapon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reloadable id -> {@link WeaponDefinition} index, plus the legacy alias map
 * that keeps pre-western arsenal stacks firing: the v0 weapon ids were
 * retired, but items stamped with them stay in players' inventories.
 */
public final class WeaponRegistry {

    /** Retired weapon id -> the current weapon that replaces it. */
    private static final Map<String, String> LEGACY_ALIASES = Map.of(
            "colt_peacemaker", "western_pistol",
            "rifle_repeater", "lever_rifle",
            "rifle_boltaction", "western_rifle",
            "shotgun_coach", "coach_shotgun",
            "pistol_flintlock", "flintlock_pistol",
            "musket_flintlock", "flintlock_musket",
            "musket_marksman", "scope_musket",
            "musket_bayonet", "bayonet_musket",
            "blunderbuss_flintlock", "blunderbuss",
            "knife_bowie", "western_pistol");

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

    /** The current weapon id a retired id resolves to, or {@code null}. */
    public static String aliasOf(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return LEGACY_ALIASES.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** True when the id was part of the retired arsenal. */
    public static boolean isLegacyId(String id) {
        return aliasOf(id) != null;
    }

    /** True when the id is a currently-configured weapon id (no aliasing). */
    public boolean isCurrent(String id) {
        return id != null && byId.containsKey(id.trim().toLowerCase(Locale.ROOT));
    }

    /** Reads a raw weapon id and follows a legacy alias to the live definition. */
    public WeaponDefinition get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        WeaponDefinition definition = byId.get(normalized);
        if (definition == null) {
            String alias = LEGACY_ALIASES.get(normalized);
            definition = alias == null ? null : byId.get(alias);
        }
        return definition;
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
