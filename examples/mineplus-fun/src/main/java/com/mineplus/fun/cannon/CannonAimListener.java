package com.mineplus.fun.cannon;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import java.util.Random;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Aimed fire for mounted level-2 cannons, driven by the vanilla bow
 * mechanics: the gunner draws the Cannon Lanyard exactly like a bow, and the
 * release's draw force (0..1 over 20 ticks, the vanilla charge curve) scales
 * the muzzle speed. The vanilla arrow launch is always cancelled - the cannon
 * fires a TNTPrimed from its muzzle instead, along the gunner's view
 * direction clamped into a cone around the bore so the stationary cannon
 * never shoots backwards through itself.
 */
public final class CannonAimListener implements Listener {

    /**
     * Level-2 muzzle geometry (cannon-3-1-1-bigger.bbmodel). Evidence: the
     * muzzle collar opening spans y 7..13, z -3..3 (bottom plate
     * [-17,6,-4]..[-10,7,4], top plate [-17,13,-4]..[-10,14,4], side plates at
     * z +/-3..4) and the breech tube [12,7,-3]..[19,13,3] shares exactly that
     * cross-section, so the bore centreline is y=10, z=0. The exit plane is
     * x=-17, the collar's front face; the business end is -X (the breech tube
     * runs to +X=19), matching level 1 and the placement pre-rotation.
     */
    private static final Vector3f MUZZLE_PIXELS = new Vector3f(-17.0f, 10.0f, 0.0f);

    /** Barrel axis in model space: the cannon fires toward -X. */
    private static final Vector3f BARREL_AXIS = new Vector3f(-1.0f, 0.0f, 0.0f);

    private static final float BLOCKS_PER_PIXEL = 1.0f / 16.0f;

    /** Draws weaker than this (vanilla taps) produce a dry click and no shot. */
    private static final float MIN_DRAW_FORCE = 0.12F;

    /** Muzzle speed at the weakest accepted draw, in blocks per tick. */
    private static final double MIN_MUZZLE_SPEED = 0.7D;

    /** Muzzle speed at a full 20-tick draw, in blocks per tick. */
    private static final double MAX_MUZZLE_SPEED = 2.8D;

    /** Maximum random yaw deviation per shot, in degrees. */
    private static final double SPREAD_DEGREES = 1.0D;

    /** Distance in front of the muzzle plane where the projectile spawns. */
    private static final double BARREL_CLEARANCE = 0.75D;

    /**
     * Half-angle of the aiming cone around the world-space bore axis. The
     * gunner aims freely inside it; directions outside are clamped onto its
     * edge so shots always leave the muzzle moving forward.
     */
    private static final double AIM_CONE_DEGREES = 60.0D;

    private final PluginContext context;
    private final CannonMountManager mounts;
    private final Random random;

