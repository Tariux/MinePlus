package com.mineplus.create.towns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java contracts board (no Bukkit, unit-testable): deliverable contracts
 * that towns complete together. A contract asks for {@code requiredUnits} of
 * shared progress; town members contribute by running their machines
 * (millstone/press outputs count as proof of work).
 */
public final class ContractBoard {

    public static final class Contract {
        public final String id;
        public final String title;
        public final int requiredUnits;
        /** XP levels paid to each contributing town member on completion. */
        public final int rewardLevels;
        public final int minTownTier;

        Contract(String id, String title, int requiredUnits, int rewardLevels, int minTownTier) {
            this.id = id;
            this.title = title;
            this.requiredUnits = requiredUnits;
            this.rewardLevels = rewardLevels;
            this.minTownTier = minTownTier;
        }
    }

    private final Map<String, Contract> contracts = new LinkedHashMap<>();

    /** Registers a contract; ignored when the id is taken. */
    public void publish(String id, String title, int requiredUnits, int rewardLevels, int minTownTier) {
        contracts.putIfAbsent(id.toLowerCase(),
                new Contract(id, title, Math.max(1, requiredUnits), Math.max(0, rewardLevels), Math.max(1, minTownTier)));
    }

    public Contract contract(String id) {
        return contracts.get(id.toLowerCase());
    }

    public List<Contract> all() {
        return Collections.unmodifiableList(new ArrayList<>(contracts.values()));
    }

    /** Contracts a town of this tier may accept. */
    public List<Contract> availableFor(int townTier) {
        List<Contract> result = new ArrayList<>();
        for (Contract contract : contracts.values()) {
            if (townTier >= contract.minTownTier) {
                result.add(contract);
            }
        }
        return result;
    }
}
