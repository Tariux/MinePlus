package com.mineplus.create.journal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure-Java Inventor's Journal research ladder (no Bukkit, unit-testable).
 *
 * <p>Tiers T0–T3; each research costs XP levels and requires the previous
 * tier's research. Unlocks are stored as research ids; a player's tier is the
 * highest tier whose gate research is unlocked.</p>
 */
public final class ResearchLadder {

    public static final String TIER_0 = "t0_workshop_basics";
    public static final String TIER_1 = "t1_rotation";
    public static final String TIER_2 = "t2_steam";
    public static final String TIER_3 = "t3_electric";

    /** One research node. */
    public static final class Research {
        public final String id;
        public final String title;
        public final int tier;
        public final int xpCost;
        public final String requires;

        Research(String id, String title, int tier, int xpCost, String requires) {
            this.id = id;
            this.title = title;
            this.tier = tier;
            this.xpCost = xpCost;
            this.requires = requires;
        }
    }

    private final Map<String, Research> research = new LinkedHashMap<>();

    public ResearchLadder() {
        add(TIER_0, "Workshop Basics", 0, 0, null);
        add(TIER_1, "Rotational Power", 1, 3, TIER_0);
        add(TIER_2, "Steam & Brass", 2, 8, TIER_1);
        add(TIER_3, "Electric Age", 3, 15, TIER_2);
    }

    private void add(String id, String title, int tier, int xpCost, String requires) {
        research.put(id, new Research(id, title, tier, xpCost, requires));
    }

    public java.util.Collection<Research> all() {
        return research.values();
    }

    public Research get(String id) {
        return research.get(id);
    }

    /**
     * Attempt to unlock {@code id} given the caller's unlocked set and XP
     * budget.
     *
     * @return the unlocked research, or {@code null} when it does not exist,
     *         is already unlocked, its prerequisite is missing, or the XP
     *         cost is not met. Never mutates {@code unlockedIds}; the caller
     *         persists the returned id itself.
     */
    public Research unlock(String id, java.util.Collection<String> unlockedIds, int availableXpLevels) {
        Research node = research.get(id);
        if (node == null || unlockedIds.contains(id)) {
            return null;
        }
        if (node.requires != null && !unlockedIds.contains(node.requires)) {
            return null;
        }
        if (availableXpLevels < node.xpCost) {
            return null;
        }
        return node;
    }

    /** Highest unlocked tier from a set of unlocked ids; -1 when nothing. */
    public static int tierOf(Iterable<String> unlockedIds) {
        int tier = -1;
        for (String id : unlockedIds) {
            tier = switch (id) {
                case TIER_0 -> Math.max(tier, 0);
                case TIER_1 -> Math.max(tier, 1);
                case TIER_2 -> Math.max(tier, 2);
                case TIER_3 -> Math.max(tier, 3);
                default -> tier;
            };
        }
        return tier;
    }
}
