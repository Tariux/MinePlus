package com.mineplus.infrastructure.render;

/**
 * The declarative render intent of one piece of content (a multiblock level
 * today): a {@link RenderMode} plus an optional {@link RenderPolicy} override
 * and a short provenance label used by telemetry.
 *
 * <p>A plan is inert — it is resolved to an effective backend/kind (with
 * subsystem availability and fallback applied) by the single routing choke
 * point, {@link RenderRouter}. Content definitions therefore never branch on
 * backend selection themselves.</p>
 *
 * @param mode   the requested render mode
 * @param policy the policy to apply, or {@code null} to use the router's global policy
 * @param source short provenance label ({@code "default"}, {@code "json"},
 *               {@code "code"}) surfaced by {@code /mineplus render stats}
 */
public record RenderPlan(RenderMode mode, RenderPolicy policy, String source) {

    public RenderPlan {
        mode = mode == null ? RenderMode.VIRTUAL : mode;
        source = source == null || source.isBlank() ? "default" : source.trim();
    }

    public static RenderPlan of(RenderMode mode) {
        return new RenderPlan(mode, null, "code");
    }

    public static RenderPlan of(RenderMode mode, RenderPolicy policy) {
        return new RenderPlan(mode, policy, "code");
    }

    /** Same mode/policy with a different provenance label. */
    public RenderPlan withSource(String newSource) {
        return new RenderPlan(mode, policy, newSource);
    }

    /** Whether this plan carries its own policy rather than inheriting the global one. */
    public boolean hasPolicyOverride() {
        return policy != null;
    }
}
