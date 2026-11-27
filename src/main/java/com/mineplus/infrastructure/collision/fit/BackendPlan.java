package com.mineplus.infrastructure.collision.fit;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The planner's decision, with reasons and counters.
 *
 * @param parts    every cell that gets something placed (deterministic order)
 * @param metrics  measured counts and error
 * @param warnings non-fatal planning notes (limits, unsupported backends)
 */
public record BackendPlan(List<CellChoice> parts, FitMetrics metrics, List<String> warnings) {

    public BackendPlan {
        parts = parts == null ? List.of() : List.copyOf(parts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static BackendPlan empty() {
        return new BackendPlan(List.of(),
                new FitMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List.of());
    }

    /** Human-readable reasoning, one line per placed cell. */
    public List<String> explain() {
        return parts.stream()
                .map(part -> part.x() + "," + part.y() + "," + part.z() + " -> "
                        + part.backend() + " (" + part.reason() + ')')
                .collect(Collectors.toList());
    }
}
