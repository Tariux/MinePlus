package com.mineplus.infrastructure.core.multiblock.lifecycle;

import com.mineplus.infrastructure.core.events.MultiBlockSignal;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import org.bukkit.entity.Player;

public record MultiBlockLifecycleEvent(
        MultiBlockLifecycleEventType type,
        MultiBlockType definition,
        MultiBlockInstance instance,
        Player actor,
        MultiBlockSignal signal
) {
}
