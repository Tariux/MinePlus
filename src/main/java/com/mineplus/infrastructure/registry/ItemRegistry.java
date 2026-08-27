package com.mineplus.infrastructure.registry;

import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.definition.ItemDefinition;
import com.mineplus.infrastructure.core.util.StringNormalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class ItemRegistry {

    private final NamespacedKey managedItemKey;
    private final NamespacedKey linkedBlockKey;
    private final Map<String, ItemDefinition> definitions;
    private final List<String> registeredKeys;

    public ItemRegistry(MineplusPlugin plugin) {
        this.managedItemKey = new NamespacedKey(plugin, "managed_item_id");
        this.linkedBlockKey = new NamespacedKey(plugin, "linked_block_id");
        this.definitions = new LinkedHashMap<>();
        this.registeredKeys = new ArrayList<>();
    }

    public void register(ItemDefinition definition) {
        String key = normalize(definition.key());
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Item key cannot be empty");
        }

        definitions.put(key, definition);
        refreshKeyCache();
    }

    public ItemStack createItem(String key) {
        String normalizedKey = normalize(key);
        ItemDefinition definition = definitions.get(normalizedKey);
        if (definition == null) {
            return null;
        }

        ItemStack item = definition.createItem().clone();
        if (!item.hasItemMeta()) {
            return item;
        }

        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(managedItemKey, PersistentDataType.STRING, normalizedKey);

        String linked = normalize(definition.linkedBlockKey());
        if (!linked.isEmpty()) {
            meta.getPersistentDataContainer().set(linkedBlockKey, PersistentDataType.STRING, linked);
        }

        item.setItemMeta(meta);
        return item;
    }

    public String readItemKey(ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = itemStack.getItemMeta();
        String key = meta.getPersistentDataContainer().get(managedItemKey, PersistentDataType.STRING);
        if (key == null || !definitions.containsKey(key)) {
            return null;
        }

        return key;
    }

    public String readLinkedBlockKey(ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) {
            return null;
        }

        String value = itemStack.getItemMeta().getPersistentDataContainer().get(linkedBlockKey, PersistentDataType.STRING);
        return value == null || value.isBlank() ? null : value;
    }

    public ItemDefinition getDefinition(String key) {
        return definitions.get(normalize(key));
    }

    public Map<String, ItemDefinition> getDefinitions() {
        return Collections.unmodifiableMap(definitions);
    }

    public List<String> getRegisteredKeys() {
        return Collections.unmodifiableList(registeredKeys);
    }

    private void refreshKeyCache() {
        registeredKeys.clear();
        registeredKeys.addAll(definitions.keySet());
    }

    private String normalize(String value) {
        return StringNormalizer.normalize(value);
    }
}
