package com.mineplus.infrastructure.mob;

import org.bukkit.World;

/**
 * World time, read one way so every scenario agrees on when it is night.
 *
 * <p>A server exposes three clocks and they are not interchangeable:
 * <ul>
 *   <li>{@link World#getTime()} — the <b>time of day</b>, 0 to 23999. It is what
 *       the sun sees, and it is what {@code /time set} moves.</li>
 *   <li>{@link World#getFullTime()} — the time of day plus the elapsed days, so
 *       it counts days but also jumps when a player sets the clock.</li>
 *   <li>{@link World#getGameTime()} — the age of the world. It only ever
 *       counts up, which makes it the right clock for <b>expiry</b> (a claim
 *       that must not survive a {@code /time set}) and the wrong clock for
 *       <b>time of day</b>.</li>
 * </ul>
 *
 * <p>Scenarios that ask "is it night" through a clock of their own get answers
 * that disagree with the sky: a march that reads the world age rests at noon
 * and walks at midnight, and a crew that counts its nights by the world age
 * never camps twice. This class is the one place that decides, so a scenario
 * asks {@link #isNight(World)} and gets the answer the player sees.
 *
 * <p>Thread model: pure reads, safe from any thread, but the scenarios that use
 * it are main-thread only anyway.
 */
public final class WorldTimes {

    /** Ticks in one Minecraft day. */
    public static final long DAY_LENGTH = 24000L;
    /** First tick of the night — dusk. */
    public static final long NIGHT_START = 13000L;
    /** Last tick of the night — the sky starts to clear. */
    public static final long NIGHT_END = 23000L;

    private WorldTimes() {
    }

    /**
     * The time of day, {@code 0..23999}.
     *
     * @return the tick of the day, or {@code 0} for a {@code null} world
     */
    public static long timeOfDay(World world) {
        if (world == null) {
            return 0L;
        }
        return Math.floorMod(world.getTime(), DAY_LENGTH);
    }

    /**
     * {@code true} between dusk and dawn — the hours a scenario rests instead
     * of working. Read from the time of day, so it always agrees with the sky.
     */
    public static boolean isNight(World world) {
        long ticks = timeOfDay(world);
        return ticks >= NIGHT_START && ticks <= NIGHT_END;
    }

    /** {@code true} in daylight. */
    public static boolean isDay(World world) {
        return !isNight(world);
    }

    /**
     * The index of the day the world is in: the same number for every tick of
     * that day, and a different one the next morning. Scenarios use it to spend
     * a night once — a camp is built one night, not once per engine roll.
     */
    public static long dayIndex(World world) {
        if (world == null) {
            return 0L;
        }
        return Math.floorDiv(world.getFullTime(), DAY_LENGTH);
    }

    /**
     * The index of the current night. A night belongs to the day it starts in,
     * so the number changes at dawn rather than at dusk — a camp raised at
     * dusk and a camp raised after midnight are the same camp.
     */
    public static long nightIndex(World world) {
        return dayIndex(world);
    }
}
