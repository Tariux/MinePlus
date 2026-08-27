package com.mineplus.infrastructure.core.multiblock.lifecycle;

import com.mineplus.infrastructure.core.multiblock.EntityStatus;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import org.bukkit.Bukkit;
import org.bukkit.World;

public final class EntityStateMachine {

    private EntityStateMachine() {
    }

    public static boolean canTransition(EntityStatus from, EntityStatus to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        if (to == EntityStatus.REMOVED) {
            return true;
        }
        if (to == EntityStatus.CORRUPTED) {
            return from == EntityStatus.CREATED
                    || from == EntityStatus.PLACED
                    || from == EntityStatus.ACTIVE;
        }
        return switch (from) {
            case CREATED -> to == EntityStatus.PLACED;
            case PLACED -> to == EntityStatus.ACTIVE;
            case ACTIVE -> to == EntityStatus.BROKEN;
            case BROKEN -> to == EntityStatus.REMOVED;
            case CORRUPTED -> to == EntityStatus.REMOVED;
            case REMOVED -> false;
        };
    }

    public static boolean transition(MultiBlockInstance instance, EntityStatus newStatus) {
        if (instance == null || newStatus == null) {
            return false;
        }
        EntityStatus from = instance.status();
        if (from == null) {
            from = EntityStatus.CREATED;
        }
        if (!canTransition(from, newStatus)) {
            return false;
        }
        instance.setStatus(newStatus);
        return true;
    }

    public static boolean validateInstance(MultiBlockInstance instance, MultiBlockRegistry registry) {
        if (instance == null || registry == null) {
            return false;
        }
        MultiBlockType type = registry.getType(instance.typeId());
        if (type == null) {
            return false;
        }
        return validateWorldLoaded(instance);
    }

    public static boolean validateWorldLoaded(MultiBlockInstance instance) {
        if (instance == null) {
            return false;
        }
        World world = Bukkit.getWorld(instance.coordinate().worldName());
        return world != null;
    }
}