    public CannonAimListener(PluginContext context, CannonMountManager mounts) {
        this.context = context;
        this.mounts = mounts;
        this.random = new Random();
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (mounts.isLanyard(event.getBow())) {
            event.setCancelled(true);
            fireFromSeat(player, event.getForce());
            return;
        }

        if (mounts.isMatch(event.getArrowItem())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.GRAY + "The cannon match only primes the Cannon Lanyard.");
        }
    }

    private void fireFromSeat(Player player, float force) {
        CannonMountManager.MountSession session = mounts.session(player);
        if (session == null) {
            player.sendMessage(ChatColor.GRAY + "The lanyard only works from a cannon's gunner's seat.");
            return;
        }

        if (!(player.getVehicle() instanceof ArmorStand vehicle)
                || !vehicle.getUniqueId().equals(session.seatEntityId())) {
            mounts.dropStaleSession(player);
            player.sendMessage(ChatColor.RED + "You are no longer seated at a cannon.");
            return;
        }

        MultiBlockInstance instance = context.infrastructureEngine().registry().getInstance(session.instanceId());
        if (instance == null || instance.level() < CannonKeys.LEVEL_AIMED) {
            mounts.dropStaleSession(player);
            player.sendMessage(ChatColor.RED + "The cannon you were serving is gone.");
            return;
        }

        if (force < MIN_DRAW_FORCE) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7F, 0.5F);
            player.sendMessage(ChatColor.GRAY + "The lanyard slackens with a dull click. Draw longer for a shot.");
            return;
        }

        int loaded = CannonTntStore.load(instance);
        if (loaded <= 0) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7F, 0.5F);
            player.sendMessage(ChatColor.RED + "The cannon is empty. Load TNT through its menu.");
            return;
        }

        World world = Bukkit.getWorld(instance.coordinate().worldName());
        if (world == null) {
            return;
        }

        CannonTntStore.save(instance, loaded - 1);

        Quaternionf rotation = instance.rotation();
        Vector3f muzzleOffset = new Vector3f(MUZZLE_PIXELS).mul(BLOCKS_PER_PIXEL).sub(0.0f, 0.5f, 0.0f);
        Vector3f axisOffset = new Vector3f(BARREL_AXIS);
        if (rotation != null) {
            rotation.transform(muzzleOffset);
            rotation.transform(axisOffset);
        }

        Location muzzle = new Location(
                world,
                instance.coordinate().x() + 0.5D + muzzleOffset.x,
                instance.coordinate().y() + 0.5D + muzzleOffset.y,
                instance.coordinate().z() + 0.5D + muzzleOffset.z
        );
        Vector barrelAxis = new Vector(axisOffset.x, axisOffset.y, axisOffset.z);

        Vector aim = clampToAimCone(player.getEyeLocation().getDirection(), barrelAxis);
        applySpread(aim);

        Location launchPoint = muzzle.clone().add(
                aim.getX() * BARREL_CLEARANCE,
                aim.getY() * BARREL_CLEARANCE,
                aim.getZ() * BARREL_CLEARANCE
        );
        double speed = MIN_MUZZLE_SPEED + force * (MAX_MUZZLE_SPEED - MIN_MUZZLE_SPEED);

        TNTPrimed projectile = world.spawn(launchPoint, TNTPrimed.class);
        projectile.setSource(player);
        projectile.setVelocity(aim.clone().multiply(speed));

        player.playSound(player.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0F, 0.55F);
        world.playSound(muzzle, Sound.ENTITY_GENERIC_EXPLODE, 0.5F + 0.5F * force, 1.7F - 0.5F * force);
        world.playSound(muzzle, Sound.ENTITY_TNT_PRIMED, 1.0F, 1.0F);
        world.spawnParticle(Particle.EXPLOSION, muzzle, 1);
        world.spawnParticle(Particle.CLOUD, muzzle, (int) (12 + 28 * force), 0.3D, 0.3D, 0.3D, 0.05D);

        player.sendMessage(ChatColor.GRAY + "Cannon fired (draw " + Math.round(force * 100.0F) + "%)."
                + " TNT remaining: " + (loaded - 1) + ".");
    }

    /**
     * Clamps the aim direction onto the aiming cone around the world-space
     * bore axis. Directions inside the cone pass through unchanged; anything
     * outside is projected onto the cone's edge.
     */
    private Vector clampToAimCone(Vector aim, Vector axis) {
        double limit = Math.cos(Math.toRadians(AIM_CONE_DEGREES));
        double along = aim.dot(axis);
        if (along >= limit) {
            return aim;
        }

        Vector perpendicular = aim.clone().subtract(axis.clone().multiply(along));
        if (perpendicular.lengthSquared() < 1.0E-6D) {
            perpendicular = axis.getCrossProduct(new Vector(0.0D, 1.0D, 0.0D));
            if (perpendicular.lengthSquared() < 1.0E-6D) {
                perpendicular = axis.getCrossProduct(new Vector(1.0D, 0.0D, 0.0D));
            }
        }
        perpendicular.normalize();

        return axis.clone()
                .multiply(limit)
                .add(perpendicular.multiply(Math.sin(Math.toRadians(AIM_CONE_DEGREES))))
                .normalize();
    }

    /** Rotates the launch direction around Y by a small random angle for shot-to-shot variance. */
    private void applySpread(Vector direction) {
        double spread = Math.toRadians((random.nextDouble() - 0.5D) * 2.0D * SPREAD_DEGREES);
        double cos = Math.cos(spread);
        double sin = Math.sin(spread);
        double x = direction.getX() * cos - direction.getZ() * sin;
        double z = direction.getX() * sin + direction.getZ() * cos;
        direction.setX(x);
        direction.setZ(z);
    }
}
