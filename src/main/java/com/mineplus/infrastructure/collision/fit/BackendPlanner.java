package com.mineplus.infrastructure.collision.fit;

import com.mineplus.infrastructure.collision.mask.OccupancyMask;
import com.mineplus.infrastructure.collision.source.DirectiveGrid;
import com.mineplus.infrastructure.collision.source.DirectiveType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assigns a backend to every fitted cell and enforces the safety rules.
 *
 * <p>Rules, first match wins:</p>
 * <ol>
 *   <li>Blueprint {@code PASSABLE_HITBOX} → interaction or passable outline.</li>
 *   <li>Forced state → real block.</li>
 *   <li>Standing surface → real block (or a platform when enabled) and never a
 *       ghost: the server must agree a block is under the player.</li>
 *   <li>Off-grid shape with a large error and shulkers enabled → shulker.</li>
 *   <li>Passable chosen state → outline, then interaction.</li>
 *   <li>Side wall with ghosts allowed → ghost block, otherwise real block.</li>
 * </ol>
 *
 * <p>Limits are hard: when the block or entity budget is spent, remaining cells
 * are dropped with one warning. The planner is deterministic and never touches
 * the world.</p>
 */
public final class BackendPlanner {

    private BackendPlanner() {
    }

    /** Plans from fitted cells; returns updated choices and metrics. */
    public static BackendPlan plan(List<CellChoice> fitted, OccupancyMask mask,
            DirectiveGrid directives, FitProfile profile, FitterOptions options) {
        if (fitted == null || fitted.isEmpty()) {
            return BackendPlan.empty();
        }
        List<String> warnings = new ArrayList<>();
        List<CellChoice> parts = new ArrayList<>(fitted.size());
        int blocks = 0;
        int ghosts = 0;
        int entities = 0;
        int dropped = 0;

        for (CellChoice cell : fitted) {
            if (directives != null && directives.has(cell.x(), cell.y(), cell.z(),
                    DirectiveType.PASSABLE_HITBOX)) {
                if (profile.allowInteraction()) {
                    parts.add(cell.withBackend(BackendType.INTERACTION_ONLY,
                            "blueprint passable: hitbox only"));
                    entities++;
                } else if (profile.allowPassableOutline()) {
                    parts.add(cell.withBackend(BackendType.PASSABLE_OUTLINE,
                            "blueprint passable: outline only"));
                    blocks++;
                } else {
                    dropped++;
                }
                continue;
            }

            boolean forced = directives != null && directives.get(cell.x(), cell.y(), cell.z()) != null
                    && directives.get(cell.x(), cell.y(), cell.z()).forcesState();
            BackendType backend;
            String reason;
            if (forced) {
                backend = BackendType.REAL_BLOCK;
                reason = "blueprint forced state";
            } else if (cell.state() == null) {
                if (profile.allowInteraction()) {
                    backend = BackendType.INTERACTION_ONLY;
                    reason = "no passable state available: interaction";
                } else {
                    backend = BackendType.NONE;
                    reason = "nothing to place";
                }
            } else if (cell.standingSurface()) {
                if (profile.allowPlates() && cell.topSurface() <= 0.0625 + 1.0e-9) {
                    backend = BackendType.PLATE;
                    reason = "thin standing surface: platform";
                } else {
                    backend = BackendType.REAL_BLOCK;
                    reason = "standing surface must be server-side solid";
                }
            } else if (profile.allowShulkerEntities() && errorRatio(cell) > 0.25) {
                backend = BackendType.SHULKER;
                reason = "off-grid shape (error " + Math.round(errorRatio(cell) * 100)
                        + "%), shulker";
            } else if (profile.allowGhostBlocks() && isSideWall(cell, mask)) {
                backend = BackendType.GHOST_BLOCK;
                reason = "side wall, client-only ghost";
            } else {
                backend = BackendType.REAL_BLOCK;
                reason = profile.allowGhostBlocks()
                        ? "exposed collision, no ghost benefit"
                        : "real block (ghosts disabled)";
            }

            if (backend.isEntity() && entities >= options.maxEntities()) {
                if (backend == BackendType.SHULKER && profile.allowGhostBlocks()) {
                    backend = BackendType.GHOST_BLOCK;
                    reason = "entity budget spent; downgraded to ghost";
                } else if (backend == BackendType.PLATE) {
                    backend = BackendType.REAL_BLOCK;
                    reason = "entity budget spent; downgraded to real block";
                } else if (backend == BackendType.INTERACTION_ONLY) {
                    backend = BackendType.NONE;
                    reason = "entity budget spent; click part dropped";
                }
            }
            if ((backend == BackendType.REAL_BLOCK || backend == BackendType.PASSABLE_OUTLINE)
                    && blocks >= options.maxBlocks()) {
                backend = BackendType.NONE;
                reason = "block budget spent; cell dropped";
            }
            if (backend.isEntity()) {
                entities++;
            } else if (backend.isRealBlock()) {
                blocks++;
            } else if (backend == BackendType.GHOST_BLOCK) {
                ghosts++;
            }
            if (backend == BackendType.NONE) {
                dropped++;
            } else {
                parts.add(cell.withBackend(backend, reason));
            }
        }

        if (dropped > 0) {
            warnings.add(dropped + " cell(s) dropped by backend budgets");
        }
        FitMetrics base = metrics(parts, mask);
        FitMetrics metrics = new FitMetrics(base.cells(), blocks, ghosts, entities,
                base.prunedInterior(), base.extraVolume(), base.missingVolume(),
                base.targetVolume(), base.maxLedge(), base.ledgeViolations());
        return new BackendPlan(parts, metrics, warnings);
    }

