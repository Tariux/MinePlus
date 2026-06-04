package com.mineplus.infrastructure.core.events;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record MultiBlockSignal(
        UUID sourceId,
        UUID targetId,
        String channel,
        Map<String, String> payload,
        int hopCount
) {

    public MultiBlockSignal {
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
