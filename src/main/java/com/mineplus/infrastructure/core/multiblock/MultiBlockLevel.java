package com.mineplus.infrastructure.core.multiblock;

import com.mineplus.infrastructure.render.RenderBackend;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MultiBlockLevel(
        int level,
        String modelPath,
        double speedMultiplier,
        double durability,
        Map<String, Integer> upgradeCost,
        Map<String, String> guiOptions,
        List<String> animations,
        RenderBackend renderBackend
) {

    public MultiBlockLevel {
        upgradeCost = Collections.unmodifiableMap(new LinkedHashMap<>(upgradeCost));
        guiOptions = Collections.unmodifiableMap(new LinkedHashMap<>(guiOptions));
        animations = animations == null ? List.of() : List.copyOf(animations);
        renderBackend = renderBackend == null ? RenderBackend.VIRTUAL : renderBackend;
    }

    public MultiBlockLevel(
            int level,
            String modelPath,
            double speedMultiplier,
            double durability,
            Map<String, Integer> upgradeCost,
            Map<String, String> guiOptions
    ) {
        this(level, modelPath, speedMultiplier, durability, upgradeCost, guiOptions, List.of());
    }

    public MultiBlockLevel(
            int level,
            String modelPath,
            double speedMultiplier,
            double durability,
            Map<String, Integer> upgradeCost,
            Map<String, String> guiOptions,
            List<String> animations
    ) {
        this(level, modelPath, speedMultiplier, durability, upgradeCost, guiOptions, animations, RenderBackend.VIRTUAL);
    }

    /**
     * Rendering backend for this level, with subsystem availability applied:
     * when the pack subsystem is not running, pack backends degrade to the
     * virtual engine in this one place.
     *
     * @param packRendererActive whether the pack renderer is available
     */
    public RenderBackend effectiveBackend(boolean packRendererActive) {
        if (!renderBackend.includesPack() || packRendererActive) {
            return renderBackend;
        }
        return RenderBackend.VIRTUAL;
    }
}
