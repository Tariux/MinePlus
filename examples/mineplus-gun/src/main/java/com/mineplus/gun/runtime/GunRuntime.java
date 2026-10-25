package com.mineplus.gun.runtime;

import com.mineplus.gun.GunServices;
import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.input.InputAdapter;
import com.mineplus.gun.weapon.WeaponDefinition;
import com.mineplus.pack.item.ItemFrameWriter;
import com.mineplus.pack.item.ItemFrames;
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
 * active shooter and every simulated bullet — never one task per bullet or
 * per weapon.
 *
 * <p><b>Controls.</b> Right-click is the one and only trigger (hold for AUTO);
 * left-click is inert; swapping hands reloads — pulling the needed rounds out
 * of the shooter's gold-nugget supply.</p>
 *
 * <p><b>Frames.</b> A framed weapon (Western pack visuals) animates through
 * its reload frames during the reload and settles on its loaded state while
 * rounds remain. Frame switches perform one slot update each, so a frame is
 * only written when it actually changes.</p>
 *
 * <p>Ammunition is authoritative in memory while a weapon is in use and is
 * flushed back to the stack's PDC when it leaves the shooter's hand, so the
 * held ItemStack is never rewritten per shot (which would replay the client's
 * item-swap equip animation).</p>
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

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private BukkitTask task;
    private long tick;

    public GunRuntime(JavaPlugin plugin, GunServices services, AmmoModel ammo,
                      BulletSimulator simulator) {
        this.plugin = plugin;
        this.services = services;
        this.ammo = ammo;
        this.simulator = simulator;
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
     * scoped weapons tighten their spread while held, melee swings. Left-click
     * is frozen by the input adapter because firing from it would replay the
     * client's arm-punch animation on every shot.
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
                } else {
                    applyReloadFrames(player, stack, session, weapon);
                }
            }
            reconcileFrame(player, stack, session, weapon);

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
            } else if (tick % 4 == 0) {
                updateHud(player, session, weapon);
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
        for (int pellet = 0; pellet < weapon.pellets(); pellet++) {
            // Each pellet gets its own cone sample of the (already aimed) cone.
            simulator.fire(player, weapon, origin, direction, spread);
        }

        services.soundFx().play(origin, weapon.soundFire(), 1.0f, 1.0f);
        services.particleFx().muzzle(origin.clone().add(origin.getDirection().normalize().multiply(0.6)), weapon);

        session.nextShotTick = tick + weapon.shotIntervalTicks();
        if (weapon.fireMode() == FireMode.BURST) {
            session.burstRemaining--;
            if (session.burstRemaining <= 0) {
                session.firing = false;
            }
        }
        updateHud(player, session, weapon);
    }

    private void dryFire(Player player, Session session, WeaponDefinition weapon) {
        session.firing = false;
        session.burstRemaining = 0;
        services.soundFx().playTo(player, weapon.soundEmpty(), 0.7f, 1.0f);
        services.ammoHud().flashEmpty(player, weapon);
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
    // Reload (gold-nugget economy)
    // ------------------------------------------------------------------

    private void startReload(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
        if (session.reloading) {
            return;
        }
        AmmoModel.Magazine magazine = magazine(player, session, weapon, stack);
        int missing = weapon.magazine() - magazine.rounds();
        if (missing <= 0) {
            return;
        }
        GunConfig.Ammo supply = services.config().ammo();
        if (AmmoSupply.count(player, supply.material()) <= 0) {
            // Out of nuggets: the reload simply never starts.
            services.soundFx().playTo(player, weapon.soundEmpty(), 0.7f, 1.2f);
            services.ammoHud().flashEmpty(player, weapon);
            return;
        }
        session.firing = false;
        session.burstRemaining = 0;
        if (weapon.reloadTicks() <= 0) {
            completeReload(player, session, weapon, stack);
            return;
        }
        session.reloading = true;
        session.reloadEndTick = tick + weapon.reloadTicks();
        session.reloadFrame = -1;
        services.soundFx().playTo(player, weapon.soundReload(), 0.8f, 1.0f);
        updateHud(player, session, weapon);
    }

    private void completeReload(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
        AmmoModel.Magazine magazine = magazine(player, session, weapon, stack);
        int missing = weapon.magazine() - magazine.rounds();
        if (missing > 0) {
            GunConfig.Ammo supply = services.config().ammo();
            int affordable = AmmoSupply.count(player, supply.material()) / supply.itemsPerRound();
            int loaded = Math.min(missing, affordable);
            if (loaded > 0) {
                int consumed = AmmoSupply.take(player, supply.material(), loaded * supply.itemsPerRound());
                magazine.addRounds(weapon, consumed / supply.itemsPerRound());
            }
        }
        session.reloading = false;
        services.soundFx().playTo(player, weapon.soundReload(), 0.8f, 1.1f);
        updateHud(player, session, weapon);
    }

    // ------------------------------------------------------------------
    // Frame driving (Core framed-item infrastructure)
    // ------------------------------------------------------------------

    /**
     * Frame progression during a reload: the weapon's reload states play
     * across the reload duration through the Core {@link ItemFrames} math;
     * the loaded state arrives on completion ({@link #reconcileFrame}).
     */
    private void applyReloadFrames(Player player, ItemStack stack, Session session, WeaponDefinition weapon) {
        ItemFrames frames = frames(weapon);
        if (frames == null || !frames.isFramed()) {
            return;
        }
        float progress = (session.reloadEndTick - tick) / (float) Math.max(1, weapon.reloadTicks());
        int state = frames.reloadState(1.0f - progress);
        if (state != session.reloadFrame && ItemFrameWriter.apply(stack, state)) {
            session.reloadFrame = state;
        }
    }

    /**
     * Lines the held stack's state up with the weapon (loaded visual while
     * rounds remain, idle when empty); the reload owns the frames while it
     * runs, except for weapons without reload states (muzzle-loaders keep
     * their idle pose while ramming).
     */
    private void reconcileFrame(Player player, ItemStack stack, Session session, WeaponDefinition weapon) {
        ItemFrames frames = frames(weapon);
        if (frames == null) {
            return;
        }
        int wanted;
        if (session.reloading) {
            if (frames.reloadReferences().isEmpty()) {
                wanted = frames.idleState();
            } else {
                return; // applyReloadFrames owns the frame during a reload
            }
        } else {
            AmmoModel.Magazine magazine = session.magazine;
            int rounds = magazine != null ? magazine.rounds() : ammo.rounds(stack, weapon);
            wanted = rounds > 0 ? frames.loadedState() : frames.idleState();
        }
        if (wanted != session.appliedFrame && ItemFrameWriter.apply(stack, wanted)) {
            session.appliedFrame = wanted;
        }
    }

    private static ItemFrames frames(WeaponDefinition weapon) {
        return weapon.frames();
    }

    // ------------------------------------------------------------------
    // HUD
    // ------------------------------------------------------------------

    private void updateHud(Player player, Session session, WeaponDefinition weapon) {
        if (!weapon.usesAmmo()) {
            return;
        }
        AmmoModel.Magazine magazine = session.magazine;
        int rounds = magazine != null ? magazine.rounds() : ammo.rounds(player.getInventory().getItemInMainHand(), weapon);
        GunConfig.Ammo supply = services.config().ammo();
        int reserve = AmmoSupply.count(player, supply.material());
        services.ammoHud().update(player, weapon, rounds, reserve, session.reloading, session.scoping);
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
        int reloadFrame = -1;
        int appliedFrame = -1;
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
            reloadFrame = -1;
            appliedFrame = -1;
        }
    }
}
