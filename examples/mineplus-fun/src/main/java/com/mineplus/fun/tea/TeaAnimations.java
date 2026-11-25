package com.mineplus.fun.tea;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.VirtualBlockPlacementHelper;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.pack.asset.ItemModelAsset;
import com.mineplus.pack.item.ItemFrameAnimator;
import com.mineplus.pack.item.ItemFrameWriter;
import com.mineplus.pack.item.PackItemDefinition;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;

/**
 * Drives the tea cup's animations through the resource pack itself.
 *
 * <p>The tea item is a framed pack item ({@link PackItemDefinition#isFramed()}):
 * its clips are baked frame models the client renders straight from the pack,
 * so the cup looks identical held, placed or in an inventory — no texel bake.
 * The animator walks the frames server-side and writes the matching custom
 * model data onto the held stack or the placed {@link ItemDisplay}'s stack.
 *
 * <p>Default behavior: {@code steam_idle} loops whenever the cup is held or
 * placed. A right click while holding the cup plays {@code drink_firstperson}
 * (the vanilla potion-drink use is cancelled so the model never disappears);
 * {@code /tea play <clip>} plays any clip on the placed cup.
 */
public final class TeaAnimations implements Listener {

    private static final long TICK_PERIOD = 1L;

    private final JavaPlugin plugin;
    private final PluginContext context;
    private final PackItemDefinition definition;
    private final ItemFramesHandle frames;
    private final Map<UUID, ItemFrameAnimator> heldSessions = new HashMap<>();
    private final Map<UUID, PlacedCup> placedCups = new HashMap<>();
    private BukkitTask ticker;

    /** Null when the frame bake failed — the runtime then stays idle. */
    public record ItemFramesHandle(com.mineplus.pack.item.ItemFrames spec) {

        public boolean available() {
            return spec != null;
        }

        public boolean hasClip(String name) {
            return spec != null && spec.clip(name) != null;
        }
    }

    private record PlacedCup(UUID instanceId, ItemDisplay display, ItemStack stack, ItemFrameAnimator animator) {
    }

    public TeaAnimations(JavaPlugin plugin, PluginContext context,
                         PackItemDefinition definition, ItemFramesHandle frames) {
        this.plugin = plugin;
        this.context = context;
        this.definition = definition;
        this.frames = frames;
    }

