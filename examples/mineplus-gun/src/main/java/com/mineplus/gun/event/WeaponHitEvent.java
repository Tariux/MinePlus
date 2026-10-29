package com.mineplus.gun.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the main thread when a fired round lands on a living entity, after
 * the ballistics model has resolved damage for falloff, penetration and the
 * headshot bonus but <em>before</em> that damage is applied.
 *
 * <p>Cancelling it withholds the damage of this round (the round is otherwise
 * spent). That is the seam for friendly-fire rules, invulnerability windows and
 * per-entity damage scaling. {@link #damage()} is what the engine intends to
 * apply; a listener that wants to rescale cannot change it here — cancel and
 * apply its own amount through the Core's damage transaction seam.</p>
 */
public final class WeaponHitEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player shooter;
    private final LivingEntity victim;
    private final double damage;
    private final double travelled;
    private final boolean headshot;
    private boolean cancelled;

    public WeaponHitEvent(Player shooter, LivingEntity victim, double damage,
                          double travelled, boolean headshot) {
        this.shooter = shooter;
        this.victim = victim;
        this.damage = damage;
        this.travelled = travelled;
        this.headshot = headshot;
    }

    public Player shooter() {
        return shooter;
    }

    public LivingEntity victim() {
        return victim;
    }

    /** Damage the engine will apply if the event is not cancelled. */
    public double damage() {
        return damage;
    }

    /** Distance the round had flown when it landed, in blocks. */
    public double travelled() {
        return travelled;
    }

    public boolean headshot() {
        return headshot;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /** Calls the event; {@code true} when the damage may be applied. */
    public static boolean allow(Player shooter, LivingEntity victim, double damage,
                                double travelled, boolean headshot) {
        WeaponHitEvent event = new WeaponHitEvent(shooter, victim, damage, travelled, headshot);
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }
}
