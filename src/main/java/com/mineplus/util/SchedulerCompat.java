package com.mineplus.util;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The single Folia probe for the whole plugin and the scheduling helpers every
 * subsystem uses. On a regionized server (Folia), repeating and immediate
 * global work runs on the global region scheduler; everywhere else on the
 * classic Bukkit timer. Subsystems never branch on Folia themselves.
 *
 * <p>Every Folia call is reflective on purpose. The build compiles against the
 * Spigot stubs only, so a Paper/Folia-only method can never be linked into the
 * bytecode and then explode as a {@code NoSuchMethodError} on a plain Spigot
 * server (which is exactly how a Paper-only {@code getPitch()} once killed the
 * whole mob render tick). The reflective surface is resolved once, lazily, and
 * only on a server that actually reports itself as regionized.</p>
 *
 * <p>The helpers never throw on shutdown races — a missed task is a dropped
 * tick or a delayed prompt, never a stack trace in the caller's path. A
 * repeating task returns {@code null} when it could not start.</p>
 */
public final class SchedulerCompat {

    private static final boolean FOLIA = detectFolia();
    private static final FoliaBridge FOLIA_BRIDGE = FOLIA ? FoliaBridge.resolve() : null;

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
            FoliaBridge bridge = FOLIA_BRIDGE;
            if (bridge == null) {
                return null;
            }
            try {
                Object scheduled = bridge.globalRunAtFixedRate.invoke(null, plugin,
                        consumer(task), initialDelayTicks, periodTicks);
                return () -> bridge.cancel(scheduled);
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
            FoliaBridge bridge = FOLIA_BRIDGE;
            if (bridge == null) {
                return;
            }
            try {
                Object entityScheduler = bridge.entityGetScheduler.invoke(player);
                if (entityScheduler == null) {
                    return;
                }
                bridge.entityRunDelayed.invoke(entityScheduler, plugin,
                        consumer(action), null, delayTicks);
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
            FoliaBridge bridge = FOLIA_BRIDGE;
            if (bridge == null) {
                return;
            }
            try {
                bridge.globalRun.invoke(null, plugin, consumer(action));
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

    /**
     * The scheduler hands the task object to a {@code Consumer}; the parameter
     * type is erased, so a plain {@link Consumer} lambda is accepted.
     */
    @SuppressWarnings("unchecked")
    private static Consumer<Object> consumer(Runnable action) {
        return ignored -> action.run();
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }

    /**
     * The reflected Folia surface. Resolved once, and only when the server
     * actually reports itself as regionized, so a Spigot runtime never pays for
     * the lookups and never links a Paper type.
     */
    private static final class FoliaBridge {

        private final Method globalRunAtFixedRate;
        private final Method globalRun;
        private final Method entityGetScheduler;
        private final Method entityRunDelayed;
        private final Method taskCancel;

        private FoliaBridge(Method globalRunAtFixedRate, Method globalRun,
                Method entityGetScheduler, Method entityRunDelayed, Method taskCancel) {
            this.globalRunAtFixedRate = globalRunAtFixedRate;
            this.globalRun = globalRun;
            this.entityGetScheduler = entityGetScheduler;
            this.entityRunDelayed = entityRunDelayed;
            this.taskCancel = taskCancel;
        }

        private static FoliaBridge resolve() {
            try {
                Class<?> globalScheduler = Class.forName(
                        "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
                Class<?> entityScheduler = Class.forName(
                        "io.papermc.paper.threadedregions.scheduler.EntityScheduler");
                Class<?> scheduledTask = Class.forName(
                        "io.papermc.paper.threadedregions.scheduler.ScheduledTask");

                // Bukkit.getGlobalRegionScheduler() is static: invoked with a null
                // receiver so no instance of Bukkit is ever needed.
                Method getGlobal = Bukkit.class.getMethod("getGlobalRegionScheduler");
                Object global = getGlobal.invoke(null);
                if (global == null) {
                    return null;
                }

                Method runAtFixedRate = globalScheduler.getMethod("runAtFixedRate",
                        Plugin.class, Consumer.class, long.class, long.class);
                Method run = globalScheduler.getMethod("run", Plugin.class, Consumer.class);
                Method getScheduler = Entity.class.getMethod("getScheduler");
                Method runDelayed = entityScheduler.getMethod("runDelayed",
                        Plugin.class, Consumer.class, Runnable.class, long.class);
                Method cancel = scheduledTask.getMethod("cancel");

                return new FoliaBridge(runAtFixedRate, run, getScheduler, runDelayed, cancel);
            } catch (Throwable unsupported) {
                return null;
            }
        }

        private void cancel(Object scheduled) {
            if (scheduled == null) {
                return;
            }
            try {
                taskCancel.invoke(scheduled);
            } catch (Throwable ignored) {
                // A task that already finished cannot be cancelled; that is fine.
            }
        }
    }
}
