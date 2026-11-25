package com.mineplus.test;

import com.mineplus.infrastructure.core.minigame.GameStateMachine;
import com.mineplus.infrastructure.core.minigame.GameStateMachine.IllegalTransitionException;
import com.mineplus.infrastructure.core.minigame.GameStateMachine.State;
import com.mineplus.infrastructure.core.minigame.GameStateMachine.Team;
import java.util.UUID;

/**
 * Plain-Java checks for the D1 minigame toolkit: legal/illegal lifecycle
 * transitions, round-robin join, score/eliminate gating by state, and
 * round resets.
 *
 * Run: java com.mineplus.test.MinigameStateTest — exit 0 means all passed.
 */
public final class MinigameStateTest {

    private static final java.util.List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) {
        lifecycleTransitions();
        joiningAndCapacity();
        scoringAndElimination();
        stateActionsAndRounds();

        if (failures.isEmpty()) {
            System.out.println("MinigameStateTest: all checks passed");
            System.exit(0);
        }
        System.err.println("MinigameStateTest: " + failures.size() + " failure(s)");
        failures.forEach(f -> System.err.println("  - " + f));
        System.exit(1);
    }

    private static void lifecycleTransitions() {
        GameStateMachine game = new GameStateMachine(2);
        check("starts in LOBBY", game.state() == State.LOBBY);
        game.transition(State.STARTING);
        game.transition(State.RUNNING);
        game.transition(State.ENDING);
        game.transition(State.LOBBY);
        check("full cycle back to LOBBY", game.state() == State.LOBBY);

        boolean threw = false;
        try {
            game.transition(State.RUNNING); // LOBBY -> RUNNING illegal
        } catch (IllegalTransitionException e) {
            threw = true;
        }
        check("illegal transition throws", threw);

        game.transition(State.CLOSED);
        boolean closed = false;
        try {
            game.transition(State.LOBBY);
        } catch (IllegalTransitionException e) {
            closed = true;
        }
        check("CLOSED is terminal", closed);
    }

    private static void joiningAndCapacity() {
        GameStateMachine game = new GameStateMachine(2);
        game.createTeam("red");
        game.createTeam("blue");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        Team ta = game.join(a, "red");
        Team tb = game.join(b, null); // round-robin fills the emptier team
        check("named join respected", ta.id().equals("red"));
        check("round-robin fills blue", tb.id().equals("blue"));
        check("teamOf resolves", game.teamOf(a).id().equals("red"));

        boolean full = false;
        try {
            game.join(c, "red"); // red has 1, cap 2 -> then blue 1 -> fits; second join throws
            game.join(c, "red");
        } catch (IllegalStateException e) {
            full = e.getMessage().startsWith("Team full");
        }
        check("capacity enforced", full);

        game.transition(State.STARTING);
        game.transition(State.RUNNING);
        boolean locked = false;
        try {
            game.join(UUID.randomUUID(), null);
        } catch (IllegalStateException e) {
            locked = true;
        }
        check("join locked while running", locked);
    }

    private static void scoringAndElimination() {
        GameStateMachine game = new GameStateMachine(4);
        game.createTeam("red");
        game.createTeam("blue");
        game.transition(State.STARTING);
        game.transition(State.RUNNING);

        game.addScore("red", 3);
        game.addScore("red", 2);
        check("score accumulates", game.team("red").score() == 5);

        boolean gated = false;
        game.transition(State.ENDING);
        try {
            game.addScore("red", 1);
        } catch (IllegalStateException e) {
            gated = true;
        }
        check("scoring only while running", gated);
    }

    private static void stateActionsAndRounds() {
        GameStateMachine game = new GameStateMachine(4);
        game.createTeam("red");
        game.createTeam("blue");
        java.util.UUID p = java.util.UUID.randomUUID();
        game.join(p, "red");

        boolean[] ran = {false};
        game.onStateEnter(() -> ran[0] = true);
        game.transition(State.STARTING);
        game.transition(State.RUNNING);
        check("state action ran once on next entry", ran[0]);

        game.addScore("red", 7);
        game.eliminate("blue");
        game.nextRound();
        check("next round resets score and elimination",
                game.team("red").score() == 0 && !game.team("blue").eliminated());
        check("members retained across rounds", game.team("red").members().contains(p));
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
