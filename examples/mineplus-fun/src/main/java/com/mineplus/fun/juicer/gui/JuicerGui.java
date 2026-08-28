package com.mineplus.fun.juicer.gui;

import com.mineplus.fun.juicer.JuicerKeys;
import com.mineplus.infrastructure.core.gui.InfrastructureGui;
import com.mineplus.infrastructure.core.gui.InteractiveInfrastructureGui;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleManager;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import com.mineplus.infrastructure.core.recipes.MachineRecipe;
import com.mineplus.infrastructure.core.recipes.RecipeManager;
import com.mineplus.infrastructure.registry.ItemRegistry;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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

public final class JuicerGui implements InfrastructureGui, InteractiveInfrastructureGui {

    private static final int INPUT_SLOT = 11;
    private static final int OUTPUT_SLOT = 15;
    private static final int CRAFT_SLOT = 13;
    private static final int UPGRADE_SLOT = 22;
    private static final int SIZE = 27;

    private final JavaPlugin plugin;
    private final MultiBlockRegistry registry;
    private final MultiBlockLifecycleManager lifecycleManager;
    private final RecipeManager recipeManager;
    private final ItemRegistry itemRegistry;
    private final Map<UUID, StoredContents> machineContents;

    public JuicerGui(
            JavaPlugin plugin,
            MultiBlockRegistry registry,
            MultiBlockLifecycleManager lifecycleManager,
            RecipeManager recipeManager,
            ItemRegistry itemRegistry
    ) {
        this.plugin = plugin;
        this.registry = registry;
        this.lifecycleManager = lifecycleManager;
        this.recipeManager = recipeManager;
        this.itemRegistry = itemRegistry;
        this.machineContents = new LinkedHashMap<>();
    }

    @Override
    public void open(Player player, MultiBlockInstance instance) {
        String title = ChatColor.DARK_GREEN + "Juicer Lv." + instance.level();
        Inventory inventory = Bukkit.createInventory(player, SIZE, title);
        fillLayout(inventory, instance);

        StoredContents contents = machineContents.get(instance.id());
        if (contents != null) {
            if (contents.input() != null) {
                inventory.setItem(INPUT_SLOT, contents.input().clone());
            }
            if (contents.output() != null) {
                inventory.setItem(OUTPUT_SLOT, contents.output().clone());
            }
        }

        player.openInventory(inventory);
    }

    @Override
    public void onClick(Player player, UUID instanceId, InventoryClickEvent event) {
        MultiBlockInstance instance = registry.getInstance(instanceId);
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

        if (rawSlot == INPUT_SLOT) {
            scheduleStateCapture(instanceId, top);
            return;
        }

        if (rawSlot == OUTPUT_SLOT) {
            if (event.getCursor() != null && event.getCursor().getType() != Material.AIR) {
                event.setCancelled(true);
            } else {
                scheduleStateCapture(instanceId, top);
            }
            return;
        }

        if (rawSlot == CRAFT_SLOT) {
            event.setCancelled(true);
            craft(player, instance, top);
            return;
        }

        if (rawSlot == UPGRADE_SLOT) {
            event.setCancelled(true);
            boolean upgraded = lifecycleManager.upgrade(instance.id(), player);
            if (upgraded) {
                MultiBlockInstance refreshed = registry.getInstance(instance.id());
                if (refreshed != null) {
                    open(player, refreshed);
                }
                player.sendMessage(ChatColor.GREEN + "Juicer upgraded to level " + instance.level() + ".");
            } else {
                player.sendMessage(ChatColor.RED + "Upgrade failed. Check required materials.");
            }
            return;
        }

        event.setCancelled(true);
    }

