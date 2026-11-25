package com.mineplus.fun.tea;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.VirtualBlockPlacementHelper;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.pack.asset.ItemModelAsset;
import com.mineplus.pack.item.ItemFrameAnimator;
import com.mineplus.pack.item.ItemFrameWriter;
import com.mineplus.pack.item.ItemFrames;
import com.mineplus.pack.item.ItemFrames.ClipPlan;
import com.mineplus.pack.item.ItemFrames.Sequence;
import com.mineplus.pack.item.PackItemDefinition;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
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
 * <p>Held item — client clock only. A held stack cannot be rewritten every
 * tick without the client flickering or dropping the model (the same limit the
 * gun module documents), so every held clip is a cooldown-driven pack
 * sequence:
 *
 * <ul>
 *   <li>{@code steam_idle} (registered as the {@code fire} clip) is gated on
 *       the resting state: the stack sits at rest forever and the runtime only
 *       re-arms the item cooldown once per cycle — the steam loop plays
 *       client-side with zero stack writes.</li>
 *   <li>{@code drink_firstperson} (registered as the {@code reload} clip) is
 *       gated on its own first frame: a right click writes that state once,
 *       arms the cooldown, and the client plays the drink. The vanilla potion
 *       use is cancelled so the model never disappears into the drinking
 *       pose.</li>
 * </ul>
 *
 * <p>Placed cup — server clock. An {@link ItemDisplay} is not a player hand,
 * so its stack may be rewritten per tick: an {@link ItemFrameAnimator} loops
 * {@code steam_idle} and {@code /tea play <clip>} plays any baked clip on it.
 */
public final class TeaAnimations implements Listener {

    private final JavaPlugin plugin;
    private final PluginContext context;
    private final PackItemDefinition definition;
    private final ItemFrames frames;
    private final int steamTicks;
    private final int drinkTicks;
    private final Map<UUID, Held> held = new HashMap<>();
    private final Map<UUID, PlacedCup> placedCups = new HashMap<>();
    private BukkitTask ticker;

    /** Null when the frame bake failed — the runtime then stays idle. */
    public record ItemFramesHandle(ItemFrames spec) {

        public boolean available() {
            return spec != null;
        }
    }

    private static final class Held {
        Mode mode = Mode.NONE;
        int remaining;
    }

    private enum Mode {
        /** Not holding yet: seat the stack at rest and arm the steam loop. */
        NONE,
        /** Steam loop: cooldown re-arms once per cycle, no stack writes. */
        STEAM,
        /** Drink playing: when the countdown ends, hand back to the loop. */
        DRINK
    }

    private record PlacedCup(UUID instanceId, ItemDisplay display, ItemStack stack, ItemFrameAnimator animator) {
    }

    public TeaAnimations(JavaPlugin plugin, PluginContext context,
                         PackItemDefinition definition, ItemFramesHandle frames) {
        this.plugin = plugin;
        this.context = context;
        this.definition = definition;
        this.frames = frames == null ? null : frames.spec();
        this.steamTicks = clipTicks("fire", 60);
        this.drinkTicks = clipTicks("reload", 32);
    }

    private int clipTicks(String name, int fallback) {
        if (frames == null) {
            return fallback;
        }
        ItemFrames.Clip clip = frames.clip(name);
        return clip == null || clip.ticks() <= 0 ? fallback : clip.ticks();
    }

