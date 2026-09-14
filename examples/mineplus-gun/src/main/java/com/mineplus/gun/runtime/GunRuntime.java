package com.mineplus.gun.runtime;

import com.mineplus.gun.GunServices;
import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.input.InputAdapter;
import com.mineplus.gun.weapon.WeaponDefinition;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * The per-player fire-flow state machine. One repeating task drives every
 * active shooter, every simulated bullet and every in-flight grenade — never
 * one task per bullet or per weapon.
 */
public final class GunRuntime implements InputAdapter.Listener {

    private final JavaPlugin plugin;
    private final GunServices services;
    private final AmmoModel ammo;
    private final BulletSimulator simulator;
    private final GrenadeRuntime grenades;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private BukkitTask task;
    private long tick;

    public GunRuntime(JavaPlugin plugin, GunServices services, AmmoModel ammo,
                      BulletSimulator simulator, GrenadeRuntime grenades) {
        this.plugin = plugin;
        this.services = services;
        this.ammo = ammo;
        this.simulator = simulator;
        this.grenades = grenades;
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        sessions.clear();
        simulator.clear();
        grenades.clear();
    }

    // ------------------------------------------------------------------
    // InputAdapter.Listener
    // ------------------------------------------------------------------

    @Override
    public boolean onPress(Player player, ItemStack stack) {
        WeaponDefinition weapon = resolve(stack);
        if (weapon == null || disabled(player)) {
            return false;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), Session::new);
        session.weaponId = weapon.id();
        switch (weapon.fireMode()) {
            case MELEE -> {
                // Melee reads the arm swing; right-click does nothing.
            }
            case THROW -> throwGrenade(player, weapon);
            case AUTO -> {
                session.firing = true;
                session.burstRemaining = 0;
                session.holdTicks = 0;
                session.nextShotTick = tick;
            }
            case BURST -> {
                if (tick >= session.nextShotTick) {
                    session.burstRemaining = weapon.burstCount();
                    session.firing = true;
                    session.holdTicks = 0;
                    session.nextShotTick = tick;
                }
            }
            default -> {
                if (tick >= session.nextShotTick) {
                    shoot(player, session, weapon, stack);
                }
            }
        }
        return true; // Cancel vanilla action for our weapons
    }

    @Override
    public void onRelease(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.firing = false;
            session.burstRemaining = 0;
        }
    }

    @Override
    public void onSwing(Player player, ItemStack stack) {
        WeaponDefinition weapon = resolve(stack);
        if (weapon == null || !weapon.isMelee() || disabled(player)) {
            return;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), Session::new);
        session.weaponId = weapon.id();
        if (tick < session.nextShotTick) {
            return;
        }
        meleeAttack(player, weapon);
        session.nextShotTick = tick + weapon.shotIntervalTicks();
    }

    @Override
    public void onReload(Player player, ItemStack stack) {
        WeaponDefinition weapon = resolve(stack);
        if (weapon == null || !weapon.usesAmmo() || disabled(player)) {
            return;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), Session::new);
        session.weaponId = weapon.id();
        startReload(player, session, weapon, stack);
    }

    @Override
    public boolean onAttack(Player player, ItemStack stack) {
        WeaponDefinition weapon = resolve(stack);
        if (weapon == null) {
            return false;
        }
        // Cancel vanilla melee damage for ALL weapons; we handle melee via onSwing.
        // For firearms this prevents the vanilla punch; for melee we use our arc.
        return true;
    }

    // ------------------------------------------------------------------
    // Tick loop
    // ------------------------------------------------------------------

    private void tick() {
        tick++;
        services.particleFx().beginTick();
        simulator.tick();
        grenades.tick();

        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }
            ItemStack stack = player.getInventory().getItemInMainHand();
            WeaponDefinition weapon = resolve(stack);
            if (weapon == null || !weapon.id().equals(session.weaponId)) {
                session.firing = false;
                session.burstRemaining = 0;
                session.reloading = false;
                continue;
            }

            if (session.reloading) {
                if (tick >= session.reloadEndTick) {
                    completeReload(player, session, weapon, stack);
                } else if (tick % 4 == 0) {
                    updateHud(player, session, weapon, stack);
                }
            }

            if (session.firing) {
                session.holdTicks++;
                if (session.holdTicks > weapon.maxHoldTicks()) {
                    session.firing = false;
                    session.burstRemaining = 0;
                } else if (tick >= session.nextShotTick) {
                    shoot(player, session, weapon, stack);
                }
            } else if (!session.reloading && tick % 4 == 0) {
                updateHud(player, session, weapon, stack);
            }
        }
    }

    // ------------------------------------------------------------------
    // Shot resolution
    // ------------------------------------------------------------------

    private void shoot(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
        if (session.reloading) {
            return;
        }
        if (weapon.usesAmmo()) {
            int rounds = ammo.rounds(stack, weapon);
            if (rounds <= 0) {
                dryFire(player, session, weapon);
                return;
            }
            ammo.spend(stack, weapon);
            player.getInventory().setItemInMainHand(stack);
        }

        Location origin = player.getEyeLocation();
        Vector direction = origin.getDirection().normalize().multiply(weapon.velocity());
        simulator.fire(player, weapon, origin, direction);

        services.soundFx().play(origin, weapon.soundFire(), 1.0f, 1.0f);
        services.particleFx().muzzle(origin.clone().add(origin.getDirection().normalize().multiply(0.6)), weapon);

        session.nextShotTick = tick + weapon.shotIntervalTicks();
        if (weapon.fireMode() == FireMode.BURST) {
            session.burstRemaining--;
            if (session.burstRemaining <= 0) {
                session.firing = false;
            }
        }
        updateHud(player, session, weapon, stack);
    }

    private void dryFire(Player player, Session session, WeaponDefinition weapon) {
        session.firing = false;
        session.burstRemaining = 0;
        services.soundFx().playTo(player, weapon.soundEmpty(), 0.7f, 1.0f);
        services.ammoHud().flashEmpty(player, weapon);
    }

    private void throwGrenade(Player player, WeaponDefinition weapon) {
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        if (stack.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        } else {
            stack.setAmount(stack.getAmount() - 1);
            player.getInventory().setItemInMainHand(stack);
        }
        Location origin = player.getEyeLocation();
        Vector velocity = origin.getDirection().normalize().multiply(1.2);
        grenades.throwGrenade(player, weapon, origin, velocity);
        services.soundFx().play(origin, "mineplusgun:grenade_pin", 0.8f, 1.2f);
        services.soundFx().play(origin, "mineplusgun:grenade_throw", 0.8f, 1.0f);
    }

    private void meleeAttack(Player player, WeaponDefinition weapon) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        double reach = weapon.meleeReach();
        double cosLimit = Math.cos(Math.toRadians(weapon.meleeArcDegrees() / 2.0));
        for (Entity nearby : player.getWorld().getNearbyEntities(eye, reach, reach, reach)) {
            if (!(nearby instanceof LivingEntity living) || living.isDead() || !living.isValid()
                    || nearby.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            Vector to = living.getLocation().add(0, living.getHeight() / 2.0, 0).toVector()
                    .subtract(eye.toVector());
            double distance = to.length();
            if (distance > reach || distance < 1.0e-4) {
                continue;
            }
            if (to.normalize().dot(direction) < cosLimit) {
                continue;
            }
            if (blocked(eye, living, distance)) {
                continue;
            }
            living.damage(weapon.damage(), player);
            Vector knockback = living.getLocation().toVector().subtract(player.getLocation().toVector());
            if (knockback.lengthSquared() > 0) {
                living.setVelocity(living.getVelocity()
                        .add(knockback.normalize().multiply(weapon.meleeKnockback())));
            }
        }
        services.soundFx().play(eye, "mineplusgun:shot", 0.5f, 1.8f);
    }

    private boolean blocked(Location eye, LivingEntity target, double distance) {
        Vector direction = target.getEyeLocation().toVector().subtract(eye.toVector());
        if (direction.lengthSquared() < 1.0e-6) {
            return false;
        }
        direction.normalize();
        return eye.getWorld() != null && eye.getWorld()
                .rayTraceBlocks(eye, direction, distance, org.bukkit.FluidCollisionMode.NEVER, true) != null;
    }

    // ------------------------------------------------------------------
    // Reload
    // ------------------------------------------------------------------

    private void startReload(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
        if (session.reloading) {
            return;
        }
        int rounds = ammo.rounds(stack, weapon);
        if (rounds >= weapon.magazine()) {
            return;
        }
        session.firing = false;
        session.burstRemaining = 0;
        if (weapon.reloadTicks() <= 0) {
            ammo.reload(stack, weapon);
            player.getInventory().setItemInMainHand(stack);
            updateHud(player, session, weapon, stack);
            return;
        }
        session.reloading = true;
        session.reloadEndTick = tick + weapon.reloadTicks();
        services.soundFx().playTo(player, weapon.soundReload(), 0.8f, 1.0f);
        updateHud(player, session, weapon, stack);
    }

    private void completeReload(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
        ammo.reload(stack, weapon);
        player.getInventory().setItemInMainHand(stack);
        session.reloading = false;
        services.soundFx().playTo(player, weapon.soundReload(), 0.8f, 1.1f);
        updateHud(player, session, weapon, stack);
    }

    private void updateHud(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
        if (!weapon.usesAmmo()) {
            return;
        }
        int rounds = ammo.rounds(stack, weapon);
        services.ammoHud().update(player, weapon, rounds, -1, session.reloading);
    }

    // ------------------------------------------------------------------
    // Helpers / lifecycle
    // ------------------------------------------------------------------

    public WeaponDefinition resolve(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || services.registry() == null) {
            return null;
        }
        String id = services.itemFactory().weaponId(stack);
        return id == null ? null : services.registry().get(id);
    }

    private boolean disabled(Player player) {
        GunConfig config = services.config();
        return config != null && player.getWorld() != null
                && config.settings().worldDisabled(player.getWorld().getName());
    }

    public void clear(Player player) {
        sessions.remove(player.getUniqueId());
        services.ammoHud().clear(player.getUniqueId());
    }

    public void clearAll() {
        sessions.clear();
        services.ammoHud().clearAll();
    }

    public int activeSessions() {
        return sessions.size();
    }

    public int activeBullets() {
        return simulator.activeBullets();
    }

    public int activeGrenades() {
        return grenades.activeGrenades();
    }

    public long totalShots() {
        return simulator.totalShots();
    }

    public long totalHits() {
        return simulator.totalHits();
    }

    public long instantTraces() {
        return simulator.instantTraces();
    }

    private static final class Session {
        final UUID playerId;
        String weaponId = "";
        boolean firing;
        boolean reloading;
        int burstRemaining;
        int holdTicks;
        long nextShotTick;
        long reloadEndTick;

        Session(UUID playerId) {
            this.playerId = playerId;
        }
    }
}
