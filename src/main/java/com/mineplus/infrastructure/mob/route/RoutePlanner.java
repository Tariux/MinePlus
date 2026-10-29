package com.mineplus.infrastructure.mob.route;

import com.mineplus.infrastructure.mob.navigation.GroundProbe;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Short-horizon route planning for walking mobs: given a straight leg, return
 * the next waypoint that reaches it without walking into the lake, the cliff
 * or the wall that sits across the line.
 *
 * <p>This is deliberately <b>segmented</b>, like Baritone's splicing strategy:
 * one search covers a 48-block window around the mob, not the whole 2,000-block
 * journey. A long march is therefore a chain of small plans, each cheap, each
 * replanned when the mob reaches it — the terrain ahead is loaded by then, and
 * the route stays honest instead of guessing at unloaded chunks.
 *
 * <p>The search is a bounded A* over a 2-block grid using dense arrays (one
 * slot per cell of the window, no per-node objects), primitive costs and an
 * octile heuristic. Only already-loaded chunks are sampled: planning never
 * forces chunk generation. Unloaded cells are allowed at a penalty so a route
 * can cross a chunk seam, but the executor's ground probe still normalises
 * every waypoint before the mob is sent to it.
 *
 * <p>Costs encode what a walking mob cares about: height change (a one-block
 * step is free-ish, falling is allowed but discouraged), water (crossable, but
 * a mob would rather walk around it), and unknown ground. Cliffs up to four
 * blocks may be dropped; anything a mob cannot climb is blocked.
 *
 * <p>Thread model: main thread only — block reads require it. One plan is
 * capped at {@link #MAX_EXPANSIONS} cells, so the cost is bounded per leg.
 */
public final class RoutePlanner {

    /** Blocks per routing cell. Two keeps doors and stair widths visible. */
    private static final int CELL = 2;
    /** Cells searched around the mob in each direction (a 25×25 window). */
    private static final int RADIUS = 12;
    private static final int WINDOW = RADIUS * 2 + 1;
    /** Cells a single search may expand before returning its best partial route. */
    private static final int MAX_EXPANSIONS = 256;
    /**
     * Searches one engine pass may run across every mob, per {@link #SEARCH_WINDOW_NANOS}.
     * The obstruction gate keeps the ordinary open-field march out of A*
     * entirely; this ceiling is what stops a terrain full of cliffs from
     * spending a whole run's budget on planning. A refused search simply leaves
     * the caller on its straight leg. The window is wall-clock, because terrain
     * cost is wall-clock: a main-thread plan that takes 40 ms has cost the tick
     * 40 ms regardless of how many engine runs it belonged to.
     */
    private static final int MAX_SEARCHES_PER_WINDOW = 24;
    private static final long SEARCH_WINDOW_NANOS = 50_000_000L;
    private static final int INF = Integer.MAX_VALUE / 4;

    private static long searchWindowStart;
    private static int searchesInWindow;

    /** How far along the straight leg the obstruction gate samples. */
    private static final int GATE_SAMPLES = 6;

    // Cell kinds after sampling.
    private static final byte KIND_UNKNOWN = 0;
    private static final byte KIND_GROUND = 1;
    private static final byte KIND_WATER = 2;
    private static final byte KIND_BLOCKED = 3;
    private static final byte KIND_UNLOADED = 4;

    private RoutePlanner() {
    }

    /**
     * The next waypoint toward {@code towards}, up to {@code maxDistance}
     * blocks away, routed around whatever sits across the straight line.
     *
     * @return a standable waypoint, or {@code null} when no path was found and
     *         the caller should keep its own straight leg
     */
    public static Location waypoint(World world, Location from, Location towards, double maxDistance) {
        if (world == null || from == null || towards == null
                || from.getWorld() != world || towards.getWorld() != world
                || maxDistance < CELL * 2.0) {
            return null;
        }
        Location straight = pointTowards(world, from, towards, maxDistance);
        if (!obstructed(world, from, straight)) {
            return straight;
        }
        return search(world, from, towards, maxDistance);
    }

    /**
     * {@code true} when the straight leg crosses water, a cliff or a wall — the
     * cheap gate that keeps an ordinary open-field march from paying for A*.
     */
    public static boolean obstructed(World world, Location from, Location to) {
        if (world == null || from == null || to == null || from.getWorld() != world) {
            return true;
        }
        int lastY = from.getBlockY();
        for (int i = 1; i <= GATE_SAMPLES; i++) {
            double t = i / (double) GATE_SAMPLES;
            int x = (int) Math.floor(from.getX() + (to.getX() - from.getX()) * t);
            int z = (int) Math.floor(from.getZ() + (to.getZ() - from.getZ()) * t);
            Sample sample = column(world, x, z, from.getBlockY());
            if (sample.kind == KIND_BLOCKED) {
                return true;
            }
            if (sample.kind == KIND_GROUND && Math.abs(sample.height - lastY) > 2) {
                return true;
            }
            if (sample.kind == KIND_WATER) {
                return true; // a mob can swim, but the straight line is not the walk
            }
            if (sample.kind == KIND_GROUND) {
                lastY = sample.height;
            }
        }
        return false;
    }

    private static Location pointTowards(World world, Location from, Location towards, double distance) {
        double dx = towards.getX() - from.getX();
        double dz = towards.getZ() - from.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 1.0E-4) {
            return from.clone();
        }
        double scale = Math.min(distance, length) / length;
        return new Location(world, from.getX() + dx * scale, from.getY(), from.getZ() + dz * scale);
    }

    // ------------------------------------------------------------------
    // A* over the local window
    // ------------------------------------------------------------------

    private static Location search(World world, Location from, Location towards, double maxDistance) {
        if (!acquireSearch()) {
            return null; // this window's planning budget is spent; walk straight
        }
        int startX = Math.floorDiv(from.getBlockX(), CELL);
        int startZ = Math.floorDiv(from.getBlockZ(), CELL);
        int goalX = Math.floorDiv(towards.getBlockX(), CELL);
        int goalZ = Math.floorDiv(towards.getBlockZ(), CELL);
        // Walk the goal back toward the window instead of abandoning the plan.
        goalX = clamp(goalX, startX - RADIUS, startX + RADIUS);
        goalZ = clamp(goalZ, startZ - RADIUS, startZ + RADIUS);

        int cells = WINDOW * WINDOW;
        int[] height = new int[cells];
        byte[] kind = new byte[cells];
        boolean[] sampled = new boolean[cells];
        int[] g = new int[cells];
        int[] parent = new int[cells];
        boolean[] closed = new boolean[cells];
        java.util.Arrays.fill(g, INF);
        java.util.Arrays.fill(parent, -1);

        int startIndex = indexOf(startX, startZ, startX, startZ);
        if (startIndex < 0) {
            return null;
        }
        int startY = from.getBlockY();
        Sample startSample = column(world, from.getBlockX(), from.getBlockZ(), startY);
        if (startSample.kind == KIND_GROUND) {
            startY = startSample.height;
        }
        height[startIndex] = startY;
        kind[startIndex] = KIND_GROUND;
        sampled[startIndex] = true;

        int goalIndex = indexOf(goalX, goalZ, startX, startZ);
        Heap heap = new Heap(Math.min(cells, MAX_EXPANSIONS * 8));
        g[startIndex] = 0;
        heap.push(startIndex, heuristic(startX, startZ, goalX, goalZ));
        closed[startIndex] = false;

        int best = startIndex;
        int bestHeuristic = heuristic(startX, startZ, goalX, goalZ);

        for (int expanded = 0; expanded < MAX_EXPANSIONS && !heap.isEmpty(); expanded++) {
            int current = heap.pop();
            if (closed[current]) {
                continue;
            }
            closed[current] = true;
            int currentX = cellX(current, startX);
            int currentZ = cellZ(current, startZ);
            int h = heuristic(currentX, currentZ, goalX, goalZ);
            if (h < bestHeuristic) {
                bestHeuristic = h;
                best = current;
            }
            if (current == goalIndex) {
                best = current;
                break;
            }
            int currentY = height[current];
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nextX = currentX + dx;
                    int nextZ = currentZ + dz;
                    int next = indexOf(nextX, nextZ, startX, startZ);
                    if (next < 0 || closed[next]) {
                        continue;
                    }
                    boolean diagonal = dx != 0 && dz != 0;
                    if (diagonal && !walkableOrthogonal(world, currentX, currentZ, dx, dz, startX, startZ,
                            from.getBlockY(), height, kind, sampled)) {
                        continue;
                    }
                    if (!sampled[next]) {
                        Sample sample = column(world, nextX * CELL, nextZ * CELL, from.getBlockY());
                        height[next] = sample.height;
                        kind[next] = sample.kind;
                        sampled[next] = true;
                    }
                    byte nextKind = kind[next];
                    if (nextKind == KIND_BLOCKED) {
                        continue;
                    }
                    int step = diagonal ? 14 : 10;
                    if (nextKind == KIND_WATER) {
                        step += 26;
                    } else if (nextKind == KIND_UNLOADED) {
                        step += 12;
                    } else if (nextKind == KIND_UNKNOWN) {
                        continue;
                    }
                    int dy = height[next] - currentY;
                    if (dy > 1 || dy < -4) {
                        continue; // unclimbable step, or a drop a walker will not take
                    }
                    step += Math.abs(dy) * 7;
                    int tentative = g[current] + step;
                    if (tentative >= g[next]) {
                        continue;
                    }
                    g[next] = tentative;
                    parent[next] = current;
                    heap.push(next, tentative + heuristic(nextX, nextZ, goalX, goalZ));
                }
            }
        }

        if (best == startIndex) {
            return null;
        }
        return reconstruct(world, best, parent, height, startX, startZ, from, maxDistance);
    }

    /** A diagonal may not cut the corner of a blocked orthogonal cell. */
    private static boolean walkableOrthogonal(World world, int cellX, int cellZ, int dx, int dz,
                                              int startX, int startZ, int fromY,
                                              int[] height, byte[] kind, boolean[] sampled) {
        int first = indexOf(cellX + dx, cellZ, startX, startZ);
        int second = indexOf(cellX, cellZ + dz, startX, startZ);
        return (first < 0 || kindOrSample(world, first, cellX + dx, cellZ, fromY, height, kind, sampled)
                        != KIND_BLOCKED)
                && (second < 0 || kindOrSample(world, second, cellX, cellZ + dz, fromY, height, kind, sampled)
                        != KIND_BLOCKED);
    }

    private static byte kindOrSample(World world, int index, int cellX, int cellZ, int fromY,
                                     int[] height, byte[] kind, boolean[] sampled) {
        if (!sampled[index]) {
            Sample sample = column(world, cellX * CELL, cellZ * CELL, fromY);
            height[index] = sample.height;
            kind[index] = sample.kind;
            sampled[index] = true;
        }
        return kind[index];
    }

    private static Location reconstruct(World world, int end, int[] parent, int[] height,
                                        int startX, int startZ, Location from, double maxDistance) {
        java.util.ArrayList<Integer> reversed = new java.util.ArrayList<>();
        for (int node = end; node >= 0; node = parent[node]) {
            reversed.add(node);
            if (reversed.size() > WINDOW * WINDOW) {
                break; // cyclic parent (must not happen), stop safely
            }
        }
        int steps = reversed.size() - 1;
        if (steps < 2) {
            return null;
        }
        // Start at the far end but never past the requested leg length.
        int limit = Math.max(1, (int) Math.ceil(maxDistance / CELL));
        int chosen = Math.min(steps, limit);
        int node = reversed.get(steps - chosen);
        int x = cellX(node, startX) * CELL + CELL / 2;
        int z = cellZ(node, startZ) * CELL + CELL / 2;
        int y = height[node];
        Location target = new Location(world, x + 0.5, y, z + 0.5);
        if (target.distanceSquared(from) < 4.0) {
            return null;
        }
        Location standable = GroundProbe.standableNear(target, 2, 3);
        return standable != null ? standable : target;
    }

    // ------------------------------------------------------------------
    // Terrain sampling
    // ------------------------------------------------------------------

    /** One column's answer: the foot height and what the column is made of. */
    private record Sample(int height, byte kind) {
    }

    /**
     * The standable foot height in one column, searched around {@code aroundY}.
     * Water and unloaded columns are reported rather than rejected, so the
     * planner can price them instead of pretending a river is a wall.
     */
    private static Sample column(World world, int x, int z, int aroundY) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return new Sample(aroundY, KIND_UNLOADED);
        }
        int minY = Math.max(world.getMinHeight() + 1, aroundY - 6);
        int maxY = Math.min(world.getMaxHeight() - 2, aroundY + 4);
        int highest = Math.min(maxY, world.getHighestBlockYAt(x, z) + 1);
        int waterY = Integer.MIN_VALUE;
        for (int y = highest; y >= minY; y--) {
            Block below = world.getBlockAt(x, y - 1, z);
            Material belowType = below.getType();
            if (belowType == Material.LAVA || belowType == Material.MAGMA_BLOCK) {
                return new Sample(y, KIND_BLOCKED);
            }
            Block foot = world.getBlockAt(x, y, z);
            Material footType = foot.getType();
            if (footType == Material.WATER || footType == Material.BUBBLE_COLUMN) {
                if (waterY == Integer.MIN_VALUE) {
                    waterY = y;
                }
                continue;
            }
            if (belowType.isSolid() && foot.isPassable() && foot.getRelative(org.bukkit.block.BlockFace.UP).isPassable()
                    && belowType != Material.CACTUS) {
                return new Sample(y, KIND_GROUND);
            }
        }
        if (waterY != Integer.MIN_VALUE) {
            return new Sample(waterY, KIND_WATER);
        }
        return new Sample(aroundY, KIND_UNKNOWN);
    }

    // ------------------------------------------------------------------
    // Window arithmetic and the open-set heap
    // ------------------------------------------------------------------

    /** Takes one search slot from the current wall-clock window. */
    private static boolean acquireSearch() {
        long now = System.nanoTime();
        if (now - searchWindowStart > SEARCH_WINDOW_NANOS) {
            searchWindowStart = now;
            searchesInWindow = 0;
        }
        if (searchesInWindow >= MAX_SEARCHES_PER_WINDOW) {
            return false;
        }
        searchesInWindow++;
        return true;
    }

    private static int indexOf(int cellX, int cellZ, int startX, int startZ) {
        int dx = cellX - startX + RADIUS;
        int dz = cellZ - startZ + RADIUS;
        if (dx < 0 || dx >= WINDOW || dz < 0 || dz >= WINDOW) {
            return -1;
        }
        return dx * WINDOW + dz;
    }

    private static int cellX(int index, int startX) {
        return (index / WINDOW) - RADIUS + startX;
    }

    private static int cellZ(int index, int startZ) {
        return (index % WINDOW) - RADIUS + startZ;
    }

    private static int heuristic(int x, int z, int goalX, int goalZ) {
        int dx = Math.abs(x - goalX);
        int dz = Math.abs(z - goalZ);
        // Octile distance, scaled to the step cost.
        return 10 * (dx + dz) + (14 - 2 * 10) * Math.min(dx, dz);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** A binary min-heap of cell indices ordered by f-score, with lazy deletions. */
    private static final class Heap {
        private int[] cells;
        private int[] scores;
        private int size;

        Heap(int capacity) {
            this.cells = new int[Math.max(16, capacity)];
            this.scores = new int[Math.max(16, capacity)];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(int cell, int score) {
            if (size == cells.length) {
                cells = java.util.Arrays.copyOf(cells, size * 2);
                scores = java.util.Arrays.copyOf(scores, size * 2);
            }
            int at = size++;
            cells[at] = cell;
            scores[at] = score;
            while (at > 0) {
                int parent = (at - 1) >>> 1;
                if (scores[parent] <= scores[at]) {
                    break;
                }
                swap(at, parent);
                at = parent;
            }
        }

        int pop() {
            int top = cells[0];
            size--;
            if (size > 0) {
                cells[0] = cells[size];
                scores[0] = scores[size];
                int at = 0;
                while (true) {
                    int left = at * 2 + 1;
                    int right = left + 1;
                    int smallest = at;
                    if (left < size && scores[left] < scores[smallest]) {
                        smallest = left;
                    }
                    if (right < size && scores[right] < scores[smallest]) {
                        smallest = right;
                    }
                    if (smallest == at) {
                        break;
                    }
                    swap(at, smallest);
                    at = smallest;
                }
            }
            return top;
        }

        private void swap(int first, int second) {
            int cell = cells[first];
            cells[first] = cells[second];
            cells[second] = cell;
            int score = scores[first];
            scores[first] = scores[second];
            scores[second] = score;
        }
    }
}
