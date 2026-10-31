package com.mineplus.gun.runtime;

import com.mineplus.gun.GunServices;
import com.mineplus.gun.MineplusGunPlugin;
import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.event.WeaponFireEvent;
import com.mineplus.gun.event.WeaponHitEvent;
import com.mineplus.gun.event.WeaponReloadEvent;
import com.mineplus.gun.weapon.WeaponDefinition;
import com.mineplus.infrastructure.combat.BallisticProfile;
import com.mineplus.infrastructure.combat.ProjectileHandler;
import com.mineplus.infrastructure.combat.ProjectileHit;
import com.mineplus.infrastructure.fx.ParticleFx;
import com.mineplus.infrastructure.fx.Sounds;
import com.mineplus.infrastructure.input.HeldItemInput;
import com.mineplus.infrastructure.registry.ItemMagazine;
import com.mineplus.pack.item.ItemFrames;
import com.mineplus.pack.item.ItemFrameAnimator;
import com.mineplus.pack.item.ItemFrameWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * The gun runtime: right-click fires, left-click is reserved for the scope
 * (and is otherwise swallowed), swap-hands reloads, empty guns reload
 * themselves.
 *
 * <p><b>Event-driven, not scanned.</b> There is no all-player loop. A session
 * is opened when a player holds a gun or first acts with one, and a single
 * one-tick task is armed only while some session actually has work (a reload, a
 * burst, an automatic trigger, a pose animation, recoil decay). When every
 * session is idle the task retires, so a server full of holstered guns costs
 * nothing.</p>
 *
 * <p><b>Every presentation is optional.</b> The runtime asks the frame spec for
 * a clip before playing it; a weapon with no fire pose, no reload frames or no
 * scope animation still fires, reloads and scopes. Missing frames degrade to
 * "the weapon keeps its resting pose", never to a broken weapon.</p>
 *
 * <p><b>Recoil is dispersion, not camera.</b> A server cannot rotate a modern
 * client's view ({@code Player#setRotation} throws), so a shot widens the next
 * shot's cone and that cone recovers over time. Movement adds its own penalty,
 * and a scope trades a smaller base cone for a harsher movement penalty.</p>
 */
public final class WeaponRuntime implements HeldItemInput.HeldItemHandler {

    /** Ticks an empty gun waits before reloading itself. */
    private static final int AUTO_RELOAD_DELAY_TICKS = 6;
    /** How long the shot pose is held. */
    private static final int FIRE_POSE_TICKS = 4;
    private static final int SCOPE_IN_TICKS = 6;
    private static final int SCOPE_OUT_TICKS = 5;
    private static final int EQUIP_TICKS = 8;
    /** Minimum gap between two hit-marker sounds (a shotgun lands many pellets). */
    private static final long HITMARK_INTERVAL_MILLIS = 90L;
    private static final double RECOIL_EPSILON = 0.01;

    private final MineplusGunPlugin plugin;
    private final GunServices services;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> hitmarkAt = new HashMap<>();
    private final ProjectileHandler projectileHandler = new WeaponHitHandler();
    /**
     * True when the pack presents this item through a modern item-model
     * definition, which is the only representation that can serialize a
     * client-clock sequence (nested {@code range_dispatch} on the item's
     * cooldown). Legacy predicate packs keep the server animator.
     */
    private boolean clientClock;

    private BukkitTask task;

    public WeaponRuntime(MineplusGunPlugin plugin, GunServices services) {
        this.plugin = plugin;
        this.services = services;
        this.clientClock = services.context().packApi().usesModernItemModels();
    }    public void start() {
        // Nothing to schedule; the first trigger arms the tick task.
    }

    /** Flushes every live magazine and stops the task. */
    public void stop() {
        for (Session session : sessions.values()) {
            Player player = Bukkit.getPlayer(session.playerId);
            if (player == null) {
                continue;
            }
            if (session.magazine != null) {
                services.itemFactory().magazine().flush(player, session.magazine);
            }
            clearCooldown(player, session.weapon);
        }
        sessions.clear();
        hitmarkAt.clear();
        BukkitTask current = task;
        task = null;
        if (current != null) {
            current.cancel();
        }
    }

    /** The live-fire telemetry line printed by {@code /mpgun stats}. */
    public String telemetry() {
        return "shooters=" + sessions.size()
                + " animation=" + (clientClock ? "client-clock" : "server-frames")
                + " " + services.ballistics().telemetrySummary();
    }

    /**
     * True when this server's pack representation lets a clip play on the
     * client's own clock (one state write plus one item cooldown, then the
     * client walks the frames). Legacy predicate packs fall back to the server
     * frame animator.
     */
    public boolean clientClock() {
        return clientClock;
    }

