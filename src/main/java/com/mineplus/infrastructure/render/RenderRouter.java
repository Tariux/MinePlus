package com.mineplus.infrastructure.render;

import com.mineplus.util.DebugLogger;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * The single place a {@link RenderPlan} becomes an effective backend/kind. The
 * routing choke point for the unified render engine: content declares intent,
 * the router applies subsystem availability and {@link RenderPolicy}, and no
 * other subsystem branches on the choice.
 *
 * <p>Routing is thread-safe and allocation-light: counters are
 * {@link LongAdder}s and the policy is a volatile reference swapped on reload.
 * Resolution never touches the world or the pack subsystem — the caller passes
 * {@code packAvailable}, so the router stays a pure decision function.</p>
 */
public final class RenderRouter {

    /** An effective route; {@link #available()} is false when no backend can serve the plan. */
    public record ResolvedRoute(RenderBackend backend, RenderKind kind, boolean degraded) {

        public boolean available() {
            return backend != null;
        }

        /** A route that renders nothing (policy forbids degradation and pack is unavailable). */
        public static ResolvedRoute unavailable() {
            return new ResolvedRoute(null, null, false);
        }
    }

    /** Immutable telemetry snapshot for {@code /mineplus render stats}. */
    public record RenderStats(
            Map<RenderMode, Long> routesByMode,
            long totalRoutes,
            long degradedRoutes,
            long unavailableRoutes,
            long attachFailures
    ) {
    }

    private volatile RenderPolicy policy;
    private final Map<RenderMode, LongAdder> routesByMode = new EnumMap<>(RenderMode.class);
    private final LongAdder totalRoutes = new LongAdder();
    private final LongAdder degradedRoutes = new LongAdder();
    private final LongAdder unavailableRoutes = new LongAdder();
    private final LongAdder attachFailures = new LongAdder();

    public RenderRouter() {
        this(RenderPolicy.defaults());
    }

    public RenderRouter(RenderPolicy policy) {
        this.policy = policy == null ? RenderPolicy.defaults() : policy;
        for (RenderMode mode : RenderMode.values()) {
            routesByMode.put(mode, new LongAdder());
        }
    }

    public RenderPolicy policy() {
        return policy;
    }

    /** Applies a new global policy (reload path). */
    public void updatePolicy(RenderPolicy newPolicy) {
        if (newPolicy != null) {
            this.policy = newPolicy;
        }
    }

    /**
     * Resolves a plan to an effective route.
     *
     * @param plan          the requested render intent
     * @param packAvailable whether the pack renderer is running
     * @return the effective route; {@link ResolvedRoute#available()} false when the
     *         plan wants the pack and the policy forbids degrading to virtual
     */
    public ResolvedRoute route(RenderPlan plan, boolean packAvailable) {
        RenderPlan resolvedPlan = plan == null ? RenderPlan.of(RenderMode.VIRTUAL) : plan;
        RenderPolicy effectivePolicy = effectivePolicy(resolvedPlan);
        RenderMode mode = resolvedPlan.mode();
        RenderBackend backend = mode.backend();
        RenderKind kind = mode.kind();

        if (backend.includesPack() && !packAvailable) {
            if (effectivePolicy.allowDegradeToVirtual()) {
                count(mode, effectivePolicy);
                if (effectivePolicy.trackTelemetry()) {
                    degradedRoutes.increment();
                }
                log("degraded " + mode + " -> VIRTUAL (pack unavailable)");
                return new ResolvedRoute(RenderBackend.VIRTUAL, RenderKind.MODEL, true);
            }
            if (effectivePolicy.trackTelemetry()) {
                unavailableRoutes.increment();
            }
            log("unavailable " + mode + " (pack unavailable, degradation disabled)");
            return ResolvedRoute.unavailable();
        }

        count(mode, effectivePolicy);
        return new ResolvedRoute(backend, kind, false);
    }

    /** Records a pack display attach failure (reported by the rendering manager). */
    public void recordAttachFailure() {
        if (policy.trackTelemetry()) {
            attachFailures.increment();
        }
    }

    /** Zeros all counters. */
    public void reset() {
        totalRoutes.reset();
        degradedRoutes.reset();
        unavailableRoutes.reset();
        attachFailures.reset();
        for (LongAdder adder : routesByMode.values()) {
            adder.reset();
        }
    }

    public RenderStats snapshot() {
        Map<RenderMode, Long> counts = new EnumMap<>(RenderMode.class);
        for (Map.Entry<RenderMode, LongAdder> entry : routesByMode.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().sum());
        }
        return new RenderStats(counts, totalRoutes.sum(), degradedRoutes.sum(),
                unavailableRoutes.sum(), attachFailures.sum());
    }

    private RenderPolicy effectivePolicy(RenderPlan plan) {
        return plan.hasPolicyOverride() ? plan.policy() : policy;
    }

    private void count(RenderMode mode, RenderPolicy effectivePolicy) {
        if (!effectivePolicy.trackTelemetry()) {
            return;
        }
        routesByMode.get(mode).increment();
        totalRoutes.increment();
    }

    private void log(String message) {
        if (policy.logRouting()) {
            DebugLogger.debug("[RenderRouter] " + message);
        }
    }
}
