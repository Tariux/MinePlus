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
 *
 * <p>Controls (all firearms): <b>left-click fires</b> (hold for AUTO/BURST; the
 * client repeats the arm-swing while the button is held, which is what keeps an
 * automatic weapon alive). <b>Right-click</b> is one trigger pull for a normal
 * firearm, the scope for a scoped weapon (the spyglass carrier's native zoom),
 * and the throw for a grenade. Swapping hands reloads.
 *
 * <p>Ammunition is authoritative in memory while a weapon is in use and is
 * flushed back to the stack's PDC when it leaves the shooter's hand, so the
 * held ItemStack is never rewritten per shot (which would replay the client's
 * item-swap equip animation).
 */
public final class GunRuntime implements InputAdapter.Listener {

    /** Ticks without a trigger repeat before automatic fire / scoping stops. */
    private static final long AUTO_RELEASE_TICKS = 8L;

    /** Minimum gap between trigger presses for a new SEMI/PUMP/BOLT/BURST shot. */
    private static final long SEMI_REPRESS_TICKS = 6L;

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
        for (Session session : sessions.values()) {
            flushMagazine(session);
        }
        sessions.clear();
        simulator.clear();
        grenades.clear();
    }

    // ------------------------------------------------------------------
    // InputAdapter.Listener
    // ------------------------------------------------------------------

    @Override
    public boolean isWeapon(ItemStack stack) {
        return resolve(stack) != null;
    }

    /**
     * Right-click is the one and only trigger: firearms fire (hold to keep
     * firing — the client repeats the use packet while the button is held),
     * scoped weapons tighten their spread while held, melee swings, grenades
     * are thrown. Left-click is frozen by the input adapter because firing from
     * it would replay the client's arm-punch animation on every shot.
     */
    @Override
    public boolean onPress(Player player, ItemStack stack) {
        WeaponDefinition weapon = resolve(stack);
        if (weapon == null || disabled(player)) {
            return false;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), Session::new);
        session.weaponId = weapon.id();
        switch (weapon.fireMode()) {
            case THROW -> throwGrenade(player, weapon);
            case MELEE -> {
                if (tick >= session.nextShotTick) {
                    meleeAttack(player, weapon);
                    session.nextShotTick = tick + weapon.shotIntervalTicks();
                }
            }
            default -> trigger(player, session, weapon, stack);
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

    /**
     * Left-click is deliberately inert for our weapons: firing from it would
     * replay the client's arm-punch animation on every shot. Every action lives
     * on right-click.
     */
    @Override
    public void onSwing(Player player, ItemStack stack) {
        // Intentionally empty.
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
        // Cancel vanilla melee damage for ALL weapons; melee is our arc sweep and
        // firearms replace the punch with the simulated round.
        return resolve(stack) != null;
    }

    // ------------------------------------------------------------------
    // Trigger handling
    // ------------------------------------------------------------------

    /**
     * One trigger event (a right-click press; the client repeats the use packet
     * roughly every four ticks while the button is held). AUTO keeps firing as
     * long as those repeats keep arriving; BURST and SEMI/PUMP/BOLT need a
     * genuine re-press, so holding the button cannot machine-gun them. A scoped
     * weapon scopes while the trigger repeats and unscopes when they stop.
     */
    private void trigger(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
        long gap = tick - session.lastSwingTick;
        session.lastSwingTick = tick;
        if (weapon.scope()) {
            session.scoping = true;
        }
        switch (weapon.fireMode()) {
            case AUTO -> {
                if (!session.firing) {
                    session.firing = true;
                    session.holdTicks = 0;
                    if (tick >= session.nextShotTick) {
                        shoot(player, session, weapon, stack);
                    }
                }
            }
            case BURST -> {
                if (gap > SEMI_REPRESS_TICKS) {
                    startBurst(session, weapon);
                }
            }
            default -> {
                if (gap > SEMI_REPRESS_TICKS && tick >= session.nextShotTick) {
                    shoot(player, session, weapon, stack);
                }
            }
        }
    }

    private void startBurst(Session session, WeaponDefinition weapon) {
        if (tick < session.nextShotTick) {
            return;
        }
        session.burstRemaining = weapon.burstCount();
        session.firing = true;
        session.holdTicks = 0;
        session.nextShotTick = tick;
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
                // The weapon left the hand (or was swapped): persist its live
                // magazine and forget the trigger state.
                flushMagazine(session);
                session.resetTransient();
                continue;
            }

            // The scope is held with the trigger and drops as soon as the
            // trigger repeats stop (the carrier is neutral, so there is no
            // vanilla use state to read).
            if (!weapon.scope() || tick - session.lastSwingTick > AUTO_RELEASE_TICKS) {
                session.scoping = false;
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
                boolean released = weapon.fireMode() == FireMode.AUTO
                        && tick - session.lastSwingTick > AUTO_RELEASE_TICKS;
                if (session.holdTicks > weapon.maxHoldTicks() || released) {
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
            AmmoModel.Magazine magazine = magazine(player, session, weapon, stack);
            if (magazine.rounds() <= 0) {
                dryFire(player, session, weapon);
                return;
            }
            magazine.spend();
        }

        Location origin = player.getEyeLocation();
        Vector direction = origin.getDirection().normalize().multiply(weapon.velocity());
        double spread = session.scoping
                ? weapon.spreadDegrees() * weapon.scopeSpreadMultiplier()
                : weapon.spreadDegrees();
        simulator.fire(player, weapon, origin, direction, spread);

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
        AmmoModel.Magazine magazine = magazine(player, session, weapon, stack);
        if (magazine.rounds() >= weapon.magazine()) {
            return;
        }
        session.firing = false;
        session.burstRemaining = 0;
        if (weapon.reloadTicks() <= 0) {
            magazine.refill(weapon.magazine());
            updateHud(player, session, weapon, stack);
            return;
        }
        session.reloading = true;
        session.reloadEndTick = tick + weapon.reloadTicks();
        services.soundFx().playTo(player, weapon.soundReload(), 0.8f, 1.0f);
        updateHud(player, session, weapon, stack);
    }

    private void completeReload(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
        magazine(player, session, weapon, stack).refill(weapon.magazine());
        session.reloading = false;
        services.soundFx().playTo(player, weapon.soundReload(), 0.8f, 1.1f);
        updateHud(player, session, weapon, stack);
    }

    private void updateHud(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
        if (!weapon.usesAmmo()) {
            return;
        }
        AmmoModel.Magazine magazine = session.magazine;
        int rounds = magazine != null ? magazine.rounds() : ammo.rounds(stack, weapon);
        services.ammoHud().update(player, weapon, rounds, -1, session.reloading, session.scoping);
    }

    // ------------------------------------------------------------------
    // Live magazine (deferred persistence)
    // ------------------------------------------------------------------

    /**
     * The live magazine of the held stack. Reopens (and flushes the previous
     * one) when the shooter switches to a different stack; a legacy stack with
     * no uid is tagged once, which is written back so it persists.
     */
    private AmmoModel.Magazine magazine(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
        String uid = services.itemFactory().readUid(stack);
        if (session.magazine != null && uid != null && session.magazine.uid().equals(uid)) {
            return session.magazine;
        }
        flushMagazine(session);
        boolean legacy = uid == null;
        session.magazine = ammo.open(stack, weapon);
        if (legacy) {
            // The stack just gained its uid: persist it (a one-time write).
            player.getInventory().setItemInMainHand(stack);
        }
        return session.magazine;
    }

    /** Persists the session's live magazine onto the matching inventory stack. */
    private void flushMagazine(Session session) {
        AmmoModel.Magazine magazine = session.magazine;
        session.magazine = null;
        if (magazine == null) {
            return;
        }
        Player player = Bukkit.getPlayer(session.playerId);
        if (player != null && player.isOnline()) {
            ammo.flush(player, magazine);
        }
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
        Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            flushMagazine(session);
        }
        services.ammoHud().clear(player.getUniqueId());
    }

    public void clearAll() {
        for (Session session : sessions.values()) {
            flushMagazine(session);
        }
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
        boolean scoping;
        int burstRemaining;
        int holdTicks;
        long nextShotTick;
        long reloadEndTick;
        long lastSwingTick = -1000L;
        AmmoModel.Magazine magazine;

        Session(UUID playerId) {
            this.playerId = playerId;
        }

        void resetTransient() {
            firing = false;
            reloading = false;
            scoping = false;
            burstRemaining = 0;
            holdTicks = 0;
        }
    }
}