    /**
     * How the held weapon will animate, one line per clip — the diagnostic
     * behind {@code /mpgun debug anim}.
     */
    public List<String> animationReport(WeaponDefinition weapon) {
        List<String> lines = new ArrayList<>();
        if (weapon == null) {
            return lines;
        }
        ItemFrames frames = weapon.frames();
        if (frames == null) {
            lines.add("no model — the weapon renders on its vanilla carrier");
            return lines;
        }
        lines.add("clock: " + (clientClock
                ? "client (cooldown range_dispatch, no per-frame packets)"
                : "server (legacy custom_model_data predicates)"));
        lines.add("states: " + frames.references().size()
                + " (idle=" + frames.idleState() + ", loaded=" + frames.loadedState() + ")");
        for (ItemFrames.Sequence sequence : frames.plan().sequences()) {
            lines.add("sequence " + sequence.name() + ": " + sequence.frames().size()
                    + " frame(s), gate state " + sequence.gateState());
        }
        for (ItemFrames.Clip clip : frames.clips()) {
            lines.add("clip " + clip.name() + ": " + clip.frameCount()
                    + " frame(s)" + (clip.loop() ? ", looping" : ""));
        }
        return lines;
    }

    /** The weapon the stack belongs to, or {@code null}. */
    public WeaponDefinition resolve(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        String id = services.itemFactory().weaponId(stack);
        return id == null ? null : services.registry().get(id);
    }

    // ------------------------------------------------------------------
    // HeldItemHandler
    // ------------------------------------------------------------------

    @Override
    public boolean owns(ItemStack stack) {
        return resolve(stack) != null;
    }

    @Override
    public boolean owns(ItemStack stack, Player holder) {
        WeaponDefinition weapon = resolve(stack);
        if (weapon == null || holder == null) {
            return weapon != null;
        }
        GunConfig config = services.config();
        return config == null || !config.settings().worldDisabled(holder.getWorld().getName());
    }

    /** Legacy alias; the secondary trigger is the gun's fire button. */
    @Override
    public boolean onPress(Player player, ItemStack held) {
        return onSecondaryPress(player, held);
    }

    /**
     * Right-click: fire. The client repeats the press while the button is
     * held, and the Core folds those repeats into a held gesture — a fresh
     * press starts a shot, an automatic weapon keeps firing while
     * {@link HeldItemInput#secondaryTrigger(Player)} reports the hold.
     */
    @Override
    public boolean onSecondaryPress(Player player, ItemStack held) {
        WeaponDefinition weapon = resolve(held);
        if (weapon == null) {
            return false;
        }
        Session session = ensure(player, held, weapon);
        if (session == null) {
            return true;
        }
        session.autoHeld = true;
        boolean fresh = services.context().heldItemInput().secondaryTrigger(player).freshPress();
        if (fresh) {
            switch (weapon.fireMode()) {
                case SEMI, PUMP, BOLT -> shoot(player, session, weapon);
                case BURST -> {
                    if (shoot(player, session, weapon)) {
                        session.burstRemaining = Math.max(0, weapon.burstCount() - 1);
                    }
                }
                case AUTO -> shoot(player, session, weapon);
            }
        }
        arm();
        return true;
    }

    /**
     * Left-click: scopes a weapon that has one, and is otherwise swallowed.
     * The gesture never reaches vanilla, which keeps the left button free for
     * future aimed actions without ever punching, mining or attacking.
     */
    @Override
    public boolean onPrimaryPress(Player player, ItemStack held) {
        WeaponDefinition weapon = resolve(held);
        if (weapon == null) {
            return false;
        }
        Session session = ensure(player, held, weapon);
        if (session != null && weapon.scope() && !session.reloading) {
            session.scoped = !session.scoped;
            playScopePose(player, session, weapon);
            pushHud(player, session, weapon);
            Sounds.playTo(player, "block.lever.click", 0.35f, session.scoped ? 1.7f : 1.4f,
                    Sound.BLOCK_LEVER_CLICK);
            arm();
        }
        return true;
    }

    /** F: reload (the Core consumes the swap so the item stays in hand). */
    @Override
    public boolean onSwapHands(Player player, ItemStack held) {
        WeaponDefinition weapon = resolve(held);
        if (weapon == null || !weapon.usesAmmo()) {
            return false;
        }
        Session session = ensure(player, held, weapon);
        if (session != null) {
            beginReload(player, session, weapon, false);
            arm();
        }
        return true;
    }

