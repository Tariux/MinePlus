package com.mineplus.create.towns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure-Java town model for the Phase 3 RP layer (no Bukkit, unit-testable).
 * A town has members, shared contract progress and a tier that rises with
 * completed contract work. Higher tiers grant a co-op stress bonus: networks
 * driven by town members tolerate more stress before a shaft snaps
 * ({@link #stressCapacityBonus(int)}).
 */
public final class TownRegistry {

    /** Contract tiers; index + 1 is the tier number. */
    static final int[] TIER_THRESHOLDS = {0, 3, 8, 15};
    static final float[] TIER_STRESS_BONUS = {0f, 4f, 8f, 16f};

    public static final class Town {
        public final String name;
        public final List<UUID> members = new ArrayList<>();
        public int completedContracts;
        /** Contract id -> shared progress units. */
        public final Map<String, Integer> contractProgress = new LinkedHashMap<>();

        Town(String name) {
            this.name = name;
        }

        /** Tier index into {@link #TIER_THRESHOLDS}; tier 1 exists from the start. */
        public int tier() {
            for (int i = TIER_THRESHOLDS.length - 1; i >= 0; i--) {
                if (completedContracts >= TIER_THRESHOLDS[i]) {
                    return i + 1;
                }
            }
            return 1;
        }
    }

    private final Map<String, Town> towns = new LinkedHashMap<>();
    private final Map<UUID, String> townOf = new HashMap<>();

    /** Creates a town; null when the name is taken or the player already has one. */
    public Town create(String name, UUID founder) {
        String key = name.toLowerCase();
        if (name.isBlank() || towns.containsKey(key) || townOf.containsKey(founder)) {
            return null;
        }
        Town town = new Town(name);
        town.members.add(founder);
        towns.put(key, town);
        townOf.put(founder, key);
        return town;
    }

    /** Joins a town; false when already in one. */
    public boolean join(String name, UUID player) {
        Town town = towns.get(name.toLowerCase());
        if (town == null || townOf.containsKey(player) || town.members.contains(player)) {
            return false;
        }
        town.members.add(player);
        townOf.put(player, name.toLowerCase());
        return true;
    }

    public Town townOf(UUID player) {
        String key = townOf.get(player);
        return key == null ? null : towns.get(key);
    }

    public Town town(String name) {
        return towns.get(name.toLowerCase());
    }

    /** Adds shared progress to a contract; returns total progress after the add. */
    public int addProgress(Town town, String contractId, int units) {
        int total = town.contractProgress.merge(contractId, Math.max(0, units), Integer::sum);
        return total;
    }

    /** Marks a contract complete, advancing the tier counter. */
    public boolean completeContract(Town town, String contractId, int requiredUnits) {
        int progress = town.contractProgress.getOrDefault(contractId, 0);
        if (progress < requiredUnits) {
            return false;
        }
        town.contractProgress.remove(contractId);
        town.completedContracts++;
        return true;
    }

    /** Extra stress capacity a source network tolerates at this town tier. */
    public static float stressCapacityBonus(int tier) {
        int index = Math.max(1, Math.min(TIER_THRESHOLDS.length, tier)) - 1;
        return TIER_STRESS_BONUS[index];
    }

    /** Shared progress view of one contract for a town. */
    public int progressOf(Town town, String contractId) {
        return town.contractProgress.getOrDefault(contractId, 0);
    }

    public List<Town> all() {
        return Collections.unmodifiableList(new ArrayList<>(towns.values()));
    }
}
