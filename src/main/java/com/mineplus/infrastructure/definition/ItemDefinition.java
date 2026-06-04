package com.mineplus.infrastructure.definition;

import java.util.List;
import org.bukkit.inventory.ItemStack;

public interface ItemDefinition {

    String key();

    ItemStack createItem();

    default ItemCategory category() {
        return ItemCategory.UTILITY;
    }

    default String displayName() {
        return key();
    }

    default List<String> descriptionLines() {
        return List.of();
    }

    default String linkedBlockKey() {
        return "";
    }

    default String recipeId() {
        return "";
    }
}
