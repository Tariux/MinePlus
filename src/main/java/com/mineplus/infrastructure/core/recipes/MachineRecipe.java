package com.mineplus.infrastructure.core.recipes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record MachineRecipe(
        String id,
        String machineTypeId,
        int minLevel,
        int craftTimeTicks,
        Map<String, Integer> input,
        Map<String, Integer> output
) {

    public MachineRecipe {
        input = Collections.unmodifiableMap(new LinkedHashMap<>(input));
        output = Collections.unmodifiableMap(new LinkedHashMap<>(output));
    }
}
