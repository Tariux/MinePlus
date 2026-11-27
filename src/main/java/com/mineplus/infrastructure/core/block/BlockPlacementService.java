package com.mineplus.infrastructure.core.block;

import com.mineplus.infrastructure.core.api.AnimationApi;
import com.mineplus.infrastructure.virtual.PlacementOptions;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.VirtualBlockPlacementHelper;
import com.mineplus.infrastructure.virtual.VirtualModel;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.joml.Quaternionf;

/**
 * Places, removes and restores block instances on top of the virtual engine,
 * and keeps the {@link BlockStore} record in step with every change.
 */
public final class BlockPlacementService {

    public record Outcome(boolean placed, String message, java.util.UUID instanceId) {
        static Outcome refused(String message) {
            return new Outcome(false, message, null);
        }
    }

    private final VirtualBlockManager manager;
    private final BlockRegistry registry;
    private final BlockStore store;
    private final BlockItemFactory items;
    private final Consumer<String> warn;
    private final Supplier<AnimationApi> animationApi;

    public BlockPlacementService(
            VirtualBlockManager manager,
            BlockRegistry registry,
            BlockStore store,
            BlockItemFactory items,
            Consumer<String> warn
    ) {
        this(manager, registry, store, items, warn, () -> null);
    }

    /**
     * @param animationApi resolved lazily because block definitions load before
     *                     {@code PluginContext.bootstrap} and the API only
     *                     exists afterwards
     */
    public BlockPlacementService(
            VirtualBlockManager manager,
            BlockRegistry registry,
            BlockStore store,
            BlockItemFactory items,
            Consumer<String> warn,
            Supplier<AnimationApi> animationApi
    ) {
        this.manager = manager;
        this.registry = registry;
        this.store = store;
        this.items = items;
        this.warn = warn == null ? message -> { } : warn;
        this.animationApi = animationApi == null ? () -> null : animationApi;
    }

    public Outcome place(Player player, BlockDefinition definition) {
        VirtualModel model = manager.getModel(definition.model());
        if (model == null) {
            return Outcome.refused("Model '" + definition.model() + "' is not loaded.");
        }
        PlacementOptions options = PlacementOptions.DEFAULT
                .withYawSnap(definition.rotationSnap())
                .withAirTargetCheck(false);
        VirtualBlockPlacementHelper.PlacementData placement =
                VirtualBlockPlacementHelper.getPlacementData(player, 6.0, options);
        if (placement == null) {
            return Outcome.refused("Aim at a block face within six blocks.");
        }
        String supportProblem = supportProblem(definition, placement);
        if (supportProblem != null) {
            return Outcome.refused(supportProblem);
        }
        if (manager.prepareSpawnArea(model, placement, false) == VirtualBlockManager.SpawnAreaResult.BLOCKED) {
            return Outcome.refused("Not enough space for " + definition.displayName() + ".");
        }
        java.util.UUID instanceId = manager.spawnModel(model, placement);
        startAutoplay(definition, instanceId);
        Location anchor = placement.location();
        store.put(new PlacedBlock(
                instanceId,
                definition.id(),
                definition.model(),
                anchor.getWorld().getName(),
                anchor.getBlockX(),
                anchor.getBlockY(),
                anchor.getBlockZ(),
                placement.attachedFace().name(),
                placement.globalRotation().x,
                placement.globalRotation().y,
                placement.globalRotation().z,
                placement.globalRotation().w,
                player.getUniqueId().toString(),
                System.currentTimeMillis()));
        store.save();
        BlockActionRunner.run(player, definition, definition.events().onPlace());
        return new Outcome(true, "Placed " + definition.displayName(), instanceId);
    }

    public boolean removeLooking(Player player) {
        org.bukkit.block.Block target = player.getTargetBlockExact(6);
        if (target == null) {
            return false;
        }
        java.util.UUID instanceId = manager.getInstanceIdAt(target.getLocation());
        if (instanceId == null) {
            return false;
        }
        manager.removeModel(instanceId);
        return true;
    }

    public ItemStack createItem(BlockDefinition definition, int amount) {
        return items.create(definition, amount);
    }

    public void forget(java.util.UUID instanceId) {
        if (store.remove(instanceId) != null) {
            store.save();
        }
    }

