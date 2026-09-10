package com.mineplus.infrastructure.virtual.texel;

import com.mineplus.infrastructure.virtual.CubeFace;
import com.mineplus.infrastructure.virtual.ModelMeta;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole-model texel bake output: one {@link TexelSurfacePlan} map per cube (parallel
 * to {@code VirtualModel.cubes()}; faces without plans render through the existing
 * UV-alignment tiers) plus the diagnostics surfaced by {@code /mineplus model info}.
 *
 * <p>Budgets are applied here, at bake time, in face emission order — faces earlier
 * in emission order keep their texel detail deterministically. Over-budget faces fall
 * back to the legacy per-face rendering for that face only. Each such face also gets a
 * <i>fallback tint</i> in {@link #cubeFallbackTints()}: the cube's dominant baked
 * palette entry, applied by the emitter when the face's own texture resolves to no
 * vanilla material — a partially baked model then degrades to a flat local tone
 * instead of concrete white.</p>
 */
public record TexelBakeResult(
        boolean enabled,
        ModelMeta.TexelMode mode,
        ModelMeta.TexelDetail detail,
        List<Map<CubeFace, TexelSurfacePlan>> cubePlans,
        int facesBaked,
        int facesTotal,
        int totalPlates,
        int maxPlatesOnFace,
        int faceBudgetFallbacks,
        int instanceBudgetFallbacks,
        long bakeTimeNanos,
        Map<String, Integer> gridHistogram,
        Map<Integer, Integer> paletteUsage,
        int effectiveMaxPlatesPerFace,
        int effectiveMaxPlatesPerInstance,
        int occludedCells,
        List<Map<CubeFace, Integer>> cubeFallbackTints
) {

    public TexelBakeResult {
        cubePlans = cubePlans == null ? List.of() : List.copyOf(cubePlans);
        gridHistogram = gridHistogram == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(gridHistogram));
        paletteUsage = paletteUsage == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(paletteUsage));
        cubeFallbackTints = cubeFallbackTints == null ? List.of() : List.copyOf(cubeFallbackTints);
    }

    /** Faces that fell back due to either budget guard. */
    public int budgetFallbackFaces() {
        return faceBudgetFallbacks + instanceBudgetFallbacks;
    }

    /** Average merged plates per baked face (0 when nothing baked). */
    public double averagePlatesPerFace() {
        return facesBaked == 0 ? 0.0 : (double) totalPlates / facesBaked;
    }

    /** Budget-fallback tints of one cube, or {@code null} when the cube has none. */
    public Map<CubeFace, Integer> fallbackTints(int cubeIndex) {
        if (cubeIndex < 0 || cubeIndex >= cubeFallbackTints.size()) {
            return null;
        }
        return cubeFallbackTints.get(cubeIndex);
    }

    /** Empty disabled result for a model with {@code cubeCount} cubes. */
    public static TexelBakeResult disabled(
            ModelMeta.TexelMode mode,
            ModelMeta.TexelDetail detail,
            TexelBakingSettings settings,
            int cubeCount
    ) {
        List<Map<CubeFace, TexelSurfacePlan>> empty = new ArrayList<>(Math.max(0, cubeCount));
        for (int i = 0; i < Math.max(0, cubeCount); i++) {
            empty.add(Map.of());
        }
        return new TexelBakeResult(false, mode, detail, empty,
                0, 0, 0, 0, 0, 0, 0L, Map.of(), Map.of(),
                settings == null ? 0 : settings.maxPlatesPerFace(),
                settings == null ? 0 : settings.maxPlatesPerInstance(),
                0,
                List.of());
    }
}
