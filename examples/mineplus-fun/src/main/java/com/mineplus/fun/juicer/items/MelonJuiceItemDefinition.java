package com.mineplus.fun.juicer.items;

import com.mineplus.fun.juicer.JuicerKeys;
import com.mineplus.infrastructure.definition.ItemCategory;
import com.mineplus.infrastructure.definition.ItemDefinition;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class MelonJuiceItemDefinition implements ItemDefinition {

    @Override
    public String key() {
        return JuicerKeys.MELON_JUICE_ITEM;
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(Material.POTION);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GREEN + "Melon Juice");
        meta.setLore(List.of(
                ChatColor.GRAY + "Hydrating and energizing.",
                ChatColor.GREEN + "+2 health",
                ChatColor.AQUA + "Speed (10s)"
        ));
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public ItemCategory category() {
        return ItemCategory.UTILITY;
    }
}
