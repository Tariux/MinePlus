package com.mineplus.infrastructure.collision.fit;

import java.util.Locale;

/**
 * Weights and permissions for one fit.
 *
 * <p>Score per candidate:
 * {@code intersection - alpha*extra - beta*missing - gamma*step - delta*cost}.
 * Higher is better. The preset supplies the four weights; the rest describes
 * what the fit is allowed to use and how strict the walkability rules are.</p>
 *
 * <p>Immutable and thread-safe. Model config overrides any field with
 * {@link Builder#build()}.</p>
 */
public record FitProfile(
        String name,
        double alpha,
        double beta,
        double gamma,
        double delta,
        double walkStep,
        double jumpStep,
        boolean allowGhostBlocks,
        boolean allowShulkerEntities,
        boolean allowVisibleBlocks,
        boolean allowInteraction,
        boolean allowPassableOutline,
        boolean allowPlates,
        boolean preferNonOccluding,
        double ledgeSmoothingTolerance) {

    public FitProfile {
        name = name == null || name.isBlank() ? "CUSTOM" : name.trim().toUpperCase(Locale.ROOT);
        alpha = Math.max(0, alpha);
        beta = Math.max(0, beta);
        gamma = Math.max(0, gamma);
        delta = Math.max(0, delta);
        walkStep = walkStep <= 0 ? 0.6 : walkStep;
        jumpStep = jumpStep <= 0 ? 1.25 : jumpStep;
        ledgeSmoothingTolerance = Math.max(0, Math.min(1, ledgeSmoothingTolerance));
    }

    /** Preset with its default backend permissions. */
    public static FitProfile preset(ProfilePreset preset) {
        return switch (preset == null ? ProfilePreset.BALANCED : preset) {
            case FURNITURE -> base(preset, true, false, false, true, true, false, true, 0.12);
            case NARROW_GATE -> base(preset, true, false, false, true, true, false, true, 0.10);
            case WALKABLE_SURFACE -> base(preset, false, false, false, true, true, false, true, 0.15);
            case DECORATIVE_PASSABLE -> base(preset, true, false, false, true, true, false, true, 0.05);
            case BALANCED -> base(preset, true, true, false, true, true, false, true, 0.10);
        };
    }

    private static FitProfile base(ProfilePreset preset, boolean ghost, boolean shulker,
            boolean visible, boolean interaction, boolean passableOutline, boolean plates,
            boolean nonOccluding, double tolerance) {
        return new FitProfile(preset.name(), preset.alpha(), preset.beta(), preset.gamma(),
                preset.delta(), 0.6, 1.25, ghost, shulker, visible, interaction, passableOutline,
                plates, nonOccluding, tolerance);
    }

    /** Preset lookup by config key, falling back to BALANCED. */
    public static FitProfile of(String key) {
        return preset(ProfilePreset.fromKey(key, ProfilePreset.BALANCED));
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder(ProfilePreset preset) {
        return new Builder(preset(preset));
    }

    /** Overrides any field; unset fields keep the preset value. */
    public static final class Builder {
        private String name;
        private double alpha;
        private double beta;
        private double gamma;
        private double delta;
        private double walkStep;
        private double jumpStep;
        private boolean allowGhostBlocks;
        private boolean allowShulkerEntities;
        private boolean allowVisibleBlocks;
        private boolean allowInteraction;
        private boolean allowPassableOutline;
        private boolean allowPlates;
        private boolean preferNonOccluding;
        private double ledgeSmoothingTolerance;

        private Builder(FitProfile base) {
            this.name = base.name;
            this.alpha = base.alpha;
            this.beta = base.beta;
            this.gamma = base.gamma;
            this.delta = base.delta;
            this.walkStep = base.walkStep;
            this.jumpStep = base.jumpStep;
            this.allowGhostBlocks = base.allowGhostBlocks;
            this.allowShulkerEntities = base.allowShulkerEntities;
            this.allowVisibleBlocks = base.allowVisibleBlocks;
            this.allowInteraction = base.allowInteraction;
            this.allowPassableOutline = base.allowPassableOutline;
            this.allowPlates = base.allowPlates;
            this.preferNonOccluding = base.preferNonOccluding;
            this.ledgeSmoothingTolerance = base.ledgeSmoothingTolerance;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder weights(double alpha, double beta, double gamma, double delta) {
            this.alpha = alpha;
            this.beta = beta;
            this.gamma = gamma;
            this.delta = delta;
            return this;
        }

        public Builder steps(double walk, double jump) {
            this.walkStep = walk;
            this.jumpStep = jump;
            return this;
        }

        public Builder ghost(boolean value) {
            this.allowGhostBlocks = value;
            return this;
        }

        public Builder shulker(boolean value) {
            this.allowShulkerEntities = value;
            return this;
        }

        public Builder visibleBlocks(boolean value) {
            this.allowVisibleBlocks = value;
            return this;
        }

        public Builder interaction(boolean value) {
            this.allowInteraction = value;
            return this;
        }

        public Builder passableOutline(boolean value) {
            this.allowPassableOutline = value;
            return this;
        }

        public Builder plates(boolean value) {
            this.allowPlates = value;
            return this;
        }

        public Builder nonOccluding(boolean value) {
            this.preferNonOccluding = value;
            return this;
        }

        public Builder ledgeTolerance(double value) {
            this.ledgeSmoothingTolerance = value;
            return this;
        }

        public FitProfile build() {
            return new FitProfile(name, alpha, beta, gamma, delta, walkStep, jumpStep,
                    allowGhostBlocks, allowShulkerEntities, allowVisibleBlocks, allowInteraction,
                    allowPassableOutline, allowPlates, preferNonOccluding,
                    ledgeSmoothingTolerance);
        }
    }

    /** Stable key for the fit cache. */
    public String cacheKey() {
        return name + ':' + alpha + ':' + beta + ':' + gamma + ':' + delta + ':'
                + walkStep + ':' + jumpStep + ':' + allowGhostBlocks + ':'
                + allowShulkerEntities + ':' + allowVisibleBlocks + ':' + allowInteraction + ':'
                + allowPassableOutline + ':' + allowPlates + ':' + preferNonOccluding + ':'
                + ledgeSmoothingTolerance;
    }
}
