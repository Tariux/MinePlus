package com.mineplus.pack;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Folia-aware scheduling for the pack subsystem, following the project's
 * probing pattern (see {@code ModelAnimationManager}): on regionized servers,
 * per-player actions run on the player's own region scheduler; otherwise the
 * standard Bukkit scheduler. No scattered Folia conditionals.
 */
final class PackScheduling {

    private static final boolean FOLIA = detectFolia();

    private PackScheduling() {
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }

    /** Runs a player-scoped action after {@code delayTicks} on the right scheduler. */
    static void schedulePlayer(Plugin plugin, Player player, long delayTicks, Runnable action) {
        if (FOLIA) {
            try {
                player.getScheduler().runDelayed(plugin, task -> action.run(), null, delayTicks);
            } catch (Throwable ignored) {
                // Folia detected but the entity scheduler is unavailable (shutdown
                // race); dropping a delayed prompt is safe.
            }
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, action, delayTicks);
    }

    /**
     * Runs a server-scoped action on the main thread (or the global region on
     * Folia) as soon as possible — used for post-compile pushes to online
     * players. Shutdown races are swallowed: a missed auto-push only delays
     * delivery to the player's next join prompt.
     */
    static void scheduleNow(Plugin plugin, Runnable action) {
        if (FOLIA) {
            try {
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> action.run());
            } catch (Throwable ignored) {
                // Shutdown race; a missed auto-push is safe.
            }
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, action);
        } catch (RuntimeException shutdownRace) {
            // Plugin disabling while a compile finishes (IllegalPluginAccessException
            // et al.); the join prompt covers the player next session.
        }
    }
}
