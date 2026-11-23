package com.mineplus.create.machines;

/**
 * Pure-Java machine definitions and math for the Create module (no Bukkit,
 * unit-testable). Also covers the steam and electric tiers' math.
 */
public final class MachineSpecs {

    /** Contract: one feature = one machine type id. */
    public static final String HAND_CRANK = "mc_hand_crank";
    public static final String WATER_WHEEL = "mc_water_wheel";
    public static final String WINDMILL = "mc_windmill";
    public static final String MILLSTONE = "mc_millstone";
    public static final String MECHANICAL_PRESS = "mc_mechanical_press";
    public static final String STEAM_ENGINE = "mc_steam_engine";
    public static final String DYNAMO = "mc_dynamo";

    private MachineSpecs() {
    }

    /** Stress units a machine draws while its network turns. */
    public static float stressOf(String typeId) {
        return switch (typeId) {
            case HAND_CRANK, WATER_WHEEL, WINDMILL -> 0f; // sources
            case MILLSTONE -> 4f;
            case MECHANICAL_PRESS -> 6f;
            default -> 1f;
        };
    }

    /** Target RPM a source produces. */
    public static float sourceRpm(String typeId) {
        return switch (typeId) {
            case HAND_CRANK -> 32f;
            case WATER_WHEEL -> 16f;
            case WINDMILL -> 24f;
            case STEAM_ENGINE -> 48f;
            default -> 0f;
        };
    }

    /** Stress capacity a source provides. */
    public static float sourceCapacity(String typeId) {
        return switch (typeId) {
            case HAND_CRANK -> 8f;
            case WATER_WHEEL -> 32f;
            case WINDMILL -> 24f;
            case STEAM_ENGINE -> 64f;
            default -> 0f;
        };
    }

    public static boolean isSource(String typeId) {
        return sourceRpm(typeId) > 0f;
    }

    /** Steam tier: one coal item burns for this many ticks of full heat. */
    public static final int COAL_BURN_TICKS = 1600;
    public static final float STEAM_ENGINE_RPM = 48f;
    public static final float STEAM_ENGINE_CAPACITY = 64f;

    /** Remaining burn ticks after stocking {@code items} coal. */
    public static int burnTicksAfter(int currentBurnTicks, int items) {
        return Math.max(0, currentBurnTicks) + Math.max(0, items) * COAL_BURN_TICKS;
    }

    /** Electric tier: dynamo converts |RPM| to energy per tick. */
    public static final float FE_PER_RPM_PER_TICK = 0.5f;
    public static final int DYNAMO_BUFFER_FE = 100_000;

    public static int dynamoFePerTick(float rpm) {
        return Math.round(Math.abs(rpm) * FE_PER_RPM_PER_TICK);
    }
}
