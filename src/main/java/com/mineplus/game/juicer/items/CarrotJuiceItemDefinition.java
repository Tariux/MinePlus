package com.mineplus.game.juicer.items;

import com.mineplus.game.juicer.JuicerKeys;
import com.mineplus.infrastructure.definition.ItemCategory;
import com.mineplus.infrastructure.definition.ItemDefinition;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class CarrotJuiceItemDefinition implements ItemDefinition {

    @Override
    public String key() {
        return JuicerKeys.CARROT_JUICE_ITEM;
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(Material.POTION);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Carrot Juice");
        meta.setLore(List.of(
                ChatColor.GRAY + "Freshly pressed vitamin burst.",
                ChatColor.GREEN + "+3 health",
                ChatColor.AQUA + "Speed (5s)"
        ));
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public ItemCategory category() {
        return ItemCategory.UTILITY;
    }
}
