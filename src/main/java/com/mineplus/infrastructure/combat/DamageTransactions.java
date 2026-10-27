package com.mineplus.infrastructure.combat;

import com.mineplus.util.DebugLogger;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * The arbitration primitive for server-side damage that a plugin applies
 * deliberately.
 *
 * <p>A gameplay module that owns a weapon almost always installs an
 * {@link EntityDamageByEntityEvent} listener to suppress the vanilla melee
 * swing of the item it handed out — "cancel the punch, we do the damage
 * ourselves". That suppression is unconditional, so when the module later calls
 * {@code victim.damage(amount, shooter)} to apply its own computed hit, the
 * event its listener is watching fires for <em>that</em> call and the module
 * cancels its own damage. The shot consumes a round, plays its report, and
 * lands for nothing — a bug that is invisible in review because each half of
 * the pair is individually correct.
 *
 * <p>The guard fixes it at the core: applying damage inside {@link #apply}
 * marks the current thread as being mid-transaction, and the suppression
 * listener asks {@link #inProgress()} before cancelling. Nested applications
 * are counted, so a transaction that itself triggers engine damage still reads
 * correctly on exit.
 *
 * <p>Transactions are thread-local and every supported server dispatches
 * {@code damage()} synchronously on the calling thread, so the window is
 * exactly the call: no expiry bookkeeping, no cross-tick leakage, and the
 * counter is restored in a finally even when a listener throws.
 */
public final class DamageTransactions {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private DamageTransactions() {
    }

    /**
     * Applies {@code amount} damage to {@code victim} as {@code attacker},
     * protected from a self-cancelling suppression listener.
     *
     * @return {@code true} when the damage call completed (not whether another
     *         plugin later reduced or blocked it)
     */
    public static boolean apply(LivingEntity victim, double amount, Entity attacker) {
        if (victim == null || victim.isDead() || !victim.isValid() || amount <= 0.0) {
            return false;
        }
        int depth = DEPTH.get();
        DEPTH.set(depth + 1);
        try {
            if (attacker != null && attacker.isValid()) {
                victim.damage(amount, attacker);
            } else {
                victim.damage(amount);
            }
            return true;
        } catch (Throwable failure) {
            // A hostile event listener must not unwind the caller's simulation
            // step: the hit does not land, and the rest of the frame survives.
            DebugLogger.warning("[combat] programmatic damage on " + victim.getType()
                    + " failed: " + failure);
            return false;
        } finally {
            DEPTH.set(depth);
        }
    }

    /** Runs {@code action} inside a transaction, for damage triggered indirectly. */
    public static void run(Runnable action) {
        int depth = DEPTH.get();
        DEPTH.set(depth + 1);
        try {
            action.run();
        } finally {
            DEPTH.set(depth);
        }
    }

    /** {@code true} while this thread is applying plugin-owned damage. */
    public static boolean inProgress() {
        return DEPTH.get() > 0;
    }

    /**
     * The check a suppression listener makes before cancelling: {@code true}
     * means this event is the deliverable of plugin-computed damage (a bullet,
     * a spell, a machine) and must be left alone.
     */
    public static boolean isTransaction(EntityDamageByEntityEvent event) {
        return inProgress();
    }
}
