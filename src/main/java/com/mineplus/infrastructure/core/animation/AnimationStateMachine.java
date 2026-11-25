package com.mineplus.infrastructure.core.animation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Animation state machine (mega-plan C5 / Phase C): declarative pose
 * graph for mobs and machines. Pure computation — states map to named
 * animations (resolved by the render engine), transitions are guarded by
 * simple predicate names the feature registers, and blending windows are
 * declared per transition. The Bukkit tick adapter feeds {@link #tick}.
 */
public final class AnimationStateMachine {

    /** One state: the animation to play and its loop behaviour. */
    public static final class State {
        final String name;
        final String animation;
        final boolean looping;
        final float speed;

        State(String name, String animation, boolean looping, float speed) {
            this.name = name;
            this.animation = animation;
            this.looping = looping;
            this.speed = speed;
        }

        public String name() {
            return name;
        }

        public String animation() {
            return animation;
        }

        public boolean looping() {
            return looping;
        }

        public float speed() {
            return speed;
        }
    }

    /** One edge: target state plus a guard key and blend ticks. */
    public record Transition(String from, String to, String guard, int blendTicks) {
    }

    /** Raised when a definition references an unknown state. */
    public static final class UnknownStateException extends IllegalStateException {
        public UnknownStateException(String message) {
            super(message);
        }
    }

    private final Map<String, State> states = new HashMap<>();
    private final List<Transition> transitions = new java.util.ArrayList<>();
    private final Map<String, java.util.function.BooleanSupplier> guards = new HashMap<>();
    private final int[] blendCounter = {0};
    private State current;
    private State previous;

    public AnimationStateMachine define(String name, String animation, boolean looping, float speed) {
        states.put(name, new State(name, animation, looping, speed));
        return this;
    }

    public AnimationStateMachine transition(String from, String to, String guard, int blendTicks) {
        requireState(from);
        requireState(to);
        transitions.add(new Transition(from, to, guard, blendTicks));
        return this;
    }

    /** Registers a guard: returns true when its transitions may fire. */
    public AnimationStateMachine guard(String name, java.util.function.BooleanSupplier condition) {
        guards.put(name, condition);
        return this;
    }

    public void start(String stateName) {
        current = requireState(stateName);
        previous = null;
    }

    public State current() {
        return current;
    }

    public State previous() {
        return previous;
    }

    /** Remaining blend ticks of the most recent transition, for the renderer. */
    public int blendTicksRemaining() {
        return Math.max(0, blendCounter[0]);
    }

    /**
     * Advances one tick: counts down any blend window, then evaluates
     * transitions from the current state in definition order and takes the
     * first whose guard passes. Guards are consumed through
     * {@link #guard}, keeping this class free of host types.
     */
    public void tick() {
        if (current == null) {
            return;
        }
        if (blendCounter[0] > 0) {
            blendCounter[0]--;
        }
        for (Transition t : transitions) {
            if (!t.from().equals(current.name)) {
                continue;
            }
            java.util.function.BooleanSupplier guard = guards.get(t.guard());
            if (guard != null && guard.getAsBoolean()) {
                previous = current;
                current = states.get(t.to());
                blendCounter[0] = t.blendTicks();
                return;
            }
        }
    }

    private State requireState(String name) {
        State state = states.get(name);
        if (state == null) {
            throw new UnknownStateException("Unknown animation state: " + name);
        }
        return state;
    }
}
