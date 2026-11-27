package com.mineplus.infrastructure.collision.fit;

import com.mineplus.infrastructure.collision.atlas.ShapeAtlas;
import com.mineplus.infrastructure.collision.atlas.ShapeEntry;
import com.mineplus.infrastructure.collision.atlas.ShapeFlag;
import com.mineplus.infrastructure.collision.mask.OccupancyMask;
import com.mineplus.infrastructure.collision.mask.VoxelMask;
import com.mineplus.infrastructure.collision.source.CellDirective;
import com.mineplus.infrastructure.collision.source.DirectiveGrid;
import com.mineplus.infrastructure.collision.source.DirectiveType;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks the best candidate per block cell and hands the result to the
 * {@link BackendPlanner}.
 *
 * <p>Scoring per cell and candidate:
 * {@code intersection - alpha*extra - beta*missing - gamma*step - delta*cost}.
 * Candidates are the atlas's invisible-capable collision states (plus visible
 * ones when the profile allows), in atlas order, so ties resolve by cost and then
 * state string and the fit is deterministic.</p>
 *
 * <p>Walkability is evaluated against the target's column tops, then a smoothing
 * pass replaces standing surfaces whose top sits a micro-ledge above a
 * neighbour with an alternative whose top matches, as long as the shape error
 * stays within the profile tolerance. Fully enclosed interior cells are pruned
 * when the options allow it.</p>
 *
 * <p>Pure computation: safe on a worker thread, never touches Bukkit.</p>
 */
public final class HybridFitter {

    private final ShapeAtlas atlas;

    public HybridFitter(ShapeAtlas atlas) {
        if (atlas == null) {
            throw new IllegalArgumentException("fitter needs an atlas");
        }
        this.atlas = atlas;
    }

    public ShapeAtlas atlas() {
        return atlas;
    }

