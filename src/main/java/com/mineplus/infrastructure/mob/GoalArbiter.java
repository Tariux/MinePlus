package com.mineplus.infrastructure.mob;

import java.util.ArrayList;
import java.util.List;

/**
 * The pure decision core of goal arbitration: when a mob has several things it
 * could do right now, which one does it try first?
 *
 * <p>Each candidate goal carries an urgency — how badly the mob needs it right
 * now (a hunted mob's flee urgency rises as its health drops, an angry mob's
 * attack urgency rises with its grudge). The arbiter orders the goals by that
 * urgency, plus a small stickiness bonus for the goal the mob is already
 * pursuing, so a mob halfway through a walk does not abandon it for a goal
 * that is merely equal — only for one that is genuinely more urgent. Ties fall
 * back to registration order, so profiles without urgency logic behave exactly
 * as they did before arbitration existed.
 *
 * <p>Pure logic (no Bukkit types) — the engine flattens its action entries to
 * {@link Goal}s, gets the ordered goals back, and tries them in that order.
 */
public final class GoalArbiter {

    /** How much pursuing a goal protects it from an equal challenger. */
    public static final double STICKINESS_BONUS = 0.15;

    /** One candidate behavior, flattened to id + current urgency. */
    public record Goal(String id, double urgency) {
    }

    /** An ordered goal plus the index it came from, so the caller can map back. */
    public record Ordered(String id, double effectiveUrgency, int index) {
    }

    private GoalArbiter() {
    }

    /**
     * Orders candidates by effective urgency (urgency + stickiness for the
     * currently pursued goal), most urgent first, stable on ties so equal
     * urgencies keep registration order.
     *
     * @param candidates     the goals the mob could pursue; may be empty
     * @param currentGoalId  id of the goal the mob is mid-way through, or null
     * @return the same goals ordered most-urgent-first, each carrying its
     *         original index into {@code candidates}
     */
    public static List<Ordered> order(List<Goal> candidates, String currentGoalId) {
        List<Ordered> ordered = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Goal goal = candidates.get(i);
            double effective = goal.urgency();
            if (currentGoalId != null && currentGoalId.equals(goal.id())) {
                effective += STICKINESS_BONUS;
            }
            ordered.add(new Ordered(goal.id(), effective, i));
        }
        // Stable sort: equal effective urgencies keep registration order.
        ordered.sort((a, b) -> Double.compare(b.effectiveUrgency(), a.effectiveUrgency()));
        return ordered;
    }
}
