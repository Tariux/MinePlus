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
import com.mineplus.infrastructure.registry.ItemMagazine;
import com.mineplus.pack.item.ItemFrameWriter;
import com.mineplus.pack.item.ItemFrames;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * The per-player fire-flow state machine.
 *
 * <p>What this class owns is only the part no server can own generically: the
 * trigger gesture (hold-to-fire, re-press gating, burst counting, scope hold),
 * the reload economy against the player's nugget supply, the weapon frame
 * progression, and the action-bar HUD cadence. Everything else is Core
 * infrastructure reached through the services —
 * {@link HeldItemInput} delivers the press (including the one aimed at an
 * entity, which never reaches a plain {@code PlayerInteractEvent}),
 * {@link com.mineplus.infrastructure.combat.Ballistics} simulates the round and
 * lands its damage transactionally, {@link ItemMagazine} meters the rounds, and
 * {@link com.mineplus.infrastructure.fx.Sounds} emits the report without ever
 * letting an unresolvable custom event unwind the shot.
 *
 * <p><b>Controls.</b> Right-click is the one and only trigger (hold for AUTO);
 * left-click is inert; swapping hands reloads — pulling the needed rounds out
 * of the shooter's gold-nugget supply.
 *
 * <p><b>Frames.</b> A framed weapon animates through its reload frames during
 * the reload and settles on its loaded state while rounds remain; a frame is
 * only written when it actually changes, because every write is a slot update
 * to the client.
 *
 * <p><b>Threading.</b> All input callbacks and the one repeating task run on the
 * main thread.
 */
public final class GunRuntime implements HeldItemInput.HeldItemHandler {

    /** Ticks without a trigger repeat before automatic fire / scoping stops. */
    private static final long AUTO_RELEASE_TICKS = 8L;

    /** Minimum gap between trigger presses for a new SEMI/PUMP/BOLT/BURST shot. */
    private static final long SEMI_REPRESS_TICKS = 6L;

    /** Ticks the per-shot recoil pose holds before the frame settles back. */
    private static final int RECOIL_TICKS = 3;