    /** Registers the interaction listener and starts the frame ticker. */
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, TICK_PERIOD);
    }

    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        heldSessions.clear();
        for (PlacedCup cup : placedCups.values()) {
            cup.display().remove();
        }
        placedCups.clear();
    }

    /* ------------------------------------------------------------------ */
    /* Placement                                                           */

    /**
     * Places the cup through the pack axis: collision-only virtual spawn (the
     * Core owns barriers and break behaviour) plus an {@link ItemDisplay}
     * holding the tea pack item, so the cup renders with the resource-pack
     * texture instead of the virtual engine's texel bake.
     *
     * @return the placed cup id, or {@code null} when the model is unknown
     */
    public UUID place(VirtualModel model, VirtualBlockPlacementHelper.PlacementData placement) {
        VirtualBlockManager blocks = context.virtualBlockManager();
        UUID instanceId = blocks.spawnCollisionModel(model, placement);
        if (instanceId == null) {
            return null;
        }
        Location origin = placement.location().clone();
        if (blocks.getModelMeta(model.name()).originMode() == ModelMeta.OriginMode.CENTER) {
            origin.add(0.5, 0, 0.5);
        }
        ItemDisplay display = origin.getWorld().spawn(origin, ItemDisplay.class, spawned -> {
            spawned.setItemStack(definition.createItem());
            spawned.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            Quaternionf rotation = placement.globalRotation();
            spawned.setTransformation(new Transformation(
                    new org.joml.Vector3f(),
                    rotation == null ? new Quaternionf() : new Quaternionf(rotation),
                    new org.joml.Vector3f(1, 1, 1),
                    new Quaternionf()));
            spawned.setPersistent(false);
        });
        placedCups.put(instanceId, new PlacedCup(instanceId, display,
                definition.createItem(),
                frames.available() ? new ItemFrameAnimator(frames.spec()) : null));
        return instanceId;
    }

    /** Removes a placed cup (its pack display and its collision lattice). */
    public boolean remove(UUID cupId) {
        PlacedCup cup = placedCups.remove(cupId);
        if (cup == null) {
            return false;
        }
        cup.display().remove();
        context.virtualBlockManager().removeModel(cup.instanceId());
        return true;
    }

    public boolean isPlaced(UUID cupId) {
        return placedCups.containsKey(cupId);
    }

    /** Starts a clip on the placed cup; steam clips loop, others play once. */
    public boolean playOnCup(UUID cupId, String clip) {
        PlacedCup cup = placedCups.get(cupId);
        return cup != null && cup.animator() != null && cup.animator().start(clip);
    }

    public void stopCup(UUID cupId) {
        PlacedCup cup = placedCups.get(cupId);
        if (cup != null) {
            cup.animator().stop();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Interaction                                                         */

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        ItemStack stack = event.getItem();
        if (!isTea(stack)) {
            return;
        }
        // The backing material is a potion: without this the client starts the
        // vanilla drink animation and the model disappears into the drinking
        // pose, and on a block the cup would try to place it.
        event.setCancelled(true);
        ItemFrameAnimator session = session(event.getPlayer());
        if (session != null && !session.active()) {
            session.start(TeaKeys.ANIM_DRINK);
        }
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isTea(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        heldSessions.remove(event.getPlayer().getUniqueId());
    }

    /* ------------------------------------------------------------------ */
    /* Frame ticking                                                       */

    private void tick() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (isTea(held)) {
                tickHeld(player, held);
            } else {
                heldSessions.remove(player.getUniqueId());
            }
        }
        Iterator<Map.Entry<UUID, PlacedCup>> cups = placedCups.entrySet().iterator();
        while (cups.hasNext()) {
            PlacedCup cup = cups.next().getValue();
            if (!cup.display().isValid()) {
                cup.display().remove();
                context.virtualBlockManager().removeModel(cup.instanceId());
                cups.remove();
                continue;
            }
            int state = 0;
            if (cup.animator() != null) {
                state = cup.animator().tick();
                if (state < 0) {
                    // One-shot clip finished: back to the default steam loop.
                    cup.animator().start(TeaKeys.ANIM_STEAM);
                    state = cup.animator().tick();
                }
            }
            cup.display().setItemStack(stackAt(cup.stack(), state));
        }
    }

    private void tickHeld(Player player, ItemStack held) {
        ItemFrameAnimator session = session(player);
        if (session == null) {
            return;
        }
        int state = session.tick();
        if (state < 0) {
            // One-shot clip finished: hand control back to the default loop.
            session.start(TeaKeys.ANIM_STEAM);
            state = session.tick();
        }
        int value = definition.frameStackValue(state);
        if (ItemFrameWriter.read(held) != value) {
            ItemFrameWriter.apply(held, value);
            player.getInventory().setItemInMainHand(held);
        }
    }

    private ItemFrameAnimator session(Player player) {
        if (!frames.available()) {
            return null;
        }
        // steam_idle loops by default whenever the cup is held and no one-shot
        // clip is running.
        return heldSessions.computeIfAbsent(player.getUniqueId(), id -> {
            ItemFrameAnimator animator = new ItemFrameAnimator(frames.spec());
            animator.start(TeaKeys.ANIM_STEAM);
            return animator;
        });
    }

    private ItemStack stackAt(ItemStack base, int state) {
        int value = definition.frameStackValue(state);
        if (ItemFrameWriter.read(base) == value) {
            return base;
        }
        ItemStack frame = base.clone();
        ItemFrameWriter.apply(frame, value);
        return frame;
    }

    private boolean isTea(ItemStack stack) {
        if (stack == null || stack.getType() != definition.backingMaterial()) {
            return false;
        }
        int read = ItemFrameWriter.read(stack);
        int base = ItemModelAsset.frameBase(TeaKeys.NAMESPACE, TeaKeys.TEA_ITEM);
        return read >= base;
    }
}
