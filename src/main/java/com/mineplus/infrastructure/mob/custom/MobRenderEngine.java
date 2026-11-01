package com.mineplus.infrastructure.mob.custom;

import com.mineplus.infrastructure.fx.ParticleFx;
import com.mineplus.infrastructure.mob.custom.backend.EntityMobBackend;
import com.mineplus.infrastructure.mob.custom.backend.MobDisplayBackend;
import com.mineplus.infrastructure.mob.custom.backend.MobDisplayHandle;
import com.mineplus.infrastructure.mob.custom.backend.TransportMobBackend;
import com.mineplus.infrastructure.mob.custom.model.ModelBone;
import com.mineplus.infrastructure.mob.custom.model.ModelData;
import com.mineplus.infrastructure.mob.custom.model.ModelFormatException;
import com.mineplus.infrastructure.mob.custom.model.BbModelParser;
import com.mineplus.infrastructure.mob.custom.model.JavaJsonParser;
import com.mineplus.infrastructure.mob.custom.model.ModelAnimation;
import com.mineplus.infrastructure.mob.custom.render.GenericFallbackAssets;
import com.mineplus.infrastructure.mob.custom.render.MobShadowAssets;
import com.mineplus.infrastructure.mob.custom.render.ModelBundle;
import com.mineplus.infrastructure.mob.custom.render.ModelBundleCompiler;
import com.mineplus.infrastructure.mob.custom.render.PackModelEmitter;
import com.mineplus.infrastructure.mob.custom.render.PackReport;
import com.mineplus.infrastructure.mob.custom.render.PngUtil;
import com.mineplus.infrastructure.mob.custom.render.VariantDatapackWriter;
import com.mineplus.infrastructure.mob.custom.rig.BoneTransformMath;
import com.mineplus.infrastructure.mob.custom.rig.MobAnimator;
import com.mineplus.infrastructure.mob.navigation.MobAiIsolation;
import com.mineplus.infrastructure.virtual.display.DisplayTransport;
import com.mineplus.pack.PackApi;
import com.mineplus.util.DebugLogger;
import com.mineplus.util.ServerVersion;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The custom-mob render engine — the {@code RenderStrategy} orchestrator of
 * the architecture. One {@code Entry} per registered definition (its compiled
 * {@link ModelBundle} or its retexture assets), one {@code Session} per live
 * rigged mob (its displays, animator clocks and viewer state), and one tick
 * pass that composes rest rotations, clip samples, head look and the body's
 * yaw frame into per-bone display matrices.
 *
 * <p><b>Fallback chain.</b> A mob only ever gets a rig when its bundle is
 * {@code OK}/{@code REPAIRED} and the pack can actually present every piece;
 * otherwise the definition's fallback applies ({@code BASE_ENTITY} — the
 * vanilla base stays visible — or {@code GENERIC_CUBE} — one neutral bundled
 * cube follows the mob). A viewer whose client has not applied the resource
 * pack never receives rig displays at all and sees the base entity; the
 * reconciliation is per viewer, in the backend.</p>
 *
 * <p>Nothing in the tick loop allocates per bone; parse and pack emission
 * happen on registration (off the hot path); every failure degrades to the
 * base entity and is reported through {@link #rigFailure(String)}.</p>
 */
public final class MobRenderEngine {

    /** Ghost-cleanup tag prefix on every display the engine spawns. */
    public static final String TAG_PREFIX = "mineplus_mobrig:";

    private static final float TICK_SECONDS = 0.05f;
    private static final int VIEWER_RECONCILE_TICKS = 20;
    private static final float DEATH_GRACE_SECONDS = 0.5f;
    /** How long a mob with no authored death clip is kept after it dies. */
    private static final float DEATH_HOLD_SECONDS = 1.6f;
    private static final float BONE_MARKER_SCALE = 0.06f;
    /** Horizontal movement per tick below which the body yaw is the better heading. */
    private static final double MIN_HEADING_STEP = 0.004;
    /** How fast the rig heading closes on its target while the Core steers it. */
    private static final float HEADING_EASE = 0.4f;
    /** A mob steered by vanilla already turns its body; the rig only smooths it. */
    private static final float HEADING_EASE_VANILLA_AI = 0.8f;
    /** Blocks of slack before a released rig is rebuilt (no border thrash). */
    private static final double RELEASE_HYSTERESIS_BLOCKS = 8.0;
    /** Pose passes a mid-detail rig waits between pushes. */
    private static final int MID_LOD_DIVISOR = 4;
    /** A pose push is skipped when neither position nor matrix moved by this. */
    private static final double POSE_POSITION_EPSILON = 0.0015;
    private static final float POSE_MATRIX_EPSILON = 0.0008f;
    /** Ticks the base model stays revealed for a vanilla hurt flash. */
    private static final int HURT_REVEAL_TICKS = 8;
    /** Pose passes between two hurt particle bursts of the same mob. */
    private static final int HURT_BURST_COOLDOWN = 4;

    private final Plugin plugin;
    private final PackApi pack;
    private volatile CustomMobSettings settings;
    private volatile DisplayTransport transport;
    private volatile TransportMobBackend transportBackend;
    private volatile ParticleFx particleFx;
    private final EntityMobBackend entityBackend;

    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Boolean> viewerPackOk = new HashMap<>();
    private final Set<String> loggedFailures = new HashSet<>();
    /** The online viewers as of this pose pass, read by every rig's distance scan. */
    private final List<Player> passViewers = new ArrayList<>();
    /** Parallel to {@link #passViewers}: whether that viewer can present the pack. */
    private final List<Boolean> passViewerPack = new ArrayList<>();

    private BukkitTask tickTask;
    private BukkitTask reconcileTask;
    /** The cadence the pose loop is actually scheduled at (mirrors the setting). */
    private int poseIntervalTicks = 1;
    /** Pose passes since the engine started; drives the per-rig LOD cadence. */
    private long passCounter;
    private volatile static MobRenderEngine active;
    private volatile boolean boneDebug;
    /** Paper's {@code Entity#setSize}, probed once; absent on plain Spigot. */
    private static MethodHandle setSizeHandle;
    private static boolean setSizeProbed;

    public MobRenderEngine(Plugin plugin, PackApi pack, CustomMobSettings settings) {
        this.plugin = plugin;
        this.pack = pack;
        this.settings = settings == null ? CustomMobSettings.defaults() : settings;
        this.entityBackend = new EntityMobBackend(plugin, TAG_PREFIX);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Late transport injection (the display transport may start after the engine). */
    public void setTransport(DisplayTransport transport) {
        this.transport = transport;
        this.transportBackend = null;
    }

    /**
     * Late particle-budget injection: the shared {@link ParticleFx} service is
     * built after the mob subsystem, and hurt feedback degrades to the hurt clip
     * alone when it never arrives.
     */
    public void setParticleFx(ParticleFx particleFx) {
        this.particleFx = particleFx;
    }

    /** Applies new subsystem settings; disabled rendering tears the rigs down. */
    public void updateSettings(CustomMobSettings next) {
        int previousInterval = settings.renderIntervalTicks();
        if (next != null) {
            this.settings = next;
        }
        if (!settings().enabled() || !settings().renderingEnabled()) {
            for (Session session : List.copyOf(sessions.values())) {
                destroySession(session);
                sessions.remove(session.entity.getUniqueId());
            }
        }
        if (tickTask != null && settings.renderIntervalTicks() != previousInterval) {
            tickTask.cancel();
            poseIntervalTicks = settings.renderIntervalTicks();
            tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick,
                    poseIntervalTicks, poseIntervalTicks);
        }
    }

    public CustomMobSettings settings() {
        return settings;
    }

    /** Starts the tick loop, the listeners and the ghost sweep. */
    public void start() {
        Bukkit.getPluginManager().registerEvents(new Listeners(), plugin);
        GenericFallbackAssets.register(pack);
        MobShadowAssets.register(pack);
        poseIntervalTicks = settings.renderIntervalTicks();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick,
                poseIntervalTicks, poseIntervalTicks);
        reconcileTask = Bukkit.getScheduler().runTaskTimer(plugin, this::reconcileViewers,
                VIEWER_RECONCILE_TICKS, VIEWER_RECONCILE_TICKS);
        active = this;
        int ghosts = entityBackend.sweepGhosts(id -> sessions.containsKey(id));
        if (ghosts > 0) {
            DebugLogger.info("[CustomMobs] Swept " + ghosts + " stale mob rig display(s).");
        }
    }

    /** Stops everything; live mobs keep their (restored) base entities. */
    public void shutdown() {
        active = null;
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (reconcileTask != null) {
            reconcileTask.cancel();
            reconcileTask = null;
        }
        HandlerList.unregisterAll(plugin);
        for (Session session : List.copyOf(sessions.values())) {
            destroySession(session);
        }
        sessions.clear();
        viewerPackOk.clear();
    }

    private MobDisplayBackend backend() {
        DisplayTransport current = transport;
        if (current != null && current.isRunning()) {
            TransportMobBackend pooled = transportBackend;
            if (pooled == null) {
                pooled = new TransportMobBackend(current, TAG_PREFIX);
                transportBackend = pooled;
            }
            return pooled;
        }
        return entityBackend;
    }

    private boolean usesTransport() {
        return backend() instanceof TransportMobBackend;
    }

    // ------------------------------------------------------------------
    // Definitions
    // ------------------------------------------------------------------

    /** Registers (or re-registers) a definition: compiles its bundle or retexture assets. */
    public void registerDefinition(CustomMobDefinition definition) {
        Entry entry = new Entry(definition, definition.renderSpec());
        entries.put(definition.id(), entry);
        rebuildAssets(entry);
    }

    /** Removes a definition and destroys the sessions of its live mobs. */
    public void unregisterDefinition(String definitionId) {
        Entry entry = entries.remove(definitionId);
        if (entry == null) {
            return;
        }
        for (Session session : List.copyOf(sessions.values())) {
            if (session.entry == entry) {
                destroySession(session);
                sessions.remove(session.entity.getUniqueId());
            }
        }
        if (entry.spec != null && entry.spec.type() == MobRenderSpec.RenderType.RETEXTURE_VANILLA) {
            String registry = VariantDatapackWriter.registryFor(entry.definition.baseType());
            if (registry != null) {
                VariantDatapackWriter.removeEntry(entry.definition.namespace(), entry.definition.path(),
                        registry);
            }
        }
    }

    private void rebuildAssets(Entry entry) {
        CustomMobDefinition definition = entry.definition;
        MobRenderSpec spec = entry.spec;
        String id = definition.id();
        if (spec == null && definition.model() == null) {
            entry.bundle = null;
            entry.status = "vanilla look (no model)";
            return;
        }
        MobRenderSpec.RenderType type = spec != null ? spec.type() : MobRenderSpec.RenderType.BBMODEL;
        switch (type) {
            case NONE -> {
                entry.bundle = null;
                entry.status = "vanilla look";
            }
            case RETEXTURE_VANILLA -> {
                entry.bundle = null;
                shipRetexture(entry);
            }
            default -> {
                ModelData model = parseModel(entry, spec);
                entry.bundle = ModelBundleCompiler.compile(pack, definition.namespace(),
                        definition.path(), model);
                entry.status = entry.bundle.statusLine();
                logOnce(id, "compile", entry.bundle.report().usable(),
                        "[CustomMobs] '" + id + "': " + entry.status);
            }
        }
    }

    private ModelData parseModel(Entry entry, MobRenderSpec spec) {
        String source = spec != null ? spec.sourceName() : "legacy-model";
        try {
            if (spec != null && spec.type() == MobRenderSpec.RenderType.JAVA_JSON) {
                return JavaJsonParser.parse(source, spec.modelBytes());
            }
            if (spec != null) {
                // textureBytes doubles as the bbmodel's sibling-texture resolver:
                // exports that do not embed their texture reference one PNG file.
                byte[] sibling = spec.textureBytes();
                return BbModelParser.parse(source, spec.modelBytes(),
                        sibling == null ? null : reference -> sibling);
            }
            return LegacyModelAdapter.adapt(source, entry.definition.model());
        } catch (ModelFormatException malformed) {
            entry.status = "BROKEN: " + malformed.getMessage();
            logOnce(entry.definition.id(), "parse", false,
                    "[CustomMobs] '" + entry.definition.id() + "' model failed to parse: "
                            + malformed.getMessage());
            return null;
        }
    }

    private void shipRetexture(Entry entry) {
        CustomMobDefinition definition = entry.definition;
        MobRenderSpec spec = entry.spec;
        byte[] png = spec.textureBytes();
        int[] dims = PngUtil.dimensions(png);
        if (dims == null) {
            entry.status = "BROKEN: the replacement texture is not a readable PNG";
            logOnce(definition.id(), "texture", false,
                    "[CustomMobs] '" + definition.id() + "': " + entry.status);
            return;
        }
        int[] expected = VariantDatapackWriter.expectedTextureSize(definition.baseType());
        if (expected != null && (dims[0] != expected[0] || dims[1] != expected[1])) {
            entry.status = "BROKEN: the " + definition.baseType().name().toLowerCase(Locale.ROOT)
                    + " texture must be " + expected[0] + "x" + expected[1]
                    + " (vanilla layout), got " + dims[0] + "x" + dims[1];
            logOnce(definition.id(), "texture", false,
                    "[CustomMobs] '" + definition.id() + "': " + entry.status);
            return;
        }
        String registry = VariantDatapackWriter.registryFor(definition.baseType());
        String whyNoVariant = registry == null ? "no variant registry for "
                + definition.baseType().name().toLowerCase(Locale.ROOT)
                : !ServerVersion.dataDrivenVariants() ? "data-driven variants need 1.21.5+ (server is "
                + ServerVersion.rawVersion() + ")" : null;
        if (whyNoVariant != null && presentRetextureAsRig(entry, png, whyNoVariant)) {
            return;
        }
        if (pack != null && pack.isAvailable()) {
            pack.registerGeneratedAsset(definition.namespace(),
                    "textures/entity/" + definition.path() + ".png", png);
        }
        if (whyNoVariant != null) {
            entry.status = "texture shipped; " + whyNoVariant;
            logOnce(definition.id(), "variant", false,
                    "[CustomMobs] '" + definition.id() + "': " + entry.status);
            return;
        }
        boolean changed = VariantDatapackWriter.ensureEntry(definition.namespace(),
                definition.path(), registry);
        entry.status = "variant '" + registry + " " + definition.path() + "' written"
                + (changed ? "; restart the server (or /reload) so the client registry syncs" : "");
        logOnce(definition.id(), "variant", true,
                "[CustomMobs] '" + definition.id() + "': " + entry.status);
    }

    /**
     * The retexture path on a server that cannot extend a vanilla variant
     * registry: the mob wears its texture on a part rig built from the vanilla
     * model descriptor instead of relying on the client's model. It is the
     * ordinary bundle path, so head look, procedural limb swing and hiding the
     * base entity all work exactly as they do for an imported model.
     */
    private boolean presentRetextureAsRig(Entry entry, byte[] png, String why) {
        CustomMobDefinition definition = entry.definition;
        if (!VanillaMobModels.supports(definition.baseType()) || pack == null
                || !pack.isAvailable()) {
            return false;
        }
        CustomMobModel legacy = CustomMobModel.vanilla(definition.baseType(), png);
        ModelData model = LegacyModelAdapter.adapt(definition.path() + ".vanilla", legacy, null);
        ModelBundle bundle = ModelBundleCompiler.compile(pack, definition.namespace(),
                definition.path(), model);
        if (!bundle.presentable()) {
            return false;
        }
        entry.bundle = bundle;
        entry.status = "retextured as a part rig (" + why + "); " + bundle.statusLine();
        logOnce(definition.id(), "variant", true, "[CustomMobs] '" + definition.id() + "': " + entry.status);
        return true;
    }

    private void logOnce(String definitionId, String key, boolean healthy, String message) {
        String composite = definitionId + "|" + key + "|" + (healthy ? "ok" : "fail");
        if (healthy) {
            if (loggedFailures.remove(definitionId + "|" + key + "|fail")) {
                DebugLogger.info(message);
            }
            return;
        }
        if (loggedFailures.add(composite)) {
            DebugLogger.warning(message);
        }
    }

    // ------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------

    /** A custom mob spawned (or re-discovered on chunk load): build its rig session. */
    public void onSpawned(LivingEntity entity, CustomMobDefinition definition) {
        Entry entry = entries.get(definition.id());
        if (entry == null) {
            return;
        }
        if (entry.spec != null && entry.spec.type() == MobRenderSpec.RenderType.RETEXTURE_VANILLA
                && entry.bundle == null) {
            // The datapack entry alone does not restkin anything: the entity has
            // to be told to use the variant. A failure here is not fatal — the
            // mob simply keeps its vanilla texture.
            VariantDatapackWriter.applyVariant(entity, definition.namespace(), definition.path(),
                    definition.baseType());
        }
        if (!ServerVersion.displayEntities()) {
            return;
        }
        Session existing = sessions.remove(entity.getUniqueId());
        if (existing != null) {
            destroySession(existing);
        }
        applyHitbox(entity, entry.spec);
        if (!settings().enabled() || !settings().renderingEnabled()) {
            return;
        }
        Session session = new Session(entity, entry, backend());
        if (session.build()) {
            sessions.put(entity.getUniqueId(), session);
        }
        applySessionViewers(session);
    }

    /**
     * Applies {@code render.hitbox} to the base entity, so the box a player aims
     * at and the model they look at agree. Paper exposes
     * {@code Entity#setSize(float, float)}; plain Spigot does not, so the call is
     * probed once and a server without it keeps the base type's own box — the
     * rig still renders, only the hitbox stays vanilla.
     */
    private void applyHitbox(LivingEntity entity, MobRenderSpec spec) {
        if (spec == null || spec.hitboxWidth() == null || spec.hitboxHeight() == null) {
            return;
        }
        MethodHandle handle = probeSetSize(entity);
        if (handle == null) {
            return;
        }
        try {
            handle.invoke(entity, spec.hitboxWidth().floatValue(), spec.hitboxHeight().floatValue());
        } catch (Throwable refused) {
            DebugLogger.warning("[CustomMobs] This server refused the hitbox override for '"
                    + spec.sourceName() + "': " + refused);
        }
    }

    private static MethodHandle probeSetSize(LivingEntity entity) {
        if (setSizeProbed) {
            return setSizeHandle;
        }
        setSizeProbed = true;
        try {
            MethodHandle handle = MethodHandles.lookup().unreflect(
                    entity.getClass().getMethod("setSize", float.class, float.class));
            setSizeHandle = handle.asType(handle.type().changeParameterType(0, LivingEntity.class));
        } catch (Throwable absent) {
            DebugLogger.info("[CustomMobs] No Entity#setSize on this server; render.hitbox "
                    + "overrides keep the base type's vanilla box.");
        }
        return setSizeHandle;
    }

    /** Re-compiles every definition and rebuilds every session (pack reload path). */
    public void refreshRendering() {
        loggedFailures.clear();
        for (Entry entry : entries.values()) {
            rebuildAssets(entry);
        }
        boolean rigsAllowed = settings().enabled() && settings().renderingEnabled();
        for (Session session : List.copyOf(sessions.values())) {
            if (!session.entity.isValid() || !rigsAllowed) {
                destroySession(session);
                sessions.remove(session.entity.getUniqueId());
                continue;
            }
            destroySession(session);
            Session rebuilt = new Session(session.entity, session.entry, backend());
            if (rebuilt.build()) {
                sessions.put(session.entity.getUniqueId(), rebuilt);
                applySessionViewers(rebuilt);
            } else {
                sessions.remove(session.entity.getUniqueId());
            }
        }
    }

    private void destroySession(Session session) {
        session.despawnDisplays();
        session.revealBase();
    }

    // ------------------------------------------------------------------
    // Tick pass
    // ------------------------------------------------------------------

    private void tick() {
        if (sessions.isEmpty()) {
            return;
        }
        CustomMobSettings current = settings;
        float passSeconds = TICK_SECONDS * poseIntervalTicks;
        long pass = ++passCounter;
        snapshotViewers();
        Iterator<Session> iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next();
            LivingEntity entity = session.entity;
            if (!entity.isValid()) {
                destroySession(session);
                iterator.remove();
                continue;
            }
            if (!session.dead && (entity.isDead() || entity.getHealth() <= 0.0)) {
                beginDeath(session);
            }
            if (session.hurtTicks > 0) {
                session.hurtTicks--;
                emitHurtFeedback(session, pass);
            }
            if (session.dead) {
                session.deathTimer -= passSeconds;
                if (session.deathTimer <= 0.0f) {
                    destroySession(session);
                    iterator.remove();
                    continue;
                }
            }
            session.scanViewers(current);
            if (!session.dead) {
                // A rig nobody is near releases its displays entirely and lets the
                // real entity stand in: an invisible hitbox carrier with no rig
                // would be worse than the vanilla mob it covers.
                if (session.nearestSq > current.renderDistance() * current.renderDistance()) {
                    session.releaseDisplays();
                    syncBasePresentation(session);
                    continue;
                }
                double wakeAt = current.renderDistance() - RELEASE_HYSTERESIS_BLOCKS;
                if (session.released && session.nearestSq <= wakeAt * wakeAt) {
                    session.rebuildDisplays(current);
                }
                syncBasePresentation(session);
                if (session.released) {
                    continue;
                }
            }
            int divisor = session.midLod() ? MID_LOD_DIVISOR : 1;
            if (!session.dueThisPass(pass, divisor)) {
                continue;
            }
            animate(session, current, divisor);
        }
    }

    /**
     * The viewer list read once per pose pass. Every rig otherwise walks the
     * player list itself, and with a crowd of mobs that is a mob-times-player
     * loop of allocations per tick — the same information, gathered one time.
     */
    private void snapshotViewers() {
        passViewers.clear();
        passViewerPack.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            passViewers.add(player);
            passViewerPack.add(Boolean.TRUE.equals(viewerPackOk.get(player.getUniqueId())));
        }
    }

    /**
     * The damage confirmation a rigged mob cannot get from the game on its own:
     * the model that would flash red is the base entity, and the base entity is
     * invisible while the rig covers it. So a hit is answered with a spark burst
     * — or, for a definition that asks for {@code FLASH}, by revealing the real
     * model for a moment so the vanilla red flash plays.
     */
    private void emitHurtFeedback(Session session, long pass) {
        MobRenderSpec spec = session.entry.spec;
        if (spec != null && spec.hurtFeedback() == MobRenderSpec.HurtFeedback.FLASH) {
            return;
        }
        ParticleFx fx = particleFx;
        if (fx == null || pass - session.lastHurtBurstPass < HURT_BURST_COOLDOWN) {
            return;
        }
        session.lastHurtBurstPass = pass;
        LivingEntity entity = session.entity;
        fx.burst(ParticleFx.Tier.CRITICAL,
                new Location(entity.getWorld(), entity.getX(),
                        entity.getY() + entity.getHeight() * 0.6, entity.getZ()),
                Particle.CRIT, 10, 0.25, 0.2, 0.25, 0.0);
    }

    /**
     * One mob just died. With an authored death clip the rig plays it; without
     * one the rig steps aside and the vanilla death animation plays, so a mob
     * never vanishes mid-collapse.
     */
    private void beginDeath(Session session) {
        session.dead = true;
        session.animator.markDead();
        if (session.animator.hasDeathClip()) {
            session.deathTimer = session.animator.deathClipLength() + DEATH_GRACE_SECONDS;
        } else {
            session.releaseDisplays();
            session.deathTimer = DEATH_HOLD_SECONDS;
        }
        syncBasePresentation(session);
    }

    private void animate(Session session, CustomMobSettings current, int divisor) {
        LivingEntity entity = session.entity;
        Location location = entity.getLocation();
        double dx = location.getX() - session.lastX;
        double dy = location.getY() - session.lastY;
        double dz = location.getZ() - session.lastZ;
        session.lastX = location.getX();
        session.lastY = location.getY();
        session.lastZ = location.getZ();
        int elapsedTicks = poseIntervalTicks * divisor;
        float speed = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / elapsedTicks;
        int interpolation = interpolationWindow(current) * divisor;

        session.animator.tick(TICK_SECONDS * elapsedTicks, speed);
        if (!session.hasRig) {
            return;
        }
        poseShadow(session, location, interpolation);
        if (session.generic) {
            poseGenericCube(session, location, interpolation);
            return;
        }

        float heading = session.dead ? session.heading : advanceHeading(session, location, dx, dz);
        BoneTransformMath.yawFrame(heading, session.entry.yawOffset(), session.yawQuaternion);
        session.yawMatrix.set(session.yawQuaternion);

        ModelBundle bundle = session.entry.bundle;
        ModelData model = bundle != null ? bundle.model() : null;
        if (model == null) {
            return;
        }
        float scale = session.entry.spec != null ? session.entry.spec.scale() : 1.0f;
        Map<String, MobAnimator.BonePose> poses = session.animator.poses();

        float headYaw = 0.0f;
        float headPitch = 0.0f;
        boolean hasHead = session.headBone >= 0;
        if (hasHead) {
            // Head yaw reaches Bukkit only through the runtime handle (the
            // stub-free NMS field), so the engine uses the shared reflective
            // helper and falls back to the rig heading when the runtime hides
            // it. The delta is measured against the rig frame, not the server
            // body yaw, or the head would point off by however far the two differ.
            headYaw = BoneTransformMath.headYawModel(MobAiIsolation.headYaw(entity, heading), heading);
            headPitch = BoneTransformMath.headPitchModel(entity.getPitch());
        }

        for (int i = 0; i < model.boneCount(); i++) {
            MobAnimator.BonePose pose = poses.get(model.bone(i).name());
            float rx = session.restX[i] + (pose != null ? pose.rotationX : 0.0f);
            float ry = session.restY[i] + (pose != null ? pose.rotationY : 0.0f);
            float rz = session.restZ[i] + (pose != null ? pose.rotationZ : 0.0f);
            float boneScale = scale * (pose != null ? pose.scale : 1.0f);
            float relX = session.relPivots[i].x + (pose != null ? pose.offsetX : 0.0f);
            float relY = session.relPivots[i].y + (pose != null ? pose.offsetY : 0.0f);
            float relZ = session.relPivots[i].z + (pose != null ? pose.offsetZ : 0.0f);
            if (hasHead && i == session.headBone) {
                rx += headPitch;
                ry += headYaw;
            }
            BoneTransformMath.localMatrix(relX, relY, relZ, rx, ry, rz, boneScale,
                    session.local[i], session.scratchQuaternion);
            if (session.parent[i] < 0) {
                session.absolute[i].set(session.yawMatrix).mul(session.local[i]);
            } else {
                session.absolute[i].set(session.absolute[session.parent[i]]).mul(session.local[i]);
            }
        }

        World world = location.getWorld();
        for (int b = 0; b < session.bones.size(); b++) {
            BoneDisplay display = session.bones.get(b);
            Matrix4f absolute = session.absolute[display.boneIndex];
            session.scratchVector.set(0.0f, 0.0f, 0.0f);
            absolute.transformPosition(session.scratchVector);
            for (int p = 0; p < display.slots.size(); p++) {
                PoseSlot slot = display.slots.get(p);
                Vector3f offset = slot.offset;
                double px = location.getX() + session.scratchVector.x;
                double py = location.getY() + session.scratchVector.y;
                double pz = location.getZ() + session.scratchVector.z;
                if (offset.lengthSquared() > 0.0f) {
                    // Rotational part only: R·S·d = transformPosition(d) −
                    // transformPosition(0), since the stub-free joml surface
                    // exposes no direction-only transform.
                    session.pieceShift.set(offset).div(16.0f).negate();
                    absolute.transformPosition(session.pieceShift);
                    session.pieceOrigin.set(0.0f, 0.0f, 0.0f);
                    absolute.transformPosition(session.pieceOrigin);
                    session.pieceShift.sub(session.pieceOrigin);
                    px += session.pieceShift.x;
                    py += session.pieceShift.y;
                    pz += session.pieceShift.z;
                }
                session.scratchMatrix.set(absolute);
                session.scratchMatrix.setTranslation(0.0f, 0.0f, 0.0f);
                poseSlot(session, slot, world, px, py, pz, interpolation);
            }
        }
        for (int i = 0; i < session.items.size(); i++) {
            AttachmentDisplay item = session.items.get(i);
            Matrix4f absolute = session.absolute[item.boneIndex];
            session.scratchVector.set(0.0f, 0.0f, 0.0f);
            absolute.transformPosition(session.scratchVector);
            session.scratchMatrix.set(absolute);
            session.scratchMatrix.setTranslation(0.0f, 0.0f, 0.0f);
            poseSlot(session, item.slot, world,
                    location.getX() + session.scratchVector.x,
                    location.getY() + session.scratchVector.y,
                    location.getZ() + session.scratchVector.z, interpolation);
        }
        poseBoneMarkers(session, model, location, interpolation);
        if (session.usesTransport()) {
            DisplayTransport currentTransport = transport;
            if (currentTransport != null) {
                currentTransport.flushDeltas(session.id);
            }
        }
    }

    /**
     * Pushes one display's pose, unless nothing about it moved. A standing mob
     * whose clip is holding a frame produces the same matrix every pass, and an
     * unchanged teleport is pure packet cost — the whole reason a crowd of
     * custom mobs was expensive.
     */
    private void poseSlot(Session session, PoseSlot slot, World world,
                          double x, double y, double z, int interpolation) {
        if (slot == null || !slot.handle.isValid()) {
            return;
        }
        if (slot.unchanged(x, y, z, session.scratchMatrix, session.matrixProbe)) {
            return;
        }
        slot.remember(x, y, z, session.scratchMatrix, session.matrixProbe);
        session.scratchLocation.setWorld(world);
        session.scratchLocation.set(x, y, z);
        session.scratchLocation.setYaw(0.0f);
        session.scratchLocation.setPitch(0.0f);
        session.backend.pose(slot.handle, session.scratchLocation, session.scratchMatrix,
                interpolation);
    }

    /** The soft ground plate under the rig: the mob's own shadow is not drawn. */
    private void poseShadow(Session session, Location location, int interpolation) {
        if (session.shadow == null) {
            return;
        }
        session.scratchMatrix.identity();
        session.scratchMatrix.scale(session.shadowDiameter);
        poseSlot(session, session.shadow, location.getWorld(), location.getX(),
                location.getY() + 0.03, location.getZ(), interpolation);
    }

    /** Positions the debug markers at the bone pivots (reuses the tick's matrices). */
    private void poseBoneMarkers(Session session, ModelData model, Location location,
                                 int interpolation) {
        session.syncBoneMarkers(model);
        if (session.boneMarkers.isEmpty()) {
            return;
        }
        World world = location.getWorld();
        for (int i = 0; i < session.boneMarkers.size() && i < model.boneCount(); i++) {
            PoseSlot marker = session.boneMarkers.get(i);
            Matrix4f absolute = session.absolute[i];
            session.scratchVector.set(0.0f, 0.0f, 0.0f);
            absolute.transformPosition(session.scratchVector);
            session.scratchMatrix.set(absolute);
            session.scratchMatrix.setTranslation(0.0f, 0.0f, 0.0f);
            session.scratchMatrix.scale(BONE_MARKER_SCALE);
            poseSlot(session, marker, world,
                    location.getX() + session.scratchVector.x,
                    location.getY() + session.scratchVector.y,
                    location.getZ() + session.scratchVector.z, interpolation);
        }
    }

    /**
     * The client easing window a pose push gets. It can never be shorter than
     * the cadence the pose loop runs at: with a 1-tick window and a 4-tick
     * interval the client finishes easing three ticks before the next sample
     * arrives and the rig visibly snaps instead of gliding.
     */
    private int interpolationWindow(CustomMobSettings current) {
        return Math.max(current.renderInterpolationTicks(), poseIntervalTicks);
    }

    /**
     * The direction the rig faces, eased one pose pass toward its target.
     *
     * <p>A mob steered by the vanilla engine turns its own body: the server yaw
     * is then the truth and the rig only smooths the engine's per-tick steps. A
     * mob whose goals were cleared has nothing rotating its body at all, so a
     * viewer reads "forward" from where the mob actually travels and the body
     * yaw is only the fallback for a mob that is standing still.</p>
     */
    private float advanceHeading(Session session, Location location, double dx, double dz) {
        double stepPerTick = Math.sqrt(dx * dx + dz * dz) / poseIntervalTicks;
        boolean moving = stepPerTick >= MIN_HEADING_STEP;
        float target;
        float ease;
        if (session.vanillaAi) {
            target = location.getYaw();
            ease = HEADING_EASE_VANILLA_AI;
        } else {
            target = moving ? (float) Math.toDegrees(Math.atan2(-dx, dz)) : location.getYaw();
            ease = HEADING_EASE;
        }
        float delta = BoneTransformMath.wrapDegrees(target - session.heading);
        session.heading = BoneTransformMath.wrapDegrees(session.heading + delta * ease);
        return session.heading;
    }

    private void poseGenericCube(Session session, Location location, int interpolation) {
        if (session.genericCube == null) {
            return;
        }
        float height = (float) entityHeight(session.entity);
        BoneTransformMath.yawFrame(location.getYaw(), session.entry.yawOffset(), session.yawQuaternion);
        session.scratchMatrix.set(session.yawQuaternion);
        session.scratchMatrix.scale(height);
        poseSlot(session, session.genericCube, location.getWorld(), location.getX(),
                location.getY() + height * 0.5f, location.getZ(), interpolation);
    }

    private double entityHeight(LivingEntity entity) {
        try {
            double height = entity.getHeight();
            return height > 0.0 ? height : 1.0;
        } catch (Throwable unavailable) {
            return 1.0;
        }
    }

    // ------------------------------------------------------------------
    // Per-viewer reconciliation
    // ------------------------------------------------------------------

    private void reconcileViewers() {
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            boolean ok = pack != null && pack.playerPackState(player.getUniqueId()).hasPack();
            Boolean previous = viewerPackOk.put(player.getUniqueId(), ok);
            if (previous == null || previous != ok) {
                applyPackState(player, ok);
            }
        }
        viewerPackOk.keySet().retainAll(online);
    }

    private void applyPackState(Player player, boolean canSeePack) {
        if (usesTransport()) {
            // One channel call covers every transport session of this viewer.
            DisplayTransport current = transport;
            if (current != null) {
                current.setViewerPackHidden(player.getUniqueId(), !canSeePack);
            }
        } else {
            for (Session session : sessions.values()) {
                if (session.entity.getWorld() != player.getWorld()) {
                    continue;
                }
                session.backend.setSessionViewerHidden(session.id, session.allHandles(),
                        player.getUniqueId(), !canSeePack);
            }
        }
        // Whether a base model may hide itself is a per-entity fact, so one
        // viewer's pack state can change what every nearby mob shows.
        for (Session session : sessions.values()) {
            syncBasePresentation(session);
        }
    }

    private void applySessionViewers(Session session) {
        for (Player player : session.entity.getWorld().getPlayers()) {
            boolean ok = Boolean.TRUE.equals(viewerPackOk.get(player.getUniqueId()));
            session.backend.setSessionViewerHidden(session.id, session.allHandles(),
                    player.getUniqueId(), !ok);
        }
        session.scanViewers(settings);
        syncBasePresentation(session);
    }

    /**
     * Decides whether the mob's own model is on screen. The base entity is never
     * removed from the client — that is what made a rigged mob impossible to
     * look at, hit, push or kill — it is only made invisible, which keeps its
     * hitbox, its collision, its sounds and its damage state fully live.
     *
     * <p>A viewer whose client has no pack never receives the rig, so the base
     * model stays visible while any nearby viewer is in that state: a plain
     * vanilla mob is a far better outcome than a mob nobody can see.</p>
     */
    private void syncBasePresentation(Session session) {
        if (!session.entry.definition.hideBaseWhenRigged()) {
            session.hideBase(false);
            return;
        }
        if (session.released || !session.hasRig) {
            // No rig on screen (nobody near, or none could be built): a visible
            // vanilla mob beats an invisible hitbox. A death with no authored
            // death clip arrives through this branch, so the engine's own
            // collapse plays; a death with one keeps the base hidden and lets
            // the rig finish its animation.
            session.hideBase(false);
            return;
        }
        if (session.hurtTicks > 0 && session.hurtReveals()) {
            session.hideBase(false);
            return;
        }
        session.hideBase(session.viewersHavePack);
    }

    // ------------------------------------------------------------------
    // Probes
    // ------------------------------------------------------------------

    /** True when the entity currently carries a rig session. */
    public boolean hasRig(LivingEntity entity) {
        Session session = entity == null ? null : sessions.get(entity.getUniqueId());
        return session != null && session.hasRig;
    }

    public int sessionCount() {
        return sessions.size();
    }

    /**
     * What the rig pass actually costs right now — the readout that answers "is
     * this mob crowd too heavy?". Released rigs (nobody near enough to see them)
     * and quarter-rate rigs (viewer close, but outside the near range) are both
     * counted, because those are the two levers that keep a horde affordable.
     */
    public String costSnapshot() {
        CustomMobSettings current = settings;
        int posed = 0;
        int quarterRate = 0;
        int released = 0;
        int displays = 0;
        for (Session session : sessions.values()) {
            if (!session.hasRig) {
                continue;
            }
            if (session.released) {
                released++;
                continue;
            }
            posed++;
            displays += session.displayCount();
            if (session.midLod()) {
                quarterRate++;
            }
        }
        return posed + " posed rig(s) pushing " + displays + " display entity(ies)"
                + (quarterRate == 0 ? "" : ", " + quarterRate + " of them at 1/" + MID_LOD_DIVISOR
                + " cadence (beyond " + current.renderNearDistance() + " blocks)")
                + ", " + released + " released (no viewer within " + current.renderDistance()
                + " blocks), " + sessions.size() + " session(s) total; the pose pass runs every "
                + poseIntervalTicks + " tick(s), cap " + current.maxRiggedPerWorld() + " rigs per world";
    }

    /** The compiled bundle of one definition, for the inspect command. */
    public ModelBundle bundle(String definitionId) {
        Entry entry = entries.get(definitionId);
        return entry == null ? null : entry.bundle;
    }

    /** The definition's last presentation status line. */
    public String rigStatus(String definitionId) {
        Entry entry = entries.get(definitionId);
        if (entry == null) {
            return null;
        }
        if (entry.status != null && !entry.status.isEmpty()) {
            return entry.status;
        }
        return entry.bundle != null ? entry.bundle.statusLine() : "no presentation attempted";
    }

    /** Why this definition last could not present its rig, or {@code null}. */
    public String rigFailure(String definitionId) {
        Entry entry = entries.get(definitionId);
        if (entry == null) {
            return null;
        }
        if (entry.bundle != null && entry.bundle.report().status() == PackReport.Status.BROKEN) {
            return entry.bundle.report().summary();
        }
        return null;
    }

    /** The live animator summary of one mob, or {@code null}. */
    public String poseSnapshot(LivingEntity entity) {
        Session session = entity == null ? null : sessions.get(entity.getUniqueId());
        return session == null ? null : session.animator.snapshot();
    }

    /** Plays the mob's attack clip now; {@code false} when impossible. */
    public boolean playAttack(LivingEntity entity) {
        Session session = entity == null ? null : sessions.get(entity.getUniqueId());
        if (session == null || !session.animator.hasAttackClip()) {
            return false;
        }
        session.animator.notifyAttack();
        return true;
    }

    /** Plays any authored clip of one rigged mob as a one-shot overlay. */
    public boolean playAnimation(LivingEntity entity, String clipName) {
        if (entity == null || clipName == null || clipName.isBlank()) {
            return false;
        }
        Session session = sessions.get(entity.getUniqueId());
        if (session == null || !session.hasRig || session.entry.bundle == null
                || session.entry.bundle.model() == null) {
            return false;
        }
        ModelAnimation clip = session.entry.bundle.model().animations()
                .get(clipName.toLowerCase(Locale.ROOT));
        return clip != null && session.animator.playOverlay(clip);
    }

    /** The bone-marker debug mode ({@code /mineplus mob bones on|off}). */
    public boolean boneDebug() {
        return boneDebug;
    }

    /** Shows or hides a tiny cube at every bone pivot of every rigged mob. */
    public void setBoneDebug(boolean on) {
        this.boneDebug = on;
    }

    /**
     * Overrides one definition's authored-front offset at runtime. {@code null}
     * restores the definition's own value; live rigs pick the change up on
     * their next pose push, so a model that renders backwards is answered with
     * one command instead of a rebuild.
     */
    public void setYawOffset(String definitionId, Float degrees) {
        Entry entry = definitionId == null ? null : entries.get(definitionId);
        if (entry == null) {
            return;
        }
        entry.yawOffsetOverride = degrees == null ? null
                : Float.valueOf(BoneTransformMath.wrapDegrees(degrees));
    }

    /** The offset one definition's rigs currently use (the override or the authored value). */
    public float yawOffset(String definitionId) {
        Entry entry = definitionId == null ? null : entries.get(definitionId);
        return entry == null ? 0.0f : entry.yawOffset();
    }

    /** The direction one live rig faces (degrees), or {@code NaN} when it has none. */
    public float rigHeading(LivingEntity entity) {
        Session session = entity == null ? null : sessions.get(entity.getUniqueId());
        return session == null ? Float.NaN : session.heading;
    }

    /** Static hook for behavior actions outside the engine's ownership. */
    public static void notifyMeleeSwing(LivingEntity attacker) {
        MobRenderEngine engine = active;
        if (engine != null && attacker != null) {
            Session session = engine.sessions.get(attacker.getUniqueId());
            if (session != null) {
                session.animator.notifyAttack();
            }
        }
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    private final class Listeners implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDeath(EntityDeathEvent event) {
            Session session = sessions.get(event.getEntity().getUniqueId());
            if (session != null && !session.dead) {
                beginDeath(session);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDamage(EntityDamageEvent event) {
            Session session = sessions.get(event.getEntity().getUniqueId());
            if (session == null) {
                return;
            }
            session.onHurt();
            syncBasePresentation(session);
        }

        /**
         * A mob steered by the vanilla engine gets no call from the Core's melee
         * action, so the damage it deals is the cue for its own swing: the attack
         * clip plays on the hit the engine decided to land. A Core-steered mob is
         * left out — its action already fired the clip on the swing, and a second
         * notify when the damage lands would restart the overlay mid-play.
         */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onMeleeSwing(EntityDamageByEntityEvent event) {
            if (!(event.getDamager() instanceof LivingEntity attacker)) {
                return;
            }
            Session session = sessions.get(attacker.getUniqueId());
            if (session != null && session.vanillaAi) {
                session.animator.notifyAttack();
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPackStatus(PlayerResourcePackStatusEvent event) {
            boolean ok = event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
            viewerPackOk.put(event.getPlayer().getUniqueId(), ok);
            applyPackState(event.getPlayer(), ok);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            Bukkit.getScheduler().runTask(plugin, MobRenderEngine.this::reconcileViewers);
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static final class Entry {
        final CustomMobDefinition definition;
        final MobRenderSpec spec;
        volatile ModelBundle bundle;
        volatile String status = "";
        /** Non-null while a runtime probe overrides the definition's yaw offset. */
        volatile Float yawOffsetOverride;

        Entry(CustomMobDefinition definition, MobRenderSpec spec) {
            this.definition = definition;
            this.spec = spec;
        }

        float yawOffset() {
            Float override = yawOffsetOverride;
            if (override != null) {
                return override.floatValue();
            }
            return spec == null ? 0.0f : spec.yawOffsetDegrees();
        }
    }

    private static final class BoneDisplay {
        final int boneIndex;
        final List<PoseSlot> slots = new ArrayList<>();

        BoneDisplay(int boneIndex) {
            this.boneIndex = boneIndex;
        }
    }

    private static final class AttachmentDisplay {
        final int boneIndex;
        final PoseSlot slot;

        AttachmentDisplay(int boneIndex, MobDisplayHandle handle) {
            this.boneIndex = boneIndex;
            this.slot = new PoseSlot(handle, null);
        }
    }

    /**
     * One display of a rig, plus the pose last actually pushed to it.
     *
     * <p>A resting mob samples the same animation frame every pass, so every one
     * of its displays gets the same matrix it had before — and pushing an
     * unchanged teleport is pure packet cost. The dirty check here is the single
     * change that makes a crowd of custom mobs affordable: it turns "nothing
     * moved" into "nothing sent", which is most of a standing rig.</p>
     */
    private static final class PoseSlot {
        final MobDisplayHandle handle;
        /** The piece's authored offset in model pixels; zero for a whole-bone display. */
        final Vector3f offset;
        private static final Vector3f NO_OFFSET = new Vector3f();
        private final float[] sent = new float[16];
        private double x;
        private double y;
        private double z;
        private boolean remembered;

        PoseSlot(MobDisplayHandle handle, Vector3f offset) {
            this.handle = handle;
            this.offset = offset == null ? NO_OFFSET : offset;
        }

        boolean unchanged(double px, double py, double pz, Matrix4f matrix, float[] probe) {
            if (!remembered) {
                return false;
            }
            if (Math.abs(px - x) > POSE_POSITION_EPSILON || Math.abs(py - y) > POSE_POSITION_EPSILON
                    || Math.abs(pz - z) > POSE_POSITION_EPSILON) {
                return false;
            }
            matrix.get(probe);
            for (int i = 0; i < 16; i++) {
                if (Math.abs(probe[i] - sent[i]) > POSE_MATRIX_EPSILON) {
                    return false;
                }
            }
            return true;
        }

        void remember(double px, double py, double pz, Matrix4f matrix, float[] probe) {
            remembered = true;
            x = px;
            y = py;
            z = pz;
            matrix.get(probe);
            System.arraycopy(probe, 0, sent, 0, 16);
        }
    }

    private final class Session {

        final UUID id = UUID.randomUUID();
        final LivingEntity entity;
        final Entry entry;
        final MobDisplayBackend backend;
        final MobAnimator animator;
        final List<BoneDisplay> bones = new ArrayList<>();
        final List<AttachmentDisplay> items = new ArrayList<>();
        final List<PoseSlot> boneMarkers = new ArrayList<>();
        final List<MobDisplayHandle> handleCache = new ArrayList<>();

        Vector3f[] relPivots;
        float[] restX;
        float[] restY;
        float[] restZ;
        int[] parent;
        int headBone = -1;
        Matrix4f[] local;
        Matrix4f[] absolute;

        PoseSlot genericCube;
        PoseSlot shadow;
        /** Plate width in blocks, derived from the mob's own hitbox unless authored. */
        float shadowDiameter = 1.0f;
        boolean generic;
        boolean hasRig;
        boolean dead;
        float deathTimer;

        /** True while the vanilla engine steers this mob (its goals were never cleared). */
        boolean vanillaAi;
        /** True while the rig is parked because no viewer is inside the render range. */
        boolean released;
        /** Squared distance to the nearest viewer inside the render range. */
        double nearestSq = Double.MAX_VALUE;
        /** True when every nearby viewer can present the pack, so the base may hide. */
        boolean viewersHavePack;
        /** Hurt passes left to answer a hit; {@code 0} when nothing is owed. */
        int hurtTicks;
        long lastHurtBurstPass = -(long) HURT_BURST_COOLDOWN;
        /** The invisibility last actually applied, so a steady state sends no packets. */
        boolean baseHidden;
        /** The pose pass this rig last pushed; drives the mid-range cadence. */
        long lastPosePass;

        double lastX;
        double lastY;
        double lastZ;
        float heading;
        final Location scratchLocation = new Location(null, 0, 0, 0);
        final Vector3f scratchVector = new Vector3f();
        final Vector3f pieceShift = new Vector3f();
        final Vector3f pieceOrigin = new Vector3f();
        final Matrix4f scratchMatrix = new Matrix4f();
        final Matrix4f yawMatrix = new Matrix4f();
        final Quaternionf yawQuaternion = new Quaternionf();
        final Quaternionf scratchQuaternion = new Quaternionf();
        /** Scratch destination for the matrix dirty check; never escapes the session. */
        final float[] matrixProbe = new float[16];

        Session(LivingEntity entity, Entry entry, MobDisplayBackend backend) {
            this.entity = entity;
            this.entry = entry;
            this.backend = backend;
            this.animator = createAnimator(entry);
            this.vanillaAi = entry.definition.keepsVanillaGoals();
            Location location = entity.getLocation();
            this.lastX = location.getX();
            this.lastY = location.getY();
            this.lastZ = location.getZ();
            this.heading = location.getYaw();
            // Rigs that come alive at different moments stay out of each other's
            // phase, so a mid-range crowd never pushes every mob on one pass.
            this.lastPosePass = -(long) MID_LOD_DIVISOR
                    - Math.floorMod(id.hashCode(), MID_LOD_DIVISOR);
        }

        boolean usesTransport() {
            return backend instanceof TransportMobBackend;
        }

        // ----------------------------------------------------------------
        // What the mob is worth to the server
        // ----------------------------------------------------------------

        /**
         * One pass over this pose pass's viewers for one mob: the nearest viewer
         * decides whether the rig earns any packets at all and how often it poses,
         * and whether every close viewer can present the pack decides whether the
         * base model may hide behind the rig.
         */
        void scanViewers(CustomMobSettings current) {
            double rangeSq = (double) current.renderDistance() * current.renderDistance();
            World world = entity.getWorld();
            double ex = entity.getX();
            double ey = entity.getY();
            double ez = entity.getZ();
            double nearest = Double.MAX_VALUE;
            boolean allHavePack = true;
            for (int i = 0; i < passViewers.size(); i++) {
                Player viewer = passViewers.get(i);
                if (viewer.getWorld() != world) {
                    continue;
                }
                double dx = viewer.getX() - ex;
                double dy = viewer.getY() - ey;
                double dz = viewer.getZ() - ez;
                double sq = dx * dx + dy * dy + dz * dz;
                if (sq > rangeSq) {
                    continue;
                }
                if (sq < nearest) {
                    nearest = sq;
                }
                if (!passViewerPack.get(i)) {
                    allHavePack = false;
                }
            }
            nearestSq = nearest;
            viewersHavePack = nearest != Double.MAX_VALUE && allHavePack;
        }

        /** True when the mob is far enough that a quarter-rate rig is still smooth. */
        boolean midLod() {
            double near = settings.renderNearDistance();
            return nearestSq > near * near;
        }

        /** How many display entities this rig keeps alive — the per-mob render cost. */
        int displayCount() {
            int total = items.size();
            if (genericCube != null) {
                total++;
            }
            if (shadow != null) {
                total++;
            }
            for (BoneDisplay bone : bones) {
                total += bone.slots.size();
            }
            return total;
        }

        /**
         * Whether this rig poses on this pass. A distant rig poses every
         * {@code divisor}th pass, and the push that follows is eased over the
         * whole gap, so the motion stays continuous at a fraction of the packets.
         */
        boolean dueThisPass(long pass, int divisor) {
            if (divisor <= 1 || pass - lastPosePass >= divisor) {
                lastPosePass = pass;
                return true;
            }
            return false;
        }

        /**
         * Parks the rig: every display is gone and the real entity stands in. A
         * mob nobody is near then costs one distance check and nothing else, and
         * because the mob underneath it is a genuine mob it looks exactly right.
         */
        void releaseDisplays() {
            if (released) {
                return;
            }
            released = true;
            despawnDisplays();
        }

        /** Rebuilds a parked rig once a viewer is close enough again. */
        void rebuildDisplays(CustomMobSettings current) {
            released = false;
            lastPosePass = -(long) MID_LOD_DIVISOR;
            if (build()) {
                applySessionViewers(this);
            }
        }

        /** Removes every display this session owns; the entity itself is untouched. */
        void despawnDisplays() {
            for (MobDisplayHandle handle : allHandles()) {
                backend.despawn(handle);
            }
            backend.destroySession(id);
            bones.clear();
            items.clear();
            boneMarkers.clear();
            genericCube = null;
            shadow = null;
            generic = false;
            hasRig = false;
        }

        // ----------------------------------------------------------------
        // Base presentation
        // ----------------------------------------------------------------

        /**
         * Makes the base model transparent — never absent. The entity stays
         * tracked, so its hitbox stays aimable and pushable, its sounds play and
         * its damage and death states stay real; that is the difference between a
         * mob and a decoration, and the reason a rigged mob used to be untouchable.
         */
        void hideBase(boolean hidden) {
            if (baseHidden == hidden) {
                return;
            }
            try {
                entity.setInvisible(hidden);
                baseHidden = hidden;
            } catch (Throwable refused) {
                baseHidden = false;
            }
        }

        void revealBase() {
            hideBase(false);
        }

        /** True when this mob answers a hit by briefly showing its real model. */
        boolean hurtReveals() {
            MobRenderSpec spec = entry.spec;
            return spec != null && spec.hurtFeedback() == MobRenderSpec.HurtFeedback.FLASH;
        }

        /** The mob was hurt: the clip plays and the feedback pass gets armed. */
        void onHurt() {
            animator.notifyHurt();
            hurtTicks = HURT_REVEAL_TICKS;
        }

        List<MobDisplayHandle> allHandles() {
            handleCache.clear();
            for (BoneDisplay display : bones) {
                for (PoseSlot slot : display.slots) {
                    handleCache.add(slot.handle);
                }
            }
            for (AttachmentDisplay item : items) {
                handleCache.add(item.slot.handle);
            }
            for (PoseSlot marker : boneMarkers) {
                handleCache.add(marker.handle);
            }
            if (genericCube != null) {
                handleCache.add(genericCube.handle);
            }
            if (shadow != null) {
                handleCache.add(shadow.handle);
            }
            return handleCache;
        }

        private MobAnimator createAnimator(Entry entry) {
            Map<String, ModelAnimation> animations = Map.of();
            ModelBundle bundle = entry.bundle;
            if (bundle != null && bundle.model() != null) {
                animations = bundle.model().animations();
            }
            List<String> legs = new ArrayList<>();
            List<String> arms = new ArrayList<>();
            if (bundle != null && bundle.model() != null) {
                for (ModelBone bone : bundle.model().bones()) {
                    String name = bone.name();
                    if (name.contains("leg")) {
                        legs.add(name);
                    } else if (name.contains("arm")) {
                        arms.add(name);
                    }
                }
            }
            Map<String, String> aliases = entry.spec != null
                    ? entry.spec.animationMap() : Map.of();
            return new MobAnimator(animations, aliases, legs, arms);
        }

        /** Spawns the rig displays; {@code true} when a presentation exists. */
        boolean build() {
            CustomMobSettings current = settings();
            World world = entity.getWorld();
            int inWorld = 0;
            for (Session other : sessions.values()) {
                if (other != this && other.entity.getWorld() == world) {
                    inWorld++;
                }
            }
            if (inWorld >= current.maxRiggedPerWorld()) {
                hasRig = false;
                entry.status = "rig skipped: the per-world rig cap (" + current.maxRiggedPerWorld()
                        + ") is reached";
                DebugLogger.warning("[CustomMobs] " + entry.status + " — '" + entry.definition.id()
                        + "' renders as its base entity.");
                return false;
            }

            ModelBundle bundle = entry.bundle;
            if (bundle == null || !bundle.presentable()) {
                return buildGenericFallback(bundle);
            }
            PackApi packApi = pack;
            if (packApi == null || !packApi.isAvailable() || !packApi.supportsGeneratedModelDisplay()) {
                return buildGenericFallback(bundle);
            }

            int interpolation = interpolationWindow(current);
            int teleport = interpolationWindow(current);
            for (int boneIndex = 0; boneIndex < bundle.model().boneCount(); boneIndex++) {
                List<PackModelEmitter.Piece> pieces = bundle.pieces(boneIndex);
                if (pieces.isEmpty()) {
                    continue;
                }
                BoneDisplay display = new BoneDisplay(boneIndex);
                for (PackModelEmitter.Piece piece : pieces) {
                    ItemStack stack = packApi.createModelDisplay(Material.PAPER,
                            bundle.namespace(), piece.componentPath());
                    if (stack == null) {
                        entry.status = "rig skipped: the pack cannot present '" + piece.componentPath()
                                + "' (recompile the pack)";
                        DebugLogger.warning("[CustomMobs] " + entry.status);
                        rollback();
                        return buildGenericFallback(bundle);
                    }
                    MobDisplayHandle handle = backend.spawn(id, entity.getLocation(), stack,
                            ItemDisplay.ItemDisplayTransform.NONE, interpolation, teleport);
                    if (handle == null) {
                        entry.status = "rig skipped: the display could not spawn";
                        DebugLogger.warning("[CustomMobs] " + entry.status);
                        rollback();
                        return buildGenericFallback(bundle);
                    }
                    display.slots.add(new PoseSlot(handle, new Vector3f(piece.offsetPx())));
                }
                bones.add(display);
            }
            spawnHandItems(bundle, interpolation, teleport);
            if (bones.isEmpty() && items.isEmpty()) {
                return buildGenericFallback(bundle);
            }
            prepareTransforms(bundle);
            hasRig = true;
            generic = false;
            spawnShadow(current);
            backend.finishSession(id, entity.getLocation());
            return true;
        }

        private void spawnHandItems(ModelBundle bundle, int interpolation, int teleport) {
            if (entry.spec == null || entry.spec.handItems().isEmpty() || bundle.model() == null) {
                return;
            }
            PackApi packApi = pack;
            for (Map.Entry<String, MobRenderSpec.HandItem> item
                    : entry.spec.handItems().entrySet()) {
                int boneIndex = bundle.model().boneIndex(item.getKey());
                if (boneIndex < 0) {
                    DebugLogger.warning("[CustomMobs] '" + entry.definition.id()
                            + "': hand-item bone '" + item.getKey() + "' is not in the model; skipped.");
                    continue;
                }
                ItemStack stack = handStack(item.getValue());
                if (stack == null) {
                    DebugLogger.warning("[CustomMobs] '" + entry.definition.id()
                            + "': hand item '" + item.getValue().packItemId() + "' cannot resolve; skipped.");
                    continue;
                }
                MobDisplayHandle handle = backend.spawn(id, entity.getLocation(), stack,
                        item.getValue().transform(), interpolation, teleport);
                if (handle != null) {
                    items.add(new AttachmentDisplay(boneIndex, handle));
                }
            }
        }

        private ItemStack handStack(MobRenderSpec.HandItem item) {
            if (item.material() != null) {
                return new ItemStack(item.material());
            }
            String id = item.packItemId();
            if (id == null) {
                return null;
            }
            int colon = id.indexOf(':');
            if (colon > 0 && pack != null) {
                return pack.createItem(id.substring(0, colon), id.substring(colon + 1));
            }
            Material material = Material.matchMaterial(id);
            return material == null ? null : new ItemStack(material);
        }

        private boolean buildGenericFallback(ModelBundle bundle) {
            if (entry.spec != null && entry.spec.fallback() != MobRenderSpec.FallbackMode.GENERIC_CUBE) {
                entry.status = bundle == null ? "no bundle; base entity stays visible"
                        : "base entity stays visible: " + bundle.report().summary();
                return false;
            }
            if (pack == null || !pack.isAvailable() || !pack.supportsGeneratedModelDisplay()) {
                entry.status = "generic fallback needs a working pack; base entity stays visible";
                return false;
            }
            ItemStack stack = pack.createModelDisplay(Material.PAPER,
                    GenericFallbackAssets.NAMESPACE, GenericFallbackAssets.COMPONENT_PATH);
            if (stack == null) {
                entry.status = "the generic fallback cube is not registered; base entity stays visible";
                return false;
            }
            CustomMobSettings current = settings();
            int interpolation = interpolationWindow(current);
            MobDisplayHandle cube = backend.spawn(id, entity.getLocation(), stack,
                    ItemDisplay.ItemDisplayTransform.NONE, interpolation, interpolation);
            if (cube == null) {
                entry.status = "the generic fallback cube could not spawn; base entity stays visible";
                return false;
            }
            genericCube = new PoseSlot(cube, null);
            entry.status = "rendering the neutral fallback cube: "
                    + (bundle == null ? "no bundle" : bundle.report().summary());
            generic = true;
            hasRig = true;
            spawnShadow(current);
            backend.finishSession(id, entity.getLocation());
            return true;
        }

        /**
         * The soft plate under the mob. A rig replaces the base model on screen,
         * and the base model is what draws the vanilla shadow, so without a plate
         * of its own a custom mob looks like it is floating. The size follows the
         * mob's own hitbox unless {@code render.shadow} pins it.
         */
        private void spawnShadow(CustomMobSettings current) {
            if (shadow != null || !current.shadows() || pack == null || !pack.isAvailable()
                    || !pack.supportsGeneratedModelDisplay()) {
                return;
            }
            Float authored = entry.spec != null ? entry.spec.shadowRadius() : null;
            if (authored != null && authored <= 0.0f) {
                return;
            }
            ItemStack stack = pack.createModelDisplay(Material.PAPER, MobShadowAssets.NAMESPACE,
                    MobShadowAssets.COMPONENT_PATH);
            if (stack == null) {
                return;
            }
            int interpolation = interpolationWindow(current);
            MobDisplayHandle handle = backend.spawn(id, entity.getLocation(), stack,
                    ItemDisplay.ItemDisplayTransform.NONE, interpolation, interpolation);
            if (handle == null) {
                return;
            }
            shadow = new PoseSlot(handle, null);
            float radius = authored != null ? authored
                    : Math.max(MobShadowAssets.BASE_DIAMETER_BLOCKS * 0.4f,
                    (float) entity.getWidth() * 0.6f);
            shadowDiameter = Math.max(MobShadowAssets.BASE_DIAMETER_BLOCKS * 0.25f, radius * 2.0f);
        }

        private void rollback() {
            for (MobDisplayHandle handle : allHandles()) {
                backend.despawn(handle);
            }
            bones.clear();
            items.clear();
            boneMarkers.clear();
            genericCube = null;
            shadow = null;
            generic = false;
            backend.destroySession(id);
        }

        /** Keeps the debug bone markers in step with the engine's debug flag. */
        void syncBoneMarkers(ModelData model) {
            if (boneDebug && boneMarkers.size() < model.boneCount()) {
                spawnBoneMarkers(model);
            } else if (!boneDebug && !boneMarkers.isEmpty()) {
                for (PoseSlot marker : boneMarkers) {
                    backend.despawn(marker.handle);
                }
                boneMarkers.clear();
            }
        }

        private void spawnBoneMarkers(ModelData model) {
            PackApi packApi = pack;
            if (packApi == null || !packApi.isAvailable() || !packApi.supportsGeneratedModelDisplay()) {
                return;
            }
            ItemStack stack = packApi.createModelDisplay(Material.PAPER,
                    GenericFallbackAssets.NAMESPACE, GenericFallbackAssets.COMPONENT_PATH);
            if (stack == null) {
                return;
            }
            int interpolation = interpolationWindow(settings);
            for (int i = boneMarkers.size(); i < model.boneCount(); i++) {
                MobDisplayHandle marker = backend.spawn(id, entity.getLocation(), stack,
                        ItemDisplay.ItemDisplayTransform.NONE, interpolation, interpolation);
                if (marker != null) {
                    boneMarkers.add(new PoseSlot(marker, null));
                }
            }
        }

        private void prepareTransforms(ModelBundle bundle) {
            ModelData model = bundle.model();
            int count = model.boneCount();
            restX = new float[count];
            restY = new float[count];
            restZ = new float[count];
            parent = new int[count];
            relPivots = new Vector3f[count];
            local = new Matrix4f[count];
            absolute = new Matrix4f[count];
            for (int i = 0; i < count; i++) {
                ModelBone bone = model.bone(i);
                restX[i] = bone.restRotationX();
                restY[i] = bone.restRotationY();
                restZ[i] = bone.restRotationZ();
                parent[i] = bone.parentIndex();
                int parentIndex = bone.parentIndex();
                float baseX = parentIndex >= 0 ? model.bone(parentIndex).pivotX() : 0.0f;
                float baseY = parentIndex >= 0 ? model.bone(parentIndex).pivotY() : 0.0f;
                float baseZ = parentIndex >= 0 ? model.bone(parentIndex).pivotZ() : 0.0f;
                relPivots[i] = new Vector3f(bone.pivotX() - baseX, bone.pivotY() - baseY,
                        bone.pivotZ() - baseZ);
                local[i] = new Matrix4f();
                absolute[i] = new Matrix4f();
                if (bone.attachment() == ModelBone.Attachment.HEAD) {
                    headBone = i;
                }
            }
        }
    }
}
