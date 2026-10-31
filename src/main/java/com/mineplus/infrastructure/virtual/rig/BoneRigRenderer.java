package com.mineplus.infrastructure.virtual.rig;

import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.display.DisplayTransport;
import com.mineplus.infrastructure.virtual.display.pool.PooledDisplay;
import com.mineplus.pack.PackApi;
import com.mineplus.util.DebugLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The generic bone rig runtime: one {@code ItemDisplay} per bone, posed
 * hierarchically from a shared {@link BoneRigBlueprint}.
 *
 * <p>This is the single display engine behind both pack bone rigs
 * ({@code renderKind: "bones"} multiblock levels) and custom-mob rigs. A model
 * with sixty cubes and twelve outliner bones renders as twelve displays, and
 * each bone's children inherit its rotation because every pose pass composes
 * {@code absolute[i] = absolute[parent] * local[i]} in one forward sweep.</p>
 *
 * <p>Displays are spawned as plain entities — exactly the pack axis's existing
 * display model — unless a running {@link DisplayTransport} is supplied, in
 * which case the rig streams through the transport's pooled item displays
 * (acquired from {@code EntityPoolManager}) and returns them to the pool on
 * detach. Either way the renderer is main-thread only.</p>
 *
 * <p>No pack capability means no rig: {@link #attach} fails and the caller
 * keeps its current rendering (a custom mob's base entity, a multiblock's
 * virtual fallback). The renderer never renders nothing silently.</p>
 */
public final class BoneRigRenderer implements Listener {

    /** Ghost-cleanup tag used by the display entities this renderer spawns. */
    public static final String DEFAULT_TAG_PREFIX = "mineplus_bonerig:";

    private static final Vector3f UNIT_SCALE = new Vector3f(1.0f, 1.0f, 1.0f);

    private final Plugin plugin;
    private final PackApi pack;
    private final String tagPrefix;
    private volatile DisplayTransport transport;
    private volatile RigSettings settings;
    private final Map<String, BoneRigBlueprint> blueprints = new ConcurrentHashMap<>();
    private final Map<UUID, RigInstance> rigs = new ConcurrentHashMap<>();

    public BoneRigRenderer(Plugin plugin, PackApi pack, DisplayTransport transport, RigSettings settings) {
        this(plugin, pack, transport, settings, DEFAULT_TAG_PREFIX);
    }

    /**
     * @param tagPrefix ghost-cleanup tag prefix; must be unique per owner so
     *                  the mob rig sweep and the pack rig sweep never touch
     *                  each other's displays
     */
    public BoneRigRenderer(
            Plugin plugin,
            PackApi pack,
            DisplayTransport transport,
            RigSettings settings,
            String tagPrefix
    ) {
        this.plugin = plugin;
        this.pack = pack;
        this.transport = transport;
        this.settings = settings == null ? RigSettings.defaults() : settings;
        this.tagPrefix = tagPrefix == null || tagPrefix.isBlank() ? DEFAULT_TAG_PREFIX : tagPrefix;
    }

    public RigSettings settings() {
        return settings;
    }

    /** Applies new limits (reload path); existing rigs keep their displays. */
    public void updateSettings(RigSettings next) {
        if (next != null) {
            this.settings = next;
        }
    }

    /** Late transport injection (the display transport starts before the pack). */
    public void setTransport(DisplayTransport transport) {
        this.transport = transport;
    }

    /** Registers (or replaces) the blueprint a model key renders through. */
    public void registerBlueprint(BoneRigBlueprint blueprint) {
        if (blueprint != null && blueprint.modelKey() != null) {
            blueprints.put(blueprint.modelKey(), blueprint);
        }
    }

    public BoneRigBlueprint blueprint(String modelKey) {
        return modelKey == null ? null : blueprints.get(modelKey);
    }

    /** True when a registered rig can render this model key. */
    public boolean hasRig(String modelKey) {
        return blueprint(modelKey) != null;
    }

    public int rigCount() {
        return rigs.size();
    }

    public boolean isAttached(UUID rigId) {
        return rigId != null && rigs.containsKey(rigId);
    }

    /**
     * Anchor resolution for a rig whose per-bone models were compiled by
     * {@link BoneRigAssetWriter}: the writer already bakes the anchor
     * convention into the geometry ({@code -8} px on x/z for a centered
     * model), so the display origin adds the block-center offset only and
     * applies no second pixel shift.
     */
    public record Anchor(Location base, Vector3f rotationPivot, float pixelShift) {

        public static Anchor forMode(ModelMeta.OriginMode mode, Location blockAnchor) {
            if (mode == ModelMeta.OriginMode.GRID) {
                return new Anchor(blockAnchor.clone(), new Vector3f(0.5f, 0.5f, 0.5f), 0.0f);
            }
            return new Anchor(blockAnchor.clone().add(0.5, 0.0, 0.5), new Vector3f(), 0.0f);
        }
    }

    /**
     * Attaches a rig by registered model key.
     *
     * @see #attach(UUID, BoneRigBlueprint, Location, Quaternionf, float, Vector3f, int, float)
     */
    public boolean attach(
            UUID rigId,
            String modelKey,
            Location base,
            Quaternionf globalRotation,
            float pixelShift,
            Vector3f rotationPivot,
            int interpolationTicks,
            float viewRange
    ) {
        return attach(rigId, blueprint(modelKey), base, globalRotation, pixelShift,
                rotationPivot, interpolationTicks, viewRange);
    }

    /**
     * Spawns every display bone of one rig.
     *
     * @param rigId             identity the caller removes the rig by
     * @param blueprint         the shared rig blueprint
     * @param base              world position of the rig's model origin
     * @param globalRotation    placement rotation applied to the whole rig
     * @param pixelShift        anchor shift in model pixels ({@code -8} for a centered model)
     * @param rotationPivot     model-space point the placement rotation orbits
     * @param interpolationTicks client-side pose interpolation window
     * @param viewRange         display view range (1.0 keeps the whole rig visible together)
     * @return {@code true} when every display bone spawned
     */
    public boolean attach(
            UUID rigId,
            BoneRigBlueprint blueprint,
            Location base,
            Quaternionf globalRotation,
            float pixelShift,
            Vector3f rotationPivot,
            int interpolationTicks,
            float viewRange
    ) {
        return attach(rigId, blueprint, base, globalRotation, pixelShift, rotationPivot,
                interpolationTicks, viewRange, interpolationTicks);
    }

    /**
     * The same attach with the two client windows stated apart.
     *
     * <p>A display interpolates its <em>transformation</em> over
     * {@code interpolationTicks} and smooths a position <em>teleport</em> over
     * {@code teleportTicks}. A rig whose pose is pushed every server tick wants
     * both equal to that interval: give the window a different length than the
     * push cadence and the client is either still easing toward the previous
     * pose when the next one lands (visible lag) or snaps between poses
     * (visible stutter).</p>
     */
    public boolean attach(
            UUID rigId,
            BoneRigBlueprint blueprint,
            Location base,
            Quaternionf globalRotation,
            float pixelShift,
            Vector3f rotationPivot,
            int interpolationTicks,
            float viewRange,
            int teleportTicks
    ) {
        if (rigId == null || blueprint == null || base == null || base.getWorld() == null) {
            return false;
        }
        RigSettings current = settings;
        if (!current.enabled() || blueprint.namespace() == null || blueprint.boneCount() == 0) {
            return false;
        }
        int displayBones = blueprint.displaySlotCount();
        if (displayBones == 0) {
            return false;
        }
        if (displayBones > current.maxDisplaysPerInstance()) {
            DebugLogger.warning("[BoneRig] Model '" + blueprint.modelKey() + "' declares " + displayBones
                    + " display bones; ceiling is " + current.maxDisplaysPerInstance() + " — rig not attached.");
            return false;
        }

        remove(rigId);
        World world = base.getWorld();
        int ticks = Math.max(1, interpolationTicks);
        int teleport = Math.max(1, teleportTicks);
        DisplayTransport activeTransport = transport;
        boolean pooled = activeTransport != null && activeTransport.isRunning();
        RigInstance instance = new RigInstance(rigId, blueprint, base, world, globalRotation,
                pixelShift, rotationPivot, ticks, pooled ? activeTransport : null);
        List<DisplayHandle> spawned = new ArrayList<>(displayBones);
        for (int bone = 0; bone < blueprint.boneCount(); bone++) {
            int pieces = blueprint.pieceCount(bone);
            for (int piece = 0; piece < pieces; piece++) {
                String geometryPath = blueprint.piecePath(bone, piece);
                ItemStack stack = pack == null ? null
                        : pack.createModelDisplay(Material.PAPER, blueprint.namespace(), geometryPath);
                if (stack == null) {
                    // The pack cannot present this piece; a partial rig would
                    // show a model with pieces missing, so attach nothing at all.
                    release(spawned, pooled ? activeTransport : null, rigId);
                    return false;
                }
                DisplayHandle handle = pooled
                        ? spawnPooled(activeTransport, instance, bone, piece, stack, base, ticks, false)
                        : spawnPlain(instance, bone, piece, stack, base, ticks, teleport, viewRange, false);
                if (handle == null) {
                    release(spawned, pooled ? activeTransport : null, rigId);
                    return false;
                }
                spawned.add(handle);
            }
            boolean hitbox = blueprint.tag(bone) != null
                    && blueprint.tag(bone).equals(BoneRigDefinition.TAG_HITBOX);
            if (hitbox && current.hitboxBones()) {
                DisplayHandle handle = pooled
                        ? spawnPooled(activeTransport, instance, bone, -1, null, base, ticks, true)
                        : spawnPlain(instance, bone, -1, null, base, ticks, teleport, viewRange, true);
                if (handle == null) {
                    release(spawned, pooled ? activeTransport : null, rigId);
                    return false;
                }
                spawned.add(handle);
            }
        }

        instance.displays = spawned.toArray(new DisplayHandle[0]);
        rigs.put(rigId, instance);
        if (pooled) {
            activeTransport.finishInstance(rigId, base, true);
        }
        pose(rigId, base);
        return true;
    }

    private DisplayHandle spawnPlain(
            RigInstance instance,
            int bone,
            int piece,
            ItemStack stack,
            Location base,
            int ticks,
            int teleportTicks,
            float viewRange,
            boolean hitbox
    ) {
        World world = base.getWorld();
        ItemDisplay display = world.spawn(base.clone(), ItemDisplay.class, spawned -> {
            if (stack != null) {
                spawned.setItemStack(stack);
            }
            spawned.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            spawned.setPersistent(false);
            spawned.setInvulnerable(true);
            spawned.setSilent(true);
            spawned.setShadowRadius(0f);
            spawned.setViewRange(Math.max(0.1f, Math.min(1.0f, viewRange)));
            // Two separate windows: the pose push cadence smooths the position,
            // the interpolation setting owns the transformation easing.
            spawned.setTeleportDuration(Math.max(1, teleportTicks));
            spawned.setInterpolationDuration(Math.max(1, ticks));
            spawned.addScoreboardTag(tagPrefix + instance.id);
        });
        if (display == null) {
            return null;
        }
        return new DisplayHandle(bone, piece, display, null, hitbox);
    }

    private DisplayHandle spawnPooled(
            DisplayTransport activeTransport,
            RigInstance instance,
            int bone,
            int piece,
            ItemStack stack,
            Location base,
            int ticks,
            boolean hitbox
    ) {
        PooledDisplay pooled = activeTransport.beginItemInstance(instance.id, base::getWorld);
        if (pooled == null) {
            return null;
        }
        ItemDisplay display = pooled.asItemDisplay();
        if (stack != null) {
            display.setItemStack(stack);
        }
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        display.addScoreboardTag(tagPrefix + instance.id);
        pooled.moveTo(activeTransport.nms(), base.getX(), base.getY(), base.getZ(), 0.0f, 0.0f);
        pooled.setTransform(new Matrix4f(), 0);
        return new DisplayHandle(bone, piece, null, pooled, hitbox);
    }

    /** Sets one bone's local (parent-relative) rotation; identity is the rest pose. */
    public void setLocal(UUID rigId, int boneIndex, Quaternionf localRotation) {
        RigInstance instance = rigs.get(rigId);
        if (instance == null || localRotation == null
                || boneIndex < 0 || boneIndex >= instance.boneCount) {
            return;
        }
        instance.local[boneIndex].set(localRotation);
    }

    /**
     * Sets one bone's local offset in blocks, applied in the parent's rotated
     * frame before the hierarchy pass — the channel a clip's position curve
     * drives. The offset moves the bone and everything below it.
     */
    public void setLocalOffset(UUID rigId, int boneIndex, float x, float y, float z) {
        RigInstance instance = rigs.get(rigId);
        if (instance == null || boneIndex < 0 || boneIndex >= instance.boneCount) {
            return;
        }
        instance.localOffset[boneIndex].set(x, y, z);
    }

    /** Resets every bone's local rotation and offset to the rest pose. */
    public void resetLocals(UUID rigId) {
        RigInstance instance = rigs.get(rigId);
        if (instance == null) {
            return;
        }
        for (Quaternionf local : instance.local) {
            local.identity();
        }
        for (Vector3f offset : instance.localOffset) {
            offset.set(0.0f, 0.0f, 0.0f);
        }
    }

    /**
     * Resolves the hierarchy for the current local rotations and pushes every
     * display. {@code base} is the rig origin (an entity's feet, or a block
     * anchor); a pose pass allocates nothing.
     */
    public void pose(UUID rigId, Location base) {
        RigInstance instance = rigs.get(rigId);
        if (instance == null || base == null || base.getWorld() == null) {
            return;
        }
        instance.x = base.getX();
        instance.y = base.getY();
        instance.z = base.getZ();
        instance.world = base.getWorld();

        BoneRigBlueprint blueprint = instance.blueprint;
        int count = instance.boneCount;
        Vector3f shift = instance.shift;
        for (int i = 0; i < count; i++) {
            int parent = blueprint.parent(i);
            instance.modelPos[i].set(blueprint.pivot(i)).add(shift).div(16.0f)
                    .add(instance.localOffset[i]);
            if (parent < 0) {
                instance.absolute[i].set(instance.local[i]);
                instance.worldPos[i].set(instance.modelPos[i]);
            } else {
                instance.absolute[i].set(instance.absolute[parent]).mul(instance.local[i]);
                instance.delta.set(instance.modelPos[i]).sub(instance.modelPos[parent]);
                instance.absolute[parent].transform(instance.delta);
                instance.worldPos[i].set(instance.worldPos[parent]).add(instance.delta);
            }
        }

        DisplayTransport activeTransport = instance.pooledTransport;
        for (DisplayHandle handle : instance.displays) {
            int bone = handle.bone;
            instance.offset.set(instance.worldPos[bone]).sub(instance.pivotCenter);
            instance.global.transform(instance.offset).add(instance.pivotCenter);
            double x = instance.x + instance.offset.x;
            double y = instance.y + instance.offset.y;
            double z = instance.z + instance.offset.z;

            instance.rotation.set(instance.global).mul(instance.absolute[bone])
                    .mul(blueprint.rest(bone));
            if (handle.piece >= 0) {
                // The piece's geometry already carries the standard half-block
                // anchor; only the extra in-window shift of a split piece has
                // to be undone, in world space and rotated with the display so
                // a split piece follows the bone like the unsplit geometry.
                Vector3f authored = blueprint.pieceOffsetPx(bone, handle.piece);
                if (authored.x != 0.0f || authored.y != 0.0f || authored.z != 0.0f) {
                    instance.pieceShift.set(authored).div(16.0f).negate();
                    instance.rotation.transform(instance.pieceShift);
                    x += instance.pieceShift.x;
                    y += instance.pieceShift.y;
                    z += instance.pieceShift.z;
                }
            }
            Vector3f scale = handle.hitbox ? blueprint.hitboxSize(bone) : UNIT_SCALE;
            if (handle.pooled != null) {
                handle.pooled.moveTo(activeTransport.nms(), x, y, z, 0.0f, 0.0f);
                handle.pooled.setTransform(instance.matrix.identity()
                        .rotate(instance.rotation)
                        .scale(scale), instance.ticks);
            } else if (handle.plain != null) {
                instance.location.setWorld(instance.world);
                instance.location.setX(x);
                instance.location.setY(y);
                instance.location.setZ(z);
                handle.plain.teleport(instance.location);
                handle.plain.setTransformationMatrix(instance.matrix.identity()
                        .rotate(instance.rotation)
                        .scale(scale));
            }
        }
        if (activeTransport != null) {
            activeTransport.flushDeltas(instance.id);
        }
    }

    /** The world position one bone resolved to during the last pose pass. */
    public Location boneLocation(UUID rigId, int boneIndex) {
        RigInstance instance = rigs.get(rigId);
        if (instance == null || boneIndex < 0 || boneIndex >= instance.boneCount) {
            return null;
        }
        return new Location(instance.world,
                instance.x + instance.worldPos[boneIndex].x,
                instance.y + instance.worldPos[boneIndex].y,
                instance.z + instance.worldPos[boneIndex].z);
    }

    /** Removes a rig, releasing its displays (pooled displays return to the pool). */
    public void remove(UUID rigId) {
        RigInstance instance = rigs.remove(rigId);
        if (instance == null) {
            return;
        }
        if (instance.pooledTransport != null) {
            instance.pooledTransport.removeInstance(rigId);
            return;
        }
        for (DisplayHandle handle : instance.displays) {
            if (handle.plain != null && handle.plain.isValid()) {
                handle.plain.remove();
            }
        }
    }

    private void release(List<DisplayHandle> handles, DisplayTransport pooledTransport, UUID rigId) {
        if (pooledTransport != null) {
            // Every pooled display acquired for this rig is owned by its
            // transport instance; removing the instance returns them all.
            pooledTransport.removeInstance(rigId);
            return;
        }
        for (DisplayHandle handle : handles) {
            if (handle.plain != null && handle.plain.isValid()) {
                handle.plain.remove();
            }
        }
    }

    /** Session-start sweep of displays whose rig is not attached in this session. */
    public int sweepGhosts() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(ItemDisplay.class)) {
                String rigId = tagOf(entity);
                if (rigId == null) {
                    continue;
                }
                try {
                    UUID id = UUID.fromString(rigId);
                    if (!rigs.containsKey(id)) {
                        entity.remove();
                        removed++;
                    }
                } catch (IllegalArgumentException malformed) {
                    entity.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            DebugLogger.info("[BoneRig] Swept " + removed + " stale rig displays.");
        }
        return removed;
    }

    private String tagOf(Entity entity) {
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith(tagPrefix)) {
                return tag.substring(tagPrefix.length());
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (rigs.isEmpty()) {
            return;
        }
        Chunk chunk = event.getChunk();
        if (!chunk.isLoaded()) {
            return;
        }
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof ItemDisplay)) {
                continue;
            }
            String rigId = tagOf(entity);
            if (rigId == null) {
                continue;
            }
            try {
                if (!rigs.containsKey(UUID.fromString(rigId))) {
                    entity.remove();
                }
            } catch (IllegalArgumentException malformed) {
                entity.remove();
            }
        }
    }

    private record DisplayHandle(int bone, int piece, ItemDisplay plain, PooledDisplay pooled, boolean hitbox) {
    }

    private static final class RigInstance {
        private final UUID id;
        private final BoneRigBlueprint blueprint;
        private final int boneCount;
        private final Quaternionf global;
        private final Vector3f pivotCenter;
        private final Vector3f shift;
        private final int ticks;
        private final DisplayTransport pooledTransport;
        private DisplayHandle[] displays = new DisplayHandle[0];
        private final Quaternionf[] local;
        private final Quaternionf[] absolute;
        private final Vector3f[] modelPos;
        private final Vector3f[] worldPos;
        private final Vector3f[] localOffset;
        private final Vector3f offset = new Vector3f();
        private final Vector3f delta = new Vector3f();
        private final Vector3f pieceShift = new Vector3f();
        private final Quaternionf rotation = new Quaternionf();
        private final Matrix4f matrix = new Matrix4f();
        private final Location location = new Location(null, 0, 0, 0);
        private World world;
        private double x;
        private double y;
        private double z;

        private RigInstance(
                UUID id,
                BoneRigBlueprint blueprint,
                Location base,
                World world,
                Quaternionf global,
                float pixelShift,
                Vector3f rotationPivot,
                int ticks,
                DisplayTransport pooledTransport
        ) {
            this.id = id;
            this.blueprint = blueprint;
            this.boneCount = blueprint.boneCount();
            this.global = global == null ? new Quaternionf() : new Quaternionf(global);
            this.pivotCenter = rotationPivot == null ? new Vector3f() : new Vector3f(rotationPivot);
            this.shift = new Vector3f(pixelShift, 0.0f, pixelShift);
            this.ticks = ticks;
            this.world = world;
            this.x = base.getX();
            this.y = base.getY();
            this.z = base.getZ();
            this.pooledTransport = pooledTransport;
            this.local = new Quaternionf[boneCount];
            this.absolute = new Quaternionf[boneCount];
            this.modelPos = new Vector3f[boneCount];
            this.worldPos = new Vector3f[boneCount];
            this.localOffset = new Vector3f[boneCount];
            for (int i = 0; i < boneCount; i++) {
                this.local[i] = new Quaternionf();
                this.absolute[i] = new Quaternionf();
                this.modelPos[i] = new Vector3f();
                this.worldPos[i] = new Vector3f();
                this.localOffset[i] = new Vector3f();
            }
        }
    }
}