    @Override
    public void onSecondaryRelease(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.autoHeld = false;
        }
    }

    /** The item left the hand: persist its magazine and drop the session. */
    @Override
    public void onRelease(Player player) {
        close(player, true);
        hitmarkAt.remove(player.getUniqueId());
    }

    @Override
    public boolean suppressVanillaMelee(Player attacker, org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        return true;
    }

    @Override
    public boolean freezeVanillaInteractions() {
        return true;
    }

    // ------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------

    /** Re-opens the session for whatever the player is holding now. */
    public void refresh(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        close(player, true);
        WeaponDefinition weapon = resolve(player.getInventory().getItemInMainHand());
        if (weapon == null) {
            return;
        }
        Session session = ensure(player, player.getInventory().getItemInMainHand(), weapon);
        if (session != null) {
            playEquipPose(player, session, weapon);
            pushHud(player, session, weapon);
            arm();
        }
    }

    /** Re-reads every online player's session (config/weapon reload). */
    public void refreshAll() {
        // The representation is fixed by the server version, but recomputing it
        // here keeps a reload honest if the pack subsystem itself restarted.
        clientClock = services.context().packApi().usesModernItemModels();
        closeAll();
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
    }

    /** Persists and drops one player's session (quit). */
    public void clear(Player player) {
        close(player, true);
        hitmarkAt.remove(player.getUniqueId());
    }

    private void closeAll() {
        for (Session session : new ArrayList<>(sessions.values())) {
            Player player = Bukkit.getPlayer(session.playerId);
            if (player != null) {
                if (session.magazine != null) {
                    services.itemFactory().magazine().flush(player, session.magazine);
                }
                clearCooldown(player, session.weapon);
            }
        }
        sessions.clear();
    }

    private void close(Player player, boolean flush) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.animator != null) {
            session.animator.stop();
        }
        if (flush && session.magazine != null) {
            services.itemFactory().magazine().flush(player, session.magazine);
        }
        // A sequence's cooldown lives on the player, not the item: leaving it
        // running would animate a gun that is no longer in hand.
        clearCooldown(player, session.weapon);
    }

    private Session ensure(Player player, ItemStack held, WeaponDefinition weapon) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.weapon == weapon && sameStack(session, held)) {
            return session;
        }
        if (session != null) {
            close(player, true);
        }
        session = open(player, held, weapon);
        sessions.put(player.getUniqueId(), session);
        pushHud(player, session, weapon);
        arm();
        return session;
    }

    private Session open(Player player, ItemStack held, WeaponDefinition weapon) {
        ItemMagazine.Live magazine = weapon.usesAmmo()
                ? services.itemFactory().magazine().open(held, weapon.magazine())
                : null;
        ItemFrameAnimator animator = weapon.hasFrames() ? new ItemFrameAnimator(weapon.frames()) : null;
        Session session = new Session(player.getUniqueId(), weapon, magazine, animator);
        rest(player, session, weapon);
        return session;
    }

    /**
     * True when the session's live magazine still belongs to the held stack.
     * Two stacks of the same weapon id are different guns: the second one
     * carries its own identity tag and its own rounds, so a session must not
     * keep spending the first one's magazine after a swap.
     */
    private boolean sameStack(Session session, ItemStack held) {
        if (session.magazine == null) {
            return true;
        }
        String uid = session.magazine.uid();
        return uid == null || uid.equals(services.itemFactory().magazine().uid(held));
    }

    // ------------------------------------------------------------------
    // Tick loop
    // ------------------------------------------------------------------

    private void arm() {
        if (task != null || !plugin.isEnabled()) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    private void tickAll() {
        boolean busy = false;
        List<Player> reopen = null;
        Iterator<Map.Entry<UUID, Session>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Session> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }
            Session session = entry.getValue();
            GunConfig config = services.config();
            if (config != null && config.settings().worldDisabled(player.getWorld().getName())) {
                // Retire in place: close() mutates the map, which would break
                // the iterator before its own remove().
                if (session.animator != null) {
                    session.animator.stop();
                }
                if (session.magazine != null) {
                    services.itemFactory().magazine().flush(player, session.magazine);
                }
                clearCooldown(player, session.weapon);
                iterator.remove();
                continue;
            }
            ItemStack held = player.getInventory().getItemInMainHand();
            WeaponDefinition weapon = resolve(held);
            if (weapon != session.weapon || !sameStack(session, held)) {
                if (session.magazine != null) {
                    services.itemFactory().magazine().flush(player, session.magazine);
                }
                if (session.animator != null) {
                    session.animator.stop();
                }
                clearCooldown(player, session.weapon);
                iterator.remove();
                if (weapon != null) {
                    // Same weapon, different stack: reopen on the stack that is
                    // actually in hand once the iteration is done.
                    if (reopen == null) {
                        reopen = new ArrayList<>();
                    }
                    reopen.add(player);
                }
                continue;
            }
            busy |= tickSession(player, session, weapon);
        }
        if (reopen != null) {
            for (Player player : reopen) {
                refresh(player);
            }
            busy = true;
        }
        if (!busy) {
            BukkitTask current = task;
            task = null;
            if (current != null) {
                current.cancel();
            }
        }
    }

    /** @return {@code true} when this session still needs a tick */
    private boolean tickSession(Player player, Session session, WeaponDefinition weapon) {
        if (session.fireCooldown > 0) {
            session.fireCooldown--;
        }
        if (session.emptyFlashCooldown > 0) {
            session.emptyFlashCooldown--;
        }
        if (session.recoil > 0.0) {
            session.recoil = Math.max(0.0, session.recoil - weapon.recoilRecovery());
        }
        if (session.reloading) {
            tickReload(player, session, weapon);
        } else if (session.autoReloadDelay > 0) {
            session.autoReloadDelay--;
            if (session.autoReloadDelay == 0) {
                beginReload(player, session, weapon, true);
            }
        }

        if (session.burstRemaining > 0 && session.fireCooldown <= 0 && !session.reloading) {
            if (shoot(player, session, weapon)) {
                session.burstRemaining--;
            }
        } else if (session.autoHeld && weapon.fireMode() == FireMode.AUTO
                && session.fireCooldown <= 0 && !session.reloading
                && services.context().heldItemInput().secondaryTrigger(player).held()) {
            shoot(player, session, weapon);
        }

        if (session.sequenceTicks > 0) {
            session.sequenceTicks--;
            if (session.sequenceTicks == 0) {
                finishPose(player, session, weapon, session.pose);
            }
        }

        tickPose(player, session, weapon);
        return needsTick(player, session, weapon);
    }

    private boolean needsTick(Player player, Session session, WeaponDefinition weapon) {
        return session.fireCooldown > 0
                || session.reloading
                || session.autoReloadDelay > 0
                || session.burstRemaining > 0
                || session.sequenceTicks > 0
                || session.recoil > RECOIL_EPSILON
                || (session.animator != null && session.animator.active())
                || (session.autoHeld && weapon.fireMode() == FireMode.AUTO
                        && services.context().heldItemInput().secondaryTrigger(player).held());
    }

    // ------------------------------------------------------------------
    // Firing
    // ------------------------------------------------------------------

    private boolean shoot(Player player, Session session, WeaponDefinition weapon) {
        if (session.reloading || session.fireCooldown > 0) {
            return false;
        }
        if (weapon.usesAmmo() && (session.magazine == null || session.magazine.empty())) {
            dryFire(player, session, weapon);
            return false;
        }
        // The veto point sits after the "has a round" check and before the
        // round is spent, so a cancelled shot costs nothing at all.
        if (!WeaponFireEvent.allow(player, player.getInventory().getItemInMainHand(), weapon)) {
            return false;
        }

        boolean scoped = session.scoped && weapon.scope();
        double movement = weapon.movementPenalty(scoped, player.isSprinting(),
                !player.isOnGround(), moving(player));
        BallisticProfile profile = weapon.profile(scoped, movement + session.recoil);

        Location origin = player.getEyeLocation();
        Vector aim = origin.getDirection();
        int launched = services.ballistics().fireWeapon(player, origin, aim, profile, projectileHandler);
        if (launched <= 0) {
            return false;
        }

        if (session.magazine != null) {
            session.magazine.spend();
        }
        session.fireCooldown = shotCooldown(weapon);
        double recoil = weapon.recoilPerShot() * (scoped ? weapon.scopeRecoilMultiplier() : 1.0);
        session.recoil = Math.min(weapon.recoilMax(), session.recoil + recoil);
        playShotPose(player, session, weapon);

        if (weapon.usesAmmo() && session.magazine != null && session.magazine.empty()) {
            session.autoReloadDelay = AUTO_RELOAD_DELAY_TICKS;
        }
        pushHud(player, session, weapon);
        arm();
        return true;
    }

    private static int shotCooldown(WeaponDefinition weapon) {
        int cadence = weapon.shotIntervalTicks();
        return switch (weapon.fireMode()) {
            case PUMP, BOLT -> Math.max(cadence, Math.max(1, weapon.cycleTicks()));
            default -> cadence;
        };
    }

    private static boolean moving(Player player) {
        Vector velocity = player.getVelocity();
        return velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ() > 1.0e-4;
    }

    private void dryFire(Player player, Session session, WeaponDefinition weapon) {
        // A burst that runs dry ends instead of re-trying every tick.
        session.burstRemaining = 0;
        if (session.emptyFlashCooldown > 0) {
            return;
        }
        session.emptyFlashCooldown = 8;
        GunConfig config = services.config();
        int reserve = config == null ? 0
                : AmmoSupply.rounds(player, config.ammo().material(), config.ammo().itemsPerRound());
        String supply = supplyName();
        services.ammoHud().flashEmpty(player, weapon, reserve, supply);
        Sounds.playTo(player, weapon.soundEmpty(), weapon.soundVolume() * 0.7f, 1.0f,
                Sound.BLOCK_LEVER_CLICK);
        if (reserve > 0) {
            session.autoReloadDelay = AUTO_RELOAD_DELAY_TICKS;
        }
    }

    // ------------------------------------------------------------------
    // Reloading
    // ------------------------------------------------------------------

    private boolean beginReload(Player player, Session session, WeaponDefinition weapon, boolean automatic) {
        if (!weapon.usesAmmo() || session.reloading || session.magazine == null) {
            return false;
        }
        if (session.magazine.full()) {
            if (!automatic) {
                services.ammoHud().flashFull(player, weapon);
            }
            return false;
        }
        GunConfig config = services.config();
        int reserve = config == null ? 0
                : AmmoSupply.rounds(player, config.ammo().material(), config.ammo().itemsPerRound());
        if (reserve <= 0) {
            if (!automatic) {
                services.ammoHud().flashNoReserve(player, supplyName());
                Sounds.playTo(player, weapon.soundEmpty(), weapon.soundVolume() * 0.7f, 1.0f,
                        Sound.BLOCK_LEVER_CLICK);
            }
            return false;
        }

        session.reloading = true;
        session.reloadTotal = Math.max(1, weapon.reloadTicks());
        if (!WeaponReloadEvent.allow(player, player.getInventory().getItemInMainHand(), weapon,
                session.reloadTotal, automatic)) {
            session.reloading = false;
            session.reloadTotal = 0;
            return false;
        }
        session.reloadRemaining = session.reloadTotal;
        session.reloadMidPlayed = false;
        session.autoReloadDelay = 0;
        session.scoped = false;
        session.pendingCycle = false;
        session.burstRemaining = 0;
        if (session.animator != null) {
            session.animator.stop();
        }
        session.pose = Pose.NONE;
        session.sequenceTicks = 0;
        if (!startSequence(player, session, weapon, weapon.reloadClip(), session.reloadTotal, Pose.RELOAD)) {
            if (clientClock) {
                startPose(player, session, weapon, weapon.reloadClip(), session.reloadTotal, Pose.RELOAD);
            } else if (!poseOnce(player, session, weapon, weapon.reloadClip(), 0.0f)) {
                rest(player, session, weapon);
            }
        }
        Sounds.playTo(player, weapon.soundReloadBegin(), weapon.soundVolume() * 0.85f,
                Sounds.variance(1.0f, 0.04f), Sound.ITEM_CROSSBOW_LOADING_START);
        reloadFx(player, ParticleFx.particle(weapon.smokeParticle(), org.bukkit.Particle.SMOKE), 4, 0.10);
        pushHud(player, session, weapon);
        arm();
        return true;
    }

    private void tickReload(Player player, Session session, WeaponDefinition weapon) {
        session.reloadRemaining--;
        if (!session.reloadMidPlayed && session.reloadRemaining <= session.reloadTotal / 2) {
            session.reloadMidPlayed = true;
            // Legacy packs cannot animate per tick without replaying the
            // equip bob, so a reload is three deliberate poses: the hand
            // takes the gun down, seats the magazine, and brings it back.
            if (!clientClock) {
                poseOnce(player, session, weapon, weapon.reloadClip(), 0.55f);
            }
            Sounds.playTo(player, weapon.soundReloadMid(), weapon.soundVolume() * 0.7f,
                    Sounds.variance(1.0f, 0.06f), Sound.ITEM_CROSSBOW_LOADING_MIDDLE);
            // The magazine seats here: a small spark at the weapon is the one
            // reload beat a packless gun can still show.
            reloadFx(player, ParticleFx.particle(weapon.sparkParticle(), org.bukkit.Particle.SMALL_FLAME),
                    5, 0.08);
        }
        if (session.reloadRemaining > 0) {
            return;
        }
        finishReload(player, session, weapon);
    }

    private void finishReload(Player player, Session session, WeaponDefinition weapon) {
        session.reloading = false;
        GunConfig config = services.config();
        if (config != null && session.magazine != null) {
            int missing = weapon.magazine() - session.magazine.rounds();
            int reserve = AmmoSupply.rounds(player, config.ammo().material(), config.ammo().itemsPerRound());
            int load = Math.min(missing, reserve);
            if (load > 0) {
                AmmoSupply.take(player, config.ammo().material(), load * config.ammo().itemsPerRound());
                session.magazine.add(load);
            }
        }
        if (session.animator != null) {
            session.animator.stop();
        }
        session.pose = Pose.NONE;
        rest(player, session, weapon);
        Sounds.playTo(player, weapon.soundReloadDone(), weapon.soundVolume() * 0.9f,
                Sounds.variance(1.0f, 0.04f), Sound.ITEM_CROSSBOW_LOADING_END);
        reloadFx(player, ParticleFx.particle(weapon.smokeParticle(), org.bukkit.Particle.SMOKE), 6, 0.12);
        pushHud(player, session, weapon);
        arm();
    }

    private String supplyName() {
        GunConfig config = services.config();
        if (config == null) {
            return "ammunition";
        }
        return config.ammo().material().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /**
     * A small mechanical puff at the weapon — reload feedback that needs no
     * pack art. Emitted through the Core's budgeted particle service, so a
     * busy server drops surplus particles instead of paying for them.
     */
    private void reloadFx(Player player, org.bukkit.Particle particle, int count, double spread) {
        if (particle == null) {
            return;
        }
        services.context().particleFx().burst(ParticleFx.Tier.AMBIENT, handLocation(player), particle,
                count, spread, spread, spread, 0.0);
    }

    /** Roughly where the held gun sits: in front of and below the eyes. */
    private static Location handLocation(Player player) {
        Location eye = player.getEyeLocation();
        return eye.clone().add(eye.getDirection().multiply(0.45)).subtract(0.0, 0.35, 0.0);
    }

    // ------------------------------------------------------------------
    // Presentation
    // ------------------------------------------------------------------

    private enum Pose {
        NONE, RELOAD, FIRE, CYCLE, SCOPE_IN, SCOPE_HOLD, SCOPE_OUT, EQUIP
    }

    /** One client-clock sequence of the weapon, or {@code null} when it has none. */
    private ItemFrames.Sequence sequence(WeaponDefinition weapon, String name) {
        if (!clientClock || weapon.frames() == null || name == null) {
            return null;
        }
        ItemFrames.Sequence sequence = weapon.frames().plan().sequence(name);
        return sequence != null && sequence.animates() ? sequence : null;
    }

    /**
     * Starts a clip on the client's own clock: one state write and one item
     * cooldown, then the client walks the clip's frames every rendered frame
     * with no further packets. Returns false when this weapon has no such
     * sequence, so the caller falls back to the server animator.
     *
     * <p>The cooldown is per material and is read by the pack's cooldown
     * dispatch — the same tinted countdown vanilla draws over a hotbar item is
     * the animation's clock.</p>
     */
    private boolean startSequence(Player player, Session session, WeaponDefinition weapon,
                                  String name, int ticks, Pose pose) {
        ItemFrames.Sequence sequence = sequence(weapon, name);
        if (sequence == null) {
            return false;
        }
        if (session.animator != null && session.animator.active()) {
            session.animator.stop();
        }
        session.pose = pose;
        writeFrame(player, session, weapon, sequence.gateState());
        player.setCooldown(weapon.backingMaterial(), Math.max(1, ticks));
        session.sequenceTicks = Math.max(1, ticks);
        return true;
    }

    private void clearCooldown(Player player, WeaponDefinition weapon) {
        if (clientClock) {
            player.setCooldown(weapon.backingMaterial(), 0);
        }
    }

    private void playShotPose(Player player, Session session, WeaponDefinition weapon) {
        // While the sights are up, the aim pose IS the shot pose: the gun does
        // not drop out of the scope between rounds. The muzzle flash, tracer
        // and the widening cone carry the shot instead.
        if (session.scoped && weapon.scope() && hasScopeHold(weapon)) {
            session.pendingCycle = false;
            return;
        }
        if (!clientClock) {
            playShotPoseLegacy(player, session, weapon);
            return;
        }
        String fireClip = weapon.fireClip();
        boolean hasCycle = canCycle(weapon);
        if (startSequence(player, session, weapon, fireClip, FIRE_POSE_TICKS, Pose.FIRE)) {
            session.pendingCycle = hasCycle;
            return;
        }
        boolean hasFire = session.animator != null && fireClip != null
                && weapon.frames() != null && weapon.frames().hasClip(fireClip);
        if (hasFire) {
            session.pendingCycle = hasCycle;
            startPose(player, session, weapon, fireClip, FIRE_POSE_TICKS, Pose.FIRE);
            return;
        }
        if (hasCycle) {
            startPose(player, session, weapon, weapon.cycleClip(), weapon.cycleTicks(), Pose.CYCLE);
            return;
        }
        // No shot/cycle pose: a scoped gun keeps its scope hold, an
        // unscoped one simply keeps resting.
        if (session.scoped && weapon.scope()) {
            startScopeHold(player, session, weapon);
            return;
        }
        rest(player, session, weapon);
    }

    /**
     * The legacy shot presentation: a pose change only where the hand is
     * <em>supposed</em> to move, never once per round.
     *
     * <p>A legacy pack selects a pose with {@code custom_model_data}, and every
     * rewrite of a held stack replays the client's equip bob. Retiming a
     * per-shot pose would therefore turn a 400 rpm rifle into a hand that
     * shakes, which is exactly what "the hand goes down and up" describes.</p>
     *
     * <ul>
     *   <li><b>Pump, bolt —</b> the action is part of the shot. The gun rocks
     *       into its cycle pose once per round and settles when the cycle is
     *       over: two slow, deliberate writes at 40–70 rpm.</li>
     *   <li><b>Semi, burst, auto —</b> no pose write at all. The muzzle flash,
     *       smoke, tracer, sound and the widening cone carry the shot, and the
     *       gun never bobs mid-burst.</li>
     * </ul>
     */
    private void playShotPoseLegacy(Player player, Session session, WeaponDefinition weapon) {
        boolean deliberate = weapon.fireMode() == FireMode.PUMP || weapon.fireMode() == FireMode.BOLT;
        if (deliberate && canCycle(weapon)
                && poseOnce(player, session, weapon, weapon.cycleClip(), 0.0f)) {
            session.pose = Pose.CYCLE;
            session.sequenceTicks = Math.max(1, weapon.cycleTicks());
            return;
        }
        if (session.pose == Pose.FIRE || session.pose == Pose.CYCLE) {
            // A previous deliberate pose is still settling; do not restart it.
            return;
        }
        if (session.scoped && weapon.scope()) {
            startScopeHold(player, session, weapon);
            return;
        }
        if (session.magazine != null && session.magazine.empty()) {
            // The last round left the gun: show it empty instead of holding a
            // loaded pose until the (possibly impossible) reload finishes.
            rest(player, session, weapon);
        }
    }

    private boolean canCycle(WeaponDefinition weapon) {
        return weapon.cycleTicks() > 0 && weapon.cycleClip() != null
                && weapon.frames() != null && weapon.frames().hasClip(weapon.cycleClip());
    }

    /** True when the weapon has a pose to hold while scoped. */
    private static boolean hasScopeHold(WeaponDefinition weapon) {
        return weapon.frames() != null && weapon.scopeHoldClip() != null
                && weapon.frames().hasClip(weapon.scopeHoldClip());
    }

    private void playScopePose(Player player, Session session, WeaponDefinition weapon) {
        if (weapon.frames() == null) {
            rest(player, session, weapon);
            return;
        }
        if (!clientClock) {
            // One pose per toggle: the client's own equip bob hides the switch,
            // and a static aim pose needs no clock at all.
            if (session.scoped) {
                startScopeHold(player, session, weapon);
            } else {
                rest(player, session, weapon);
            }
            return;
        }
        if (session.scoped) {
            if (!startSequence(player, session, weapon, weapon.scopeInClip(), SCOPE_IN_TICKS, Pose.SCOPE_IN)
                    && !startPose(player, session, weapon, weapon.scopeInClip(), SCOPE_IN_TICKS, Pose.SCOPE_IN)) {
                startScopeHold(player, session, weapon);
            }
        } else if (!startSequence(player, session, weapon, weapon.scopeOutClip(), SCOPE_OUT_TICKS,
                Pose.SCOPE_OUT)
                && !startPose(player, session, weapon, weapon.scopeOutClip(), SCOPE_OUT_TICKS,
                        Pose.SCOPE_OUT)) {
            rest(player, session, weapon);
        }
    }

    private void startScopeHold(Player player, Session session, WeaponDefinition weapon) {
        String clip = weapon.scopeHoldClip();
        if (clip == null || weapon.frames() == null || !weapon.frames().hasClip(clip)) {
            rest(player, session, weapon);
            return;
        }
        if (!clientClock) {
            if (poseOnce(player, session, weapon, clip, 1.0f)
                    || poseOnce(player, session, weapon, weapon.scopeInClip(), 1.0f)) {
                session.pose = Pose.SCOPE_HOLD;
                session.sequenceTicks = 0;
            } else {
                rest(player, session, weapon);
            }
            return;
        }
        var frameClip = weapon.frames().clip(clip);
        if (frameClip != null && frameClip.frameCount() <= 1) {
            // A one-frame hold is static: write it once and keep the pose until
            // the player leaves the scope. This is also the client-clock path —
            // a held pose needs no cooldown, only the state.
            if (session.animator != null && session.animator.active()) {
                session.animator.stop();
            }
            session.sequenceTicks = 0;
            writeFrame(player, session, weapon, weapon.frames().stateAtProgress(clip, 1.0f));
            session.pose = Pose.SCOPE_HOLD;
            return;
        }
        if (session.animator == null) {
            rest(player, session, weapon);
            return;
        }
        session.animator.stop();
        if (session.animator.start(clip)) {
            session.pose = Pose.SCOPE_HOLD;
        } else {
            rest(player, session, weapon);
        }
    }

    private void playEquipPose(Player player, Session session, WeaponDefinition weapon) {
        // Vanilla already plays its draw animation on the slot change that put
        // the gun in hand; a second, authored draw on top of it is the double
        // "hand down and up" a legacy pack cannot avoid. Legacy weapons simply
        // settle into their resting pose.
        if (!clientClock) {
            rest(player, session, weapon);
            return;
        }
        if (startSequence(player, session, weapon, weapon.equipClip(), EQUIP_TICKS, Pose.EQUIP)) {
            return;
        }
        if (!startPose(player, session, weapon, weapon.equipClip(), EQUIP_TICKS, Pose.EQUIP)) {
            rest(player, session, weapon);
        }
    }

    /**
     * Writes one frame of a clip and stops there.
     *
     * <p>This is the legacy pack's whole animation vocabulary: the pose is
     * chosen from the clip's progress and written once, so the client sees a
     * single item change per deliberate state transition instead of a rewrite
     * every tick. Returns {@code false} when the clip or the pose is missing,
     * letting the caller degrade to the resting pose.</p>
     */
    private boolean poseOnce(Player player, Session session, WeaponDefinition weapon,
                             String clip, float progress) {
        if (clientClock || weapon.frames() == null || clip == null) {
            return false;
        }
        if (!weapon.frames().hasClip(clip)) {
            return false;
        }
        int state = weapon.frames().stateAtProgress(clip, progress);
        if (state < 0) {
            return false;
        }
        writeFrame(player, session, weapon, state);
        return true;
    }

    private boolean startPose(Player player, Session session, WeaponDefinition weapon,
                              String clip, int ticks, Pose pose) {
        if (session.animator == null || clip == null || weapon.frames() == null
                || !weapon.frames().hasClip(clip)) {
            return false;
        }
        session.animator.stop();
        if (!session.animator.start(clip, Math.max(1, ticks))) {
            return false;
        }
        session.pose = pose;
        return true;
    }

    private void tickPose(Player player, Session session, WeaponDefinition weapon) {
        ItemFrameAnimator animator = session.animator;
        if (animator == null || !animator.active()) {
            return;
        }
        int state = animator.tick();
        if (state >= 0) {
            writeFrame(player, session, weapon, state);
        }
        if (!animator.finished()) {
            return;
        }
        Pose finished = session.pose;
        animator.stop();
        finishPose(player, session, weapon, finished);
    }

    /** What a finished pose hands off to: the next clip, the scope hold, or rest. */
    private void finishPose(Player player, Session session, WeaponDefinition weapon, Pose finished) {
        session.pose = Pose.NONE;
        switch (finished) {
            case FIRE -> {
                if (session.pendingCycle && canCycle(weapon)) {
                    session.pendingCycle = false;
                    if (!startSequence(player, session, weapon, weapon.cycleClip(),
                            Math.max(1, weapon.cycleTicks()), Pose.CYCLE)) {
                        if (clientClock) {
                            startPose(player, session, weapon, weapon.cycleClip(), weapon.cycleTicks(), Pose.CYCLE);
                        } else if (poseOnce(player, session, weapon, weapon.cycleClip(), 0.0f)) {
                            session.pose = Pose.CYCLE;
                            session.sequenceTicks = Math.max(1, weapon.cycleTicks());
                        } else {
                            rest(player, session, weapon);
                        }
                    }
                } else if (session.scoped && weapon.scope()) {
                    startScopeHold(player, session, weapon);
                } else {
                    rest(player, session, weapon);
                }
            }
            case SCOPE_IN -> {
                if (session.scoped) {
                    startScopeHold(player, session, weapon);
                } else {
                    rest(player, session, weapon);
                }
            }
            case RELOAD -> {
                if (!session.reloading) {
                    rest(player, session, weapon);
                }
            }
            default -> rest(player, session, weapon);
        }
    }

    /** Writes the resting pose (loaded when rounds remain, idle when empty). */
    private void rest(Player player, Session session, WeaponDefinition weapon) {
        if (session.animator != null && session.animator.active()) {
            session.animator.stop();
        }
        session.pose = Pose.NONE;
        session.sequenceTicks = 0;
        clearCooldown(player, weapon);
        if (weapon.frames() == null) {
            return;
        }
        int state = session.magazine != null && session.magazine.rounds() > 0
                ? weapon.frames().loadedState()
                : weapon.frames().idleState();
        writeFrame(player, session, weapon, state);
    }

    private void writeFrame(Player player, Session session, WeaponDefinition weapon, int state) {
        if (state < 0 || state == session.frameState || !weapon.hasFrames()) {
            return;
        }
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (resolve(stack) != weapon) {
            return;
        }
        if (ItemFrameWriter.apply(stack, weapon.frameStackValue(state))) {
            player.getInventory().setItemInMainHand(stack);
        }
        session.frameState = state;
    }

    private void pushHud(Player player, Session session, WeaponDefinition weapon) {
        if (!weapon.usesAmmo() || session.magazine == null) {
            return;
        }
        GunConfig config = services.config();
        if (config == null) {
            return;
        }
        int mag = session.magazine.rounds();
        int reserve = AmmoSupply.rounds(player, config.ammo().material(), config.ammo().itemsPerRound());
        boolean scoped = session.scoped && weapon.scope();
        String signature = mag + "|" + reserve + "|" + session.reloading + "|" + scoped;
        if (signature.equals(session.hudSignature)) {
            return;
        }
        session.hudSignature = signature;
        float progress = session.reloading
                ? 1.0f - session.reloadRemaining / (float) Math.max(1, session.reloadTotal)
                : mag / (float) Math.max(1, weapon.magazine());
        services.ammoHud().update(player, weapon, mag, reserve, session.reloading, scoped, progress);
    }

    /** Hit confirmation and the pvp-only target filter. */
    private final class WeaponHitHandler implements ProjectileHandler {

        @Override
        public boolean allowTarget(Entity candidate, Player shooter) {
            if (!(candidate instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                return false;
            }
            if (shooter != null && candidate.getUniqueId().equals(shooter.getUniqueId())) {
                return false;
            }
            GunConfig config = services.config();
            return config == null || !config.settings().pvpOnly() || candidate instanceof Player;
        }

        @Override
        public boolean onEntityHit(ProjectileHit hit) {
            Player shooter = hit.shooter();
            if (shooter == null) {
                return true;
            }
            if (hit.target() instanceof LivingEntity victim
                    && !WeaponHitEvent.allow(shooter, victim, hit.damage(), hit.travelled(),
                            hit.headshot())) {
                return false;
            }
            long now = System.currentTimeMillis();
            Long last = hitmarkAt.get(shooter.getUniqueId());
            if (last != null && now - last < HITMARK_INTERVAL_MILLIS) {
                return true;
            }
            hitmarkAt.put(shooter.getUniqueId(), now);
            Sounds.playTo(shooter, "entity.arrow.hit_player", 0.6f,
                    hit.headshot() ? 1.9f : 1.5f, Sound.ENTITY_ARROW_HIT_PLAYER);
            return true;
        }
    }

    /** One player's live weapon: magazine, cadence, pose and recoil. */
    private static final class Session {

        private final UUID playerId;
        private final WeaponDefinition weapon;
        private final ItemMagazine.Live magazine;
        private final ItemFrameAnimator animator;

        private Pose pose = Pose.NONE;
        private int fireCooldown;
        private int burstRemaining;
        private int reloadRemaining;
        private int reloadTotal;
        private boolean reloading;
        private boolean reloadMidPlayed;
        private int autoReloadDelay;
        private int emptyFlashCooldown;
        private double recoil;
        private boolean scoped;
        private boolean autoHeld;
        private boolean pendingCycle;
        private String hudSignature;
        private int frameState = Integer.MIN_VALUE;
        /** Ticks left in the client-clock sequence being played, 0 when idle. */
        private int sequenceTicks;

        private Session(UUID playerId, WeaponDefinition weapon,
                        ItemMagazine.Live magazine, ItemFrameAnimator animator) {
            this.playerId = playerId;
            this.weapon = weapon;
            this.magazine = magazine;
            this.animator = animator;
        }
    }
}
