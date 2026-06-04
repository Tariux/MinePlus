package com.mineplus.infrastructure.core.multiblock;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record MultiBlockLevel(
        int level,
        String modelPath,
        double speedMultiplier,
        double durability,
        Map<String, Integer> upgradeCost,
        Map<String, String> guiOptions
) {

    public MultiBlockLevel {
        upgradeCost = Collections.unmodifiableMap(new LinkedHashMap<>(upgradeCost));
        guiOptions = Collections.unmodifiableMap(new LinkedHashMap<>(guiOptions));
    }
}
