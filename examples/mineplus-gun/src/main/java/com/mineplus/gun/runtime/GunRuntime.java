package com.mineplus.gun.runtime;

import com.mineplus.gun.GunServices;
import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.diag.PressTrace;
import com.mineplus.gun.weapon.WeaponDefinition;
import com.mineplus.gun.weapon.WeaponItemFactory;
import com.mineplus.infrastructure.combat.BallisticProfile;
import com.mineplus.infrastructure.combat.DamageTransactions;
import com.mineplus.infrastructure.combat.ProjectileHandler;
import com.mineplus.infrastructure.combat.ProjectileHit;
import com.mineplus.infrastructure.fx.Sounds;
import com.mineplus.infrastructure.input.HeldItemInput;
import com.mineplus.infrastructure.input.TriggerState;
import com.mineplus.infrastructure.registry.ItemMagazine;
import com.mineplus.pack.item.ItemFrameWriter;
import com.mineplus.pack.item.ItemFrames;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * The per-player fire-flow state machine.
 *
 * <p>What this class owns is only the part no server can own generically: the
 * reload economy against the player's nugget supply, the weapon frame
 * progression, and the action-bar HUD cadence. The trigger itself belongs to
 * the Core: {@link com.mineplus.infrastructure.input.HeldItemInput} resolves
 * the press stream into a hold, so an automatic weapon keeps firing while the
 * button is down whatever carrier item the content happens to use, and a
 * semi-automatic one fires once per real press instead of once per packet the
 * client repeats.</p>
 *
 * <p>Everything else is Core infrastructure reached through the services —
 * {@link HeldItemInput} delivers the press (including the one aimed at an
 * entity, which never reaches a plain {@code PlayerInteractEvent}),
 * {@link com.mineplus.infrastructure.combat.Ballistics} simulates the round and
 * lands its damage transactionally, {@link ItemMagazine} meters the rounds, and
 * {@link com.mineplus.infrastructure.fx.Sounds} emits the report without ever
 * letting an unresolvable custom event unwind the shot.</p>
 *
 * <p><b>Controls.</b> Right-click is the one and only trigger (hold for AUTO);
 * left-click is inert; swapping hands reloads — pulling the needed rounds out
 * of the shooter's gold-nugget supply. A press on an empty weapon reloads it
 * when the reserve allows it, so the shot never silently evaporates.</p>
 *
 * <p><b>Frames.</b> A framed weapon animates through its reload frames during
 * the reload and settles on its loaded state while rounds remain; a frame is
 * only written when it actually changes, because every write is a slot update
 * to the client.</p>
 *
 * <p><b>Threading.</b> All input callbacks and the one repeating task run on the
 * main thread. The loop isolates every player session: a fault in one
 * shooter's weapon must never end the loop for everybody else.</p>
 */
public final class GunRuntime implements HeldItemInput.HeldItemHandler {

    /** Ticks the per-shot fire pose holds before the weapon settles back. */
    private static final int FIRE_FRAME_TICKS = 4;

    /** Minimum ticks between reload frame switches (avoids client equip strobe). */
    private static final int RELOAD_FRAME_HOLD_TICKS = 8;

    /** Ticks between HUD refreshes when nothing is happening. */
    private static final int HUD_INTERVAL_TICKS = 4;

    /** Pitch of the hit marker the shooter gets for a landed round. */
    private static final String HIT_MARKER_SOUND = "block.note_block.hat";

    private final JavaPlugin plugin;
    private final GunServices services;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ProjectileHandler projectileHandler = new GunRounds();
    private BukkitTask task;
    private long tick;

