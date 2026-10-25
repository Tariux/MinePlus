package com.mineplus.infrastructure.mob;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-action outcome report of a forced firing pass
 * ({@link MobBehaviorApi#forceFire}). Unlike the engine's regular one-action-
 * per-evaluation dispatch, a forced pass attempts every registered action of
 * the profile and reports each, so test commands can show exactly what fired,
 * what declined, and what was gated by profile conditions.
 */
public final class BehaviorFireResult {

    /** Outcome of one forced action attempt. */
    public enum Status {
        /** The action executed and reported a world change. */
        FIRED,
        /** The action executed but declined (its own context checks failed). */
        DECLINED,
        /** Profile conditions blocked the action before it could run. */
        CONDITION_FAILED
    }

    /** One action attempt with its outcome. */
    public record Attempt(String actionId, Status status) {
    }

    /** Shared empty result (no actions were registered for the entity type). */
    public static final BehaviorFireResult EMPTY = new BehaviorFireResult(List.of());

    private final List<Attempt> attempts;

    private BehaviorFireResult(List<Attempt> attempts) {
        this.attempts = attempts;
    }

    /** All attempts in evaluation order (unmodifiable). */
    public List<Attempt> attempts() {
        return attempts;
    }

    /** {@code true} when at least one action executed successfully. */
    public boolean anyFired() {
        for (Attempt attempt : attempts) {
            if (attempt.status() == Status.FIRED) {
                return true;
            }
        }
        return false;
    }

    /** Multi-line, colorless report suitable for logs and test commands. */
    public String report() {
        if (attempts.isEmpty()) {
            return "no actions registered for this entity type";
        }
        StringBuilder sb = new StringBuilder(16 * attempts.size());
        for (Attempt attempt : attempts) {
            sb.append(switch (attempt.status()) {
                case FIRED -> "  FIRED    " + attempt.actionId();
                case DECLINED -> "  DECLINED " + attempt.actionId() + " (context check failed)";
                case CONDITION_FAILED -> "  BLOCKED  " + attempt.actionId() + " (profile conditions)";
            }).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** Accumulator used by the engine while forcing. */
    static final class Builder {
        private final List<Attempt> attempts = new ArrayList<>();

        void add(String actionId, Status status) {
            attempts.add(new Attempt(actionId, status));
        }

        BehaviorFireResult build() {
            return attempts.isEmpty() ? EMPTY : new BehaviorFireResult(Collections.unmodifiableList(attempts));
        }
    }
}