    /** Registers the interaction listener and starts the animation ticker. */
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            // Drop any armed cooldown so the overlay does not outlive the feature.
            player.setCooldown(definition.backingMaterial(), 0);
        }
        held.clear();
        for (PlacedCup cup : placedCups.values()) {
            cup.display().remove();
        }
        placedCups.clear();
    }

    /* ------------------------------------------------------------------ */
    /* Placement                                                           */

    /**
     * Places the cup through the Core's anchored-model API. The cup's meta
     * declares {@code anchorMode: FRAME}, so the Core spawns no barrier blocks
     * at all — just one invisible {@code Interaction} entity sized from the
     * model's bounding box — and registers the instance in its books, so
     * breaks, clears and the remove event all flow through Core code. The pack
     * {@link ItemDisplay} stays an independent entity (mounting it as a
     * passenger lets Minecraft override its position with the vehicle's ride
     * offset, which sank the cup half into the ground); its lifecycle is bound
     * to the instance by the {@code VirtualBlockInstanceRemoveEvent} handler,
     * so it still cannot outlive its anchor.
     *
     * @return the placed cup id, or {@code null} when the model is unknown
     */
    public UUID place(VirtualModel model, VirtualBlockPlacementHelper.PlacementData placement) {
        VirtualBlockManager blocks = context.virtualBlockManager();
        VirtualBlockManager.AnchoredModel anchored = blocks.spawnAnchoredModel(model, placement);
        if (anchored == null) {
            return null;
        }
        UUID instanceId = anchored.instanceId();
        // Ask the Core for the anchor it actually resolved (meta origin mode,
        // rotation snapping) so the pack display lands exactly where the
        // interaction entity sits instead of recomputing either by hand.
        VirtualBlockManager.DisplayAnchor anchor = blocks.displayAnchorFor(model, placement);
        // The anchor is the display origin the Core's own renderer uses. An
        // ItemDisplay still centers the 16px item-model box on the entity, so
        // lift by half a block only — never add a center offset here, the Core
        // already resolved it (adding one again put the cup a block ahead).
        Location origin = anchor.origin().clone().add(0, 0.5, 0);
        ItemDisplay display = origin.getWorld().spawn(origin, ItemDisplay.class, spawned -> {
            spawned.setItemStack(definition.createItem());
            spawned.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            spawned.setTransformation(new Transformation(
                    new org.joml.Vector3f(),
                    new Quaternionf(anchor.rotation()),
                    new org.joml.Vector3f(1, 1, 1),
                    new Quaternionf()));
            spawned.setPersistent(false);
        });
        placedCups.put(instanceId, new PlacedCup(instanceId, display,
                definition.createItem(),
                frames == null ? null : new ItemFrameAnimator(frames)));
        return instanceId;
    }

    /** Removes a placed cup (its pack display and its Core instance). */
    public boolean remove(UUID cupId) {
        PlacedCup cup = placedCups.remove(cupId);
        if (cup == null) {
            return false;
        }
        cup.display().remove();
        context.virtualBlockManager().removeModel(cup.instanceId());
        return true;
    }

    /**
     * Core hook: any removal path (player breaks a lattice cell in legacy
     * worlds, area clear, explicit remove) tears the pack display down with the
     * instance, so a placed cup can never outlive its anchor.
     */
    @EventHandler
    public void onInstanceRemove(com.mineplus.infrastructure.api.event.VirtualBlockInstanceRemoveEvent event) {
        PlacedCup cup = placedCups.remove(event.getInstanceId());
        if (cup != null) {
            cup.display().remove();
        }
    }

    public boolean isPlaced(UUID cupId) {
        return placedCups.containsKey(cupId);
    }

    /** Starts a clip on the placed cup; steam clips loop, others play once. */
    public boolean playOnCup(UUID cupId, String clip) {
        PlacedCup cup = placedCups.get(cupId);
        if (cup == null || cup.animator() == null) {
            return false;
        }
        String registered = clip;
        if (clip.equalsIgnoreCase("steam_idle")) {
            registered = TeaKeys.ANIM_STEAM;
        } else if (clip.equalsIgnoreCase("drink_firstperson")) {
            registered = TeaKeys.ANIM_DRINK;
        }
        return cup.animator().start(registered);
    }

    public void stopCup(UUID cupId) {
        PlacedCup cup = placedCups.get(cupId);
        if (cup != null && cup.animator() != null) {
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
        if (!isTea(event.getItem())) {
            return;
        }
        // The backing material is a potion: without this the client starts the
        // vanilla drink animation and the model disappears into the drinking
        // pose, and on a block the cup would try to place it.
        event.setCancelled(true);
        startDrink(event.getPlayer());
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isTea(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        held.remove(event.getPlayer().getUniqueId());
    }

    /* ------------------------------------------------------------------ */
    /* Frame ticking                                                       */

    private void tick() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            ItemStack heldStack = player.getInventory().getItemInMainHand();
            if (isTea(heldStack)) {
                tickHeld(player, heldStack);
            } else {
                // Stopped holding the tea: drop the held session and cancel the
                // cooldown clock, otherwise the overlay keeps sweeping over
                // every potion left in the inventory.
                if (held.remove(player.getUniqueId()) != null) {
                    player.setCooldown(definition.backingMaterial(), 0);
                }
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
            ItemStack frame = stackAt(cup.stack(), state);
            if (frame != cup.stack()) {
                cup.display().setItemStack(frame);
            }
        }
    }

    private void tickHeld(Player player, ItemStack stack) {
        if (frames == null || definition.plan().isEmpty()) {
            return;
        }
        Held session = held.computeIfAbsent(player.getUniqueId(), id -> new Held());
        Material material = definition.backingMaterial();
        switch (session.mode) {
            case NONE -> {
                // Seat the stack at rest once; the steam sequence is gated on
                // rest, so the client walks it on the cooldown's clock.
                writeFrame(player, stack, restState());
                player.setCooldown(material, 0);
                player.setCooldown(material, steamTicks);
                session.mode = Mode.STEAM;
                session.remaining = steamTicks;
            }
            case STEAM -> {
                session.remaining--;
                if (session.remaining <= 0 && player.getCooldown(material) <= 1) {
                    player.setCooldown(material, steamTicks);
                    session.remaining = steamTicks;
                }
            }
            case DRINK -> {
                session.remaining--;
                if (session.remaining <= 0) {
                    writeFrame(player, stack, restState());
                    player.setCooldown(material, steamTicks);
                    session.mode = Mode.STEAM;
                    session.remaining = steamTicks;
                }
            }
        }
    }

    /** Plays the drink clip on the held cup, client-side. */
    private void startDrink(Player player) {
        if (frames == null || definition.plan().isEmpty()) {
            return;
        }
        Held session = held.get(player.getUniqueId());
        if (session != null && session.mode == Mode.DRINK) {
            return; // already drinking
        }
        ClipPlan plan = definition.plan();
        Sequence drink = plan == null ? null : plan.sequence("reload");
        if (drink == null) {
            return;
        }
        ItemStack stack = player.getInventory().getItemInMainHand();
        Material material = definition.backingMaterial();
        // Drop any running steam cooldown first so the client is back on the
        // stack state, then move that state onto the drink's gate and arm it.
        player.setCooldown(material, 0);
        writeFrame(player, stack, definition.frameStackValue(drink.gateState()));
        player.setCooldown(material, drinkTicks);
        if (session == null) {
            session = new Held();
            held.put(player.getUniqueId(), session);
        }
        session.mode = Mode.DRINK;
        session.remaining = drinkTicks;
    }

    private int restState() {
        return definition.frameStackValue(0);
    }

    private void writeFrame(Player player, ItemStack stack, int value) {
        if (ItemFrameWriter.read(stack) != value) {
            ItemFrameWriter.apply(stack, value);
            player.getInventory().setItemInMainHand(stack);
        }
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
