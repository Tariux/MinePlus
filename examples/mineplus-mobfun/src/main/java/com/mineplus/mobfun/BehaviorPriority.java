package com.mineplus.mobfun;

/**
 * The arbitration order between this module's villager showcases.
 *
 * <p>Three showcases want the same mob — the lamplighter, the two golem
 * guardians and the pilgrim — and the Core resolves that with a profile
 * priority: the higher priority wins the mob, and a running scenario can be
 * taken over by a higher-priority one. The order below is the gameplay:
 * <ul>
 *   <li><b>Guardians first</b> — a villager under attack belongs to the
 *       guardian that answers the attack, not to the pilgrimage it happened to
 *       be walking. The guardian takes the villager over, raises its golem,
 *       and the villager keeps its post (the builder is tagged, so the
 *       pilgrimage never claims it again).</li>
 *   <li><b>The lamplighter next</b> — it places torches on the way, but it
 *       never claims the mob: a scenario that does not claim keeps only the
 *       turn it was given.</li>
 *   <li><b>The pilgrim last</b> — it owns the villager for the whole march,
 *       which is the longest-running claim in the module. It is the one that
 *       yields when a guardian needs the mob.</li>
 * </ul>
 */
final class BehaviorPriority {

    /** A golem guardian: defensive, short-range, and it takes the mob over. */
    static final int GUARDIAN = 100;
    /** The lamplighter: a small errand that never claims the mob. */
    static final int LAMPLIGHTER = 50;
    /** The pilgrim: the long march, and the claim that yields. */
    static final int PILGRIM = 10;

    private BehaviorPriority() {
    }
}
