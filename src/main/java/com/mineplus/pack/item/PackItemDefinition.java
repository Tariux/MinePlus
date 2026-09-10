package com.mineplus.pack.item;

import com.mineplus.infrastructure.definition.ItemCategory;
import com.mineplus.infrastructure.definition.ItemDefinition;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * A custom item as Mineplus content: Mineplus identity
 * ({@code namespace:id}), a backing vanilla material (implementation detail
 * only — vanilla items never carry Mineplus state), and the registered model
 * that renders it. Implements the existing {@link ItemDefinition} contract, so
 * pack items flow through the existing {@code ItemRegistry} identity and
 * recognition (PDC) like every other Mineplus item.
 *
 * <p>Armor is expressed through {@link #equipmentSlot}: the same identity,
 * model and texture pipeline, with equipment semantics preserved instead of
 * being flattened into "an ordinary item".</p>
 */
public final class PackItemDefinition implements ItemDefinition {

    private final String namespace;
    private final String id;
    private final Material backingMaterial;
    private final String modelKey;
    private final String displayName;
    private final ItemCategory category;
    private final String linkedBlockKey;
    private final org.bukkit.inventory.EquipmentSlot equipmentSlot;
    private final List<String> descriptionLines;

    /** Assigned by {@code PackApi} during registration; internal wiring. */
    private volatile PackItemFactory factory;

    public PackItemDefinition(
            String namespace,
            String id,
            Material backingMaterial,
            String modelKey,
            String displayName,
            ItemCategory category,
            String linkedBlockKey,
            org.bukkit.inventory.EquipmentSlot equipmentSlot,
            List<String> descriptionLines
    ) {
        this.namespace = normalizeToken(namespace);
        this.id = normalizeToken(id);
        this.backingMaterial = Objects.requireNonNull(backingMaterial, "backingMaterial");
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey").trim().toLowerCase(Locale.ROOT);
        this.displayName = displayName == null ? "" : displayName;
        this.category = category == null ? ItemCategory.UTILITY : category;
        this.linkedBlockKey = linkedBlockKey == null ? "" : linkedBlockKey;
        this.equipmentSlot = equipmentSlot;
        this.descriptionLines = descriptionLines == null ? List.of() : List.copyOf(descriptionLines);
    }

    private static String normalizeToken(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || !normalized.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid pack item token: '" + value + "'");
        }
        return normalized;
    }

    /** Builder for the common case. */
    public static Builder builder(String namespace, String id, Material backingMaterial, String modelKey) {
        return new Builder(namespace, id, backingMaterial, modelKey);
    }

    public String namespace() {
        return namespace;
    }

    public String id() {
        return id;
    }

    /** Logical Mineplus identity, e.g. {@code weapons:plasma_rifle}. */
    public String contentId() {
        return namespace + ":" + id;
    }

    public Material backingMaterial() {
        return backingMaterial;
    }

    /** Key of the registered virtual model this item renders. */
    public String modelKey() {
        return modelKey;
    }

    public org.bukkit.inventory.EquipmentSlot equipmentSlot() {
        return equipmentSlot;
    }

    @Override
    public String key() {
        return contentId();
    }

    @Override
    public ItemStack createItem() {
        PackItemFactory bound = factory;
        if (bound != null) {
            return bound.create(this);
        }
        // Vanilla fallback (subsystem disabled): plain backing item, but the
        // display name still reads as the custom content.
        ItemStack stack = new ItemStack(backingMaterial);
        if (!displayName.isBlank()) {
            org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(displayName);
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    @Override
    public ItemCategory category() {
        return category;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public List<String> descriptionLines() {
        return descriptionLines;
    }

    @Override
    public String linkedBlockKey() {
        return linkedBlockKey;
    }

    /** Internal: invoked by {@code PackApi} during registration. */
    public void bindFactory(PackItemFactory bound) {
        this.factory = bound;
    }

    public static final class Builder {
        private final String namespace;
        private final String id;
        private final Material backingMaterial;
        private final String modelKey;
        private String displayName = "";
        private ItemCategory category = ItemCategory.UTILITY;
        private String linkedBlockKey = "";
        private org.bukkit.inventory.EquipmentSlot equipmentSlot;
        private List<String> descriptionLines = List.of();

        private Builder(String namespace, String id, Material backingMaterial, String modelKey) {
            this.namespace = namespace;
            this.id = id;
            this.backingMaterial = backingMaterial;
            this.modelKey = modelKey;
        }

        public Builder displayName(String value) {
            this.displayName = value == null ? "" : value;
            return this;
        }

        public Builder category(ItemCategory value) {
            this.category = value;
            return this;
        }

        public Builder linkedBlockKey(String value) {
            this.linkedBlockKey = value == null ? "" : value;
            return this;
        }

        public Builder equipmentSlot(org.bukkit.inventory.EquipmentSlot value) {
            this.equipmentSlot = value;
            return this;
        }

        public Builder descriptionLines(List<String> value) {
            this.descriptionLines = value;
            return this;
        }

        public PackItemDefinition build() {
            return new PackItemDefinition(namespace, id, backingMaterial, modelKey,
                    displayName, category, linkedBlockKey, equipmentSlot, descriptionLines);
        }
    }
}
