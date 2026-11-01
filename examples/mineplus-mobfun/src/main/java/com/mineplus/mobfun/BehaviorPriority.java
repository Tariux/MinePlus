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
 *   <li><b>The pilgrim next</b> — the march is a process with a destination
 *       and a crew, so it owns the villager outright. Torch errands may not
 *       detour a marching pilgrim: a villager stopped to plant a torch every
 *       few blocks never gets anywhere.</li>
 *   <li><b>The lamplighter last</b> — it places torches on the way and never
 *       claims the mob, so it only gets the villagers the pilgrimage leaves
 *       alone (builders, or villagers with no village to march to).</li>
 * </ul>
 */
final class BehaviorPriority {

    /** A golem guardian: defensive, short-range, and it takes the mob over. */
    static final int GUARDIAN = 100;
    /**
     * The necromancer raid: a villager-level scenario for a monster. Its
     * pipeline claims above this anyway; the profile priority only orders it
     * against the witch's own ambient summoning.
     */
    static final int NECROMANCER_RAID = 80;
    /** The pilgrim: the long march, which outranks ambient errands. */
    static final int PILGRIM = 60;
    /** The lamplighter: a small errand that never claims the mob. */
    static final int LAMPLIGHTER = 10;
    /**
     * The Evil Pig: a custom mob, so its profile never competes with a vanilla
     * mob's profile. The value only orders it against another profile a module
     * might bind to the same custom id.
     */
    static final int EVIL_PIG = 50;

    private BehaviorPriority() {
    }
}
