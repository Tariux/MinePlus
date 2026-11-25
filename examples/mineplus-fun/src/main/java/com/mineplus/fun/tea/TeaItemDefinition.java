package com.mineplus.fun.tea;

import com.mineplus.infrastructure.definition.ItemCategory;
import com.mineplus.infrastructure.definition.ItemDefinition;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** The tea cup item handed out by {@code /tea give}. */
public final class TeaItemDefinition implements ItemDefinition {

    @Override
    public String key() {
        return TeaKeys.TEA_ITEM;
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(Material.POTION);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Tea Cup");
        meta.setLore(List.of(
                ChatColor.GRAY + "Freshly brewed, still steaming.",
                ChatColor.DARK_GRAY + "Spawn the cup with /tea spawn, then /tea play <animation>"
        ));
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public ItemCategory category() {
        return ItemCategory.UTILITY;
    }
}
