package com.mineplus.infrastructure.mob.custom;

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
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
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
    private static final float BONE_MARKER_SCALE = 0.06f;

    private final Plugin plugin;
    private final PackApi pack;
    private volatile CustomMobSettings settings;
    private volatile DisplayTransport transport;
    private volatile TransportMobBackend transportBackend;
    private final EntityMobBackend entityBackend;

    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Boolean> viewerPackOk = new HashMap<>();
    private final Set<String> loggedFailures = new HashSet<>();

    private BukkitTask tickTask;
    private BukkitTask reconcileTask;
    private volatile static MobRenderEngine active;
    private volatile boolean boneDebug;

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

    /** Applies new subsystem settings; disabled rendering tears the rigs down. */
    public void updateSettings(CustomMobSettings next) {
        if (next != null) {
            this.settings = next;
        }
        if (!settings().enabled() || !settings().renderingEnabled()) {
            for (Session session : List.copyOf(sessions.values())) {
                destroySession(session);
                sessions.remove(session.entity.getUniqueId());
            }
        }
    }

    public CustomMobSettings settings() {
        return settings;
    }

    /** Starts the tick loop, the listeners and the ghost sweep. */
    public void start() {
        Bukkit.getPluginManager().registerEvents(new Listeners(), plugin);
        GenericFallbackAssets.register(pack);
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
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
        if (pack != null && pack.isAvailable()) {
            pack.registerGeneratedAsset(definition.namespace(),
                    "textures/entity/" + definition.path() + ".png", png);
        }
        String registry = VariantDatapackWriter.registryFor(definition.baseType());
        if (registry == null) {
            entry.status = "no variant registry for " + definition.baseType()
                    + "; the texture ships for servers that support one";
            logOnce(definition.id(), "variant", false,
                    "[CustomMobs] '" + definition.id() + "': " + entry.status);
            return;
        }
        if (!ServerVersion.dataDrivenVariants()) {
            entry.status = "texture shipped; data-driven variants need 1.21.5+ (server is "
                    + ServerVersion.rawVersion() + ")";
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
        if (entry == null || !ServerVersion.displayEntities()) {
            return;
        }
        Session existing = sessions.remove(entity.getUniqueId());
        if (existing != null) {
            destroySession(existing);
        }
        if (!settings().enabled() || !settings().renderingEnabled()) {
            return;
        }
        Session session = new Session(entity, entry, backend());
        if (session.build()) {
            sessions.put(entity.getUniqueId(), session);
        }
        applySessionViewers(session);
    }

    /** The mob is gone or the definition unregistered: remove its rig. */
    public void onRemoved(LivingEntity entity) {
        Session session = sessions.remove(entity.getUniqueId());
        if (session != null) {
            destroySession(session);
        }
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
        for (MobDisplayHandle handle : session.allHandles()) {
            session.backend.despawn(handle);
        }
        session.backend.destroySession(session.id);
        LivingEntity entity = session.entity;
        if (entity.isValid() && session.entry.definition.hideBaseWhenRigged()) {
            for (Player viewer : entity.getWorld().getPlayers()) {
                viewer.showEntity(plugin, entity);
            }
        }
    }

    // ------------------------------------------------------------------
    // Tick pass
    // ------------------------------------------------------------------

    private void tick() {
        if (sessions.isEmpty()) {
            return;
        }
        CustomMobSettings current = settings;
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
                session.dead = true;
                session.animator.markDead();
                session.deathTimer = session.animator.deathClipLength() + DEATH_GRACE_SECONDS;
            }
            if (session.dead) {
                session.deathTimer -= TICK_SECONDS;
                if (session.deathTimer <= 0.0f || !session.animator.hasDeathClip()) {
                    destroySession(session);
                    iterator.remove();
                    continue;
                }
            }
            if (!session.dead && !viewerInRange(entity, current.renderDistance())) {
                continue; // nobody close enough to see pose updates; freeze
            }
            animate(session, current);
        }
    }

    private boolean viewerInRange(LivingEntity entity, double range) {
        double rangeSq = range * range;
        Location center = entity.getLocation();
        for (Player player : entity.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= rangeSq) {
                return true;
            }
        }
        return false;
    }

    private void animate(Session session, CustomMobSettings current) {
        LivingEntity entity = session.entity;
        Location location = entity.getLocation();
        double dx = location.getX() - session.lastX;
        double dy = location.getY() - session.lastY;
        double dz = location.getZ() - session.lastZ;
        session.lastX = location.getX();
        session.lastY = location.getY();
        session.lastZ = location.getZ();
        float speed = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        session.animator.tick(TICK_SECONDS, speed);
        if (!session.hasRig) {
            return;
        }
        if (session.generic) {
            poseGenericCube(session, location);
            return;
        }

        float bodyYaw = location.getYaw();
        BoneTransformMath.yawFrame(bodyYaw, session.yawQuaternion);
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
            // helper and falls back to the body yaw when the runtime hides it.
            headYaw = BoneTransformMath.headYawModel(MobAiIsolation.headYaw(entity, bodyYaw), bodyYaw);
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
            for (int p = 0; p < display.handles.size(); p++) {
                MobDisplayHandle handle = display.handles.get(p);
                Vector3f offset = display.pieceOffsets.get(p);
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
                session.scratchLocation.setWorld(world);
                session.scratchLocation.set(px, py, pz);
                session.scratchLocation.setYaw(0.0f);
                session.scratchLocation.setPitch(0.0f);
                session.backend.pose(handle, session.scratchLocation, session.scratchMatrix,
                        current.renderInterpolationTicks());
            }
        }
        for (int i = 0; i < session.items.size(); i++) {
            AttachmentDisplay item = session.items.get(i);
            Matrix4f absolute = session.absolute[item.boneIndex];
            session.scratchVector.set(0.0f, 0.0f, 0.0f);
            absolute.transformPosition(session.scratchVector);
            session.scratchMatrix.set(absolute);
            session.scratchMatrix.setTranslation(0.0f, 0.0f, 0.0f);
            session.scratchLocation.setWorld(world);
            session.scratchLocation.set(location.getX() + session.scratchVector.x,
                    location.getY() + session.scratchVector.y,
                    location.getZ() + session.scratchVector.z);
            session.scratchLocation.setYaw(0.0f);
            session.scratchLocation.setPitch(0.0f);
            session.backend.pose(item.handle, session.scratchLocation, session.scratchMatrix,
                    current.renderInterpolationTicks());
        }
        poseBoneMarkers(session, model, location, current);
        if (session.usesTransport()) {
            DisplayTransport currentTransport = transport;
            if (currentTransport != null) {
                currentTransport.flushDeltas(session.id);
            }
        }
    }

    /** Positions the debug markers at the bone pivots (reuses the tick's matrices). */
    private void poseBoneMarkers(Session session, ModelData model, Location location,
                                 CustomMobSettings current) {
        session.syncBoneMarkers(model, current);
        if (session.boneMarkers.isEmpty()) {
            return;
        }
        World world = location.getWorld();
        for (int i = 0; i < session.boneMarkers.size() && i < model.boneCount(); i++) {
            MobDisplayHandle marker = session.boneMarkers.get(i);
            Matrix4f absolute = session.absolute[i];
            session.scratchVector.set(0.0f, 0.0f, 0.0f);
            absolute.transformPosition(session.scratchVector);
            session.scratchMatrix.set(absolute);
            session.scratchMatrix.setTranslation(0.0f, 0.0f, 0.0f);
            session.scratchMatrix.scale(BONE_MARKER_SCALE);
            session.scratchLocation.setWorld(world);
            session.scratchLocation.set(location.getX() + session.scratchVector.x,
                    location.getY() + session.scratchVector.y,
                    location.getZ() + session.scratchVector.z);
            session.scratchLocation.setYaw(0.0f);
            session.scratchLocation.setPitch(0.0f);
            session.backend.pose(marker, session.scratchLocation, session.scratchMatrix,
                    current.renderInterpolationTicks());
        }
    }

    private void poseGenericCube(Session session, Location location) {
        if (session.genericCube == null) {
            return;
        }
        float yaw = location.getYaw();
        float height = (float) entityHeight(session.entity);
        session.scratchMatrix.identity();
        session.scratchMatrix.rotateY((180.0f - yaw) * (float) (Math.PI / 180.0));
        session.scratchMatrix.scale(height);
        session.scratchLocation.setWorld(location.getWorld());
        session.scratchLocation.set(location.getX(),
                location.getY() + height * 0.5f,
                location.getZ());
        session.scratchLocation.setYaw(0.0f);
        session.scratchLocation.setPitch(0.0f);
        session.backend.pose(session.genericCube, session.scratchLocation, session.scratchMatrix,
                settings().renderInterpolationTicks());
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
        for (Session session : sessions.values()) {
            applyBaseVisibility(session, player, canSeePack);
        }
    }

    private void applySessionViewers(Session session) {
        for (Player player : session.entity.getWorld().getPlayers()) {
            boolean ok = Boolean.TRUE.equals(viewerPackOk.get(player.getUniqueId()));
            session.backend.setSessionViewerHidden(session.id, session.allHandles(),
                    player.getUniqueId(), !ok);
            applyBaseVisibility(session, player, ok);
        }
    }

    private void applyBaseVisibility(Session session, Player player, boolean canSeePack) {
        if (!session.hasRig || !session.entry.definition.hideBaseWhenRigged()) {
            return;
        }
        LivingEntity entity = session.entity;
        if (!entity.isValid()) {
            return;
        }
        if (canSeePack) {
            player.hideEntity(plugin, entity);
        } else {
            player.showEntity(plugin, entity);
        }
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

    /** The bound clip per animation state, for the inspect command. */
    public Map<String, String> animatorBinding(LivingEntity entity) {
        Session session = entity == null ? null : sessions.get(entity.getUniqueId());
        return session == null ? Map.of() : session.animator.binding();
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
                session.dead = true;
                session.animator.markDead();
                session.deathTimer = session.animator.deathClipLength() + DEATH_GRACE_SECONDS;
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDamage(EntityDamageEvent event) {
            Session session = sessions.get(event.getEntity().getUniqueId());
            if (session != null) {
                session.animator.notifyHurt();
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

        Entry(CustomMobDefinition definition, MobRenderSpec spec) {
            this.definition = definition;
            this.spec = spec;
        }
    }

    private static final class BoneDisplay {
        final int boneIndex;
        final List<MobDisplayHandle> handles = new ArrayList<>();
        final List<Vector3f> pieceOffsets = new ArrayList<>();

        BoneDisplay(int boneIndex) {
            this.boneIndex = boneIndex;
        }
    }

    private static final class AttachmentDisplay {
        final int boneIndex;
        final MobDisplayHandle handle;

        AttachmentDisplay(int boneIndex, MobDisplayHandle handle) {
            this.boneIndex = boneIndex;
            this.handle = handle;
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
        final List<MobDisplayHandle> boneMarkers = new ArrayList<>();
        final List<MobDisplayHandle> handleCache = new ArrayList<>();

        Vector3f[] relPivots;
        float[] restX;
        float[] restY;
        float[] restZ;
        int[] parent;
        int headBone = -1;
        Matrix4f[] local;
        Matrix4f[] absolute;

        MobDisplayHandle genericCube;
        boolean generic;
        boolean hasRig;
        boolean dead;
        float deathTimer;

        double lastX;
        double lastY;
        double lastZ;
        final Location scratchLocation = new Location(null, 0, 0, 0);
        final Vector3f scratchVector = new Vector3f();
        final Vector3f pieceShift = new Vector3f();
        final Vector3f pieceOrigin = new Vector3f();
        final Matrix4f scratchMatrix = new Matrix4f();
        final Matrix4f yawMatrix = new Matrix4f();
        final Quaternionf yawQuaternion = new Quaternionf();
        final Quaternionf scratchQuaternion = new Quaternionf();

        Session(LivingEntity entity, Entry entry, MobDisplayBackend backend) {
            this.entity = entity;
            this.entry = entry;
            this.backend = backend;
            this.animator = createAnimator(entry);
            Location location = entity.getLocation();
            this.lastX = location.getX();
            this.lastY = location.getY();
            this.lastZ = location.getZ();
        }

        boolean usesTransport() {
            return backend instanceof TransportMobBackend;
        }

        List<MobDisplayHandle> allHandles() {
            handleCache.clear();
            for (BoneDisplay display : bones) {
                handleCache.addAll(display.handles);
            }
            for (AttachmentDisplay item : items) {
                handleCache.add(item.handle);
            }
            handleCache.addAll(boneMarkers);
            if (genericCube != null) {
                handleCache.add(genericCube);
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

            int interpolation = Math.max(1, current.renderInterpolationTicks());
            int teleport = Math.max(1, current.renderInterpolationTicks());
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
                    display.handles.add(handle);
                    display.pieceOffsets.add(new Vector3f(piece.offsetPx()));
                }
                bones.add(display);
            }
            spawnHandItems(bundle, interpolation, teleport);
            if (bones.isEmpty() && items.isEmpty()) {
                return buildGenericFallback(bundle);
            }
            prepareTransforms(bundle);
            hasRig = true;
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
            genericCube = backend.spawn(id, entity.getLocation(), stack,
                    ItemDisplay.ItemDisplayTransform.NONE,
                    Math.max(1, current.renderInterpolationTicks()),
                    Math.max(1, current.renderInterpolationTicks()));
            if (genericCube == null) {
                entry.status = "the generic fallback cube could not spawn; base entity stays visible";
                return false;
            }
            entry.status = "rendering the neutral fallback cube: "
                    + (bundle == null ? "no bundle" : bundle.report().summary());
            generic = true;
            hasRig = true;
            backend.finishSession(id, entity.getLocation());
            return true;
        }

        private void rollback() {
            for (MobDisplayHandle handle : allHandles()) {
                backend.despawn(handle);
            }
            bones.clear();
            items.clear();
            boneMarkers.clear();
            genericCube = null;
            backend.destroySession(id);
        }

        /** Keeps the debug bone markers in step with the engine's debug flag. */
        void syncBoneMarkers(ModelData model, CustomMobSettings current) {
            if (boneDebug && boneMarkers.isEmpty()) {
                spawnBoneMarkers(model, current);
            } else if (!boneDebug && !boneMarkers.isEmpty()) {
                for (MobDisplayHandle marker : boneMarkers) {
                    backend.despawn(marker);
                }
                boneMarkers.clear();
            }
        }

        private void spawnBoneMarkers(ModelData model, CustomMobSettings current) {
            PackApi packApi = pack;
            if (packApi == null || !packApi.isAvailable() || !packApi.supportsGeneratedModelDisplay()) {
                return;
            }
            ItemStack stack = packApi.createModelDisplay(Material.PAPER,
                    GenericFallbackAssets.NAMESPACE, GenericFallbackAssets.COMPONENT_PATH);
            if (stack == null) {
                return;
            }
            int interpolation = Math.max(1, current.renderInterpolationTicks());
            for (int i = 0; i < model.boneCount(); i++) {
                MobDisplayHandle marker = backend.spawn(id, entity.getLocation(), stack,
                        ItemDisplay.ItemDisplayTransform.NONE, interpolation, interpolation);
                if (marker != null) {
                    boneMarkers.add(marker);
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
