package com.mineplus.infrastructure.core.gui;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Base class for machine GUIs that eliminates the interaction-guard boilerplate
 * every module previously re-implemented.
 *
 * <p>The base owns the parts that are identical across all machine menus:
 * <ul>
 *   <li>top vs bottom raw-slot routing, including shift-click cancellation
 *       from the player inventory;</li>
 *   <li>click cancellation on every non-container top slot (buttons, filler)
 *       with dispatch to {@link #onButtonClick};</li>
 *   <li>insertion guards on container slots: the cursor
 *       ({@code event.getCursor()}), hotbar-number swaps
 *       ({@code event.getHotbarButton() >= 0}; the off-hand swap arrives as
 *       button 40), and drags ({@code event.getOldCursor()}) are all routed
 *       through {@link #accepts(int, ItemStack)};</li>
 *   <li>take-only slots (outputs) reject every insertion path;</li>
 *   <li>capture scheduling: the result of a click is not final when the event
 *       fires, so accepted interactions are captured on the next tick via
 *       {@link #captureLater}; close captures immediately.</li>
 * </ul>
 *
 * <p>Subclasses implement layout, container-slot topology, item validation,
 * button behavior, and state capture — nothing else.
 */
public abstract class AbstractMachineGui implements InteractiveInfrastructureGui {

    private final JavaPlugin plugin;
    private final MultiBlockRegistry registry;
    private final int size;

    protected AbstractMachineGui(JavaPlugin plugin, MultiBlockRegistry registry, int size) {
        this.plugin = plugin;
        this.registry = registry;
        this.size = size;
    }

    /** Inventory title shown when the GUI opens. */
    protected abstract String title(MultiBlockInstance instance);

    /** Builds the full slot layout (filler, buttons, restored container contents). */
    protected abstract void layout(Inventory inventory, MultiBlockInstance instance);

    /** Slots holding machine contents that players may interact with; every other top slot is a cancelled button click. */
    protected abstract Set<Integer> containerSlots();

    /** Container slots players may only take from (outputs). Empty by default. */
    protected Set<Integer> takeOnlySlots() {
        return Set.of();
    }

    /**
     * Validates an item that would enter a container slot through any
     * insertion path (cursor, hotbar swap, drag). Rejections are cancelled.
     */
    protected boolean accepts(int slot, ItemStack item) {
        return true;
    }

    /** Handles a click on a non-container top slot. The event is already cancelled. */
    protected abstract void onButtonClick(Player player, MultiBlockInstance instance, int slot, InventoryClickEvent event);

    /** Persists machine contents read from the top inventory. Called on close and one tick after accepted interactions. */
    protected abstract void capture(Player player, MultiBlockInstance instance, Inventory inventory);

    @Override
    public void open(Player player, MultiBlockInstance instance) {
        Inventory inventory = Bukkit.createInventory(player, size, title(instance));
        layout(inventory, instance);
        player.openInventory(inventory);
    }

    @Override
    public final void onClick(Player player, UUID instanceId, InventoryClickEvent event) {
        MultiBlockInstance instance = instance(instanceId);
        if (instance == null) {
            event.setCancelled(true);
            return;
        }

        Inventory top = event.getView().getTopInventory();
        int rawSlot = event.getRawSlot();
        if (rawSlot >= top.getSize()) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }

        if (!containerSlots().contains(rawSlot)) {
            event.setCancelled(true);
            onButtonClick(player, instance, rawSlot, event);
            return;
        }

        if (takeOnlySlots().contains(rawSlot)) {
            boolean placing = (event.getCursor() != null && !event.getCursor().getType().isAir())
                    || event.getHotbarButton() >= 0;
            if (placing) {
                event.setCancelled(true);
                return;
            }
            captureLater(player, instanceId, top);
            return;
        }

        if (!accepts(rawSlot, event.getCursor())) {
            event.setCancelled(true);
            return;
        }

        ItemStack swapCandidate = event.getHotbarButton() >= 0
                ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton())
                : null;
        if (swapCandidate != null && !accepts(rawSlot, swapCandidate)) {
            event.setCancelled(true);
            return;
        }

        captureLater(player, instanceId, top);
    }

    @Override
    public final void onDrag(Player player, UUID instanceId, InventoryDragEvent event) {
        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot >= topSize) {
                continue;
            }
            if (!containerSlots().contains(slot) || takeOnlySlots().contains(slot)) {
                event.setCancelled(true);
                return;
            }
        }

        for (int slot : event.getRawSlots()) {
            if (slot < topSize && !accepts(slot, event.getOldCursor())) {
                event.setCancelled(true);
                return;
            }
        }

        captureLater(player, instanceId, event.getView().getTopInventory());
    }

    @Override
    public final void onClose(Player player, UUID instanceId, InventoryCloseEvent event) {
        MultiBlockInstance instance = instance(instanceId);
        if (instance == null) {
            return;
        }
        capture(player, instance, event.getView().getTopInventory());
        onClosed(player, instance);
    }

    /**
     * Called after {@link #capture} when the player actually closes this GUI —
     * never on the per-interaction {@code captureLater} captures. The override
     * point for close-time side effects (model swaps, door animations).
     */
    protected void onClosed(Player player, MultiBlockInstance instance) {
    }

    /** The owning module plugin, for scheduling and logging. */
    protected final JavaPlugin plugin() {
        return plugin;
    }

    /** Resolves the live instance behind a GUI session, or {@code null} if it was removed. */
    protected final MultiBlockInstance instance(UUID instanceId) {
        return registry.getInstance(instanceId);
    }

    /** Resolves the multiblock type of an instance, or {@code null} if it is no longer registered. */
    protected final MultiBlockType type(MultiBlockInstance instance) {
        return registry.getType(instance.typeId());
    }

    /**
     * Schedules a capture for the next tick, after the accepted interaction
     * has been applied to the inventory.
     */
    protected final void captureLater(Player player, UUID instanceId, Inventory inventory) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            MultiBlockInstance instance = instance(instanceId);
            if (instance != null) {
                capture(player, instance, inventory);
            }
        });
    }

    /** Fills every slot of the inventory with the named filler pane. */
    protected final void fill(Inventory inventory, ItemStack filler) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    /** Builds a single renamed item. */
    protected static ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    /** Standard filler pane used for non-functional slots. */
    protected static ItemStack fillerPane() {
        return named(Material.GRAY_STAINED_GLASS_PANE, ChatColor.RESET + " ");
    }
}
