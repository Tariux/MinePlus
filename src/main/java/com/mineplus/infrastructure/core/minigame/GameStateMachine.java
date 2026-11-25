package com.mineplus.infrastructure.core.minigame;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minigame toolkit, part 1 (mega-plan D1 / Phase D): game lifecycle state
 * machine and team management. Pure computation over UUIDs and plain data
 * — no Bukkit types — so game logic stays testable off-thread and the
 * Bukkit adapter (join/quit/score wiring) stays a thin shell.
 *
 * <p>States: LOBBY → STARTING → RUNNING → ENDING → (LOBBY | CLOSED).
 * Illegal transitions throw; the per-game adapter decides whether that
 * aborts a command or is a bug. Rounds are optional: {@link #nextRound()}
 * resets team scores for round-based arenas.
 */
public final class GameStateMachine {

    /** Lifecycle states of one game instance. */
    public enum State {
        LOBBY, STARTING, RUNNING, ENDING, CLOSED
    }

    /** Terminal or requested transition not allowed by the lifecycle. */
    public static final class IllegalTransitionException extends IllegalStateException {
        public IllegalTransitionException(State from, State to) {
            super("Cannot transition " + from + " -> " + to);
        }
    }

    /** One team: members, score, and whether it has been eliminated. */
    public static final class Team {
        private final String id;
        private final List<UUID> members = new ArrayList<>();
        private int score;
        private boolean eliminated;

        Team(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public List<UUID> members() {
            return Collections.unmodifiableList(members);
        }

        public int score() {
            return score;
        }

        public boolean eliminated() {
            return eliminated;
        }
    }

    private State state = State.LOBBY;
    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final int maxPlayersPerTeam;
    private final Deque<Runnable> stateActions = new ArrayDeque<>();

    public GameStateMachine(int maxPlayersPerTeam) {
        this.maxPlayersPerTeam = Math.max(1, maxPlayersPerTeam);
    }

    public State state() {
        return state;
    }

    /** Queues one action per state entry (e.g. teleport on RUNNING). */
    public void onStateEnter(Runnable action) {
        stateActions.add(action);
    }

    public Team team(String id) {
        return teams.get(id);
    }

    public List<Team> teams() {
        return List.copyOf(teams.values());
    }

    /** Creates a team; duplicates throw so config typos surface on enable. */
    public Team createTeam(String id) {
        if (teams.containsKey(id)) {
            throw new IllegalArgumentException("Team already exists: " + id);
        }
        Team team = new Team(id);
        teams.put(id, team);
        return team;
    }

    /** Joins a player; fills teams round-robin when no team is named. */
    public Team join(UUID player, String teamId) {
        requireState(State.LOBBY, State.STARTING);
        Team target;
        if (teamId != null) {
            target = teams.get(teamId);
            if (target == null) {
                throw new IllegalArgumentException("No such team: " + teamId);
            }
        } else {
            target = teams.values().stream()
                    .min(java.util.Comparator.comparingInt(t -> t.members.size()))
                    .orElseThrow(() -> new IllegalStateException("No teams defined"));
        }
        if (target.members.size() >= maxPlayersPerTeam) {
            throw new IllegalStateException("Team full: " + target.id);
        }
        leave(player);
        target.members.add(player);
        return target;
    }

    /** Removes a player from whichever team holds them; no-op when absent. */
    public void leave(UUID player) {
        for (Team team : teams.values()) {
            team.members.remove(player);
        }
    }

    public Team teamOf(UUID player) {
        for (Team team : teams.values()) {
            if (team.members.contains(player)) {
                return team;
            }
        }
        return null;
    }

    public void addScore(String teamId, int points) {
        Team team = teams.get(teamId);
        if (team == null) {
            throw new IllegalArgumentException("No such team: " + teamId);
        }
        requireState(State.RUNNING);
        team.score += points;
    }

    /** Marks a team eliminated; returns true when at most one team remains. */
    public boolean eliminate(String teamId) {
        requireState(State.RUNNING);
        Team team = teams.get(teamId);
        if (team == null) {
            throw new IllegalArgumentException("No such team: " + teamId);
        }
        team.eliminated = true;
        long active = teams.values().stream().filter(t -> !t.eliminated).count();
        return active <= 1;
    }

    /** Advances the lifecycle one step; each state entry runs a queued action. */
    public void transition(State to) {
        boolean allowed = switch (state) {
            case LOBBY -> to == State.STARTING || to == State.CLOSED;
            case STARTING -> to == State.RUNNING || to == State.LOBBY;
            case RUNNING -> to == State.ENDING;
            case ENDING -> to == State.LOBBY || to == State.CLOSED;
            case CLOSED -> false;
        };
        if (!allowed) {
            throw new IllegalTransitionException(state, to);
        }
        state = to;
        Runnable action = stateActions.poll();
        if (action != null) {
            action.run();
        }
    }

    /** Round-based arenas: zero scores and un-eliminate for the next round. */
    public void nextRound() {
        requireState(State.RUNNING, State.ENDING);
        for (Team team : teams.values()) {
            team.score = 0;
            team.eliminated = false;
        }
    }

    /** Serializable view of one team for persistence. */
    public record TeamSnapshot(List<UUID> members, int score, boolean eliminated) {
    }

    /** Serializable view of the whole machine for persistence. */
    public record Snapshot(State state, Map<String, TeamSnapshot> teams) {
    }

    /** Point-in-time copy for the persistence layer. */
    public Snapshot snapshot() {
        Map<String, TeamSnapshot> copy = new LinkedHashMap<>();
        for (Team team : teams.values()) {
            copy.put(team.id, new TeamSnapshot(List.copyOf(team.members), team.score, team.eliminated));
        }
        return new Snapshot(state, copy);
    }

    /**
     * Restores a snapshot (server restart): rebuilds teams and scores and
     * sets the state directly — restore is not a lifecycle transition, so
     * transition legality is deliberately bypassed and no state-enter
     * action fires.
     */
    public void restore(Snapshot snapshot) {
        teams.clear();
        for (Map.Entry<String, TeamSnapshot> entry : snapshot.teams().entrySet()) {
            Team team = new Team(entry.getKey());
            team.members.addAll(entry.getValue().members());
            team.score = entry.getValue().score();
            team.eliminated = entry.getValue().eliminated();
            teams.put(entry.getKey(), team);
        }
        state = snapshot.state();
    }

    private void requireState(State... allowed) {
        for (State s : allowed) {
            if (state == s) {
                return;
            }
        }
        throw new IllegalStateException("Operation not allowed in state " + state);
    }
}
