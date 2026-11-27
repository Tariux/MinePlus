package com.mineplus.infrastructure.collision;

import com.mineplus.infrastructure.collision.mask.RasterMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The {@code COLLISION} block of {@code settings.mp.yml}, resolved once at
 * startup and on reload.
 *
 * <p>Every switch has a working default; an operator only writes the keys they
 * want to change. Old keys from the pre-atlas configuration are read as
 * fallbacks and reported through {@link #deprecatedKeys()}.
 *
 * @param enabled              master switch; off keeps legacy behaviour
 * @param resolution           sub-voxels per block edge (8, 16 or 32)
 * @param rasterMode           conservative or centred rasterisation
 * @param defaultProfile       name of the fit profile used when a model has none
 * @param maxBlocks            real blocks per model
 * @param maxEntities          entities per model
 * @param allowGhostBlocks     permit packet ghosts
 * @param allowShulkerEntities permit scaled shulkers
 * @param allowVisibleBlocks   permit block states the resource pack does not hide
 * @param allowInteraction     permit interaction entities
 * @param allowPassableOutline permit outline-only passable parts
 * @param allowPlates          permit boat/minecart plates
 * @param minecartPlates      prefer minecarts over boats when plates are on
 * @param shulkerScale         base scale for shulker boxes
 * @param partsPerTick         apply budget per tick
 * @param cacheFits            reuse fitted results between restarts
 * @param validateAtStartup    fit every model at enable and report failures
 * @param extraMaterials       additional candidate materials
 * @param invisibleStates      block states the resource pack hides
 * @param excludedMaterials    materials never to use
 * @param debug                log and render fit diagnostics
 */
public record CollisionSettings(
        boolean enabled,
        int resolution,
        RasterMode rasterMode,
        String defaultProfile,
        int maxBlocks,
        int maxEntities,
        boolean allowGhostBlocks,
        boolean allowShulkerEntities,
        boolean allowVisibleBlocks,
        boolean allowInteraction,
        boolean allowPassableOutline,
        boolean allowPlates,
        boolean minecartPlates,
        double shulkerScale,
        int partsPerTick,
        boolean cacheFits,
        boolean validateAtStartup,
        List<String> extraMaterials,
        List<String> invisibleStates,
        Set<String> excludedMaterials,
        List<String> deprecatedKeys) {

    public CollisionSettings {
        resolution = Math.max(8, Math.min(32, resolution));
        maxBlocks = Math.max(1, maxBlocks);
        maxEntities = Math.max(0, maxEntities);
        shulkerScale = Math.max(0.0625, Math.min(16.0, shulkerScale));
        partsPerTick = Math.max(16, partsPerTick);
        defaultProfile = defaultProfile == null || defaultProfile.isBlank()
                ? "BALANCED" : defaultProfile.trim().toUpperCase(Locale.ROOT);
        extraMaterials = List.copyOf(extraMaterials == null ? List.of() : extraMaterials);
        invisibleStates = List.copyOf(invisibleStates == null ? List.of()
                : invisibleStates);
        excludedMaterials = Set.copyOf(excludedMaterials == null ? Set.of()
                : excludedMaterials);
        deprecatedKeys = List.copyOf(deprecatedKeys == null ? List.of() : deprecatedKeys);
    }

    public static CollisionSettings defaults() {
        return new CollisionSettings(true, 16, RasterMode.CONSERVATIVE, "BALANCED", 256, 64,
                true, true, false, true, true, false, false, 1.0, 256, true, false,
                List.of(), List.of(), Set.of(), List.of());
    }

    /** Applies the fitter option limits this configuration implies. */
    public com.mineplus.infrastructure.collision.fit.FitterOptions toFitterOptions() {
        return new com.mineplus.infrastructure.collision.fit.FitterOptions(maxBlocks, maxEntities,
                allowGhostBlocks, allowShulkerEntities, allowVisibleBlocks, allowInteraction,
                allowPassableOutline, allowPlates, true,
                com.mineplus.infrastructure.collision.fit.FitterOptions.RotationSnap.QUARTER_TURNS);
    }

    /** Warning lines to log once on startup. */
    public List<String> deprecationWarnings() {
        List<String> lines = new ArrayList<>();
        for (String key : deprecatedKeys) {
            lines.add("collision: '" + key + "' is deprecated; map it to the COLLISION section"
                    + " (see docs/collision/migration.md)");
        }
        return lines;
    }
}