    public GunRuntime(JavaPlugin plugin, GunServices services) {
        this.plugin = plugin;
        this.services = services;
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Flushes every live magazine and stops the loop. Idempotent. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                flushMagazine(player, entry.getValue());
            }
        }
        sessions.clear();
    }

    // ------------------------------------------------------------------
    // HeldItemInput.HeldItemHandler
    // ------------------------------------------------------------------

    @Override
    public boolean owns(ItemStack stack) {
        return resolve(stack) != null;
    }

    /**
     * A weapon in a disabled world is not owned <em>for that holder</em>: the
     * Core then stops routing its presses and stops freezing its vanilla
     * behavior, so the item is exactly the vanilla carrier it always was.
     */
    @Override
    public boolean owns(ItemStack stack, Player holder) {
        return owns(stack) && enabledIn(holder);
    }

    /** {@code true} when gun gameplay runs in the shooter's world. */
    private boolean enabledIn(Player player) {
        GunConfig config = services.config();
        return config == null || player.getWorld() == null
                || !config.settings().worldDisabled(player.getWorld().getName());
    }

    /**
     * Right-click is the one and only trigger: firearms fire (hold to keep
     * firing), scoped weapons tighten their spread while held, melee swings.
     * Left-click is frozen by the input service because firing from it would
     * replay the client's arm-punch animation on every shot.
     */
    @Override
    public boolean onPress(Player player, ItemStack stack) {
        WeaponDefinition weapon = resolve(stack);
        if (weapon == null) {
            return false;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), Session::new);
        adopt(player, session, weapon);
        PressTrace.trace(plugin, PressTrace.key(player.getUniqueId(), "press"),
                "[MineplusGun] press by " + player.getName() + ": weapon=" + weapon.id()
                        + " mode=" + weapon.fireMode());
        TriggerState trigger = trigger(player);
        boolean fresh = trigger == null || trigger.freshPress();
        if (!fresh) {
            // The client repeating a press it already sent: an automatic
            // weapon keeps its trigger down, a semi-automatic one waits for a
            // real press. Nothing else may act on it — a blade that swings on
            // the continuation packet swings twice for one click.
            if (weapon.fireMode() == FireMode.AUTO && !session.capped) {
                session.firing = true;
            }
            return true;
        }
        if (weapon.isMelee()) {
            if (tick >= session.nextShotTick) {
                meleeAttack(player, weapon);
                session.nextShotTick = tick + weapon.shotIntervalTicks();
            } else {
                // The swing has not recovered yet. The pull is not thrown
                // away: the blade owes that arc and takes it as soon as it
                // can.
                session.queuedPress = true;
            }
            return true;
        }
        press(player, session, weapon, stack);
        return true;
    }

    /**
     * The item left the hand: stop the trigger, rest the weapon on its
     * resting pose and persist the live magazine, which is exactly the moment
     * the Core's deferred write is designed for.
     */
    @Override
    public void onRelease(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        session.firing = false;
        session.burstRemaining = 0;
        session.scoping = false;
        if (!session.weaponId.isEmpty()) {
            restFrame(player, session);
            flushMagazine(player, session);
            session.resetTransient();
            session.weaponId = "";
        }
    }

    /**
     * The trigger ended while the weapon stayed in the hand: the player let
     * go of the button. The magazine is untouched — only the firing stops.
     * A committed burst is not cancelled: it was already promised to the
     * shooter, and a quick tap would otherwise truncate it after the first
     * round.
     */
    @Override
    public void onTriggerRelease(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        session.firing = false;
        session.scoping = false;
        session.capped = false;
    }

    /**
     * Swap-hands is the reload gesture, and the weapon always consumes it: the
     * gun stays in the hand, so the reload is observable and the weapon is
     * never silently stowed in the off hand by a gesture that was meant to
     * load it.
     */
    @Override
    public boolean onSwapHands(Player player, ItemStack stack) {
        WeaponDefinition weapon = resolve(stack);
        if (weapon == null) {
            return false;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), Session::new);
        adopt(player, session, weapon);
        if (!weapon.usesAmmo()) {
            return true;
        }
        startReload(player, session, weapon, stack, false);
        return true;
    }

    /**
     * Vanilla melee never lands with a gun in hand: a firearm replaces the
     * punch with its round, and a blade replaces it with the arc sweep. The
     * module's own damage calls are unaffected — the Core's suppression listener
     * stands down while a {@link DamageTransactions} is in progress.
     */
    @Override
    public boolean suppressVanillaMelee(Player attacker, EntityDamageByEntityEvent event) {
        return true;
    }

    // ------------------------------------------------------------------
    // Trigger handling
    // ------------------------------------------------------------------

    /**
     * One real press: the shot a semi-automatic weapon fires, the burst a
     * burst weapon starts, the hold an automatic weapon begins.
     */
    private void press(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
        // A real press starts a new hold: the continuous-fire cap is measured
        // per hold, and a hold that was cut short starts over.
        session.capped = false;
        session.holdTicks = 0;
        if (session.reloading) {
            // The weapon is loading: the pull neither fires nor clicks empty.
            // An empty click during a reload tells the shooter the weapon is
            // dry while it is actually being loaded — the magazine is empty
            // precisely because the reload is running.
            return;
        }
        if (weapon.scope()) {
            session.scoping = true;
        }
        if (weapon.usesAmmo()) {
            ItemMagazine.Live magazine = magazine(player, session, weapon, stack);
            if (magazine.empty()) {
                // An empty weapon reloads when the reserve allows it; it only
                // clicks when there is nothing to load.
                if (startReload(player, session, weapon, stack, true)) {
                    return;
                }
                dryFire(player, session, weapon);
                return;
            }
        }
        switch (weapon.fireMode()) {
            case AUTO -> {
                session.firing = true;
                session.holdTicks = 0;
                if (tick >= session.nextShotTick) {
                    shoot(player, session, weapon, stack);
                }
            }
            case BURST -> startBurst(session, weapon);
            default -> {
                if (tick >= session.nextShotTick) {
                    shoot(player, session, weapon, stack);
                } else {
                    // The weapon's cycle has not finished. The trigger pull is
                    // not thrown away: the gun owes that round and fires it as
                    // soon as it is ready again.
                    session.queuedPress = true;
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
        session.nextShotTick = tick;
    }

    // ------------------------------------------------------------------
    // Tick loop
    // ------------------------------------------------------------------

    private void tick() {
        tick++;
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            try {
                tickSession(entry.getKey(), entry.getValue());
            } catch (Throwable failure) {
                // One player's session must never end the loop for everybody
                // else: a broken weapon would otherwise silently disarm the
                // whole server's arsenal.
                plugin.getLogger().warning("[MineplusGun] session tick failed for "
                        + entry.getKey() + ": " + failure);
            }
        }
    }

    private void tickSession(UUID playerId, Session session) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        ItemStack stack = player.getInventory().getItemInMainHand();
        WeaponDefinition weapon = resolve(stack);
        // The world this player is in may have been disabled since the
        // session started; treat that exactly like the item leaving the
        // hand, so the live magazine is persisted and nothing fires.
        if (weapon == null || !enabledIn(player)) {
            // The weapon left the hand (or was swapped): persist its live
            // magazine and forget the trigger state. A session that never
            // held a weapon (weaponId empty) skips the churn entirely.
            if (!session.weaponId.isEmpty()) {
                PressTrace.trace(plugin, PressTrace.key(playerId, "session-flip"),
                        "[MineplusGun] session reset for " + player.getName() + ": was=" + session.weaponId
                                + " held=" + (weapon == null ? stack.getType() + " (not a weapon)"
                                : weapon.id()) + " reloading=" + session.reloading);
                restFrame(player, session);
                flushMagazine(player, session);
                session.resetTransient();
                session.weaponId = "";
            }
            return;
        }
        // A different weapon is in the hand: the session follows the weapon, so
        // the new one starts with its own magazine, its own cadence and its
        // own frame instead of inheriting the previous one's state.
        adopt(player, session, weapon);

        // The scope is held with the trigger and drops as soon as the trigger
        // ends (the Core's trigger state knows when that is, whatever carrier
        // the weapon uses).
        TriggerState trigger = trigger(player);
        boolean held = trigger != null && trigger.held();
        if (!weapon.scope() || !held) {
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

        if (session.burstRemaining > 0) {
            // A committed burst runs to completion: releasing the trigger does
            // not take back the rounds the weapon already promised.
            if (tick >= session.nextShotTick) {
                shoot(player, session, weapon, stack);
            }
        } else if (session.firing) {
            session.holdTicks++;
            if (!held) {
                // The Core saw the repeats stop: the player let go.
                session.firing = false;
            } else if (session.holdTicks > weapon.maxHoldTicks()) {
                // One hold may not fire forever. The weapon goes quiet until
                // the trigger is released and pressed again — the cap is
                // measured per hold, so the cadence never degrades.
                session.firing = false;
                session.capped = true;
            } else if (tick >= session.nextShotTick) {
                shoot(player, session, weapon, stack);
            }
        } else if (session.queuedPress && !session.reloading && tick >= session.nextShotTick) {
            // A pull that arrived while the weapon was still cycling fires
            // now. The player asked for that round and gets it — a trigger
            // pull is never silently dropped.
            session.queuedPress = false;
            if (weapon.isMelee()) {
                meleeAttack(player, weapon);
                session.nextShotTick = tick + weapon.shotIntervalTicks();
            } else {
                shoot(player, session, weapon, stack);
            }
        }
        if (tick % HUD_INTERVAL_TICKS == 0) {
            updateHud(player, session, weapon);
        }
    }

    /**
     * Makes the session describe the weapon the player holds now.
     *
     * <p>Switching weapons starts a clean session: the weapon left behind
     * keeps the rounds it had (they are written back to its own stack), and
     * the new one starts with its own magazine, its own cadence and its own
     * frame. Without this the state of one gun leaked into the next — a
     * reload still running on the previous weapon blocked the new one from
     * firing, a spent cooldown delayed its first shot, and an applied frame
     * number made it skip the pose it owed.</p>
     */
    private void adopt(Player player, Session session, WeaponDefinition weapon) {
        if (weapon.id().equals(session.weaponId)) {
            return;
        }
        if (!session.weaponId.isEmpty()) {
            PressTrace.trace(plugin, PressTrace.key(player.getUniqueId(), "session-flip"),
                    "[MineplusGun] weapon switch for " + player.getName() + ": " + session.weaponId
                            + " -> " + weapon.id());
            // The weapon leaving the hand rests on its own pose: a gun left in
            // the inventory on its recoil frame looks broken, and every gun
            // must keep its own pose when the shooter carries several.
            restFrame(player, session);
        }
        flushMagazine(player, session);
        session.resetTransient();
        session.weaponId = weapon.id();
        session.weapon = weapon;
    }

    // ------------------------------------------------------------------
    // Shot resolution
    // ------------------------------------------------------------------

    /**
     * Fires one trigger's worth of rounds. The Core engine owns the muzzle
     * flash, the report and the tracer; this method owns the magazine, the
     * cadence, the recoil frame and the HUD.
     */
    private void shoot(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
        if (session.reloading) {
            PressTrace.debug("[MineplusGun] shot blocked: reload in progress for " + player.getName());
            return;
        }
        boolean stacked = false;
        if (weapon.usesAmmo()) {
            ItemMagazine.Live magazine = magazine(player, session, weapon, stack);
            if (magazine.empty()) {
                dryFire(player, session, weapon);
                return;
            }
            magazine.spend();
            // An untagged (multi-count, anomalous) stack cannot be traced by
            // identity, so its count is persisted per shot instead of deferred.
            stacked = magazine.uid() == null;
            if (stacked) {
                factory().setRounds(stack, magazine.rounds(), weapon);
                // The count only reaches the stack's own metadata; without the
                // write-back the next open would read the old count back and
                // the weapon would never run down.
                player.getInventory().setItemInMainHand(stack);
            }
            PressTrace.trace(plugin, PressTrace.key(player.getUniqueId(), "shot"),
                    "[MineplusGun] SHOT by " + player.getName() + ": " + weapon.id() + " rounds="
                            + magazine.rounds() + "/" + weapon.magazine() + " pellets=" + weapon.pellets()
                            + " interval=" + weapon.shotIntervalTicks() + "t");
        }

        Location origin = player.getEyeLocation();
        Vector aim = origin.getDirection();
        BallisticProfile profile = session.scoping ? weapon.scopedBallistics() : weapon.ballistics();
        int launched = services.ballistics().fireWeapon(player, origin, aim, profile, projectileHandler);
        if (launched == 0 && weapon.usesAmmo() && !stacked) {
            // The engine refused (disabled or unusable origin): the round was
            // never fired, so it was never spent.
            ItemMagazine.Live magazine = session.magazine;
            if (magazine != null) {
                magazine.add(1);
            }
        }

        session.nextShotTick = tick + weapon.shotIntervalTicks();
        if (weapon.fireMode() == FireMode.BURST) {
            session.burstRemaining--;
            if (session.burstRemaining <= 0) {
                session.firing = false;
            }
        }
        punchRecoil(player, session, weapon, stack);
        updateHud(player, session, weapon);
    }

    /**
     * Per-shot visual kick: a framed weapon shows its fire pose for a few
     * ticks — the muzzle jumping, the hammer snapping forward — before it
     * settles back on the loaded frame. The pose is written on the spot, so
     * the kick lands with the shot instead of a tick later. Recoil pauses
     * while a reload owns the frames.
     */
    private void punchRecoil(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
        if (session.reloading || !weapon.hasFrames()) {
            return;
        }
        ItemFrames frames = weapon.frames();
        if (frames == null || !frames.hasFireModel()) {
            return;
        }
        session.fireUntil = tick + FIRE_FRAME_TICKS;
        int state = frames.fireState();
        if (state != session.appliedFrame) {
            session.appliedFrame = state;
            writeFrame(player, stack, weapon, state);
        }
    }

    private void dryFire(Player player, Session session, WeaponDefinition weapon) {
        session.firing = false;
        session.burstRemaining = 0;
        // Gate the empty-click cadence like a shot, so spam-clicking an empty
        // gun clicks once per interval instead of machine-gunning the sound.
        session.nextShotTick = tick + Math.max(4, weapon.shotIntervalTicks() / 2);
        GunConfig.Ammo supply = services.config().ammo();
        int reserve = AmmoSupply.rounds(player, supply.material(), supply.itemsPerRound());
        PressTrace.trace(plugin, PressTrace.key(player.getUniqueId(), "dry-fire"),
                "[MineplusGun] DRY FIRE by " + player.getName() + ": " + weapon.id() + " magazine empty,"
                        + " reserve=" + reserve + " round(s) from "
                        + supply.material().name().toLowerCase(Locale.ROOT));
        Sounds.playTo(player, weapon.soundEmpty(), 0.7f, 1.0f, org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT);
        services.ammoHud().flashEmpty(player, weapon, reserve, supplyName(supply));
    }

    private void meleeAttack(Player player, WeaponDefinition weapon) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        double reach = weapon.meleeReach();
        double cosLimit = Math.cos(Math.toRadians(weapon.meleeArcDegrees() / 2.0));
        boolean landed = false;
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
            // Transactional: the arc's damage is this module's own hit, and a
            // melee-suppression listener must not be allowed to cancel it.
            if (DamageTransactions.apply(living, weapon.damage(), player)) {
                landed = true;
            }
            Vector knockback = living.getLocation().toVector().subtract(player.getLocation().toVector());
            if (knockback.lengthSquared() > 0) {
                living.setVelocity(living.getVelocity()
                        .add(knockback.normalize().multiply(weapon.meleeKnockback())));
            }
        }
        if (landed) {
            Sounds.play(eye, HIT_MARKER_SOUND, 0.5f, 1.6f);
        }
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

    /**
     * Starts a reload when the magazine is short and the reserve can fill it.
     *
     * @param automatic {@code true} when the reload was triggered by an empty
     *                  weapon instead of the explicit swap gesture
     * @return {@code true} when a reload actually started
     */
    private boolean startReload(Player player, Session session, WeaponDefinition weapon, ItemStack stack,
                                boolean automatic) {
        if (session.reloading) {
            PressTrace.debug("[MineplusGun] reload ignored: one already running for " + player.getName());
            return false;
        }
        ItemMagazine.Live magazine = magazine(player, session, weapon, stack);
        int missing = weapon.magazine() - magazine.rounds();
        if (missing <= 0) {
            PressTrace.trace(plugin, PressTrace.key(player.getUniqueId(), "reload"),
                    "[MineplusGun] reload ignored: magazine already full ("
                            + magazine.rounds() + "/" + weapon.magazine() + ") for " + player.getName());
            if (!automatic) {
                services.ammoHud().flashFull(player, weapon);
            }
            return false;
        }
        GunConfig.Ammo supply = services.config().ammo();
        int nuggets = AmmoSupply.count(player, supply.material());
        int affordable = nuggets / supply.itemsPerRound();
        if (affordable <= 0) {
            // Not even a whole round's worth of supply: the reload simply never
            // starts - make that legible instead of playing a reload that ends
            // with an empty magazine.
            PressTrace.trace(plugin, PressTrace.key(player.getUniqueId(), "reload"),
                    "[MineplusGun] reload cannot start: " + nuggets + " "
                            + supply.material().name().toLowerCase(Locale.ROOT)
                            + " is not a whole round (" + supply.itemsPerRound() + " per round) for "
                            + player.getName());
            if (!automatic) {
                Sounds.playTo(player, weapon.soundEmpty(), 0.7f, 1.2f, org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT);
                services.ammoHud().flashNoReserve(player, supplyName(supply));
            }
            return false;
        }
        PressTrace.trace(plugin, PressTrace.key(player.getUniqueId(), "reload"),
                "[MineplusGun] reload started: " + weapon.id() + " need=" + missing + " reserve=" + affordable
                        + " round(s) from " + supply.material().name().toLowerCase(Locale.ROOT)
                        + " duration=" + weapon.reloadTicks() + "t for " + player.getName());
        session.firing = false;
        session.burstRemaining = 0;
        // The reload answers the pull that asked for it: a round still owed
        // from an earlier pull is not fired on top of it.
        session.queuedPress = false;
        if (weapon.reloadTicks() <= 0) {
            completeReload(player, session, weapon, stack);
            return true;
        }
        session.reloading = true;
        session.reloadStartTick = tick;
        session.reloadEndTick = tick + weapon.reloadTicks();
        session.reloadFrame = -1;
        // The first pose may be written on the very tick the reload starts.
        session.reloadFrameAtTick = tick - RELOAD_FRAME_HOLD_TICKS;
        Sounds.playTo(player, weapon.soundReload(), 0.8f, 1.0f, org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS);
        updateHud(player, session, weapon);
        return true;
    }

    private void completeReload(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
        ItemMagazine.Live magazine = magazine(player, session, weapon, stack);
        int missing = weapon.magazine() - magazine.rounds();
        if (missing > 0) {
            GunConfig.Ammo supply = services.config().ammo();
            int affordable = AmmoSupply.count(player, supply.material()) / supply.itemsPerRound();
            int loaded = Math.min(missing, affordable);
            if (loaded > 0) {
                int consumed = AmmoSupply.take(player, supply.material(), loaded * supply.itemsPerRound());
                int roundsLoaded = consumed / supply.itemsPerRound();
                magazine.add(roundsLoaded);
                if (magazine.uid() == null) {
                    factory().setRounds(stack, magazine.rounds(), weapon);
                }
                PressTrace.trace(plugin, PressTrace.key(player.getUniqueId(), "reload"),
                        "[MineplusGun] reload complete: " + roundsLoaded + " round(s) loaded into " + weapon.id()
                                + " (magazine " + magazine.rounds() + "/" + weapon.magazine()
                                + ") for " + player.getName());
            }
        }
        session.reloading = false;
        Sounds.playTo(player, weapon.soundReload(), 0.8f, 1.1f, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING);
        updateHud(player, session, weapon);
    }

    // ------------------------------------------------------------------
    // Frame driving (Core framed-item infrastructure)
    // ------------------------------------------------------------------

    /**
     * Frame progression during a reload: the weapon's reload states play
     * across the reload duration through the Core {@link ItemFrames} math;
     * the loaded state arrives on completion ({@link #reconcileFrame}).
     *
     * <p>The progress is the <em>elapsed</em> fraction of the reload, so the
     * first animation pose is on screen from the first tick and the last one
     * is on screen before the reload completes. Both ends used to fall
     * outside the walk, which is why a reload showed a handful of poses
     * instead of the whole animation.</p>
     */
    private void applyReloadFrames(Player player, ItemStack stack, Session session, WeaponDefinition weapon) {
        ItemFrames frames = weapon.frames();
        if (frames == null || !frames.isFramed()) {
            return;
        }
        int animations = frames.reloadReferences().size();
        if (animations == 0) {
            return;
        }
        float elapsed = (tick - session.reloadStartTick + 1.0f) / Math.max(1, weapon.reloadTicks());
        int state = frames.reloadState(elapsed);
        if (state == session.reloadFrame) {
            return;
        }
        // Hold each pose long enough for the client to render one deliberate
        // frame switch, not a strobe: too-fast flips replay the equip raise
        // animation every time and read as jitter. The hold never outlasts
        // the pose's own share of the reload, so no pose is skipped.
        int hold = Math.max(1, Math.min(RELOAD_FRAME_HOLD_TICKS,
                weapon.reloadTicks() / Math.max(1, animations * 2)));
        if (tick - session.reloadFrameAtTick < hold) {
            return;
        }
        session.reloadFrame = state;
        session.reloadFrameAtTick = tick;
        // The reload owns the frame while it runs, so the session has to
        // remember what is on the stack: otherwise the loaded pose is never
        // written when the reload ends and the weapon stays on its last
        // loading frame.
        session.appliedFrame = state;
        writeFrame(player, stack, weapon, state);
    }

    /**
     * Lines the held stack's state up with the weapon (loaded visual while
     * rounds remain, idle when empty); the reload owns the frames while it
     * runs, except for weapons without reload states (muzzle-loaders keep
     * their idle pose while ramming). A weapon that declares a fire pose
     * shows it right after the shot and settles back on the loaded pose.
     */
    private void reconcileFrame(Player player, ItemStack stack, Session session, WeaponDefinition weapon) {
        ItemFrames frames = weapon.frames();
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
        } else if (tick < session.fireUntil && frames.hasFireModel()) {
            wanted = frames.fireState();
        } else {
            int rounds = session.magazine != null
                    ? session.magazine.rounds()
                    : factory().rounds(stack, weapon);
            wanted = rounds > 0 ? frames.loadedState() : frames.idleState();
        }
        if (wanted != session.appliedFrame) {
            session.appliedFrame = wanted;
            writeFrame(player, stack, weapon, wanted);
        }
    }

    /**
     * Renders one frame state: the stack custom-model-data value is the
     * pack-mapped {@code frameStackValue} (identical on every representation),
     * and the held stack is written back so the change reaches the shooter's
     * client as a slot update instead of only mutating the server mirror.
     */
    private void writeFrame(Player player, ItemStack stack, WeaponDefinition weapon, int state) {
        if (ItemFrameWriter.apply(stack, weapon.frameStackValue(state))) {
            player.getInventory().setItemInMainHand(stack);
        }
    }

    /**
     * Rests the weapon on its idle pose when it leaves the hand.
     *
     * <p>The release routes run while the main hand still holds the stack (a
     * slot switch, a sneak, an inventory close), so the resting pose is
     * written onto the stack that is on its way out: in the inventory and on
     * the ground the weapon shows the pose it was designed to rest in, not the
     * aiming or recoil pose it happened to be in when the player let go.
     * Picking it up again restores the live pose (loaded while rounds remain)
     * on the next tick.</p>
     *
     * <p>The stack is located in the inventory rather than assumed to be in the
     * main hand: a release route that already moved the stack would otherwise
     * rest the wrong item — or none at all.</p>
     */
    private void restFrame(Player player, Session session) {
        WeaponDefinition weapon = session.weapon;
        if (weapon == null || !weapon.hasFrames()) {
            return;
        }
        ItemFrames frames = weapon.frames();
        if (frames == null || !frames.isFramed()) {
            return;
        }
        int idle = frames.idleState();
        if (session.appliedFrame == idle) {
            return;
        }
        session.appliedFrame = idle;
        PlayerInventory inventory = player.getInventory();
        int slot = findWeaponSlot(inventory, session.weaponId);
        if (slot < 0) {
            return;
        }
        ItemStack stack = inventory.getItem(slot);
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        if (ItemFrameWriter.apply(stack, weapon.frameStackValue(idle))) {
            inventory.setItem(slot, stack);
        }
    }

    /**
     * The inventory slot that holds the weapon: the main hand when it still
     * does, the first matching stack otherwise. {@code -1} when the weapon is
     * gone from the inventory (dropped, destroyed).
     */
    private int findWeaponSlot(PlayerInventory inventory, String weaponId) {
        if (weaponId == null || weaponId.isEmpty()) {
            return -1;
        }
        ItemStack held = inventory.getItemInMainHand();
        if (held != null && !held.getType().isAir() && weaponId.equals(factory().weaponId(held))) {
            return inventory.getHeldItemSlot();
        }
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack candidate = contents[slot];
            if (candidate == null || candidate.getType().isAir()) {
                continue;
            }
            if (weaponId.equals(factory().weaponId(candidate))) {
                return slot;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // HUD
    // ------------------------------------------------------------------

    private void updateHud(Player player, Session session, WeaponDefinition weapon) {
        if (!weapon.usesAmmo()) {
            return;
        }
        int rounds = session.magazine != null
                ? session.magazine.rounds()
                : factory().rounds(player.getInventory().getItemInMainHand(), weapon);
        GunConfig.Ammo supply = services.config().ammo();
        int reserve = AmmoSupply.rounds(player, supply.material(), supply.itemsPerRound());
        services.ammoHud().update(player, weapon, rounds, reserve, session.reloading, session.scoping);
    }

    /** The reserve supply's display name, e.g. {@code "gold nuggets"}. */
    private static String supplyName(GunConfig.Ammo supply) {
        Material material = supply.material();
        String raw = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return material == Material.GOLD_NUGGET ? "gold nuggets" : raw + 's';
    }

    // ------------------------------------------------------------------
    // Live magazine (Core deferred persistence)
    // ------------------------------------------------------------------

    /**
     * The live magazine of the held stack. Reopens (and flushes the previous
     * one) when the shooter switches to a different stack; a stack that has no
     * identity yet is tagged by {@link ItemMagazine#open} and mirrored once,
     * which is the only write the held item ever gets between shots.
     */
    private ItemMagazine.Live magazine(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
        String uid = factory().uid(stack);
        if (session.magazine != null && uid != null && uid.equals(session.magazine.uid())) {
            return session.magazine;
        }
        flushMagazine(player, session);
        ItemMagazine.Live live = factory().magazine().open(stack, WeaponItemFactory.capacity(weapon));
        session.magazine = live;
        if (uid == null && live.uid() != null) {
            // The stack just gained its identity: persist it (a one-time write).
            player.getInventory().setItemInMainHand(stack);
        }
        return live;
    }

    /** Persists the session's live magazine onto the matching inventory stack. */
    private void flushMagazine(Player player, Session session) {
        ItemMagazine.Live magazine = session.magazine;
        session.magazine = null;
        if (magazine == null) {
            return;
        }
        factory().magazine().flush(player, magazine);
    }

    // ------------------------------------------------------------------
    // Helpers / lifecycle
    // ------------------------------------------------------------------

    private WeaponItemFactory factory() {
        return services.itemFactory();
    }

    /** The Core-owned trigger state of a player, or null when unavailable. */
    private TriggerState trigger(Player player) {
        return services.context().heldItemInput().trigger(player);
    }

    public WeaponDefinition resolve(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || services.registry() == null) {
            return null;
        }
        String id = factory().weaponId(stack);
        WeaponDefinition weapon = id == null ? null : services.registry().get(id);
        if (weapon == null) {
            // Anomalies only: stacks carrying THIS module's stamps (or an
            // unknown id stamp) that fail to resolve — half-migrated or
            // registry-drifted stacks. Vanilla items stay silent.
            String stamp = factory().readWeaponStamp(stack);
            String uid = factory().uid(stack);
            if (stamp != null || uid != null) {
                plugin.getLogger().warning("[MineplusGun] held item is stamped as a gun but does not"
                        + " resolve: item=" + stack.getType() + " stamp=" + stamp + " uid="
                        + (uid == null ? "none" : uid.substring(0, Math.min(8, uid.length())))
                        + " knownWeapons=" + services.registry().size());
            }
        }
        return weapon;
    }

    /** Drops one player's session, persisting the magazine first. */
    public void clear(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            flushMagazine(player, session);
        }
        services.ammoHud().clear(player.getUniqueId());
    }

    public void clearAll() {
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                flushMagazine(player, entry.getValue());
            }
        }
        sessions.clear();
        services.ammoHud().clearAll();
    }

    public int activeSessions() {
        return sessions.size();
    }

    /** One-line telemetry for the module's status command. */
    public String telemetry() {
        return "shooters=" + sessions.size() + " " + services.ballistics().telemetrySummary();
    }

    /**
     * The module's aim rules on top of the Core engine's defaults. Targeting is
     * the one place a weapon module genuinely differs from server to server —
     * a PvP-only arsenal must not wound wildlife — so it is expressed here and
     * everything else (damage, falloff, effects) stays Core-owned.
     */
    private final class GunRounds implements ProjectileHandler {

        @Override
        public boolean allowTarget(Entity candidate, Player shooter) {
            if (!(candidate instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                return false;
            }
            if (shooter != null && candidate.getUniqueId().equals(shooter.getUniqueId())) {
                return false;
            }
            GunConfig current = services.config();
            if (current != null) {
                if (current.settings().pvpOnly() && !(candidate instanceof Player)) {
                    return false;
                }
                if (living.getWorld() != null
                        && current.settings().worldDisabled(living.getWorld().getName())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean onEntityHit(ProjectileHit hit) {
            Player shooter = hit.shooter();
            if (shooter != null) {
                // Hit marker: a short native tick, the confirmation that works
                // for every client without a pack or chat spam.
                Sounds.playTo(shooter, HIT_MARKER_SOUND, 0.5f, hit.headshot() ? 2.2f : 1.7f);
            }
            return true;
        }
    }

    private static final class Session {
        final UUID playerId;
        String weaponId = "";
        WeaponDefinition weapon;
        boolean firing;
        boolean reloading;
        boolean scoping;
        boolean capped;
        boolean queuedPress;
        int burstRemaining;
        int holdTicks;
        int reloadFrame = -1;
        long reloadFrameAtTick;
        long reloadStartTick;
        long fireUntil;
        int appliedFrame = -1;
        long nextShotTick;
        long reloadEndTick;
        ItemMagazine.Live magazine;

        Session(UUID playerId) {
            this.playerId = playerId;
        }

        /**
         * Drops everything that belongs to the weapon the session just left.
         * The magazine is persisted before this runs, so the rounds are safe;
         * what goes here is the state that must not leak into the next gun.
         */
        void resetTransient() {
            firing = false;
            reloading = false;
            scoping = false;
            capped = false;
            queuedPress = false;
            burstRemaining = 0;
            holdTicks = 0;
            reloadFrame = -1;
            reloadFrameAtTick = 0;
            reloadStartTick = 0;
            reloadEndTick = 0;
            fireUntil = 0;
            appliedFrame = -1;
            nextShotTick = 0;
            magazine = null;
            weapon = null;
        }
    }
}
