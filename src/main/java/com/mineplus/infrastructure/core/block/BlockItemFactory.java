package com.mineplus.infrastructure.core.block;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Builds and reads the placeable item for a block definition. */
public final class BlockItemFactory {

    private final NamespacedKey blockKey;

    public BlockItemFactory(Plugin plugin) {
        this.blockKey = new NamespacedKey(plugin, "block_id");
    }

    public NamespacedKey key() {
        return blockKey;
    }

    public ItemStack create(BlockDefinition definition, int amount) {
        Material material = Material.matchMaterial(definition.itemMaterial());
        ItemStack stack = new ItemStack(material == null ? Material.PAPER : material,
                Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.setDisplayName(ChatColor.RESET + definition.displayName());
        meta.getPersistentDataContainer().set(blockKey, PersistentDataType.STRING, definition.id());
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + definition.id());
        lore.add(ChatColor.GRAY + String.valueOf(definition.breaking().hardness()) + "s"
                + " · " + definition.breaking().tool().tier() + " "
                + definition.breaking().tool().type());
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public String readId(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(blockKey, PersistentDataType.STRING);
    }
}