    @Override
    public void onDrag(Player player, UUID instanceId, InventoryDragEvent event) {
        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize && slot != INPUT_SLOT) {
                event.setCancelled(true);
                return;
            }
        }
        scheduleStateCapture(instanceId, event.getView().getTopInventory());
    }

    @Override
    public void onClose(Player player, UUID instanceId, InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        ItemStack input = copyOrNull(top.getItem(INPUT_SLOT));
        ItemStack output = copyOrNull(top.getItem(OUTPUT_SLOT));
        machineContents.put(instanceId, new StoredContents(input, output));
    }

    private void craft(Player player, MultiBlockInstance instance, Inventory inventory) {
        ItemStack input = inventory.getItem(INPUT_SLOT);
        if (input == null || input.getType().isAir()) {
            player.sendMessage(ChatColor.YELLOW + "Insert fruit in the input slot first.");
            return;
        }

        String inputKey = resolveItemKey(input);
        if (inputKey == null) {
            player.sendMessage(ChatColor.RED + "That item cannot be juiced.");
            return;
        }

        MachineRecipe recipe = recipeManager.findMatch(
                JuicerKeys.MACHINE_ID,
                instance.level(),
                Map.of(inputKey, input.getAmount())
        );
        if (recipe == null || recipe.output().isEmpty() || recipe.input().isEmpty()) {
            player.sendMessage(ChatColor.RED + "No matching juicer recipe.");
            return;
        }

        Map.Entry<String, Integer> requiredInput = recipe.input().entrySet().iterator().next();
        int requiredAmount = Math.max(1, requiredInput.getValue());
        if (input.getAmount() < requiredAmount) {
            player.sendMessage(ChatColor.RED + "Not enough input items.");
            return;
        }

        Map.Entry<String, Integer> result = recipe.output().entrySet().iterator().next();
        ItemStack outputItem = createResult(result.getKey(), result.getValue());
        if (outputItem == null) {
            player.sendMessage(ChatColor.RED + "Recipe output item is not registered: " + result.getKey());
            return;
        }

        if (!canInsertOutput(inventory, outputItem)) {
            player.sendMessage(ChatColor.RED + "Output slot is full or incompatible.");
            return;
        }

        input.setAmount(input.getAmount() - requiredAmount);
        if (input.getAmount() <= 0) {
            inventory.setItem(INPUT_SLOT, null);
        } else {
            inventory.setItem(INPUT_SLOT, input);
        }

        mergeOutput(inventory, outputItem);
        machineContents.put(instance.id(), new StoredContents(
                copyOrNull(inventory.getItem(INPUT_SLOT)),
                copyOrNull(inventory.getItem(OUTPUT_SLOT))
        ));
        player.sendMessage(ChatColor.GREEN + "Juicing complete.");
    }

    private void fillLayout(Inventory inventory, MultiBlockInstance instance) {
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        inventory.setItem(INPUT_SLOT, null);
        inventory.setItem(OUTPUT_SLOT, null);
        inventory.setItem(CRAFT_SLOT, named(Material.LIME_DYE, ChatColor.GREEN + "Process"));
        inventory.setItem(UPGRADE_SLOT, buildUpgradeButton(instance));
    }

    private ItemStack buildUpgradeButton(MultiBlockInstance instance) {
        MultiBlockType type = registry.getType(instance.typeId());
        if (type == null || type.level(instance.level() + 1) == null) {
            return named(Material.BARRIER, ChatColor.RED + "Max Level");
        }

        var next = type.level(instance.level() + 1);
        ItemStack item = new ItemStack(Material.ANVIL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Upgrade to Lv." + (instance.level() + 1));
        java.util.List<String> lore = new java.util.ArrayList<>();
        lore.add(ChatColor.GRAY + "Cost:");
        for (Map.Entry<String, Integer> entry : next.upgradeCost().entrySet()) {
            lore.add(ChatColor.YELLOW + "- " + entry.getValue() + "x " + entry.getKey());
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private String resolveItemKey(ItemStack stack) {
        String custom = itemRegistry.readItemKey(stack);
        if (custom != null) {
            return custom;
        }
        return "minecraft:" + stack.getType().name().toLowerCase(Locale.ROOT);
    }

    private ItemStack createResult(String key, int amount) {
        ItemStack custom = itemRegistry.createItem(key);
        if (custom != null) {
            custom.setAmount(Math.max(1, amount));
            return custom;
        }

        Material material = resolveMaterial(key);
        if (material == null || material.isAir()) {
            return null;
        }
        return new ItemStack(material, Math.max(1, amount));
    }

    private Material resolveMaterial(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.toLowerCase(Locale.ROOT).trim();
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        return Material.matchMaterial(normalized.toUpperCase(Locale.ROOT));
    }

    private boolean canInsertOutput(Inventory inventory, ItemStack outputItem) {
        ItemStack existing = inventory.getItem(OUTPUT_SLOT);
        if (existing == null || existing.getType().isAir()) {
            return true;
        }
        if (!existing.isSimilar(outputItem)) {
            return false;
        }
        return existing.getAmount() + outputItem.getAmount() <= existing.getMaxStackSize();
    }

    private void mergeOutput(Inventory inventory, ItemStack outputItem) {
        ItemStack existing = inventory.getItem(OUTPUT_SLOT);
        if (existing == null || existing.getType().isAir()) {
            inventory.setItem(OUTPUT_SLOT, outputItem);
            return;
        }
        existing.setAmount(existing.getAmount() + outputItem.getAmount());
        inventory.setItem(OUTPUT_SLOT, existing);
    }

    private void scheduleStateCapture(UUID instanceId, Inventory inventory) {
        Bukkit.getScheduler().runTask(plugin, () -> machineContents.put(instanceId, new StoredContents(
                copyOrNull(inventory.getItem(INPUT_SLOT)),
                copyOrNull(inventory.getItem(OUTPUT_SLOT))
        )));
    }

    private ItemStack copyOrNull(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private record StoredContents(ItemStack input, ItemStack output) {
    }
}
