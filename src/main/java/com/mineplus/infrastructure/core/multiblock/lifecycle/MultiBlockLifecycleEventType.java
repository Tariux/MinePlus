package com.mineplus.infrastructure.core.multiblock.lifecycle;

public enum MultiBlockLifecycleEventType {
    CREATE,
    CRAFT,
    PLACE,
    ACTIVATE,
    USAGE,
    UPGRADE,
    INTERACT,
    REMOVE,
    DESTRUCTION,
    MODEL_RELOAD,
    TICK,
    /** A timed crafting process was started on an instance (see MachineProcessManager). */
    PROCESS_START,
    /** A timed crafting process finished on an instance (see MachineProcessManager). */
    PROCESS_COMPLETE
}
