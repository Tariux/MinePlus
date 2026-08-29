package com.mineplus.fun.cannon;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
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
import org.joml.Quaternionf;
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
 * <p>Firing geometry follows the Core's CENTER origin convention: model pixel
 * {@code (0,0,0)} is the anchor block's center at its base, so a model point
 * {@code p} (in pixels) sits at {@code anchorCenter + R · (p/16 - (0, 1/2, 0))},
 * where {@code R} is the instance rotation - the same transform the display
 * renderer applies, so the shot always leaves the rendered barrel.
 */
public final class CannonFireHook implements MultiBlockHook {

    /** Level-1 muzzle exit point in model pixels: the barrel bore runs along −X at y=6.5, z=0. */
    private static final Vector3f MUZZLE_PIXELS = new Vector3f(-15.0f, 6.5f, 0.0f);

    /** Barrel axis in model space: the cannon fires toward −X. */
    private static final Vector3f BARREL_AXIS = new Vector3f(-1.0f, 0.0f, 0.0f);

    private static final float BLOCKS_PER_PIXEL = 1.0f / 16.0f;

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
    private final Map<UUID, Long> lastFiredAt;
    private final Random random;

    public CannonFireHook(PluginContext context, CannonMountManager mounts) {
        this.context = context;
        this.mounts = mounts;
        this.lastFiredAt = new HashMap<>();
        this.random = new Random();
    }

    @Override
    public void onInteract(MultiBlockInstance instance, Player actor) {
        if (instance.level() >= CannonKeys.LEVEL_AIMED) {
            interactAimed(instance, actor);
            return;
        }

        if (isHoldingTorch(actor)) {
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
        if (isHoldingSaddle(actor)) {
            mounts.mount(actor, instance);
            return;
        }
        if (isHoldingTorch(actor)) {
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
        lastFiredAt.remove(instance.id());
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

    private boolean isHoldingTorch(Player actor) {
        ItemStack mainHand = actor.getInventory().getItemInMainHand();
        if (mainHand != null && mainHand.getType() == Material.TORCH) {
            return true;
        }
        ItemStack offHand = actor.getInventory().getItemInOffHand();
        return mainHand != null
                && mainHand.getType().isAir()
                && offHand != null
                && offHand.getType() == Material.TORCH;
    }

    private boolean isHoldingSaddle(Player actor) {
        ItemStack mainHand = actor.getInventory().getItemInMainHand();
        if (mainHand != null && mainHand.getType() == Material.SADDLE) {
            return true;
        }
        ItemStack offHand = actor.getInventory().getItemInOffHand();
        return mainHand != null
                && mainHand.getType().isAir()
                && offHand != null
                && offHand.getType() == Material.SADDLE;
    }

    private void fire(MultiBlockInstance instance, Player actor) {
        long now = System.currentTimeMillis();
        Long last = lastFiredAt.get(instance.id());
        if (last != null && now - last < FIRE_COOLDOWN_MILLIS) {
            return;
        }
        lastFiredAt.put(instance.id(), now);

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

        Quaternionf rotation = instance.rotation();
        Vector3f muzzleOffset = new Vector3f(MUZZLE_PIXELS).mul(BLOCKS_PER_PIXEL).sub(0.0f, 0.5f, 0.0f);
        rotation.transform(muzzleOffset);
        Vector3f barrelAxis = new Vector3f(BARREL_AXIS);
        rotation.transform(barrelAxis);

        Location muzzle = new Location(
                world,
                instance.coordinate().x() + 0.5D + muzzleOffset.x,
                instance.coordinate().y() + 0.5D + muzzleOffset.y,
                instance.coordinate().z() + 0.5D + muzzleOffset.z
        );
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
