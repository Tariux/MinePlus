package com.mineplus.pack.render;

import com.mineplus.infrastructure.virtual.VirtualBlockPlacementHelper;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.pack.PackAssetRegistry;
import com.mineplus.pack.PackLighting;
import com.mineplus.pack.asset.PackBlockAsset;
import com.mineplus.util.DebugLogger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Pack-axis world renderer for {@code RenderKind.BLOCK} content: one
 * {@link BlockDisplay} per rendered instance, carrying the pack block's
 * allocated carrier state. The client resolves that state's overridden
 * blockstate model and draws the full-fidelity model in one display — the
 * block peer of {@link PackModelRenderer}'s per-instance item display.
 *
 * <p>Collision, break handling, occupancy, persistence and restore all come
 * from the existing multiblock/virtual lifecycle: {@code ModelRenderingManager}
 * spawns the collision lattice through {@code VirtualBlockManager}'s
 * collision-only path and hands the returned id here. This renderer owns
 * visuals only, keys them by that id, and never touches the virtual display
 * transport, the texel bake pool, or the animation loop.</p>
 *
 * <p>Display math matches the virtual lattice: the entity sits at the anchor
 * block corner (block models are block-local; the serializer emits the
 * center-to-corner shift), and placement rotation pivots about the anchor
 * block center {@code (0.5, 0.5, 0.5)} exactly like virtual displays.</p>
 */
public final class PackBlockRenderer implements Listener {

    /** Ghost-cleanup tag prefix; distinct from the item renderer's and the virtual engine's. */
    private static final String PACK_BLOCK_TAG_PREFIX = "mineplus_packblock:";

    private final PackAssetRegistry registry;
    private final java.util.logging.Logger logger;
    private volatile PackLighting lighting = PackLighting.AUTO;
    private final Map<UUID, UUID> displaysByInstance = new ConcurrentHashMap<>();

    public PackBlockRenderer(PackAssetRegistry registry, java.util.logging.Logger logger) {
        this(registry, logger, PackLighting.AUTO);
    }

    public PackBlockRenderer(PackAssetRegistry registry, java.util.logging.Logger logger, PackLighting lighting) {
        this.registry = registry;
        this.logger = logger;
        this.lighting = lighting == null ? PackLighting.AUTO : lighting;
    }

    /** Applies a new lighting policy (reload path); existing displays keep their spawned brightness. */
    public void setLighting(PackLighting lighting) {
        this.lighting = lighting == null ? PackLighting.AUTO : lighting;
    }

    /** True when a registered pack block renders this model key (render feasibility check). */
    public boolean hasBlockForModel(String modelKey) {
        return registry.blockForModel(modelKey) != null;
    }

    /**
     * Attaches the pack block display for an instance.
     *
     * @param instanceId the rendered-instance id (shared with the collision owner)
     * @param model      the resolved virtual model (geometry; the carrier comes from the registration)
     * @param placement  the placement (anchor + rotation)
     * @return {@code true} when a display was spawned
     */
    public boolean attach(UUID instanceId, VirtualModel model, VirtualBlockPlacementHelper.PlacementData placement) {
        if (instanceId == null || model == null || placement == null) {
            return false;
        }
        PackBlockAsset block = registry.blockForModel(model.name());
        if (block == null) {
            return false;
        }
        Location anchor = placement.location();
        World world = anchor.getWorld();
        if (world == null) {
            return false;
        }
        BlockData data;
        try {
            data = block.blockData();
        } catch (RuntimeException failure) {
            logger.warning("[PackRender] Could not build carrier state '" + block.stateString()
                    + "' for block '" + block.id() + "': " + failure);
            return false;
        }

        remove(instanceId);
        // The entity origin is the anchor block corner; the block model is
        // block-local, so the display occupies the anchor exactly.
        Location entityLocation = anchor.clone();
        Transformation transformation = transformationFor(placement);

        // Lighting policy: display entities inherit world light. AUTO applies an
        // override only to emissive models (block light = model max emission), so
        // non-emissive models are lit naturally and glowing models still glow.
        PackLighting lightingPolicy = lighting;
        int emission = PackLighting.maxEmission(model);
        boolean overrideLight = lightingPolicy.applies(emission);
        int blockLight = lightingPolicy.blockLight(emission);
        int skyLight = lightingPolicy.skyLight();

        BlockDisplay display = world.spawn(entityLocation, BlockDisplay.class, spawned -> {
            spawned.setBlock(data);
            spawned.addScoreboardTag(PACK_BLOCK_TAG_PREFIX + instanceId);
            spawned.setPersistent(true);
            spawned.setShadowRadius(0f);
            spawned.setViewRange(1.0f);
            spawned.setInterpolationDuration(0);
            if (overrideLight) {
                spawned.setBrightness(new org.bukkit.entity.Display.Brightness(blockLight, skyLight));
            }
            spawned.setTransformation(transformation);
        });
        displaysByInstance.put(instanceId, display.getUniqueId());
        return true;
    }

    /**
     * Rotation about the anchor block center: the virtual lattice uses the same
     * pivot, so both backends rotate identically.
     */
    private Transformation transformationFor(VirtualBlockPlacementHelper.PlacementData placement) {
        Quaternionf rotation = new Quaternionf(placement.globalRotation());
        Vector3f pivot = new Vector3f(0.5f, 0.5f, 0.5f);
        Matrix4f matrix = new Matrix4f()
                .translate(pivot)
                .rotate(rotation)
                .translate(pivot.negate());
        return new Transformation(
                matrix.getTranslation(new Vector3f()),
                matrix.getUnnormalizedRotation(new Quaternionf()),
                matrix.getScale(new Vector3f()),
                new Quaternionf());
    }

    /** Removes the display for an instance (no-op when none). */
    public void remove(UUID instanceId) {
        UUID displayId = displaysByInstance.remove(instanceId);
        if (displayId == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(displayId);
        if (entity != null) {
            entity.remove();
        }
    }

    /** The carrier block data a model key renders, or null (diagnostics). */
    public BlockData blockDataForModel(String modelKey) {
        PackBlockAsset block = registry.blockForModel(modelKey);
        return block == null ? null : block.blockData();
    }

    /**
     * Session-start sweep: removes pack block displays whose instance is not
     * attached in this session. Restored instances attach fresh displays before
     * the sweep runs, so live renders survive and stale entities do not.
     */
    public int sweepGhosts() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(BlockDisplay.class)) {
                String instanceTag = packTag(entity);
                if (instanceTag == null) {
                    continue;
                }
                try {
                    UUID instanceId = UUID.fromString(instanceTag);
                    if (!displaysByInstance.containsKey(instanceId)) {
                        entity.remove();
                        removed++;
                    }
                } catch (IllegalArgumentException ignored) {
                    entity.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            DebugLogger.info("[PackRender] Swept " + removed + " stale pack block display entities.");
        }
        return removed;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        if (!chunk.isLoaded()) {
            return;
        }
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof BlockDisplay)) {
                continue;
            }
            String instanceTag = packTag(entity);
            if (instanceTag == null) {
                continue;
            }
            try {
                UUID instanceId = UUID.fromString(instanceTag);
                if (!displaysByInstance.containsKey(instanceId)) {
                    entity.remove();
                }
            } catch (IllegalArgumentException ignored) {
                entity.remove();
            }
        }
    }

    private static String packTag(Entity entity) {
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith(PACK_BLOCK_TAG_PREFIX)) {
                return tag.substring(PACK_BLOCK_TAG_PREFIX.length());
            }
        }
        return null;
    }
}
