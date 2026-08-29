package com.mineplus.fun.cannon;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import com.mineplus.infrastructure.core.util.Cooldowns;
import com.mineplus.infrastructure.core.util.ModelPoints;
import java.util.Random;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

/**
 * Cannon behaviour hook. Interaction is level-dependent:
 *
 * <ul>
 *   <li>Level 1 (classic): right-clicking with a torch fires a fixed-power
 *       shot; anything else opens the ammunition menu.</li>
 *   <li>Level 2 (Cannon II): right-clicking with a saddle takes the gunner's
 *       seat; anything else opens the menu, which also offers the seat. Firing
 *       happens through the mounted aiming bow - see
 *       {@link CannonAimListener} and {@link CannonMountManager}.</li>
 * </ul>
 *
 * <p>The multiblock type is registered <em>without</em> a GUI key on purpose - the
 * Core would then always open the GUI on interact. Instead this hook decides per
 * interaction and opens the registered GUI itself through {@code openGui}.
 *
 * <p>Firing geometry follows the Core's CENTER origin convention through
 * {@link ModelPoints}: the shared transform is exactly what the display
 * renderer applies, so the shot always leaves the rendered barrel.
 */
public final class CannonFireHook implements MultiBlockHook {

    /** Level-1 muzzle exit point in model pixels: the barrel bore runs along −X at y=6.5, z=0. */
    private static final Vector3f MUZZLE_PIXELS = new Vector3f(-15.0f, 6.5f, 0.0f);

    /** Barrel axis in model space: the cannon fires toward −X. */
    private static final Vector3f BARREL_AXIS = new Vector3f(-1.0f, 0.0f, 0.0f);

    /** Launch speed in blocks per tick; combined with the elevation this lands ~15 blocks away. */
    private static final double MUZZLE_SPEED = 0.95D;

    /** Fixed elevation for ground placements, giving the shot a natural ballistic arc. */
    private static final double ELEVATION_DEGREES = 20.0D;

    /** Maximum random yaw deviation per shot, in degrees. */
    private static final double SPREAD_DEGREES = 1.5D;

    /** Extra distance in front of the muzzle where the projectile spawns, clearing the barrel. */
    private static final double BARREL_CLEARANCE = 0.5D;

    /** Minimum time between shots; also deduplicates the main/off-hand interact pair. */
    private static final long FIRE_COOLDOWN_MILLIS = 1000L;

    private final PluginContext context;
    private final CannonMountManager mounts;
    private final Cooldowns fireCooldowns;
    private final Random random;

    public CannonFireHook(PluginContext context, CannonMountManager mounts) {
        this.context = context;
        this.mounts = mounts;
        this.fireCooldowns = new Cooldowns();
        this.random = new Random();
    }

    @Override
    public void onInteract(MultiBlockInstance instance, Player actor) {
        if (instance.level() >= CannonKeys.LEVEL_AIMED) {
            interactAimed(instance, actor);
            return;
        }

        if (isHolding(actor, Material.TORCH)) {
            fire(instance, actor);
            return;
        }
        context.infrastructureApi().openGui(CannonKeys.GUI_KEY, actor, instance);
    }

    private void interactAimed(MultiBlockInstance instance, Player actor) {
        // A seated gunner pointing at their own cannon is aiming, not interacting;
        // without this guard the menu would pop open mid-draw.
        if (mounts.session(actor) != null) {
            return;
        }
        if (isHolding(actor, Material.SADDLE)) {
            mounts.mount(actor, instance);
            return;
        }
        if (isHolding(actor, Material.TORCH)) {
            actor.sendMessage(ChatColor.GRAY + "This cannon is worked from the gunner's seat. Mount it with a"
                    + " saddle or through its menu, then draw the lanyard to fire.");
            return;
        }
        context.infrastructureApi().openGui(CannonKeys.GUI_KEY, actor, instance);
    }

    @Override
    public void onUpgrade(MultiBlockInstance instance, int previousLevel, int nextLevel, Player actor) {
        if (actor == null || nextLevel < CannonKeys.LEVEL_AIMED) {
            return;
        }
        actor.sendMessage(ChatColor.GOLD + "The cannon is reborn as Cannon II.");
        actor.sendMessage(ChatColor.GRAY + "Take the gunner's seat with a saddle (or from its menu) and draw the"
                + " lanyard bow to aim and fire - longer draws hit harder.");
    }

    @Override
    public void onBreak(MultiBlockInstance instance, Player actor) {
        cleanup(instance);
    }

    @Override
    public void onRemove(MultiBlockInstance instance, Player actor) {
        cleanup(instance);
    }

    /** Returns any loaded ammunition to the world and unseats the gunner so nothing is silently destroyed. */
    private void cleanup(MultiBlockInstance instance) {
        fireCooldowns.remove(instance.id());
        fireCooldowns.prune(FIRE_COOLDOWN_MILLIS * 10L);
        mounts.ejectInstance(instance.id());
        dropLoadedTnt(instance);
    }