    /** Fits a model at {@code yawDegrees}, snapped as the options say. */
    public FitResult fit(String modelKey, OccupancyMask mask, DirectiveGrid directives,
            FitProfile profile, FitterOptions options, double yawDegrees) {
        FitProfile effective = options.restrict(profile);
        DirectiveGrid sourceDirectives = directives == null
                ? DirectiveGrid.empty(mask.blocksX(), mask.blocksY(), mask.blocksZ())
                : directives;
        RotationSupport.Rotated rotation = RotationSupport.apply(mask, sourceDirectives,
                yawDegrees, options.rotationSnap());
        OccupancyMask grid = rotation.mask();
        DirectiveGrid dirs = rotation.directives();
        List<String> warnings = new ArrayList<>();
        if (Math.abs(rotation.yaw() - Math.round(rotation.yaw() / 90.0) * 90.0) > 1.0e-6
                && !sourceDirectives.isEmpty()) {
            warnings.add("arbitrary yaw resampled the grid; per-cell blueprint directives"
                    + " were dropped");
        }

        List<ShapeEntry> candidates = new ArrayList<>();
        for (ShapeEntry entry : atlas.entries()) {
            if (!entry.hasCollision()) {
                continue;
            }
            if (!effective.allowVisibleBlocks() && !entry.invisibleCapable()) {
                continue;
            }
            candidates.add(entry);
        }
        if (candidates.isEmpty()) {
            warnings.add("atlas has no usable collision candidates; enable visible blocks or"
                    + " install the resource pack");
        }

        int sizeX = grid.blocksX();
        int sizeY = grid.blocksY();
        int sizeZ = grid.blocksZ();
        VoxelMask[][][] targets = new VoxelMask[sizeX][sizeY][sizeZ];
        boolean[][][] occupied = new boolean[sizeX][sizeY][sizeZ];
        double[][] columnTop = new double[sizeX][sizeZ];
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                columnTop[x][z] = grid.topSurfaceAt(x, z);
            }
        }
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    VoxelMask target = grid.blockMask(x, y, z);
                    targets[x][y][z] = target;
                    occupied[x][y][z] = !target.isEmpty();
                }
            }
        }

        List<CellChoice> fitted = new ArrayList<>();
        int pruned = 0;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    VoxelMask target = targets[x][y][z];
                    CellDirective directive = dirs.get(x, y, z);
                    if (directive != null && directive.type() == DirectiveType.PASSABLE_HITBOX) {
                        fitted.add(new CellChoice(x, y, z, null, BackendType.NONE, 0, 0, 0, 0,
                                false, "blueprint passable"));
                        continue;
                    }
                    if (target.isEmpty()) {
                        continue;
                    }
                    if (options.pruneInterior() && directive == null
                            && enclosed(occupied, x, y, z)) {
                        pruned++;
                        continue;
                    }
                    ShapeEntry chosen = null;
                    if (directive != null && directive.forcesState()) {
                        chosen = atlas.get(directive.forceState());
                        if (chosen == null) {
                            warnings.add("forced state not in atlas: " + directive.forceState());
                        }
                    }
                    double score;
                    if (chosen == null) {
                        chosen = choose(candidates, target, x, y, z, columnTop, grid, effective);
                        score = chosen == null ? 0 : shapeScore(chosen, target, effective)
                                - effective.gamma() * stepPenalty(x, y, z, chosen.topSurface(),
                                        columnTop, grid, effective);
                    } else {
                        score = shapeScore(chosen, target, effective);
                    }
                    if (chosen == null) {
                        warnings.add("no candidate for cell " + x + "," + y + "," + z);
                        continue;
                    }
                    int intersection = chosen.mask().intersectionCount(target);
                    int extra = chosen.bitCount() - intersection;
                    int missing = target.count() - intersection;
                    boolean standing = !occupiedAt(occupied, x, y + 1, z)
                            && chosen.topSurface() >= 0.25;
                    fitted.add(new CellChoice(x, y, z, chosen.state(), BackendType.NONE, score,
                            extra, missing, chosen.topSurface(), standing,
                            "score " + Math.round(score)));
                }
            }
        }

        int smoothed = smoothLedges(fitted, targets, candidates, columnTop, grid, effective);
        if (smoothed > 0) {
            warnings.add(smoothed + " cell(s) smoothed to remove micro-ledges");
        }
        if (pruned > 0) {
            warnings.add(pruned + " interior cell(s) pruned");
        }

        BackendPlan plan = BackendPlanner.plan(fitted, grid, dirs, effective, options);
        warnings.addAll(plan.warnings());
        FitMetrics metrics = new FitMetrics(plan.metrics().cells(), plan.metrics().blocks(),
                plan.metrics().ghosts(), plan.metrics().entities(), pruned,
                plan.metrics().extraVolume(), plan.metrics().missingVolume(),
                plan.metrics().targetVolume(), plan.metrics().maxLedge(),
                plan.metrics().ledgeViolations());
        return new FitResult(modelKey, atlas.key().describe(),
                effective.name() + '/' + effective.cacheKey(), rotation.yaw(),
                mask.resolution(), grid.blocksX(), grid.blocksY(), grid.blocksZ(),
                plan.parts(), metrics, warnings);
    }

    private ShapeEntry choose(List<ShapeEntry> candidates, VoxelMask target, int x, int y, int z,
            double[][] columnTop, OccupancyMask grid, FitProfile profile) {
        ShapeEntry best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (ShapeEntry candidate : candidates) {
            double score = shapeScore(candidate, target, profile)
                    - profile.gamma() * stepPenalty(x, y, z, candidate.topSurface(), columnTop,
                            grid, profile);
            if (best == null || score > bestScore + 1.0e-9
                    || (Math.abs(score - bestScore) <= 1.0e-9
                            && candidate.backendCost() < best.backendCost())) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static double shapeScore(ShapeEntry candidate, VoxelMask target, FitProfile profile) {
        int intersection = candidate.mask().intersectionCount(target);
        int extra = candidate.bitCount() - intersection;
        int missing = target.count() - intersection;
        double cost = candidate.backendCost();
        if (profile.preferNonOccluding() && candidate.flags().contains(ShapeFlag.OCCLUDING)) {
            cost += 2;
        }
        return intersection - profile.alpha() * extra - profile.beta() * missing
                - profile.delta() * cost;
    }

    private static double stepPenalty(int x, int y, int z, double top, double[][] columnTop,
            OccupancyMask grid, FitProfile profile) {
        double own = y + top;
        double penalty = 0;
        for (int[] offset : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int nx = x + offset[0];
            int nz = z + offset[1];
            if (nx < 0 || nz < 0 || nx >= grid.blocksX() || nz >= grid.blocksZ()) {
                continue;
            }
            double neighbourTop = columnTop[nx][nz];
            if (neighbourTop <= 0) {
                continue;
            }
            double step = Math.abs(own - neighbourTop);
            if (step <= 1.0e-9) {
                continue;
            }
            if (step <= 0.125) {
                penalty += 2;
            } else if (step > profile.walkStep() && step <= profile.jumpStep()) {
                penalty += 1;
            }
        }
        return penalty;
    }

    /**
     * Replaces standing cells whose top is a micro-ledge above a neighbour with a
     * candidate whose top matches, when the shape error stays within tolerance.
     */
    private int smoothLedges(List<CellChoice> fitted, VoxelMask[][][] targets,
            List<ShapeEntry> candidates, double[][] columnTop, OccupancyMask grid,
            FitProfile profile) {
        int changed = 0;
        for (int i = 0; i < fitted.size(); i++) {
            CellChoice cell = fitted.get(i);
            if (!cell.standingSurface() || cell.state() == null) {
                continue;
            }
            double own = cell.y() + cell.topSurface();
            double wanted = -1;
            for (int[] offset : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = cell.x() + offset[0];
                int nz = cell.z() + offset[1];
                if (nx < 0 || nz < 0 || nx >= grid.blocksX() || nz >= grid.blocksZ()) {
                    continue;
                }
                double neighbourSurface = columnTop[nx][nz];
                if (neighbourSurface > own + 1.0e-9 && neighbourSurface - own <= 0.125) {
                    wanted = Math.max(wanted, neighbourSurface);
                }
            }
            if (wanted < 0) {
                continue;
            }
            VoxelMask target = targets[cell.x()][cell.y()][cell.z()];
            ShapeEntry best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (ShapeEntry candidate : candidates) {
                if (!candidate.hasCollision()) {
                    continue;
                }
                if (Math.abs(cell.y() + candidate.topSurface() - wanted) > 1.0e-9) {
                    continue;
                }
                double score = shapeScore(candidate, target, profile);
                if (score >= cell.score() - profile.ledgeSmoothingTolerance() * 4096
                        && (best == null || score > bestScore + 1.0e-9)) {
                    best = candidate;
                    bestScore = score;
                }
            }
            if (best == null) {
                continue;
            }
            int intersection = best.mask().intersectionCount(target);
            int extra = best.bitCount() - intersection;
            int missing = target.count() - intersection;
            fitted.set(i, new CellChoice(cell.x(), cell.y(), cell.z(), best.state(),
                    BackendType.NONE, bestScore, extra, missing, best.topSurface(), true,
                    "smoothed ledge for neighbour top " + wanted));
            changed++;
        }
        return changed;
    }

    private static boolean enclosed(boolean[][][] occupied, int x, int y, int z) {
        return occupiedAt(occupied, x - 1, y, z) && occupiedAt(occupied, x + 1, y, z)
                && occupiedAt(occupied, x, y - 1, z) && occupiedAt(occupied, x, y + 1, z)
                && occupiedAt(occupied, x, y, z - 1) && occupiedAt(occupied, x, y, z + 1);
    }

    private static boolean occupiedAt(boolean[][][] occupied, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= occupied.length
                || y >= occupied[0].length || z >= occupied[0][0].length) {
            return false;
        }
        return occupied[x][y][z];
    }
}
