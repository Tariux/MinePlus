package com.mineplus.create.electric;

import com.mineplus.create.machines.MachineSpecs;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure-Java dynamo buffer bank (no Bukkit, unit-testable): per-position FE
 * buffers charged from network RPM and drained by gadgets/machines.
 */
public final class DynamoBufferBank {

    private final Map<String, Integer> buffers = new HashMap<>();

    /** Registers a dynamo position. */
    public void register(String key) {
        buffers.putIfAbsent(key, 0);
    }

    public void unregister(String key) {
        buffers.remove(key);
    }

    /** One generation step: charge every registered dynamo from its network RPM. */
    public void generateTick(java.util.function.ToDoubleFunction<String> networkRpm) {
        for (String key : buffers.keySet()) {
            int generated = MachineSpecs.dynamoFePerTick((float) networkRpm.applyAsDouble(key));
            int buffered = buffers.merge(key, generated, Integer::sum);
            if (buffered > MachineSpecs.DYNAMO_BUFFER_FE) {
                buffers.put(key, MachineSpecs.DYNAMO_BUFFER_FE);
            }
        }
    }

    /** Stored FE at a position. */
    public int buffer(String key) {
        return buffers.getOrDefault(key, 0);
    }

    /** Draws FE; returns the amount actually drawn (may be less than requested). */
    public int draw(String key, int amount) {
        int stored = buffers.getOrDefault(key, 0);
        int taken = Math.min(stored, Math.max(0, amount));
        buffers.put(key, stored - taken);
        return taken;
    }
}
