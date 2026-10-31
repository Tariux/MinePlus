package com.mineplus.infrastructure.mob.custom;

import com.mineplus.infrastructure.mob.navigation.MobAiIsolation;
import com.mineplus.infrastructure.virtual.rig.BoneRigBlueprint;
import com.mineplus.infrastructure.virtual.rig.BoneRigDefinition;
import com.mineplus.infrastructure.virtual.rig.BoneRigRenderer;
import com.mineplus.infrastructure.virtual.rig.RigSettings;
import com.mineplus.pack.PackApi;
import com.mineplus.util.DebugLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The visual rig for custom mobs: the base mob is hidden and each model part is
 * one {@link ItemDisplay} carrying a generated pack model.
 *
 * <p>Display lifecycle and the hierarchical pose math live in the Core's
 * generic {@link BoneRigRenderer}; this class owns only what is mob-specific:
 * identity and lifecycle, the part tree, the pose source and the base entity's
 * visibility.</p>
 *
 * <p>Two pose sources share one renderer:</p>
 * <ul>
 *   <li><b>Authored clips</b> — a model that carries {@code animation.walk} /
 *       {@code animation.idle} (an imported {@code .bbmodel}) is posed by those
 *       clips: every part samples its own curves and the whole outliner
 *       hierarchy follows, because the part tree is the blueprint. Walk and
 *       idle are blended by a smoothed movement weight, so a mob standing still
 *       breathes and a mob walking swings its legs. An optional
 *       {@code animation.attack} clip is a one-shot overlay: every time the mob
 *       lands a hit ({@link #playAttack(LivingEntity)}, also fired by the
 *       renderer's own damage listener) it plays once and then hands the part
 *       back to walk/idle.</li>
 *   <li><b>Roles</b> — a model with no clips (a vanilla-descriptor reskin) keeps
 *       the procedural pose: head tracking and quadruped leg swing driven by
 *       distance travelled.</li>
 * </ul>
 *
 * <p>Only the visual layer lives here. Identity, spawning, behavior and
 * lifecycle belong to {@code CustomMobManager}; the rig never decides what a
 * mob does, and a mob works with behavior intact when rigging is unavailable
 * (the pack is off, or the server predates the {@code item_model} component):
 * this renderer owns base visibility, so the base entity stays visible exactly
 * when no rig is presenting the mob — never invisible without a model.</p>
 *
 * <p>Pose space: a part's display sits at the part pivot and the generated
 * model is authored around that pivot, so rotating the display rotates the
 * part about its pivot exactly like a vanilla model part. Model space grows
 * downward; world space grows upward, so a pivot at vanilla pixel height
 * {@code y} sits {@code (modelHeightPx - y) / 16} blocks above the feet.</p>
 */
public final class MobRigRenderer implements Listener {

    private static final String TAG_PREFIX = "mineplus_mobrig:";
    private static final double MOVED_EPSILON_SQ = 0.0004; // 0.02 blocks
    private static final float ANGLE_EPSILON = 0.35f;
    private static final float PHASE_EPSILON = 0.03f;
    /** Stride length in blocks: how far the mob walks per full leg cycle. */
    private static final double STRIDE_BLOCKS = 1.05;
    private static final float MAX_LEG_ANGLE = 0.85f;
    /** Blocks per second at which an authored walk clip plays at its own cadence. */
    private static final float WALK_REFERENCE_SPEED = 2.0f;
    private static final float MIN_WALK_RATE = 0.5f;
    private static final float MAX_WALK_RATE = 2.0f;
    /** Smoothed weight step per pose pass; the blend settles in a few ticks. */
    private static final float WALK_BLEND_STEP = 0.35f;
    /** Exponential smoother time constant for velocity (seconds). */
    private static final float VELOCITY_SMOOTH_SECONDS = 0.15f;
    /**
     * Fraction of an attack clip's length that a re-issued swing may not restart
     * inside: a mob swinging every tick would freeze its arm at frame zero,
     * which reads as a stall rather than as a flurry.
     */
    private static final float ATTACK_RESTART_GUARD = 0.25f;
    /** Seconds an attack pose cross-fades out over when the clip ends. */
    private static final float ATTACK_FADE_SECONDS = 0.2f;
    /** How far a procedurally posed head turns relative to its body, in degrees. */
    private static final float HEAD_TRACK_LIMIT = 75.0f;

    private final Plugin plugin;
    private final PackApi pack;
    private final BoneRigRenderer boneRig;
    private final Map<UUID, Rig> rigs = new ConcurrentHashMap<>();
    private final Map<String, BoneRigBlueprint> blueprints = new ConcurrentHashMap<>();
    /** Each definition's last presentation outcome, so a status line can say it out loud. */
    private final Map<String, RigOutcome> rigOutcomes = new ConcurrentHashMap<>();

    /**
     * One recorded attempt: whether it ended with the mob presented as its rig,
     * and the sentence that explains the outcome either way.
     */
    private record RigOutcome(boolean failed, String message) {
    }
    private volatile CustomMobSettings settings;
    private BukkitTask task;
    private boolean prepared;

    /**
     * The Core's one live rig renderer, so a melee swing inside the mob action
     * axis can reach the presentation axis without either owning the other.
     */
    private static volatile MobRigRenderer activeRenderer;

    public MobRigRenderer(Plugin plugin, PackApi pack, CustomMobSettings settings) {
        this(plugin, pack, settings, RigSettings.defaults());
    }

    public MobRigRenderer(Plugin plugin, PackApi pack, CustomMobSettings settings, RigSettings rigSettings) {
        this.plugin = plugin;
        this.pack = pack;
        this.settings = settings;
        activeRenderer = this;
        // Mob rigs keep the plain-entity display model they have always had;
        // pooling through the display transport is the pack bone axis's path.
        // CUSTOM_MOBS.RENDERING already gates mob rigs, so the pack axis's
        // RIGS.ENABLED switch must not silently turn them off.
        this.boneRig = new BoneRigRenderer(plugin, pack, null,
                mobRigLimits(rigSettings), TAG_PREFIX);
    }

    private static RigSettings mobRigLimits(RigSettings settings) {
        return (settings == null ? RigSettings.defaults() : settings).withEnabled(true);
    }

    /** True when this server can present rigs at all. */
    public boolean isAvailable() {
        return pack != null && pack.isAvailable() && pack.supportsGeneratedModelDisplay();
    }

    /**
     * Prepares the renderer: sweeps display entities left by a previous
     * session. The pose task is armed lazily by the first attached rig, so a
     * server with no rigged mobs pays nothing.
     */
    public void start() {
        if (!isAvailable() || prepared) {
            return;
        }
        prepared = true;
        sweepGhosts();
    }

    private void ensureTicking() {
        if (task != null || !settings.renderingEnabled() || !isAvailable()) {
            return;
        }
        int interval = Math.max(1, settings.renderIntervalTicks());
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : new ArrayList<>(rigs.keySet())) {
            detach(id);
        }
    }

    /** Applies reloaded bone-rig limits; existing rigs keep their displays. */
    public void updateRigSettings(RigSettings next) {
        if (next != null) {
            boneRig.updateSettings(mobRigLimits(next));
        }
    }

    /** Applies new settings (reload path): interval change re-arms the task. */
    public void updateSettings(CustomMobSettings next) {
        if (next == null) {
            return;
        }
        this.settings = next;
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (settings.renderingEnabled()) {
            if (!rigs.isEmpty()) {
                ensureTicking();
            }
        } else {
            for (UUID id : new ArrayList<>(rigs.keySet())) {
                detach(id);
            }
        }
    }

    public int rigCount() {
        return rigs.size();
    }

    /**
     * Builds the rig for one mob.
     *
     * <p>Visibility is decided here, not by the caller: a mob that this
     * renderer presents is hidden (when its definition asks for it), and a mob
     * it cannot present is shown as its base entity. Calling {@code attach}
     * again for an already-rigged mob re-applies that decision instead of
     * leaving the base visible beside its rig.</p>
     *
     * @return {@code true} when every part display was spawned
     */
    public boolean attach(LivingEntity entity, CustomMobDefinition definition) {
        if (!isAvailable() || entity == null || definition == null || definition.model() == null
                || !settings.renderingEnabled()) {
            noteFailure(definition, unavailableReason(definition));
            return false;
        }
        if (definition.visual() == CustomMobDefinition.VisualMode.BASE) {
            // The author asked for the base entity; on a 1.21.1 client this is
            // the only honest reading — there is no entity-model override
            // channel — so the mob simply keeps its vanilla look.
            notePresented(definition, "keeps its vanilla base look (VisualMode.BASE)");
            detach(entity.getUniqueId());
            return false;
        }
        UUID ownerId = entity.getUniqueId();
        Rig existing = rigs.get(ownerId);
        if (existing != null) {
            if (boneRig.isAttached(ownerId) && existing.definition == definition) {
                applyBaseVisibility(entity, definition);
                return true;
            }
            // A rig whose displays are gone (another subsystem swept them) or
            // whose definition changed is rebuilt rather than trusted.
            detach(ownerId);
        }
        if (countInWorld(entity.getWorld()) >= settings.maxRiggedPerWorld()) {
            noteFailure(definition, "the per-world rig cap (" + settings.maxRiggedPerWorld()
                    + ") is already reached");
            DebugLogger.warning("[CustomMobs] Rig cap reached in " + entity.getWorld().getName()
                    + "; '" + definition.id() + "' renders as its base entity.");
            return false;
        }

        BoneRigBlueprint blueprint = blueprints.computeIfAbsent(definition.id(),
                id -> blueprintFor(definition));
        Location base = entity.getLocation();
        World world = base.getWorld();
        if (world == null) {
            return false;
        }
        int interval = Math.max(1, settings.renderIntervalTicks());
        int interpTicks = Math.max(1, settings.renderInterpolationTicks());
        float viewRange = Math.min(1.0f, Math.max(0.1f, settings.renderDistance() / 64.0f));

        boolean attached = boneRig.attach(ownerId, blueprint,
                new Location(world, base.getX(), base.getY(), base.getZ()),
                null, 0.0f, new Vector3f(), interpTicks, viewRange, interval);
        if (!attached) {
            noteFailure(definition, "none of its part displays could be spawned; the compiled pack"
                    + " carries no model at assets/" + blueprint.namespace() + "/models/item/entity/"
                    + definition.path() + "/<part>.json yet");
            return false;
        }
        notePresented(definition, "presenting " + definition.model().parts().size()
                + " part display(s) per mob");

        Rig rig = new Rig(entity, definition, (float) base.getYaw(),
                MobModelOverrides.forMob(plugin, definition.path()));
        rigs.put(ownerId, rig);
        applyBaseVisibility(entity, definition);
        rig.hiddenBase = definition.hideBaseWhenRigged();
        ensureTicking();
        applyPose(rig, base, true);
        if (DebugLogger.isEnabled()) {
            DebugLogger.info("[CustomMobs] Rigged '" + definition.id() + "' with "
                    + rig.model.parts().size() + " part(s)"
                    + (rig.hasClips() ? ", clips: " + rig.clipNames() : ", procedural pose"));
        }
        return true;
    }

    /** Discards a cached blueprint (definition re-registered with a new model). */
    public void invalidateBlueprint(String definitionId) {
        if (definitionId != null) {
            blueprints.remove(definitionId);
            rigOutcomes.remove(definitionId);
        }
    }

    /**
     * Why the last attach attempt for one definition could not present a rig,
     * or {@code null} when the rig is (or was last) attached. A definition
     * falling back to its base entity is a legitimate outcome; it is never
     * supposed to be a silent one.
     */
    public String rigFailure(String definitionId) {
        RigOutcome status = definitionId == null ? null : rigOutcomes.get(definitionId);
        return status != null && status.failed() ? status.message() : null;
    }

    /**
     * The last presentation outcome for one definition, in one sentence — the
     * honest version of {@link #rigFailure}, which cannot tell "the rig works"
     * from "nothing has tried yet". Status output should print this.
     */
    public String rigStatus(String definitionId) {
        RigOutcome status = definitionId == null ? null : rigOutcomes.get(definitionId);
        return status == null
                ? "nothing has asked for this rig since the last model reload"
                : status.message();
    }

    /** Records why no rig was presented; a {@code RIG} definition also says so in the log. */
    private void noteFailure(CustomMobDefinition definition, String reason) {
        if (definition == null || reason == null) {
            return;
        }
        RigOutcome previous = rigOutcomes.put(definition.id(), new RigOutcome(true, reason));
        if (definition.visual() == CustomMobDefinition.VisualMode.RIG
                && (previous == null || !reason.equals(previous.message()))) {
            plugin.getLogger().warning("[CustomMobs] '" + definition.id()
                    + "' asks for a rig, but " + reason + "; it renders as its base entity.");
        }
    }

    /** Records that a rig was presented, so a report never reads an untried mob as a working one. */
    private void notePresented(CustomMobDefinition definition, String message) {
        if (definition != null) {
            rigOutcomes.put(definition.id(), new RigOutcome(false, message));
        }
    }

    /** The one reason a definition could not be presented as a rig right now. */
    private String unavailableReason(CustomMobDefinition definition) {
        if (!isAvailable()) {
            return "the pack subsystem is off or cannot present generated item models";
        }
        if (!settings.renderingEnabled()) {
            return "CUSTOM_MOBS.RENDERING is false";
        }
        if (definition == null || definition.model() == null) {
            return "the definition carries no model";
        }
        return "the rig could not be built";
    }

    /**
     * Plays a rigged mob's authored attack clip once, if it has one.
     *
     * <p>The renderer calls this when the mob swings (a hunting action reports
     * the attempt through {@link #notifyMeleeSwing}) and when a hit lands, so
     * content that strikes through {@code DamageTransactions} needs no wiring. A module with
     * its own timing (a projectile, a scripted lunge) can call it directly; a
     * mob with no attack clip ignores the request.</p>
     *
     * <p>A request that arrives while the previous swing is still winding up is
     * absorbed, not restarted: an aggressive mob swings every tick, and
     * restarting would pin the arm at frame zero.</p>
     *
     * @return {@code true} when an attack clip is playing
     */
    public boolean playAttack(LivingEntity mob) {
        if (mob == null) {
            return false;
        }
        Rig rig = rigs.get(mob.getUniqueId());
        return rig != null && playAttack(rig);
    }

    private boolean playAttack(Rig rig) {
        if (rig.attackClip == null || !rig.owner.isValid() || rig.owner.isDead()) {
            return false;
        }
        float guard = rig.attackSeconds() * ATTACK_RESTART_GUARD;
        if (rig.attacking && rig.attackClock < guard) {
            return true;
        }
        rig.attacking = true;
        rig.attackClock = 0.0f;
        return true;
    }

    /**
     * The last pose one rigged mob was given, for a status line or a test
     * command; {@code null} when the mob carries no rig.
     */
    public String poseSnapshot(UUID mobId) {
        Rig rig = mobId == null ? null : rigs.get(mobId);
        return rig == null ? null : rig.poseDescription();
    }

    private void applyBaseVisibility(LivingEntity entity, CustomMobDefinition definition) {
        entity.setInvisible(definition.hideBaseWhenRigged());
    }

    /**
     * The mob blueprint: one synthetic root bone carrying the entity's yaw,
     * then one bone per part in the model's own preorder. A part's parent is
     * the authored group's parent, so a clip that rotates a bone moves
     * everything below it. Part pivots are converted from vanilla model space
     * (y down) to the rig's y-up pixel space.
     */
    private BoneRigBlueprint blueprintFor(CustomMobDefinition definition) {
        CustomMobModel model = definition.model();
        List<MobPart> parts = model.parts();
        List<BoneRigDefinition.Bone> bones = new ArrayList<>(parts.size() + 1);
        bones.add(new BoneRigDefinition.Bone("root", -1, new Vector3f()));
        for (int i = 0; i < parts.size(); i++) {
            MobPart part = parts.get(i);
            int parentPart = part.parent();
            int parent = parentPart >= 0 && parentPart < i ? parentPart + 1 : 0;
            // The blueprint must carry the same piece plan the asset writer
            // emitted: a part too large for the client's element window renders
            // as several displays, each with its own in-window shift.
            List<BoneRigDefinition.GeometryPiece> pieces = part.boxes().isEmpty()
                    ? List.of()
                    : MobRigAssetWriter.geometryPieces(part, definition.path(), true);
            bones.add(new BoneRigDefinition.Bone(
                    part.name(),
                    parent,
                    new Vector3f(part.pivotX(), model.modelHeightPx() - part.pivotY(), part.pivotZ()),
                    null,
                    null,
                    null,
                    null,
                    pieces));
        }
        return BoneRigBlueprint.of(definition.id(), definition.namespace(),
                new BoneRigDefinition(definition.id(), bones));
    }

    public void detach(UUID ownerId) {
        Rig rig = rigs.remove(ownerId);
        if (rig == null) {
            return;
        }
        releaseRig(rig);
    }

    public void detachAll() {
        for (UUID id : new ArrayList<>(rigs.keySet())) {
            detach(id);
        }
    }

    /** Removes every rig whose owner is in {@code world}. */
    public void detachWorld(World world) {
        if (world == null) {
            return;
        }
        for (Map.Entry<UUID, Rig> entry : rigs.entrySet()) {
            Entity owner = entry.getValue().owner;
            if (owner.getWorld().equals(world)) {
                detach(entry.getKey());
            }
        }
    }

    private void releaseRig(Rig rig) {
        boneRig.remove(rig.owner.getUniqueId());
        LivingEntity owner = rig.owner;
        if (rig.hiddenBase && owner.isValid() && !owner.isDead()) {
            owner.setInvisible(false);
        }
        rig.hiddenBase = false;
    }

    private int countInWorld(World world) {
        int count = 0;
        for (Rig rig : rigs.values()) {
            if (rig.owner.getWorld().equals(world)) {
                count++;
            }
        }
        return count;
    }

    private void tick() {
        if (!settings.renderingEnabled()) {
            return;
        }
        for (Map.Entry<UUID, Rig> entry : rigs.entrySet()) {
            Rig rig = entry.getValue();
            LivingEntity owner = rig.owner;
            if (!owner.isValid() || owner.isDead()) {
                detach(entry.getKey());
                continue;
            }
            Location location = owner.getLocation();
            if (location.getWorld() == null) {
                detach(entry.getKey());
                continue;
            }
            if (!isNearAnyPlayer(location)) {
                continue;
            }
            applyPose(rig, location, false);
        }
        if (rigs.isEmpty()) {
            // The last rig retired: stop paying for an empty pose pass.
            BukkitTask current = task;
            task = null;
            if (current != null) {
                current.cancel();
            }
        }
    }

    private boolean isNearAnyPlayer(Location location) {
        double limit = settings.renderDistance() * settings.renderDistance();
        for (org.bukkit.entity.Player player : location.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= limit) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pushes one pose to every part display. {@code force} always writes;
     * otherwise an unchanged procedural rig (a standing mob no player is
     * watching move) costs nothing, while a clip-driven rig always advances
     * because its pose is a function of time.
     *
     * <p>Speed is derived from the entity's live velocity (continuous) rather
     * than per-tick displacement (tick-quantised noise). Forward movement sign
     * drives leg phase direction, so strafing and backward travel animate
     * correctly.</p>
     */
    private void applyPose(Rig rig, Location location, boolean force) {
        float bodyYaw = location.getYaw();
        float headYaw = MobAiIsolation.headYaw(rig.owner, bodyYaw);
        // Location pitch is the entity's own xRot (its look pitch); the NMS
        // field is obfuscated on some runtimes, and a rig that ignores the
        // pitch tilts its head at the horizon while the mob looks up or down.
        float pitch = MobAiIsolation.pitch(rig.owner, location.getPitch());

        boolean sameWorld = rig.lastWorld != null && rig.lastWorld.equals(location.getWorld());
        long worldTime = location.getWorld().getGameTime();
        float dt = 0.0f;
        if (sameWorld && rig.lastWorldTime > 0 && worldTime > rig.lastWorldTime) {
            dt = (worldTime - rig.lastWorldTime) / 20.0f;
        }
        float smoothDt = Math.max(dt, VELOCITY_SMOOTH_SECONDS);

        // Bukkit velocity is blocks per tick while the walk reference and the
        // blend thresholds are authored in blocks per second; converting once
        // here keeps the stride phase (naturally per tick) and the clip weight
        // (naturally per second) measured on the same movement.
        org.bukkit.util.Vector vel = rig.owner.getVelocity();
        float vx = (float) vel.getX();
        float vz = (float) vel.getZ();
        float rawSpeed = (float) Math.sqrt(vx * vx + vz * vz) * 20.0f;

        float targetSpeed = rig.smoothedSpeed + (rawSpeed - rig.smoothedSpeed)
                * Math.min(1.0f, smoothDt / VELOCITY_SMOOTH_SECONDS);
        rig.smoothedSpeed = targetSpeed;

        float yawRad = (float) Math.toRadians(bodyYaw);
        float forwardDot = (float) (-Math.sin(yawRad) * vx + Math.cos(yawRad) * vz);
        float phaseSign = forwardDot >= 0.0f ? 1.0f : -1.0f;
        double stride = rig.overrides.strideBlocks() > 0.0
                ? rig.overrides.strideBlocks() : STRIDE_BLOCKS;
        float phaseDelta = (float) ((targetSpeed / 20.0f) * (Math.PI * 2.0 / stride) * phaseSign);
        if (!sameWorld) {
            phaseDelta = 0.0f;
        }

        float phase = rig.phase + phaseDelta;
        phase = (float) (phase % (Math.PI * 2.0));
        float targetAmplitude = Math.min(1.0f, targetSpeed / WALK_REFERENCE_SPEED) * MAX_LEG_ANGLE;
        float amplitude = rig.amplitude + (targetAmplitude - rig.amplitude) * 0.35f;

        // One pose pass covers renderIntervalTicks of server time; a clip's own
        // tempo is then scaled by the authored reference speed and the operator's
        // animationScale, so a slow model does not gallop and a fast one does
        // not crawl.
        float seconds = Math.max(1, settings.renderIntervalTicks()) / 20.0f;
        float tempo = rig.overrides.animationScale();
        float reference = rig.overrides.walkSpeedReference() > 0.0f
                ? rig.overrides.walkSpeedReference() : WALK_REFERENCE_SPEED;
        float targetWeight = Math.min(1.0f, targetSpeed / reference);
        rig.walkWeight += (targetWeight - rig.walkWeight) * WALK_BLEND_STEP;
        if (rig.walkWeight < 0.005f) {
            rig.walkWeight = 0.0f;
        }
        if (rig.walkClip != null && targetSpeed > 1.0e-5) {
            float rate = Math.min(MAX_WALK_RATE, Math.max(MIN_WALK_RATE, targetSpeed / reference));
            rig.walkClock = rig.walkClip.wrap(rig.walkClock + seconds * rate * tempo);
        }
        if (rig.idleClip != null) {
            rig.idleClock = rig.idleClip.wrap(rig.idleClock + seconds * tempo);
        }
        rig.attackBlend = attackBlend(rig, seconds * tempo);

        // The step this pass advances the stride by; the wrapped phase itself
        // jumps by a full turn, so it can never be compared across passes.
        boolean changed = force
                || rig.hasClips()
                || !sameWorld
                || Math.abs(targetSpeed - rig.lastSpeed) > 0.02f
                || Math.abs(MobPoseSpace.unwrap(rig.lastBodyYaw, bodyYaw)) > ANGLE_EPSILON
                || Math.abs(MobPoseSpace.unwrap(rig.lastHeadYaw, headYaw)) > ANGLE_EPSILON
                || Math.abs(pitch - rig.lastPitch) > ANGLE_EPSILON
                || Math.abs(phaseDelta) > PHASE_EPSILON
                || Math.abs(amplitude - rig.amplitude) > 0.01f;

        rig.lastX = location.getX();
        rig.lastZ = location.getZ();
        rig.lastWorld = location.getWorld();
        rig.lastWorldTime = worldTime;
        rig.lastSpeed = targetSpeed;
        rig.lastBodyYaw = bodyYaw;
        rig.lastHeadYaw = headYaw;
        rig.lastPitch = pitch;
        rig.phase = phase;
        rig.amplitude = amplitude;
        if (!changed) {
            return;
        }

        UUID ownerId = rig.owner.getUniqueId();
        Quaternionf bodyRotation = MobPoseSpace.yawFrame(rig.scratch, bodyYaw,
                rig.overrides.yawOffsetDegrees());
        boneRig.setLocal(ownerId, 0, bodyRotation);
        if (rig.hasClips()) {
            poseClips(rig, ownerId);
        } else {
            poseProcedural(rig, ownerId, bodyRotation, bodyYaw, headYaw, pitch, phase, amplitude);
        }
        boneRig.pose(ownerId, location);
    }

    /**
     * The attack overlay's weight for this pass: up while the clip runs, then
     * cross-faded back to walk/idle over {@link #ATTACK_FADE_SECONDS} so the arm
     * returns instead of snapping.
     */
    private static float attackBlend(Rig rig, float seconds) {
        if (rig.attacking) {
            rig.attackClock += seconds;
            if (rig.attackClip == null || rig.attackClock >= rig.attackSeconds()) {
                rig.attacking = false;
                rig.attackClock = 0.0f;
            }
        }
        if (rig.attacking) {
            return Math.min(1.0f, rig.attackBlend + 4.0f * seconds + 0.34f);
        }
        if (rig.attackBlend <= 0.0f) {
            return 0.0f;
        }
        return Math.max(0.0f, rig.attackBlend - seconds / ATTACK_FADE_SECONDS);
    }

    /**
     * Pose from a model's own animation clips: every part samples its rotation
     * and position curves, walk and idle are blended by the movement weight, an
     * active attack clip overlays the parts it names and cross-fades back when
     * it ends, and the generic renderer composes the hierarchy.
     *
     * <p>The authored rest rotation is folded into each sampled curve as an
     * additive Euler sum before blending — Blockbench's own semantics, since a
     * clip stores offsets from the rest pose. That is also what carries a group
     * down to its children: this rig's blueprint deliberately keeps its per-bone
     * rest empty, so the hierarchy pass never applies the same rotation twice.</p>
     */
    private void poseClips(Rig rig, UUID ownerId) {
        List<MobPart> parts = rig.model.parts();
        float weight = rig.walkWeight;
        float walkTime = rig.walkClock;
        float idleTime = rig.idleClock;
        float attack = rig.attackBlend;
        boolean attacking = attack > 0.0f && rig.attackClip != null;
        float attackTime = rig.attackClock;
        for (int i = 0; i < parts.size(); i++) {
            MobPart part = parts.get(i);
            String name = part.name();
            MobPoseSpace.restDegrees(part, rig.rest);
            MobAnimation.Track idle = rig.idleClip == null ? null : rig.idleClip.track(name);
            MobAnimation.Track walk = rig.walkClip == null ? null : rig.walkClip.track(name);
            MobAnimation.Track hit = attacking ? rig.attackClip.track(name) : null;
            MobAnimation.Curve idleRotation = idle == null ? null : idle.rotation();
            MobAnimation.Curve walkRotation = walk == null ? null : walk.rotation();
            MobAnimation.Curve hitRotation = hit == null ? null : hit.rotation();
            MobAnimation.Curve idlePosition = idle == null ? null : idle.position();
            MobAnimation.Curve walkPosition = walk == null ? null : walk.position();
            MobAnimation.Curve hitPosition = hit == null ? null : hit.position();

            // Locomotion: the rest pose blended toward idle and then toward walk.
            // A clip that does not name this part leaves it at rest while that
            // clip plays, so a missing walk track fades the idle pose out as the
            // mob starts moving instead of pinning the idle pose forever.
            Quaternionf local = rig.clipLocal;
            if (walkRotation != null && idleRotation != null) {
                sampledWithRest(rig.clipIdle, rig, sample(rig, idleRotation, idleTime));
                sampledWithRest(rig.clipWalk, rig, sample(rig, walkRotation, walkTime));
                local.set(rig.clipIdle).slerp(rig.clipWalk, weight);
            } else if (walkRotation != null) {
                sampledWithRest(rig.clipWalk, rig, sample(rig, walkRotation, walkTime));
                sampledWithRest(local, rig, null);
                local.slerp(rig.clipWalk, weight);
            } else if (idleRotation != null) {
                sampledWithRest(rig.clipIdle, rig, sample(rig, idleRotation, idleTime));
                sampledWithRest(rig.clipWalk, rig, null);
                local.set(rig.clipIdle).slerp(rig.clipWalk, weight);
            } else {
                sampledWithRest(local, rig, null);
            }

            if (hitRotation != null) {
                sampledWithRest(rig.clipWalk, rig, sample(rig, hitRotation, attackTime));
                local.slerp(rig.clipWalk, attack);
            }
            boneRig.setLocal(ownerId, i + 1, local);

            float offsetX = 0.0f;
            float offsetY = 0.0f;
            float offsetZ = 0.0f;
            if (hitPosition != null && attack > 0.5f) {
                hitPosition.sample(attackTime, rig.value);
                offsetX = rig.value.x;
                offsetY = rig.value.y;
                offsetZ = rig.value.z;
            } else if (walkPosition != null && idlePosition != null) {
                walkPosition.sample(walkTime, rig.value);
                idlePosition.sample(idleTime, rig.position);
                offsetX = lerp(rig.position.x, rig.value.x, weight);
                offsetY = lerp(rig.position.y, rig.value.y, weight);
                offsetZ = lerp(rig.position.z, rig.value.z, weight);
            } else if (walkPosition != null) {
                walkPosition.sample(walkTime, rig.value);
                offsetX = rig.value.x * weight;
                offsetY = rig.value.y * weight;
                offsetZ = rig.value.z * weight;
            } else if (idlePosition != null) {
                idlePosition.sample(idleTime, rig.value);
                float restWeight = 1.0f - weight;
                offsetX = rig.value.x * restWeight;
                offsetY = rig.value.y * restWeight;
                offsetZ = rig.value.z * restWeight;
            }
            // Clip positions are model pixels; the rig offsets in blocks.
            boneRig.setLocalOffset(ownerId, i + 1,
                    offsetX / 16.0f, offsetY / 16.0f, offsetZ / 16.0f);
        }
    }

    /** Samples a curve into the rig's scratch vector and returns it. */
    private static Vector3f sample(Rig rig, MobAnimation.Curve curve, float seconds) {
        if (curve == null) {
            return null;
        }
        curve.sample(seconds, rig.value);
        return rig.value;
    }

    /**
     * {@code out = Rz·Ry·Rx} of the part's rest degrees plus a sampled delta
     * (the rest alone when the delta is {@code null}).
     */
    private static void sampledWithRest(Quaternionf out, Rig rig, Vector3f delta) {
        float x = rig.rest.x;
        float y = rig.rest.y;
        float z = rig.rest.z;
        if (delta != null) {
            x += delta.x;
            y += delta.y;
            z += delta.z;
        }
        MobPoseSpace.euler(out, x, y, z);
    }

    private static float lerp(float from, float to, float weight) {
        return from + (to - from) * weight;
    }

    /**
     * Pose from part roles (a model with no usable clips): head tracking,
     * quadruped leg swing and the body's rest rotation. The authored rest is
     * folded into every bone here too, so a rig looks the same whether a clip or
     * a role posed it.
     */
    private void poseProcedural(Rig rig, UUID ownerId, Quaternionf bodyRotation,
                                float bodyYaw, float headYaw, float pitch,
                                float phase, float amplitude) {
        List<MobPart> parts = rig.model.parts();
        Quaternionf bodyInverse = new Quaternionf(bodyRotation).invert();
        // A mob does not twist its neck past its own field of regard; clamping
        // the relative yaw is what keeps a head from snapping round a shoulder.
        float relative = MobPoseSpace.unwrap(bodyYaw, headYaw);
        relative = Math.max(-HEAD_TRACK_LIMIT, Math.min(HEAD_TRACK_LIMIT, relative));
        Quaternionf headFrame = MobPoseSpace.yawFrame(rig.clipWalk, bodyYaw + relative,
                        rig.overrides.yawOffsetDegrees())
                .mul(new Quaternionf().rotationX((float) Math.toRadians(-pitch)));
        Quaternionf headRotation = new Quaternionf(bodyInverse).mul(headFrame);

        for (int i = 0; i < parts.size(); i++) {
            MobPart part = parts.get(i);
            Quaternionf base = rig.base(i);
            MobPart.Role role = rig.overrides.roleFor(part, part.role());
            Quaternionf local = switch (role) {
                case HEAD -> new Quaternionf(headRotation).mul(base);
                case LEG_FRONT_LEFT, LEG_BACK_RIGHT ->
                        swing(rig.clipIdle, (float) -Math.cos(phase) * amplitude).mul(base);
                case LEG_FRONT_RIGHT, LEG_BACK_LEFT ->
                        swing(rig.clipIdle, (float) Math.cos(phase) * amplitude).mul(base);
                default -> base;
            };
            boneRig.setLocal(ownerId, i + 1, local);
        }
    }

    /** A leg's forward swing, in radians of pitch rotation. */
    private static Quaternionf swing(Quaternionf out, float radians) {
        return out.rotationX(radians);
    }

    private static MobAnimation resolveClip(CustomMobModel model, String... names) {
        for (String name : names) {
            MobAnimation clip = model.animation(name);
            if (clip != null && clip.hasTracks()) {
                return clip;
            }
        }
        return null;
    }

    @SuppressWarnings("removal")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        if (!rigs.isEmpty()) {
            detach(event.getEntity().getUniqueId());
        }
    }

    /**
     * A rigged mob landing a melee hit plays its attack clip. The Core's
     * {@code AttackNearestAction} (and any module action routed through
     * {@code DamageTransactions}) reaches this without knowing about rendering.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRigDamageDealt(EntityDamageByEntityEvent event) {
        if (rigs.isEmpty()) {
            return;
        }
        Entity damager = event.getDamager();
        Rig rig = damager == null ? null : rigs.get(damager.getUniqueId());
        if (rig != null) {
            playAttack(rig);
        }
    }

    /**
     * A melee swing plays the attack clip even when it hits nothing. Waiting for
     * landed damage alone makes a mob that misses look like it never attacked at
     * all, which is what a hunting mob does most of the time. The Core's attack
     * actions call this at swing time; a module that strikes elsewhere is still
     * covered by {@link #onRigDamageDealt}.
     */
    public static void notifyMeleeSwing(LivingEntity attacker) {
        MobRigRenderer renderer = activeRenderer;
        if (renderer != null && attacker != null) {
            renderer.playAttack(attacker);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (rigs.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Rig> entry : rigs.entrySet()) {
            Entity owner = entry.getValue().owner;
            if (owner.getWorld().equals(event.getWorld())
                    && ((int) Math.floor(owner.getLocation().getX()) >> 4) == event.getChunk().getX()
                    && ((int) Math.floor(owner.getLocation().getZ()) >> 4) == event.getChunk().getZ()) {
                detach(entry.getKey());
            }
        }
    }

    /** Startup sweep: removes rig displays left by a previous session. */
    public int sweepGhosts() {
        return boneRig.sweepGhosts();
    }

    /** The owner UUID encoded in a display's scoreboard tags, or {@code null}. */
    public static UUID mobRigOwner(Entity entity) {
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith(TAG_PREFIX)) {
                try {
                    return UUID.fromString(tag.substring(TAG_PREFIX.length()));
                } catch (IllegalArgumentException malformed) {
                    return null;
                }
            }
        }
        return null;
    }

    private static final class Rig {
        private final LivingEntity owner;
        private final CustomMobDefinition definition;
        private final CustomMobModel model;
        private final MobModelOverrides overrides;
        private final Quaternionf[] bases;
        private final MobAnimation walkClip;
        private final MobAnimation idleClip;
        private final MobAnimation attackClip;
        private final Quaternionf clipIdle = new Quaternionf();
        private final Quaternionf clipWalk = new Quaternionf();
        private final Quaternionf clipLocal = new Quaternionf();
        private final Quaternionf scratch = new Quaternionf();
        private final Vector3f rest = new Vector3f();
        private final Vector3f value = new Vector3f();
        private final Vector3f position = new Vector3f();
        private double lastX;
        private double lastZ;
        private World lastWorld;
        private long lastWorldTime;
        private float lastSpeed;
        private float smoothedSpeed;
        private float lastBodyYaw;
        private float lastHeadYaw;
        private float lastPitch;
        private float phase;
        private float amplitude;
        private float walkWeight;
        private float walkClock;
        private float idleClock;
        private float attackClock;
        /** 0 = the walk/idle pose, 1 = the full attack pose. */
        private float attackBlend;
        private boolean attacking;
        private boolean hiddenBase;

        private Rig(LivingEntity owner, CustomMobDefinition definition, float yaw,
                    MobModelOverrides overrides) {
            this.owner = owner;
            this.definition = definition;
            this.model = definition.model();
            this.overrides = overrides == null ? MobModelOverrides.DEFAULTS : overrides;
            this.bases = new Quaternionf[model.parts().size()];
            for (int i = 0; i < bases.length; i++) {
                bases[i] = MobPoseSpace.restRotation(model.parts().get(i));
            }
            this.walkClip = resolveClip(model, "animation.walk", "walk");
            this.idleClip = resolveClip(model, "animation.idle", "idle");
            this.attackClip = resolveClip(model, "animation.attack", "attack");
            this.lastX = owner.getLocation().getX();
            this.lastZ = owner.getLocation().getZ();
            this.lastWorld = owner.getWorld();
            this.lastBodyYaw = yaw;
            this.lastHeadYaw = yaw;
        }

        private boolean hasClips() {
            return overrides.useClips(walkClip != null || idleClip != null || attackClip != null);
        }

        /** The last pose this rig wrote, for {@code /mobfun rig} and other probes. */
        private String poseDescription() {
            return (hasClips() ? "clips[" + clipNames() + "]" : "procedural")
                    + " speed=" + String.format(java.util.Locale.ROOT, "%.2f", smoothedSpeed)
                    + " walk=" + String.format(java.util.Locale.ROOT, "%.2f", walkWeight)
                    + " attack=" + (attacking
                        ? String.format(java.util.Locale.ROOT, "%.2f/%ds", attackBlend,
                            Math.round(attackSeconds())) : "idle")
                    + " phase=" + String.format(java.util.Locale.ROOT, "%.2f", phase);
        }

        private float attackSeconds() {
            return attackClip == null ? 0.0f : attackClip.lengthSeconds();
        }

        private String clipNames() {
            StringBuilder names = new StringBuilder();
            if (walkClip != null) {
                names.append('\'').append(walkClip.name()).append('\'');
            }
            if (idleClip != null) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append('\'').append(idleClip.name()).append('\'');
            }
            if (attackClip != null) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append('\'').append(attackClip.name()).append('\'');
            }
            return names.toString();
        }

        private Quaternionf base(int index) {
            return bases[index];
        }
    }
}