    public ItemStack dropFor(PlacedBlock record) {
        BlockDefinition definition = registry.get(record.definitionId());
        if (definition == null || definition.breaking().drops().isEmpty()) {
            return definition == null ? null : items.create(definition, 1);
        }
        BlockDefinition.Drop drop = definition.breaking().drops().get(0);
        if (drop.chance() < 1.0 && Math.random() > drop.chance()) {
            return null;
        }
        BlockDefinition dropDefinition = registry.get(drop.item());
        int amount = drop.min() + (drop.max() > drop.min()
                ? (int) (Math.random() * (drop.max() - drop.min() + 1)) : 0);
        if (dropDefinition != null) {
            return items.create(dropDefinition, amount);
        }
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(drop.item());
        if (material == null || material.isAir()) {
            return null;
        }
        return new ItemStack(material, Math.max(1, Math.min(64, amount)));
    }

    /** Restores records whose chunk is loaded; returns how many were respawned. */
    public int restoreAll() {
        int restored = 0;
        for (PlacedBlock record : store.all()) {
            if (restore(record)) {
                restored++;
            }
        }
        return restored;
    }

    public boolean restoreInChunk(World world, int chunkX, int chunkZ) {
        boolean restored = false;
        for (PlacedBlock record : store.all()) {
            if (!record.world().equals(world.getName())) {
                continue;
            }
            if ((record.x() >> 4) != chunkX || (record.z() >> 4) != chunkZ) {
                continue;
            }
            if (restore(record)) {
                restored = true;
            }
        }
        return restored;
    }

    private boolean restore(PlacedBlock record) {
        World world = Bukkit.getWorld(record.world());
        if (world == null) {
            return false;
        }
        Location anchor = new Location(world, record.x(), record.y(), record.z());
        if (!world.isChunkLoaded(record.x() >> 4, record.z() >> 4)) {
            return false;
        }
        if (manager.exists(record.instanceId()) || manager.getInstanceIdAt(anchor) != null) {
            return false;
        }
        VirtualModel model = manager.getModel(record.model());
        if (model == null) {
            warn.accept("blocks: cannot restore " + record.definitionId()
                    + " at " + record.world() + " " + record.x() + "," + record.y() + "," + record.z()
                    + ": model '" + record.model() + "' missing");
            return false;
        }
        BlockFace face;
        try {
            face = BlockFace.valueOf(record.face());
        } catch (IllegalArgumentException unknownFace) {
            face = BlockFace.UP;
        }
        VirtualBlockPlacementHelper.PlacementData placement =
                new VirtualBlockPlacementHelper.PlacementData(
                        anchor, face, new Quaternionf(record.qx(), record.qy(), record.qz(), record.qw()));
        if (manager.prepareSpawnArea(model, placement, false) == VirtualBlockManager.SpawnAreaResult.BLOCKED) {
            warn.accept("blocks: space occupied, skipping restore of " + record.definitionId()
                    + " at " + record.world() + " " + record.x() + "," + record.y() + "," + record.z());
            return false;
        }
        java.util.UUID instanceId = manager.spawnModel(model, placement);
        startAutoplay(registry.get(record.definitionId()), instanceId);
        store.remove(record.instanceId());
        store.put(new PlacedBlock(instanceId, record.definitionId(), record.model(),
                record.world(), record.x(), record.y(), record.z(), face.name(),
                record.qx(), record.qy(), record.qz(), record.qw(),
                record.owner(), record.placedAt()));
        store.save();
        return true;
    }

    private void startAutoplay(BlockDefinition definition, java.util.UUID instanceId) {
        if (definition == null || definition.autoplay().isEmpty()) {
            return;
        }
        AnimationApi api = animationApi.get();
        if (api == null) {
            return;
        }
        for (String clip : definition.autoplay()) {
            if (!api.playAnimation(instanceId, clip)) {
                warn.accept("blocks: " + definition.id() + " declares autoplay clip '" + clip
                        + "' but the model has no such animation");
            }
        }
    }

    private static String supportProblem(
            BlockDefinition definition,
            VirtualBlockPlacementHelper.PlacementData placement
    ) {
        BlockFace face = placement.attachedFace();
        Location anchor = placement.location();
        boolean belowEmpty = anchor.getBlock().getRelative(BlockFace.DOWN).getType().isAir();
        return BlockPlacementRules.supportProblem(
                definition.placement().support(), face.name(), belowEmpty);
    }
}
