package com.mineplus.infrastructure.core.minigame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minigame toolkit, part 2 (mega-plan D1 / Phase D): map regions and
 * markers, per-game scoreboards, and spectator handling — all pure
 * computation over plain data, matching {@link GameStateMachine}.
 *
 * <p>Regions are axis-aligned cuboids with named markers (spawns, goals,
 * capture points). Markers support round-robin claiming so spawns spread
 * players without extra logic. Scoreboards are ordered line lists the
 * Bukkit adapter renders; spectators are excluded from team membership
 * and win checks.
 */
public final class GameMap {

    /** Axis-aligned cuboid in world coordinates. */
    public record Region(String name, String worldName,
                         int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        public boolean contains(String world, int x, int y, int z) {
            return world.equals(worldName)
                    && x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }

        public int volume() {
            return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    /** Named point marker: spawn, goal, capture point, etc. */
    public record Marker(String name, String worldName, int x, int y, int z) {
    }

    /** Scoreboard line entry: priority-ordered content lines. */
    public record Line(String key, String text, int priority) {
    }

    private final Map<String, Region> regions = new HashMap<>();
    private final Map<String, List<Marker>> markers = new HashMap<>();
    private final List<Line> scoreboard = new ArrayList<>();
    private final List<UUID> spectators = new ArrayList<>();
    private final Map<String, Integer> markerCursor = new HashMap<>();

    public void addRegion(Region region) {
        regions.put(region.name(), region);
    }

    public Region region(String name) {
        return regions.get(name);
    }

    public void addMarker(Marker marker) {
        markers.computeIfAbsent(marker.name(), k -> new ArrayList<>()).add(marker);
    }

    public List<Marker> markers(String name) {
        return markers.getOrDefault(name, Collections.emptyList());
    }

    /**
     * Claims the next marker of {@code name} in round-robin order — spawn
     * distribution without per-game logic. Returns null when none exist.
     */
    public Marker claimMarker(String name) {
        List<Marker> pool = markers.get(name);
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        int cursor = markerCursor.getOrDefault(name, 0) % pool.size();
        markerCursor.put(name, cursor + 1);
        return pool.get(cursor);
    }

    public void resetMarkers() {
        markerCursor.clear();
    }

    /** Replaces the scoreboard content; lines render ordered by priority. */
    public void setScoreboard(List<Line> lines) {
        scoreboard.clear();
        scoreboard.addAll(lines);
        scoreboard.sort(java.util.Comparator.comparingInt(Line::priority));
    }

    public List<Line> scoreboard() {
        return Collections.unmodifiableList(scoreboard);
    }

    /** Adds a spectator; idempotent. */
    public boolean addSpectator(UUID player) {
        if (spectators.contains(player)) {
            return false;
        }
        spectators.add(player);
        return true;
    }

    /** Removes a spectator; no-op when absent. */
    public void removeSpectator(UUID player) {
        spectators.remove(player);
    }

    public boolean isSpectator(UUID player) {
        return spectators.contains(player);
    }

    public List<UUID> spectators() {
        return Collections.unmodifiableList(spectators);
    }
}
