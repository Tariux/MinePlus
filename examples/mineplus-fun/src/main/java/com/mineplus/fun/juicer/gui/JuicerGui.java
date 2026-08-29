package com.mineplus.fun.juicer.gui;

import com.mineplus.fun.juicer.JuicerKeys;
import com.mineplus.infrastructure.core.gui.AbstractMachineGui;
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
import java.util.Set;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Juicer machine menu built on the Core's {@link AbstractMachineGui}: the base
 * class owns slot guarding (cursor, hotbar swaps, drags), shift-click
 * cancellation, and capture-on-next-tick; this class only defines layout,
 * container topology, and the craft/upgrade behavior.
 */
public final class JuicerGui extends AbstractMachineGui {

    private static final int INPUT_SLOT = 11;
    private static final int OUTPUT_SLOT = 15;
    private static final int CRAFT_SLOT = 13;
    private static final int UPGRADE_SLOT = 22;
    private static final int SIZE = 27;

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
        super(plugin, registry, SIZE);
        this.lifecycleManager = lifecycleManager;
        this.recipeManager = recipeManager;
        this.itemRegistry = itemRegistry;
        this.machineContents = new LinkedHashMap<>();
    }

    @Override
    protected String title(MultiBlockInstance instance) {
        return ChatColor.DARK_GREEN + "Juicer Lv." + instance.level();
    }

    @Override
    protected void layout(Inventory inventory, MultiBlockInstance instance) {
        fill(inventory, fillerPane());

        StoredContents contents = machineContents.get(instance.id());
        if (contents != null) {
            if (contents.input() != null) {
                inventory.setItem(INPUT_SLOT, contents.input().clone());
            }
            if (contents.output() != null) {
                inventory.setItem(OUTPUT_SLOT, contents.output().clone());
            }
        }

        inventory.setItem(CRAFT_SLOT, named(Material.LIME_DYE, ChatColor.GREEN + "Process"));
        inventory.setItem(UPGRADE_SLOT, buildUpgradeButton(instance));
    }

    @Override
    protected Set<Integer> containerSlots() {
        return Set.of(INPUT_SLOT, OUTPUT_SLOT);
    }

    @Override
    protected Set<Integer> takeOnlySlots() {
        return Set.of(OUTPUT_SLOT);
    }

    @Override
    protected void onButtonClick(Player player, MultiBlockInstance instance, int slot, InventoryClickEvent event) {
        if (slot == CRAFT_SLOT) {
            craft(player, instance, event.getView().getTopInventory());
            return;
        }

        if (slot == UPGRADE_SLOT) {
            boolean upgraded = lifecycleManager.upgrade(instance.id(), player);
            if (upgraded) {
                MultiBlockInstance refreshed = instance(instance.id());
                if (refreshed != null) {
                    open(player, refreshed);
                }
                player.sendMessage(ChatColor.GREEN + "Juicer upgraded to level " + instance.level() + ".");
            } else {
                player.sendMessage(ChatColor.RED + "Upgrade failed. Check required materials.");
            }
        }
    }

    @Override
    protected void capture(Player player, MultiBlockInstance instance, Inventory inventory) {
        machineContents.put(instance.id(), new StoredContents(
                copyOrNull(inventory.getItem(INPUT_SLOT)),
                copyOrNull(inventory.getItem(OUTPUT_SLOT))
        ));
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

    private ItemStack buildUpgradeButton(MultiBlockInstance instance) {
        MultiBlockType type = type(instance);
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

    private ItemStack copyOrNull(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private record StoredContents(ItemStack input, ItemStack output) {
    }
}
