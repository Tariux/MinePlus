package com.mineplus.infrastructure.mob.custom.backend;

import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.joml.Matrix4f;

/**
 * The default backend: real {@code ItemDisplay} entities spawned into the
 * world, the same display model the pack bone rigs use. Per-viewer visibility
 * is {@code Player#hideEntity}/{@code showEntity}; display entities are
 * never persistent, never interactable and tagged for ghost cleanup.
 */
public final class EntityMobBackend implements MobDisplayBackend {

    private final Plugin plugin;
    private final String tagPrefix;

    public EntityMobBackend(Plugin plugin, String tagPrefix) {
        this.plugin = plugin;
        this.tagPrefix = tagPrefix == null || tagPrefix.isBlank() ? "mineplus_mobrig:" : tagPrefix;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public MobDisplayHandle spawn(UUID sessionId, Location at, ItemStack stack,
                                  ItemDisplay.ItemDisplayTransform transform,
                                  int interpolationTicks, int teleportTicks) {
        World world = at.getWorld();
        if (world == null) {
            return null;
        }
        ItemDisplay display = world.spawn(at.clone(), ItemDisplay.class, spawned -> {
            if (stack != null) {
                spawned.setItemStack(stack);
            }
            spawned.setItemDisplayTransform(transform == null
                    ? ItemDisplay.ItemDisplayTransform.NONE : transform);
            spawned.setPersistent(false);
            spawned.setInvulnerable(true);
            spawned.setSilent(true);
            spawned.setShadowRadius(0f);
            spawned.setTeleportDuration(Math.max(1, teleportTicks));
            spawned.setInterpolationDuration(Math.max(1, interpolationTicks));
            spawned.setInterpolationDelay(0);
            spawned.addScoreboardTag(tagPrefix + sessionId);
        });
        return display == null ? null : new MobDisplayHandle(display, null);
    }

    @Override
    public void finishSession(UUID sessionId, Location origin) {
        // Plain entities are visible the moment they spawn.
    }

    @Override
    public void pose(MobDisplayHandle handle, Location at, Matrix4f matrix, int interpolationTicks) {
        if (handle == null || handle.plain() == null || !handle.plain().isValid()) {
            return;
        }
        handle.plain().teleport(at);
        handle.plain().setTransformationMatrix(matrix);
    }

    @Override
    public void setSessionViewerHidden(UUID sessionId, List<MobDisplayHandle> handles,
                                       UUID viewerId, boolean hidden) {
        Player viewer = Bukkit.getPlayer(viewerId);
        if (viewer == null) {
            return;
        }
        for (MobDisplayHandle handle : handles) {
            if (handle.plain() == null || !handle.plain().isValid()) {
                continue;
            }
            if (hidden) {
                viewer.hideEntity(plugin, handle.plain());
            } else {
                viewer.showEntity(plugin, handle.plain());
            }
        }
    }

    @Override
    public void despawn(MobDisplayHandle handle) {
        if (handle != null && handle.plain() != null && handle.plain().isValid()) {
            handle.plain().remove();
        }
    }

    @Override
    public void destroySession(UUID sessionId) {
        // Plain handles are removed individually.
    }

    /** Removes displays tagged for sessions this backend no longer knows. */
    public int sweepGhosts(java.util.function.Predicate<UUID> knownSession) {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(ItemDisplay.class)) {
                String rigId = tagOf(entity);
                if (rigId == null) {
                    continue;
                }
                try {
                    UUID id = UUID.fromString(rigId);
                    if (!knownSession.test(id)) {
                        entity.remove();
                        removed++;
                    }
                } catch (IllegalArgumentException malformed) {
                    entity.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    private String tagOf(Entity entity) {
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith(tagPrefix)) {
                return tag.substring(tagPrefix.length());
            }
        }
        return null;
    }
}
