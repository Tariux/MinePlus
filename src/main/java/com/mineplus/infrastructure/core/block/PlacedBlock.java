package com.mineplus.infrastructure.core.block;

import java.util.UUID;

/** One placed block: its definition, anchor cell and stored orientation. */
public record PlacedBlock(
        UUID instanceId,
        String definitionId,
        String model,
        String world,
        int x,
        int y,
        int z,
        String face,
        float qx,
        float qy,
        float qz,
        float qw,
        String owner,
        long placedAt
) {
}
