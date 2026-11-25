package com.mineplus.infrastructure.minigame;

import com.mineplus.infrastructure.core.minigame.GameMap;
import com.mineplus.infrastructure.core.minigame.GameStateMachine;
import com.mineplus.infrastructure.core.minigame.InstanceManager;
import com.mineplus.infrastructure.core.minigame.MinigameAdapter;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

/**
 * Real player-handle wiring for the minigame adapter (mega-plan D1):
 * maps the adapter's id-based operations onto live Bukkit players. This
 * is the only place minigame code touches player handles; the Core
 * machines stay pure.
 *
 * <p>Join puts the player into the active game (tracked + lobby), quit
 * unbinds them, the renderer writes real sidebar scoreboards, and the
 * teleporter resolves marker world names through {@link Bukkit#getWorld}.
 */
public final class MinigameBukkitWiring implements Listener,
        MinigameAdapter.ScoreboardRenderer, MinigameAdapter.Teleporter {

    private final JavaPlugin plugin;
    private final InstanceManager instances;
    private MinigameAdapter active;

    public MinigameBukkitWiring(JavaPlugin plugin, InstanceManager instances) {
        this.plugin = plugin;
        this.instances = instances;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Points the join/quit wiring at the currently served game. */
    public void serve(MinigameAdapter adapter) {
        this.active = adapter;
    }

    /** The adapter currently served, or null when no game is live. */
    public MinigameAdapter served() {
        return active;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (active != null && !active.isTracked(event.getPlayer().getUniqueId())) {
            active.bindPlayer(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (active != null) {
            active.unbindPlayer(event.getPlayer().getUniqueId());
        }
    }

    @Override
    public void render(UUID player, List<GameMap.Line> lines) {
        Player handle = Bukkit.getPlayer(player);
        if (handle == null || !handle.isOnline()) {
            return;
        }
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }
        Scoreboard scoreboard = manager.getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("mineplus", "dummy", "Mineplus");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        // Bukkit sidebar is 15 lines, highest score at the top: index 0
        // renders bottom-up, so score each line as (lineCount - index).
        int rendered = Math.min(lines.size(), 15);
        for (int i = 0; i < rendered; i++) {
            objective.getScore(lines.get(i).text()).setScore(rendered - i);
        }
        handle.setScoreboard(scoreboard);
    }

    @Override
    public void teleport(UUID player, GameMap.Marker marker) {
        Player handle = Bukkit.getPlayer(player);
        World world = Bukkit.getWorld(marker.worldName());
        if (handle == null || world == null) {
            return;
        }
        handle.teleport(new Location(world, marker.x(), marker.y(), marker.z()));
    }

    /** Sends every tracked player of the active game to their spawn marker. */
    public void startSpawns() {
        if (active == null || active.state().state() != GameStateMachine.State.RUNNING) {
            return;
        }
        for (UUID player : active.trackedPlayers()) {
            active.sendToSpawn(player);
        }
    }

    /** Runs the adapter's scoreboard refresh once (tie into the tick loop). */
    public void refreshScoreboards() {
        if (active != null) {
            active.tickPass().run();
        }
    }

    /** Counts instances that still exist in the manager (restart sanity). */
    public int instanceCount() {
        return instances.all().size();
    }
}
