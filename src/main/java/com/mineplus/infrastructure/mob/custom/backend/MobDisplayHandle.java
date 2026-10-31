package com.mineplus.infrastructure.mob.custom.backend;

import com.mineplus.infrastructure.virtual.display.pool.PooledDisplay;
import org.bukkit.entity.ItemDisplay;

/**
 * Opaque handle to one spawned display of a mob rig — a plain Bukkit
 * {@code ItemDisplay} or a pooled transport display. The engine never touches
 * the entity directly; every operation goes through its
 * {@link MobDisplayBackend}.
 */
public record MobDisplayHandle(ItemDisplay plain, PooledDisplay pooled) {

    public boolean isValid() {
        if (plain != null) {
            return plain.isValid();
        }
        return pooled != null && pooled.isInUse();
    }
}
