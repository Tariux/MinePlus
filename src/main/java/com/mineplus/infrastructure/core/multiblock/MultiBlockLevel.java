package com.mineplus.infrastructure.core.multiblock;

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
        List<String> animations
) {

    public MultiBlockLevel {
        upgradeCost = Collections.unmodifiableMap(new LinkedHashMap<>(upgradeCost));
        guiOptions = Collections.unmodifiableMap(new LinkedHashMap<>(guiOptions));
        animations = animations == null ? List.of() : List.copyOf(animations);
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
}
