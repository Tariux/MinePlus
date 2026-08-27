package com.mineplus.infrastructure.core.multiblock.progress;

import java.util.Map;

/**
 * A timed crafting process running on a multiblock instance.
 *
 * <p>Represents a recipe that takes {@code craftTimeTicks} ticks to complete, advanced
 * by the {@link MachineProcessManager} on every lifecycle tick. The process state is
 * encoded into the owning instance's {@code stateData} map, which is already persisted,
 * so processes survive server restarts and resume from their remaining tick count.
 *
 * <p>Example scenario: a blast furnace with {@code craftTimeTicks: 600} (30 seconds).
 * A level with {@code speedMultiplier: 2.0} advances the process twice as fast, and a
 * server restart mid-smelt resumes exactly where it left off.
 *
 * @param recipeId       the machine recipe being crafted
 * @param machineTypeId  the type of the machine running the process (recorded for validation)
 * @param totalTicks     the full recipe duration in ticks (without speed scaling)
 * @param remainingTicks the remaining duration in ticks (without speed scaling); consumed
 *                       at {@code intervalTicks * speedMultiplier} per lifecycle tick
 */
public record MachineProcess(
        String recipeId,
        String machineTypeId,
        int totalTicks,
        int remainingTicks
) {

    /** stateData key for the recipe id of the running process. */
    static final String KEY_RECIPE = "mp.process.recipe";
    /** stateData key for the machine type the process was started on. */
    static final String KEY_MACHINE = "mp.process.machine";
    /** stateData key for the total (unscaled) duration in ticks. */
    static final String KEY_TOTAL = "mp.process.total";
    /** stateData key for the remaining (unscaled) duration in ticks. */
    static final String KEY_REMAINING = "mp.process.remaining";

    public MachineProcess {
        if (recipeId == null || recipeId.isBlank()) {
            throw new IllegalArgumentException("recipeId must not be blank");
        }
        if (machineTypeId == null || machineTypeId.isBlank()) {
            throw new IllegalArgumentException("machineTypeId must not be blank");
        }
        totalTicks = Math.max(1, totalTicks);
        remainingTicks = Math.min(Math.max(0, remainingTicks), totalTicks);
    }

    /**
     * Encodes this process into a stateData map so it is persisted with the instance.
     * Four flat string keys are used (prefixed {@code mp.process.*}) rather than a
     * serialized blob, keeping the data human-readable and forward-compatible.
     *
     * @param stateData the mutable stateData map of the owning instance
     */
    void encodeInto(Map<String, String> stateData) {
        stateData.put(KEY_RECIPE, recipeId);
        stateData.put(KEY_MACHINE, machineTypeId);
        stateData.put(KEY_TOTAL, Integer.toString(totalTicks));
        stateData.put(KEY_REMAINING, Integer.toString(remainingTicks));
    }

    /**
     * Removes all process keys from a stateData map, marking the process as finished
     * or cancelled.
     *
     * @param stateData the mutable stateData map of the owning instance
     */
    static void clearFrom(Map<String, String> stateData) {
        stateData.remove(KEY_RECIPE);
        stateData.remove(KEY_MACHINE);
        stateData.remove(KEY_TOTAL);
        stateData.remove(KEY_REMAINING);
    }

    /**
     * Decodes a process from a stateData map, if one is present and well-formed.
     * Malformed values (non-numeric counts, missing keys) yield {@code null} and the
     * keys should be treated as absent.
     *
     * @param stateData the instance's stateData map
     * @return the decoded process, or {@code null} if none is running or it is malformed
     */
    static MachineProcess decodeFrom(Map<String, String> stateData) {
        String recipe = stateData.get(KEY_RECIPE);
        if (recipe == null || recipe.isBlank()) {
            return null;
        }
        String machine = stateData.getOrDefault(KEY_MACHINE, "");
        Integer total = parseIntOrNull(stateData.get(KEY_TOTAL));
        Integer remaining = parseIntOrNull(stateData.get(KEY_REMAINING));
        if (total == null || remaining == null) {
            return null;
        }
        try {
            return new MachineProcess(recipe, machine, total, remaining);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * @return the completion ratio in the range [0.0, 1.0], suitable for GUI progress bars
     */
    public double progressRatio() {
        if (totalTicks <= 0) {
            return 1.0;
        }
        return 1.0 - (double) remainingTicks / (double) totalTicks;
    }

    private static Integer parseIntOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
