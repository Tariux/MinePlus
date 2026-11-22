package com.mineplus.infrastructure.mob.action;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * The pure scoring core of target choice for {@link AttackNearestAction}: a
 * candidate is not merely "the nearest one" but the one whose situation argues
 * for it — close, visible, remembered as an attacker, or already the mob's
 * committed target.
 *
 * <p>Pure logic (no Bukkit types), so the whole scoring model is unit-testable
 * and tweaks can be reasoned about without a server. The action supplies the
 * situational facts; this class only weighs them.
 */
public final class TargetSelector {

    /** Bonus for a candidate the mob can currently see (line of sight). */
    public static final double VISIBILITY_BONUS = 0.3;
    /** Bonus for a candidate in the mob's hurt memory: a mob retaliates. */
    public static final double GRUDGE_BONUS = 0.6;
    /** Bonus for the mob's current target, so prey does not flicker between two mobs. */
    public static final double STICKINESS_BONUS = 0.2;

    /** One huntable entity, flattened to the facts that change its score. */
    public record Candidate(UUID id, double distanceSq, boolean visible, boolean grudge) {

        public static Candidate hidden(UUID id, double distanceSq) {
            return new Candidate(id, distanceSq, false, false);
        }
    }

    private TargetSelector() {
    }

    /**
     * Scores one candidate. The base term is proximity, normalized into
     * {@code [0, 1]} over the search radius (slightly negative past the edge,
     * so an outside candidate never wins on bonuses alone); each situational
     * fact then adds its bonus.
     */
    public static double score(Candidate candidate, double searchRadiusSq, boolean isCurrentTarget) {
        double proximity = 1.0 - Math.sqrt(Math.max(0.0, candidate.distanceSq()) / searchRadiusSq);
        double total = Math.max(-0.1, proximity);
        if (candidate.visible()) {
            total += VISIBILITY_BONUS;
        }
        if (candidate.grudge()) {
            total += GRUDGE_BONUS;
        }
        if (isCurrentTarget) {
            total += STICKINESS_BONUS;
        }
        return total;
    }

    /** The best-scoring candidate, or {@code null} for an empty set. */
    public static Candidate best(List<Candidate> candidates, double searchRadiusSq, UUID currentTargetId) {
        Candidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Iterator<Candidate> it = candidates.iterator();
        while (it.hasNext()) {
            Candidate c = it.next();
            double s = score(c, searchRadiusSq, currentTargetId != null && c.id().equals(currentTargetId));
            if (s > bestScore) {
                bestScore = s;
                best = c;
            }
        }
        return best;
    }
}
