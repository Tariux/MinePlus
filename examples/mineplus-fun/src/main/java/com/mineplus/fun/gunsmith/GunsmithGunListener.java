package com.mineplus.fun.gunsmith;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * The gun firing runtime — a pure hitscan that never launches a real
 * projectile. Every vanilla launch path is cancelled at
 * {@link EventPriority#LOWEST} before the server spawns anything (no arrow
 * entity, no consumption, no vanilla launch sound), and the shot itself is
 * one ray trace from the gunner's eye along the crosshair: entities first
 * (pixel-precise hitboxes), blocks second (range cap). An entity hit
 * damages the victim and flashes impact particles at the exact spot; a wall
 * hit chips a puff where the bullet strikes. No movement or teleport is
 * ever applied to the shooter — no desync, no lag compensation needed.
 *
 * <p>Firing model:
 * <ul>
 *   <li><b>Pistol (bow)</b>: draw and release, vanilla-style. Taps below
 *       {@link #MIN_DRAW_FORCE} are dead triggers (dry click) — no wasted
 *       arrows, no half shots.</li>
 *   <li><b>Rifle (crossbow)</b>: the vanilla charge itself drives the
 *       rifle's feel — hold right-click to load (the client plays the
 *       pack's loading frames), and the loaded release fires the hitscan.
 *       Because the state lives in the vanilla charge, it can never
 *       desync from what the player sees, and a re-draw after an
 *       interrupted shot simply re-charges.</li>
 * </ul>
 *
 * <p>Ammunition is a PDC round counter on the gun (see {@link GunsmithKeys#PDC_AMMO});
 * each shot spends one round, an empty gun only dry-clicks, and a fresh
 * magazine comes from {@code /gunsmith give}. A short per-player cooldown
 * rejects double events from a single click burst. The vanilla arrow a
 * bow/crossbow draw requires is never spent: the launch event is always
 * cancelled, and the crossbow's charged projectile is drained after the
 * shot so re-drawing stays consistent.</p>
 */
final class GunsmithGunListener implements Listener {

    /** Range of one shot in blocks. */
    private static final double RANGE = 60.0D;

    /** Draw force below which a pistol shot is a dead trigger. */
    private static final float MIN_DRAW_FORCE = 0.12F;

    /** Pistol damage per round. */
    private static final double PISTOL_DAMAGE = 5.0D;

    /** Rifle damage per round. */
    private static final double RIFLE_DAMAGE = 9.0D;

    /** Minimum milliseconds between shots by the same player. */
    private static final long FIRE_COOLDOWN_MILLIS = 150L;

    /** Tracer particle step in blocks (one puff per step). */
    private static final double TRACER_STEP = 1.6D;
    private static final double TRACER_SPREAD = 0.03D;

    private static final double IMPACT_SPREAD = 0.08D;

    private final GunsmithRig rig;
    private final Map<UUID, Long> nextShotAt = new ConcurrentHashMap<>();

    GunsmithGunListener(GunsmithRig rig) {
        this.rig = rig;
    }

    /**
     * Both guns fire from the vanilla bow/crossbow launch event: the pistol
     * from a released draw, the rifle from a released charge. Cancelling at
     * LOWEST priority stops the arrow spawn, the item consumption and the
     * vanilla sound before any of it happens, then the hitscan replaces it.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack gun = event.getBow();
        if (!rig.isGun(gun)) {
            return;
        }
        event.setCancelled(true);

        boolean rifle = rig.isRifle(gun);
        // A bow draw below the dead-trigger threshold is a slack lanyard, not
        // a shot; an uncharged crossbow release (not fully loaded) clicks too.
        if (!rifle && event.getForce() < MIN_DRAW_FORCE) {
            dryFire(player, gun);
            return;
        }
        // The cancelled launch leaves the crossbow's charged projectile in
        // its meta — drain it so the next shot needs a fresh vanilla charge
        // (otherwise every later right-click fires instantly with no frames),
        // and refund the arrow the vanilla load consumed: the rig promises
        // the shot itself never really spends ammunition arrows.
        if (rifle && gun.getItemMeta() instanceof CrossbowMeta meta && meta.hasChargedProjectiles()) {
            meta.setChargedProjectiles(null);
            gun.setItemMeta(meta);
            if (player.getGameMode() != GameMode.CREATIVE) {
                player.getInventory().addItem(new ItemStack(Material.ARROW)).values()
                        .forEach(overflow -> player.getWorld().dropItemNaturally(player.getLocation(), overflow));
            }
        }

        fire(player, gun, rifle ? RIFLE_DAMAGE : PISTOL_DAMAGE, rifle ? 0.05F : 0.15F);
    }

    /** Spills nothing on quit except the cooldown bookkeeping. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        nextShotAt.remove(event.getPlayer().getUniqueId());
    }

    /** Drops all cooldown bookkeeping (feature teardown). */
    void clearSessions() {
        nextShotAt.clear();
    }

    // ------------------------------------------------------------------
    // The shot
    // ------------------------------------------------------------------

    private void fire(Player shooter, ItemStack gun, double damage, float spreadDegrees) {
        long now = System.currentTimeMillis();
        Long gate = nextShotAt.get(shooter.getUniqueId());
        if (gate != null && now < gate) {
            return; // double event from one click burst — swallow it
        }
        if (!rig.spendRound(gun)) {
            dryFire(shooter, gun);
            return;
        }
        nextShotAt.put(shooter.getUniqueId(), now + FIRE_COOLDOWN_MILLIS);

        World world = shooter.getWorld();
        Location eye = shooter.getEyeLocation();
        Vector direction = eye.getDirection();
        if (spreadDegrees > 0.0F) {
            direction = applySpread(direction, spreadDegrees);
        }

        // Entities first (precise hitboxes, shooter excluded); blocks second
        // as the range cap. Whichever the ray meets closer is the impact.
        RayTraceResult entityHit = world.rayTraceEntities(
                eye, direction, RANGE, 0.0D,
                entity -> entity instanceof Damageable && !entity.getUniqueId().equals(shooter.getUniqueId()));
        RayTraceResult blockHit = world.rayTraceBlocks(eye, direction, RANGE, FluidCollisionMode.NEVER, true);

        Location impact;
        LivingEntity victim = null;
        double blockDistance = blockHit == null
                ? Double.MAX_VALUE
                : blockHit.getHitPosition().distance(eye.toVector());
        if (entityHit != null && entityHit.getHitPosition().distance(eye.toVector()) <= blockDistance) {
            impact = entityHit.getHitPosition().toLocation(world);
            if (entityHit.getHitEntity() instanceof LivingEntity living) {
                victim = living;
            }
        } else if (blockHit != null) {
            impact = blockHit.getHitPosition().toLocation(world);
        } else {
            impact = eye.clone().add(direction.clone().multiply(RANGE));
        }

        drawTracer(world, eye, impact);
        spawnImpact(world, impact);
        if (victim != null) {
            victim.damage(damage, shooter);
            world.spawnParticle(Particle.DAMAGE_INDICATOR,
                    victim.getLocation().add(0.0D, victim.getHeight() * 0.5D, 0.0D), 4,
                    IMPACT_SPREAD, IMPACT_SPREAD, IMPACT_SPREAD, 0.0D);
        }

        // The gunshot for everyone in earshot; volume falls off with
        // distance from the shooter (vanilla location sound behaviour).
        world.playSound(shooter.getLocation(), GunsmithKeys.FIRE_SOUND, 1.2F, 1.0F);
        // Subtle mechanical bolt cycle for the shooter only.
        shooter.playSound(shooter.getLocation(), Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 0.5F, 1.8F);
    }

    private void dryFire(Player shooter, ItemStack gun) {
        shooter.playSound(shooter.getLocation(), Sound.UI_BUTTON_CLICK, 0.7F, 0.5F);
        if (rig.rounds(gun) <= 0) {
            shooter.sendMessage(org.bukkit.ChatColor.RED + "Empty magazine — use /gunsmith give for a fresh one.");
        }
    }

    /**
     * Traces the bullet path with a sparse line of smoke puffs, one per
     * {@link #TRACER_STEP} blocks — dense enough to read the trajectory,
     * light enough to never lag.
     */
    private void drawTracer(World world, Location from, Location to) {
        Vector step = to.toVector().subtract(from.toVector());
        double distance = step.length();
        if (distance < TRACER_STEP) {
            return;
        }
        step.normalize().multiply(TRACER_STEP);
        Location cursor = from.clone().add(step);
        for (double travelled = TRACER_STEP; travelled < distance; travelled += TRACER_STEP) {
            world.spawnParticle(Particle.SMOKE, cursor, 1,
                    TRACER_SPREAD, TRACER_SPREAD, TRACER_SPREAD, 0.0D);
            cursor.add(step);
        }
    }

    /** The bullet-impact flash at the exact hit point. */
    private void spawnImpact(World world, Location impact) {
        world.spawnParticle(Particle.CRIT, impact, 8,
                IMPACT_SPREAD, IMPACT_SPREAD, IMPACT_SPREAD, 0.1D);
        world.spawnParticle(Particle.SMOKE, impact, 3,
                IMPACT_SPREAD, IMPACT_SPREAD, IMPACT_SPREAD, 0.01D);
    }

    /** Small random cone deviation; zero degrees keeps the shot dead straight. */
    private Vector applySpread(Vector direction, float degrees) {
        double radians = Math.toRadians(degrees);
        double yaw = (Math.random() - 0.5D) * radians;
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        return new Vector(
                direction.getX() * cos - direction.getZ() * sin,
                direction.getY(),
                direction.getX() * sin + direction.getZ() * cos).normalize();
    }
}
