package com.mineplus.infrastructure.mob.custom;

import com.mineplus.infrastructure.mob.navigation.MobAiIsolation;
import com.mineplus.pack.PackApi;
import com.mineplus.util.DebugLogger;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The visual rig for texture-extended custom mobs: the base mob is hidden and
 * each model part is one {@link ItemDisplay} carrying a generated pack model.
 * Parts animate independently — head tracking, quadruped leg swing, body yaw —
 * entirely server-side, on a vanilla client.
 *
 * <p>Only the visual layer lives here. Identity, spawning, behavior and
 * lifecycle belong to {@code CustomMobManager}; the rig never decides what a
 * mob does, and a mob works with behavior intact when rigging is unavailable
 * (the pack is off, or the server predates the {@code item_model} component):
 * the base entity then stays visible, so custom mobs are never invisible
 * simply because their visuals could not be presented.</p>
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

    private final Plugin plugin;
    private final PackApi pack;
    private final Map<UUID, Rig> rigs = new ConcurrentHashMap<>();
    private volatile CustomMobSettings settings;
    private BukkitTask task;
    private boolean prepared;

    public MobRigRenderer(Plugin plugin, PackApi pack, CustomMobSettings settings) {
        this.plugin = plugin;
        this.pack = pack;
        this.settings = settings;
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
     * @return {@code true} when every part display was spawned
     */
    public boolean attach(LivingEntity entity, CustomMobDefinition definition) {
        if (!isAvailable() || entity == null || definition == null || definition.model() == null
                || !settings.renderingEnabled()) {
            return false;
        }
        UUID ownerId = entity.getUniqueId();
        if (rigs.containsKey(ownerId)) {
            return true;
        }
        if (countInWorld(entity.getWorld()) >= settings.maxRiggedPerWorld()) {
            DebugLogger.info("[CustomMobs] Rig cap reached in " + entity.getWorld().getName()
                    + "; '" + definition.id() + "' renders as its base entity.");
            return false;
        }

        CustomMobModel model = definition.model();
        String namespace = definition.namespace();
        String idPath = definition.path();
        Location base = entity.getLocation();
        World world = base.getWorld();
        if (world == null) {
            return false;
        }
        int interval = Math.max(1, settings.renderIntervalTicks());
        float viewRange = Math.min(1.0f, Math.max(0.1f, settings.renderDistance() / 64.0f));

        Rig rig = new Rig(entity, model, (float) base.getYaw());
        for (MobPart part : model.parts()) {
            ItemStack stack = pack.createModelDisplay(Material.PAPER, namespace,
                    MobRigAssetWriter.componentPath(idPath, part.name()));
            if (stack == null) {
                releaseRig(rig);
                return false;
            }
            Location at = new Location(world, base.getX(), base.getY(), base.getZ(), 0.0f, 0.0f);
            ItemDisplay display = world.spawn(at, ItemDisplay.class, spawned -> {
                spawned.setItemStack(stack);
                spawned.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                spawned.setPersistent(false);
                spawned.setInvulnerable(true);
                spawned.setSilent(true);
                spawned.addScoreboardTag(TAG_PREFIX + ownerId);
                spawned.setViewRange(viewRange);
                spawned.setTeleportDuration(interval);
                spawned.setInterpolationDuration(interval);
            });
            if (display == null) {
                releaseRig(rig);
                return false;
            }
            rig.parts.add(new PartRig(part, display, baseQuaternion(part)));
        }

        rigs.put(ownerId, rig);
        if (definition.hideBaseWhenRigged()) {
            rig.hiddenBase = true;
            entity.setInvisible(true);
        }
        ensureTicking();
        applyPose(rig, base, true);
        return true;
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
        for (PartRig part : rig.parts) {
            if (part.display != null && part.display.isValid()) {
                part.display.remove();
            }
        }
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
     * otherwise unchanged rigs (a standing mob no player is watching move)
     * cost nothing.
     */
    private void applyPose(Rig rig, Location location, boolean force) {
        float bodyYaw = location.getYaw();
        float headYaw = MobAiIsolation.headYaw(rig.owner, bodyYaw);
        float pitch = MobAiIsolation.pitch(rig.owner, 0.0f);

        double dx = location.getX() - rig.lastX;
        double dz = location.getZ() - rig.lastZ;
        double movedSq = dx * dx + dz * dz;
        double distance = Math.sqrt(movedSq);
        boolean sameWorld = rig.lastWorld != null && rig.lastWorld.equals(location.getWorld());
        if (!sameWorld) {
            distance = 0.0;
        }
        float phase = rig.phase + (float) (distance * (Math.PI * 2.0 / STRIDE_BLOCKS));
        phase = (float) (phase % (Math.PI * 2.0));
        double perUpdate = 0.25 * Math.max(1, settings.renderIntervalTicks());
        float targetAmplitude = (float) Math.min(1.0, distance / perUpdate) * MAX_LEG_ANGLE;
        float amplitude = rig.amplitude + (targetAmplitude - rig.amplitude) * 0.35f;

        boolean changed = force
                || !sameWorld
                || movedSq > MOVED_EPSILON_SQ
                || Math.abs(bodyYaw - rig.lastBodyYaw) > ANGLE_EPSILON
                || Math.abs(headYaw - rig.lastHeadYaw) > ANGLE_EPSILON
                || Math.abs(pitch - rig.lastPitch) > ANGLE_EPSILON
                || Math.abs(phase - rig.phase) > PHASE_EPSILON
                || Math.abs(amplitude - rig.amplitude) > 0.01f;
        rig.lastX = location.getX();
        rig.lastZ = location.getZ();
        rig.lastWorld = location.getWorld();
        rig.lastBodyYaw = bodyYaw;
        rig.lastHeadYaw = headYaw;
        rig.lastPitch = pitch;
        rig.phase = phase;
        rig.amplitude = amplitude;
        if (!changed) {
            return;
        }

        Quaternionf bodyRotation = yawQuaternion(bodyYaw);
        Quaternionf headRotation = yawQuaternion(headYaw)
                .mul(new Quaternionf().rotationX((float) Math.toRadians(-pitch)));
        for (PartRig part : rig.parts) {
            if (part.display == null || !part.display.isValid()) {
                continue;
            }
            MobPart model = part.part;
            float yUp = (rig.model.modelHeightPx() - model.pivotY()) / 16.0f;
            float offsetX = model.pivotX() / 16.0f;
            float offsetZ = model.pivotZ() / 16.0f;

            Vector3f offset = new Vector3f(offsetX, yUp, offsetZ);
            bodyRotation.transform(offset);
            Location at = new Location(location.getWorld(),
                    location.getX() + offset.x,
                    location.getY() + offset.y,
                    location.getZ() + offset.z,
                    0.0f, 0.0f);

            Quaternionf total = switch (model.role()) {
                case HEAD -> new Quaternionf(headRotation).mul(part.base);
                case LEG_FRONT_LEFT, LEG_BACK_RIGHT ->
                        new Quaternionf(bodyRotation)
                                .mul(new Quaternionf().rotationX(-(float) Math.cos(phase) * amplitude))
                                .mul(part.base);
                case LEG_FRONT_RIGHT, LEG_BACK_LEFT ->
                        new Quaternionf(bodyRotation)
                                .mul(new Quaternionf().rotationX((float) Math.cos(phase) * amplitude))
                                .mul(part.base);
                default -> new Quaternionf(bodyRotation).mul(part.base);
            };

            part.display.teleport(at);
            part.display.setTransformation(new Transformation(
                    new Vector3f(), total, new Vector3f(1.0f, 1.0f, 1.0f), new Quaternionf()));
        }
    }

    /**
     * A part's baked rest rotation, converted to the client's y-up space:
     * vanilla {@code X} and {@code Z} rotations flip sign when the vertical
     * axis flips, {@code Y} keeps it.
     */
    private static Quaternionf baseQuaternion(MobPart part) {
        float x = (float) Math.toRadians(-part.rotationX());
        float y = (float) Math.toRadians(part.rotationY());
        float z = (float) Math.toRadians(-part.rotationZ());
        return new Quaternionf().rotationZ(z).rotateY(y).rotateX(x);
    }

    /** {@code R_y(180° - entityYaw)}: model front ({@code -z}) turns with the entity. */
    private static Quaternionf yawQuaternion(float entityYaw) {
        return new Quaternionf().rotationY((float) Math.toRadians(180.0 - entityYaw));
    }

    @SuppressWarnings("removal")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        if (!rigs.isEmpty()) {
            detach(event.getEntity().getUniqueId());
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
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(ItemDisplay.class)) {
                if (mobRigOwner(entity) != null && !rigs.containsKey(mobRigOwner(entity))) {
                    entity.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            DebugLogger.info("[CustomMobs] Swept " + removed + " stale mob rig displays.");
        }
        return removed;
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
        private final CustomMobModel model;
        private final ArrayList<PartRig> parts = new ArrayList<>();
        private double lastX;
        private double lastZ;
        private World lastWorld;
        private float lastBodyYaw;
        private float lastHeadYaw;
        private float lastPitch;
        private float phase;
        private float amplitude;
        private boolean hiddenBase;

        private Rig(LivingEntity owner, CustomMobModel model, float yaw) {
            this.owner = owner;
            this.model = model;
            this.lastX = owner.getLocation().getX();
            this.lastZ = owner.getLocation().getZ();
            this.lastWorld = owner.getWorld();
            this.lastBodyYaw = yaw;
            this.lastHeadYaw = yaw;
        }
    }

    private static final class PartRig {
        private final MobPart part;
        private final ItemDisplay display;
        private final Quaternionf base;

        private PartRig(MobPart part, ItemDisplay display, Quaternionf base) {
            this.part = part;
            this.display = display;
            this.base = base;
        }
    }
}
