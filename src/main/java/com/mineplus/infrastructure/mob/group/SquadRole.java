package com.mineplus.infrastructure.mob.group;

/**
 * What one member does inside a squad, expressed as a nudge to its formation
 * slot.
 *
 * <p>A role is not an AI of its own — it is the small bias that turns a
 * symmetric formation into a working one: the tank walks the front, support
 * walks behind it, flankers widen out, a sniper stays furthest back. The crew
 * keeps one shared route; the role only decides which slot on it the member
 * takes, so roles never fight the march.
 */
public enum SquadRole {

    /** The front-most slot — the member the formation is built around. */
    LEADER(0.8, 0.0, 1.0),
    /** Slightly ahead of the line, centered: takes the first contact. */
    TANK(0.2, 0.0, 1.0),
    /** A step ahead and centered: opens the way for the rest. */
    BREAKER(0.4, 0.0, 1.0),
    /** Behind and wide: approaches from the side. */
    FLANKER(-0.3, 1.0, 1.1),
    /** Behind the line: follows it in. */
    SUPPORT(-1.2, 0.4, 1.2),
    /** Furthest back: engages from range. */
    SNIPER(-1.8, 0.9, 1.4),
    /** Wide and loose: pulls attention away from the line. */
    DISTRACTOR(-0.2, 1.4, 1.1);

    private final double forwardBias;
    private final double lateralBias;
    private final double spacingMultiplier;

    SquadRole(double forwardBias, double lateralBias, double spacingMultiplier) {
        this.forwardBias = forwardBias;
        this.lateralBias = lateralBias;
        this.spacingMultiplier = spacingMultiplier;
    }

    /** Forward offset in spacing units; negative is behind the formation point. */
    public double forwardBias() {
        return forwardBias;
    }

    /** Lateral offset in spacing units; the sign follows the member's side. */
    public double lateralBias() {
        return lateralBias;
    }

    /** How much room this role keeps between itself and its neighbours. */
    public double spacingMultiplier() {
        return spacingMultiplier;
    }
}
