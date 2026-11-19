package com.mineplus.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The single Folia probe for the whole plugin and the scheduling helpers every
 * subsystem uses. On a regionized server (Folia), repeating and immediate
 * global work runs on the global region scheduler; everywhere else on the
 * classic Bukkit timer. Subsystems never branch on Folia themselves.
 *
 * <p>The helpers never throw on shutdown races — a missed task is a dropped
 * tick or a delayed prompt, never a stack trace in the caller's path. A
 * repeating task returns {@code null} when it could not start.</p>
 */
public final class SchedulerCompat {

    private static final boolean FOLIA = detectFolia();

    /** A running scheduled task, cancellable under either scheduler. */
    public interface Handle {
        void cancel();
    }

    private SchedulerCompat() {
    }

    /** True on a regionized (Folia) server. Cached; never touches Bukkit. */
    public static boolean isFolia() {
        return FOLIA;
    }

    /**
     * Repeating task on the global region (Folia) or the main-thread timer.
     * Returns {@code null} when the runtime refused the task (shutdown race).
     */
    public static Handle repeat(Plugin plugin, long initialDelayTicks, long periodTicks, Runnable task) {
        if (FOLIA) {
            try {
                ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler()
                        .runAtFixedRate(plugin, unused -> task.run(), initialDelayTicks, periodTicks);
                return scheduled::cancel;
            } catch (Throwable shutdownRace) {
                return null;
            }
        }
        try {
            BukkitTask bukkit = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, task, initialDelayTicks, periodTicks);
            return bukkit::cancel;
        } catch (Throwable shutdownRace) {
            return null;
        }
    }

    /**
     * Repeating task that always runs on the classic main-thread timer, even on
     * Folia — for sweeps that touch cross-region state and are best-effort
     * there anyway.
     */
    public static Handle repeatOnMainScheduler(Plugin plugin, long initialDelayTicks, long periodTicks, Runnable task) {
        try {
            BukkitTask bukkit = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, task, initialDelayTicks, periodTicks);
            return bukkit::cancel;
        } catch (Throwable shutdownRace) {
            return null;
        }
    }

    /** Runs a player-scoped action after {@code delayTicks} on the right scheduler. */
    public static void runForPlayer(Plugin plugin, Player player, long delayTicks, Runnable action) {
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
     * Folia) as soon as possible.
     */
    public static void runNow(Plugin plugin, Runnable action) {
        if (FOLIA) {
            try {
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> action.run());
            } catch (Throwable ignored) {
                // Shutdown race; a missed run is safe.
            }
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, action);
        } catch (RuntimeException shutdownRace) {
            // Plugin disabling while an async job finishes.
        }
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}
