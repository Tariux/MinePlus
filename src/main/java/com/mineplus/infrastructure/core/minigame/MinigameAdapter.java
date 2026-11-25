package com.mineplus.infrastructure.core.minigame;

import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Minigame Bukkit adapter glue (mega-plan D1 remainder): binds a game
 * instance's machine and map to the server tick and player actions
 * without the Core depending on live player handles.
 *
 * <p>All player interactions arrive as id-based operations ({@code UUID}
 * + action name); the host maps them to real players. Tick follows the
 * machine heartbeat via the lifecycle manager's post-tick pass, and every
 * state entry re-renders the scoreboard through the pluggable renderer.
 */
public final class MinigameAdapter {

    /** Renders a scoreboard for one player (host maps to real UI). */
    public interface ScoreboardRenderer {
        void render(UUID player, List<GameMap.Line> lines);
    }

    /** Moves a player to a position (host maps to real teleport). */
    public interface Teleporter {
        void teleport(UUID player, GameMap.Marker marker);
    }

    private final GameStateMachine state;
    private final GameMap map;
    private final ScoreboardRenderer scoreboards;
    private final Teleporter teleporter;
    private final List<UUID> tracked = new ArrayList<>();
    private final int[] lastState = {-1};
    private final List<Consumer<GameMap.Line>> onLine = new ArrayList<>();
    private boolean started;

    public MinigameAdapter(GameStateMachine state, GameMap map,
                           ScoreboardRenderer scoreboards, Teleporter teleporter) {
        this.state = state;
        this.map = map;
        this.scoreboards = scoreboards;
        this.teleporter = teleporter;
    }

    /** The bound game machine (wiring checks its lifecycle state). */
    public GameStateMachine state() {
        return state;
    }

    /** Queues the join sequence: claim a spawn marker and track the player.
     * The actual teleport happens on the RUNNING entry, when spawns are final.
     */
    public void bindPlayer(UUID player) {
        tracked.add(player);
        state.onStateEnter(() -> {
            if (state.state() == GameStateMachine.State.RUNNING && !started) {
                started = true;
                map.resetMarkers();
            }
        });
    }

    /** Sends a player to their spawn: claims the next marker and teleports. */
    public void sendToSpawn(UUID player) {
        GameMap.Marker spawn = map.claimMarker("spawn");
        if (spawn != null && teleporter != null) {
            teleporter.teleport(player, spawn);
        }
    }

    /**
     * Per-tick glue, registered as a lifecycle post-tick pass: advances the
     * state machine and refreshes scoreboards when the blend window or the
     * scoreboard content changed since the last pass.
     */
    public Runnable tickPass() {
        return () -> {
            // Refresh scoreboards for tracked players when the content is
            // marked changed; per-frame blend countdown belongs to the
            // animation machines (C5), not the game lifecycle.
            if (scoreboards == null) {
                return;
            }
            int blend = state.state().ordinal();
            List<GameMap.Line> lines = map.scoreboard();
            if (blend != lastState[0]) {
                lastState[0] = blend;
                for (UUID player : tracked) {
                    scoreboards.render(player, lines);
                }
            }
        };
    }

    /** Registers this adapter's tick pass with the lifecycle manager. */
    public void attachTo(MultiBlockLifecycleManager lifecycle) {
        lifecycle.addPostTickPass(tickPass());
    }

    /** Drops a player from tracking (quit/kick); no-op for unknown players. */
    public void unbindPlayer(UUID player) {
        tracked.remove(player);
    }

    /** True when the player is tracked by this adapter (joined the game). */
    public boolean isTracked(UUID player) {
        return tracked.contains(player);
    }

    public List<UUID> trackedPlayers() {
        return List.copyOf(tracked);
    }
}
