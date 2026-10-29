package com.mineplus.infrastructure.input;

/**
 * The Core-owned trigger state of one player: is the activation gesture
 * currently held down, and was the last activation a fresh press or the
 * continuation of the one before it?
 *
 * <p><b>Why the Core owns this.</b> How many packets one physical click
 * produces depends on what the player is aiming at: a click on a block sends
 * the "use item on block" packet, and the client follows it with the "use
 * item in hand" packet whenever the block did not consume the interaction.
 * Both reach the server as press events. A module that reads its trigger
 * state straight off those events therefore fires twice for one click,
 * dry-fires a weapon that is already reloading, and spends two rounds where
 * the player pulled one. The Core resolves the press stream once, for every
 * module, and reports the two facts a trigger needs: "the button is down"
 * and "this was a new press, not the client continuing the one it already
 * sent".</p>
 *
 * <p><b>The three ways a hold ends.</b> The client repeating the press keeps
 * the hold alive; the Core's grace window ends it when the presses stop (the
 * player let go of the button on a carrier with no use action, where the
 * server never sees a release); an explicit release (the item left the hand,
 * or the client reported the end of a use gesture) ends it immediately.</p>
 *
 * <p><b>What counts as a continuation.</b> A press that lands inside the click
 * window belongs to the click the client already started: the second packet
 * of one physical click, or a client that repeats the press it is holding. A
 * press outside the window is a new gesture. The window is deliberately
 * short — a bare time threshold wide enough to cover a slow round trip would
 * swallow a fast, deliberate second click, and any weapon whose rate of fire
 * is quicker than that threshold would silently lose shots.</p>
 *
 * <p>Threading: one instance per player, touched only from the main thread
 * (Bukkit events and the Core's own task).</p>
 */
public final class TriggerState {

    private boolean held;
    private boolean freshPress = true;
    private int pressCount;
    private long lastPressAt;
    private long heldSince;
    private long lastReleaseAt;

    TriggerState() {
    }

    /**
     * Records one activation.
     *
     * @param now               monotonic timestamp of the press
     * @param clickWindowMillis the gap inside which a press continues the
     *                           click the client already started
     * @return {@code true} when this was a new gesture (not a continuation)
     */
    boolean press(long now, long clickWindowMillis) {
        // One physical click is one press. The client answers a click on a
        // block with two packets and both arrive here; the second one
        // continues the gesture the client already started, so it must not
        // read as a new press — a weapon that fires on it fires twice per
        // click, and a reloading weapon dry-fires on it.
        boolean continuation = held && (now - lastPressAt) <= clickWindowMillis;
        freshPress = !continuation;
        pressCount++;
        lastPressAt = now;
        if (!held) {
            held = true;
            heldSince = now;
        }
        return freshPress;
    }

    /**
     * Ends the hold.
     *
     * @return {@code true} when a hold was active and is now over
     */
    boolean release(long now) {
        if (!held) {
            return false;
        }
        held = false;
        // The next activation is always a fresh press.
        freshPress = true;
        lastReleaseAt = now;
        return true;
    }

    /**
     * Ends the hold when the client stopped repeating the press.
     *
     * @return {@code true} when this call ended an active hold
     */
    boolean expire(long now, long graceMillis) {
        if (!held || now - lastPressAt <= graceMillis) {
            return false;
        }
        release(now);
        return true;
    }

    /** {@code true} while the activation gesture is considered held down. */
    public boolean held() {
        return held;
    }

    /**
     * {@code true} when the last activation was a new press rather than the
     * client continuing the press it already sent. A semi-automatic action
     * fires on this; an automatic one keeps firing while {@link #held()}.
     */
    public boolean freshPress() {
        return freshPress;
    }

    /** How many activations this player has made. */
    public int pressCount() {
        return pressCount;
    }

    /** Timestamp of the current hold's start, or 0 when no hold is active. */
    public long heldSince() {
        return heldSince;
    }

    /** Timestamp of the last activation. */
    public long lastPressAt() {
        return lastPressAt;
    }

    /** Timestamp of the last release, or 0 when the player never released. */
    public long lastReleaseAt() {
        return lastReleaseAt;
    }
}
