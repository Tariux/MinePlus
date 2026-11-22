package com.mineplus.infrastructure.render;

import com.mineplus.infrastructure.PluginContext;
import org.bukkit.World;
import org.bukkit.entity.Display;

/**
 * Rendering 2.0 diagnostics: a cheap, allocation-light census of everything the
 * render pipeline is currently keeping alive. Exposed on the developer API so
 * modules (and the status command) can watch budgets instead of guessing.
 */
public final class RenderDiagnostics {

    /** Point-in-time render census. */
    public record RenderSnapshot(int riggedMobs, int virtualBlockInstances, int displayEntities, int loadedWorlds) {

        @Override
        public String toString() {
            return "riggedMobs=" + riggedMobs
                    + " virtualBlocks=" + virtualBlockInstances
                    + " displayEntities=" + displayEntities
                    + " worlds=" + loadedWorlds;
        }
    }

    private RenderDiagnostics() {}

    public static RenderSnapshot snapshot(PluginContext context) {
        int displays = 0;
        int worlds = 0;
        for (World world : context.plugin().getServer().getWorlds()) {
            worlds++;
            for (org.bukkit.entity.Entity entity : world.getEntitiesByClass(Display.class)) {
                if (entity.isValid()) {
                    displays++;
                }
            }
        }
        return new RenderSnapshot(
                context.riggedMobCount(),
                context.virtualBlockManager().activeBlocksView().size(),
                displays,
                worlds);
    }
}