    private static double errorRatio(CellChoice cell) {
        return (cell.extraBits() + cell.missingBits()) / 4096.0;
    }

    private static boolean isSideWall(CellChoice cell, OccupancyMask mask) {
        return emptyAt(mask, cell.x() - 1, cell.y(), cell.z())
                || emptyAt(mask, cell.x() + 1, cell.y(), cell.z())
                || emptyAt(mask, cell.x(), cell.y(), cell.z() - 1)
                || emptyAt(mask, cell.x(), cell.y(), cell.z() + 1);
    }

    private static boolean emptyAt(OccupancyMask mask, int x, int y, int z) {
        return mask.blockMask(x, y, z).isEmpty();
    }

    private static FitMetrics metrics(List<CellChoice> cells, OccupancyMask mask) {
        long extra = 0;
        long missing = 0;
        long target = 0;
        int pruned = 0;
        for (CellChoice cell : cells) {
            if (cell.backend() == BackendType.NONE) {
                pruned++;
                continue;
            }
            extra += cell.extraBits();
            missing += cell.missingBits();
            target += mask.blockMask(cell.x(), cell.y(), cell.z()).count();
        }
        Map<Long, CellChoice> byCell = new HashMap<>();
        for (CellChoice cell : cells) {
            if (cell.backend().placesSomething() && cell.state() != null) {
                byCell.put(key(cell.x(), cell.y(), cell.z(), mask), cell);
            }
        }
        double maxLedge = 0;
        int violations = 0;
        for (CellChoice cell : cells) {
            if (!cell.backend().placesSomething() || cell.state() == null) {
                continue;
            }
            double ownTop = cell.y() + cell.topSurface();
            for (int[] offset : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                CellChoice neighbour = byCell.get(key(cell.x() + offset[0], cell.y(),
                        cell.z() + offset[1], mask));
                if (neighbour == null) {
                    continue;
                }
                double step = Math.abs(ownTop - (neighbour.y() + neighbour.topSurface()));
                if (step > 1.0e-9 && step <= 1.25) {
                    maxLedge = Math.max(maxLedge, step);
                    if (step <= 0.125 || step > 0.6) {
                        violations++;
                    }
                }
            }
        }
        return new FitMetrics(cells.size(), 0, 0, 0, pruned, extra, missing, target,
                maxLedge, violations / 2);
    }

    private static long key(int x, int y, int z, OccupancyMask mask) {
        return ((long) y * mask.blocksZ() + z) * mask.blocksX() + x;
    }
}
