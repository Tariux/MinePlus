package com.mineplus.pack.render;

import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualBlockPlacementHelper;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.pack.PackAssetRegistry;
import com.mineplus.pack.item.PackItemFactory;
import com.mineplus.pack.asset.ItemModelAsset;
import com.mineplus.util.DebugLogger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Pack-axis world renderer: one {@link ItemDisplay} per rendered instance,
 * holding the pack custom item whose model the client renders at full
 * fidelity. Players without the pack see the backing vanilla item — the
 * automatic vanilla-approximation fallback; the preferred virtual fallback is
 * applied one level up, in {@code ModelRenderingManager}'s backend selection.
 *
 * <p>Reuses the existing collision lattice: {@code VirtualBlockManager}'s
 * collision-only spawn places the barriers and owns break/chunk behavior
 * under the same rendered-instance id; this renderer only owns visuals keyed
 * by that id. It does not touch the virtual display transport or the texel
 * bake pool (coexistence contract), and it spawns plain Bukkit entities —
 * no second packet transport.</p>
 *
 * <p>Display math mirrors the virtual pipeline: the model renders in raw
 * item-model space under {@code ItemDisplayTransform.NONE} (16 px = 1 block,
 * model origin at the entity position), so a CENTER-authored model anchors at
 * the block center, a GRID-authored model at the block corner, and placement
 * rotation pivots around the anchor block center exactly like virtual
 * displays do.</p>
 */
public final class PackModelRenderer implements Listener {

    /** Ghost-cleanup tag prefix; separate from the virtual renderer's tags. */
    private static final String PACK_TAG_PREFIX = "mineplus_packdisplay:";

    private final PackAssetRegistry registry;
    private final PackItemFactory itemFactory;
    private final Map<UUID, UUID> displaysByInstance = new ConcurrentHashMap<>();
    /** Set by the owning PackSystem: re-attaches pending assets after model reloads. */
    private volatile Runnable modelsReloadedHook;

    public PackModelRenderer(PackAssetRegistry registry, PackItemFactory itemFactory) {
        this.registry = registry;
        this.itemFactory = itemFactory;
    }

    /** Binds the post-reload hook (model/texture assets re-attach, then recompile). */
    public void bindModelsReloadedHook(Runnable hook) {
        this.modelsReloadedHook = hook;
    }

    /** Invoked by the rendering manager after model definitions reload. */
    public void onModelsReloaded() {
        Runnable hook = modelsReloadedHook;
        if (hook != null) {
            hook.run();
        }
    }

    /** True when a registered pack item renders this model key (render feasibility check). */
    public boolean hasItemForModel(String modelKey) {
        return stackForModel(modelKey) != null;
    }

    /**
     * Attaches the pack display for an instance.
     *
     * @param instanceId the rendered-instance id (shared with the collision owner)
     * @param model      the resolved virtual model (geometry + origin mode)
     * @param placement  the placement (anchor + rotation)
     * @return {@code true} when a display was spawned
     */
    public boolean attach(UUID instanceId, VirtualModel model, VirtualBlockPlacementHelper.PlacementData placement) {
        if (instanceId == null || model == null || placement == null) {
            return false;
        }
        ItemStack stack = stackForModel(model.name());
        if (stack == null) {
            return false;
        }
        remove(instanceId);

        Location anchor = placement.location();
        World world = anchor.getWorld();
        if (world == null) {
            return false;
        }

        ModelMeta.OriginMode originMode = ModelMeta.OriginMode.forModel(model.modelFormat(), model.cubes());
        Location entityLocation = originMode == ModelMeta.OriginMode.GRID
                ? anchor.clone()
                : anchor.clone().add(0.5, 0.0, 0.5);

        ItemDisplay display = world.spawn(entityLocation, ItemDisplay.class, spawned -> {
            spawned.setItemStack(stack);
            spawned.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            spawned.addScoreboardTag(PACK_TAG_PREFIX + instanceId);
            spawned.setPersistent(true);
            spawned.setTransformation(transformationFor(placement, originMode));
        });

        displaysByInstance.put(instanceId, display.getUniqueId());
        return true;
    }

    /**
     * Placement transform: rotate about the anchor block center. For CENTER
     * models the entity already sits at the center (no pivot needed); for GRID
     * models the model origin is the corner, so the pivot is +0.5 in-block.
     */
    private Transformation transformationFor(VirtualBlockPlacementHelper.PlacementData placement, ModelMeta.OriginMode originMode) {
        Quaternionf rotation = new Quaternionf(placement.globalRotation());
        if (originMode == ModelMeta.OriginMode.CENTER) {
            return new Transformation(
                    new Vector3f(), rotation, new Vector3f(1, 1, 1), new Quaternionf());
        }
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

    /**
     * Session-start sweep: removes pack displays whose instance is not attached
     * in this session. Restores attach fresh displays before the sweep runs, so
     * live renders survive and stale entities from the previous session do not.
     */
    public int sweepGhosts() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(ItemDisplay.class)) {
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
            DebugLogger.info("[PackRender] Swept " + removed + " stale pack display entities.");
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
            if (!(entity instanceof ItemDisplay)) {
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
            if (tag.startsWith(PACK_TAG_PREFIX)) {
                return tag.substring(PACK_TAG_PREFIX.length());
            }
        }
        return null;
    }

    /** The pack item stack rendering a model key, or null when none is bound. */
    public ItemStack stackForModel(String modelKey) {
        ItemModelAsset item = registry.itemForModel(modelKey);
        if (item == null) {
            return null;
        }
        return itemFactory.create(item);
    }
}