    /** Minimum ticks between reload frame switches (avoids client equip strobe). */
    private static final int RELOAD_FRAME_HOLD_TICKS = 8;

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
     * firing — the client repeats the use packet while the button is held),
     * scoped weapons tighten their spread while held, melee swings. Left-click
     * is frozen by the input service because firing from it would replay the
     * client's arm-punch animation on every shot.
     */
    @Override
    public boolean onPress(Player player, ItemStack stack) {
        WeaponDefinition weapon = resolve(stack);
        if (weapon == null) {
            return false;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), Session::new);
        session.weaponId = weapon.id();
        PressTrace.trace(plugin, PressTrace.key(player.getUniqueId(), "press"),
                "[MineplusGun] press by " + player.getName() + ": weapon=" + weapon.id()
                        + " mode=" + weapon.fireMode());
        if (weapon.isMelee()) {
            if (tick >= session.nextShotTick) {
                meleeAttack(player, weapon);
                session.nextShotTick = tick + weapon.shotIntervalTicks();
            }
            return true;
        }
        trigger(player, session, weapon, stack);
        return true;
    }

    /**
     * The item left the hand: stop the trigger and persist the live magazine,
     * which is exactly the moment the Core's deferred write is designed for.
     */
    @Override
    public void onRelease(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        session.firing = false;
        session.burstRemaining = 0;
        if (!session.weaponId.isEmpty()) {
            flushMagazine(player, session);
            session.resetTransient();
            session.weaponId = "";
        }
    }

    /** Swap-hands is the reload gesture; it is consumed whenever a reload can run. */
    @Override
    public boolean onSwapHands(Player player, ItemStack stack) {
        WeaponDefinition weapon = resolve(stack);
        if (weapon == null || !weapon.usesAmmo()) {
            return false;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), Session::new);
        session.weaponId = weapon.id();
        return startReload(player, session, weapon, stack);
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
     * One trigger event (a right-click press; the client repeats the use packet
     * roughly every four ticks while the button is held). AUTO keeps firing as
     * long as those repeats keep arriving; BURST and SEMI/PUMP/BOLT need a
     * genuine re-press, so holding the button cannot machine-gun them. A scoped
     * weapon scopes while the trigger repeats and unscopes when they stop.
     */
    private void trigger(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
        long gap = tick - session.lastTriggerTick;
        session.lastTriggerTick = tick;
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
                if (tick < session.nextShotTick) {
                    PressTrace.debug("[MineplusGun] trigger suppressed (cooldown, "
                            + (session.nextShotTick - tick) + " ticks left) for " + player.getName());
                    return;
                }
                if (gap <= SEMI_REPRESS_TICKS) {
                    PressTrace.debug("[MineplusGun] trigger suppressed (re-press faster than "
                            + SEMI_REPRESS_TICKS + " ticks) for " + player.getName());
                    return;
                }
                shoot(player, session, weapon, stack);
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
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }
            ItemStack stack = player.getInventory().getItemInMainHand();
            WeaponDefinition weapon = resolve(stack);
            // The world this player is in may have been disabled since the
            // session started; treat that exactly like the item leaving the
            // hand, so the live magazine is persisted and nothing fires.
            boolean inPlay = weapon != null && weapon.id().equals(session.weaponId) && enabledIn(player);
            if (!inPlay) {
                // The weapon left the hand (or was swapped): persist its live
                // magazine and forget the trigger state. A session that never
                // held a weapon (weaponId empty) skips the churn entirely.
                if (!session.weaponId.isEmpty()) {
                    PressTrace.trace(plugin, PressTrace.key(player.getUniqueId(), "session-flip"),
                            "[MineplusGun] session reset for " + player.getName() + ": was=" + session.weaponId
                                    + " held=" + (weapon == null ? stack.getType() + " (not a weapon)"
                                    : weapon.id()) + " reloading=" + session.reloading);
                    flushMagazine(player, session);
                    session.resetTransient();
                    session.weaponId = "";
                }
                continue;
            }

            // The scope is held with the trigger and drops as soon as the
            // trigger repeats stop (the carrier is neutral, so there is no
            // vanilla use state to read).
            if (!weapon.scope() || tick - session.lastTriggerTick > AUTO_RELEASE_TICKS) {
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
                        && tick - session.lastTriggerTick > AUTO_RELEASE_TICKS;
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
        punchRecoil(session, weapon);
        updateHud(player, session, weapon);
    }

    /**
     * Per-shot visual kick: a framed weapon flashes its first reload pose for
     * a few ticks (hammer/lever action) before settling back on the loaded
     * frame — the server-side stand-in for a fire animation. Recoil pauses while
     * a reload owns the frames.
     */
    private void punchRecoil(Session session, WeaponDefinition weapon) {
        if (session.reloading || !weapon.hasFrames()) {
            return;
        }
        ItemFrames frames = weapon.frames();
        if (frames != null && !frames.reloadReferences().isEmpty()) {
            session.recoilEndTick = tick + RECOIL_TICKS;
        }
    }

    private void dryFire(Player player, Session session, WeaponDefinition weapon) {
        session.firing = false;
        session.burstRemaining = 0;
        // Gate the empty-click cadence like a shot, so spam-clicking an empty
        // gun clicks once per interval instead of machine-gunning the sound.
        session.nextShotTick = tick + Math.max(4, weapon.shotIntervalTicks() / 2);
        GunConfig.Ammo supply = services.config().ammo();
        int reserve = AmmoSupply.count(player, supply.material());
        PressTrace.trace(plugin, PressTrace.key(player.getUniqueId(), "dry-fire"),
                "[MineplusGun] DRY FIRE by " + player.getName() + ": " + weapon.id() + " magazine empty,"
                        + " reserve(" + supply.material().name().toLowerCase(Locale.ROOT) + ")=" + reserve
                        + " (swap hands to reload)");
        Sounds.playTo(player, weapon.soundEmpty(), 0.7f, 1.0f, org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT);
        services.ammoHud().flashEmpty(player, weapon, reserve);
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

    /** @return {@code true} when a reload actually started (the swap is consumed) */
    private boolean startReload(Player player, Session session, WeaponDefinition weapon, ItemStack stack) {
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
            return false;
        }
        GunConfig.Ammo supply = services.config().ammo();
        int nuggets = AmmoSupply.count(player, supply.material());
        if (nuggets <= 0) {
            // Out of nuggets: the reload simply never starts — make that legible.
            PressTrace.trace(plugin, PressTrace.key(player.getUniqueId(), "reload"),
                    "[MineplusGun] reload cannot start: no " + supply.material().name()
                            + " in inventory for " + player.getName());
            Sounds.playTo(player, weapon.soundEmpty(), 0.7f, 1.2f, org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT);
            services.ammoHud().flashNoReserve(player);
            return false;
        }
        PressTrace.trace(plugin, PressTrace.key(player.getUniqueId(), "reload"),
                "[MineplusGun] reload started: " + weapon.id() + " need=" + missing + " reserve=" + nuggets
                        + " " + supply.material().name().toLowerCase(Locale.ROOT)
                        + " duration=" + weapon.reloadTicks() + "t for " + player.getName());
        session.firing = false;
        session.burstRemaining = 0;
        if (weapon.reloadTicks() <= 0) {
            completeReload(player, session, weapon, stack);
            return true;
        }
        session.reloading = true;
        session.reloadEndTick = tick + weapon.reloadTicks();
        session.reloadFrame = -1;
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
     */
    private void applyReloadFrames(Player player, ItemStack stack, Session session, WeaponDefinition weapon) {
        ItemFrames frames = weapon.frames();
        if (frames == null || !frames.isFramed()) {
            return;
        }
        float progress = (session.reloadEndTick - tick) / (float) Math.max(1, weapon.reloadTicks());
        int state = frames.reloadState(1.0f - progress);
        if (state == session.reloadFrame) {
            return;
        }
        // Hold each pose long enough for the client to render one deliberate
        // frame switch, not a strobe: too-fast flips replay the equip raise
        // animation every time and read as jitter.
        if (tick - session.reloadFrameAtTick < RELOAD_FRAME_HOLD_TICKS) {
            return;
        }
        session.reloadFrame = state;
        session.reloadFrameAtTick = tick;
        writeFrame(player, stack, weapon, state);
    }

    /**
     * Lines the held stack's state up with the weapon (loaded visual while
     * rounds remain, idle when empty); the reload owns the frames while it
     * runs, except for weapons without reload states (muzzle-loaders keep
     * their idle pose while ramming).
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
        } else if (tick < session.recoilEndTick && !frames.reloadReferences().isEmpty()) {
            wanted = 1; // recoil pose: the first staged frame flinches the gun
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
        int reserve = AmmoSupply.count(player, supply.material());
        services.ammoHud().update(player, weapon, rounds, reserve, session.reloading, session.scoping);
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
        boolean firing;
        boolean reloading;
        boolean scoping;
        int burstRemaining;
        int holdTicks;
        int reloadFrame = -1;
        long reloadFrameAtTick;
        long recoilEndTick;
        int appliedFrame = -1;
        long nextShotTick;
        long reloadEndTick;
        long lastTriggerTick = -1000L;
        ItemMagazine.Live magazine;

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
            reloadFrameAtTick = 0;
            recoilEndTick = 0;
            appliedFrame = -1;
            magazine = null;
        }
    }
}
