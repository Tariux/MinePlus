package com.mineplus.infrastructure.mob.custom.render;

import java.util.ArrayList;
import java.util.List;

/**
 * The validation verdict for one model's compiled pack assets.
 *
 * <ul>
 *   <li>{@link Status#OK} — compiled as authored.</li>
 *   <li>{@link Status#REPAIRED} — compiled with substitutions (a missing
 *       texture became a neutral placeholder, a bone was renamed, geometry was
 *       split for the client window); every substitution is listed.</li>
 *   <li>{@link Status#BROKEN} — the model cannot be presented at all; the rig
 *       is never attached and the fallback chain takes over. Reasons name the
 *       exact failure.</li>
 * </ul>
 */
public record PackReport(Status status, List<String> reasons) {

    public enum Status {
        OK,
        REPAIRED,
        BROKEN
    }

    public static PackReport ok() {
        return new PackReport(Status.OK, List.of());
    }

    public static PackReport broken(String reason) {
        return new PackReport(Status.BROKEN, List.of(reason));
    }

    /** Builds the verdict from non-fatal repairs: empty means OK. */
    public static PackReport fromRepairs(List<String> repairs) {
        return repairs.isEmpty() ? ok() : new PackReport(Status.REPAIRED, List.copyOf(repairs));
    }

    public boolean usable() {
        return status != Status.BROKEN;
    }

    /** One-line summary for status output. */
    public String summary() {
        if (reasons.isEmpty()) {
            return status.name();
        }
        return status.name() + " (" + reasons.size() + "): " + String.join("; ", reasons);
    }

    /** Accumulates repairs/problems while compiling. */
    public static final class Builder {

        private final List<String> broken = new ArrayList<>();
        private final List<String> repairs = new ArrayList<>();

        public Builder repair(String reason) {
            repairs.add(reason);
            return this;
        }

        public Builder broken(String reason) {
            broken.add(reason);
            return this;
        }

        public boolean isBroken() {
            return !broken.isEmpty();
        }

        public PackReport build() {
            if (!broken.isEmpty()) {
                List<String> all = new ArrayList<>(broken);
                all.addAll(repairs);
                return new PackReport(Status.BROKEN, List.copyOf(all));
            }
            return fromRepairs(repairs);
        }
    }
}