    /** Returns any loaded ammunition to the world so it is never silently destroyed. */
    private void dropLoadedTnt(MultiBlockInstance instance) {
        World world = Bukkit.getWorld(instance.coordinate().worldName());
        if (world == null) {
            return;
        }
        Location dropLocation = new Location(
                world,
                instance.coordinate().x() + 0.5D,
                instance.coordinate().y() + 0.5D,
                instance.coordinate().z() + 0.5D
        );

        int tnt = CannonTntStore.load(instance);
        if (tnt > 0) {
            world.dropItemNaturally(dropLocation, new ItemStack(Material.TNT, tnt));
            CannonTntStore.save(instance, 0);
        }

        int fireballs = CannonTntStore.loadFireballs(instance);
        if (fireballs > 0) {
            world.dropItemNaturally(dropLocation, new ItemStack(Material.FIRE_CHARGE, fireballs));
            CannonTntStore.saveFireballs(instance, 0);
        }
    }

    /** True when the player holds the material in the main hand, or in the off hand while the main hand is empty. */
    private boolean isHolding(Player actor, Material material) {
        ItemStack mainHand = actor.getInventory().getItemInMainHand();
        if (mainHand != null && mainHand.getType() == material) {
            return true;
        }
        ItemStack offHand = actor.getInventory().getItemInOffHand();
        return mainHand != null
                && mainHand.getType().isAir()
                && offHand != null
                && offHand.getType() == material;
    }

    private void fire(MultiBlockInstance instance, Player actor) {
        if (!fireCooldowns.tryAcquire(instance.id(), FIRE_COOLDOWN_MILLIS)) {
            return;
        }

        boolean fireball = CannonTntStore.hasFireballLoaded(instance);
        int loaded = fireball ? CannonTntStore.loadFireballs(instance) : CannonTntStore.load(instance);
        if (loaded <= 0) {
            actor.sendMessage(ChatColor.RED + "The cannon is empty. Load TNT or fire charges through its menu.");
            return;
        }

        World world = Bukkit.getWorld(instance.coordinate().worldName());
        if (world == null) {
            return;
        }

        if (fireball) {
            CannonTntStore.saveFireballs(instance, loaded - 1);
        } else {
            CannonTntStore.save(instance, loaded - 1);
        }
        context.infrastructureApi().stagePersist(instance.id());

        Location muzzle = ModelPoints.toWorld(instance, world, MUZZLE_PIXELS);
        Vector3f barrelAxis = ModelPoints.direction(instance, BARREL_AXIS);
        muzzle.add(barrelAxis.x * BARREL_CLEARANCE, barrelAxis.y * BARREL_CLEARANCE, barrelAxis.z * BARREL_CLEARANCE);

        Vector launchDirection = fireball
                ? new Vector(barrelAxis.x, barrelAxis.y, barrelAxis.z)
                : launchVelocity(barrelAxis);
        CannonProjectiles.launch(world, muzzle, launchDirection, MUZZLE_SPEED, actor, fireball);

        world.playSound(muzzle, Sound.ENTITY_GENERIC_EXPLODE, 0.4F, 1.5F);
        if (fireball) {
            actor.playSound(actor.getLocation(), Sound.ENTITY_GHAST_SHOOT, 0.8F, 1.2F);
        } else {
            world.playSound(muzzle, Sound.ENTITY_TNT_PRIMED, 1.0F, 1.0F);
        }
        world.spawnParticle(Particle.EXPLOSION, muzzle, 1);
        world.spawnParticle(Particle.CLOUD, muzzle, 20, 0.25D, 0.25D, 0.25D, 0.05D);

        String payload = fireball ? "fire charge" : "TNT";
        actor.sendMessage(ChatColor.GRAY + "Cannon fires a " + payload + ". Ammunition remaining: " + (loaded - 1) + ".");
    }

    /**
     * Builds the launch velocity from the world-space barrel axis. For a mostly
     * horizontal barrel the shot is elevated {@link #ELEVATION_DEGREES} degrees so it
     * flies a natural arc of roughly 20 blocks; a barrel aimed steeply up or down
     * (wall/ceiling placements) fires straight along the bore.
     */
    private Vector launchVelocity(Vector3f barrelAxis) {
        Vector axis = new Vector(barrelAxis.x, barrelAxis.y, barrelAxis.z);
        Vector launch;

        Vector horizontal = new Vector(axis.getX(), 0.0D, axis.getZ());
        if (horizontal.lengthSquared() < 1.0E-4D || Math.abs(axis.getY()) >= 0.9D) {
            launch = axis.lengthSquared() < 1.0E-8D ? new Vector(0.0D, 1.0D, 0.0D) : axis.clone();
            launch.normalize();
        } else {
            horizontal.normalize();
            double elevation = Math.toRadians(ELEVATION_DEGREES);
            launch = horizontal.multiply(Math.cos(elevation));
            launch.setY(Math.sin(elevation));
        }

        applySpread(launch);
        return launch.multiply(MUZZLE_SPEED);
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
