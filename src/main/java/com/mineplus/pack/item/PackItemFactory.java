package com.mineplus.pack.item;

import com.mineplus.pack.PackFormat;
import com.mineplus.pack.asset.ItemModelAsset;
import com.mineplus.util.DebugLogger;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Creates the Bukkit {@link ItemStack} for pack items — the single place a
 * {@code ContentDefinition} becomes a Minecraft representation. Applies the
 * modern {@code item_model} component (resolved reflectively so one jar
 * serves both pre- and post-1.21.4 APIs) or the legacy
 * {@code custom_model_data} metadata, plus the optional display name.
 *
 * <p>Recognition flows exclusively through the existing {@code ItemRegistry}
 * PDC identity — never display names, lore, or texture paths.</p>
 */
public final class PackItemFactory {

    private static final MethodHandle SET_ITEM_MODEL = resolveSetItemModel();

    private final PackFormat.ItemRepresentation representation;

    public PackItemFactory(PackFormat.ItemRepresentation representation) {
        this.representation = representation;
    }

    /** The stack for one definition: backing material + model component + display name. */
    public ItemStack create(PackItemDefinition definition) {
        ItemStack stack = new ItemStack(definition.backingMaterial());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        if (!definition.displayName().isBlank()) {
            meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', definition.displayName()));
        }
        if (!definition.descriptionLines().isEmpty()) {
            java.util.List<String> lore = new java.util.ArrayList<>(definition.descriptionLines().size());
            for (String line : definition.descriptionLines()) {
                lore.add(org.bukkit.ChatColor.translateAlternateColorCodes('&', line));
            }
            meta.setLore(lore);
        }

        boolean modern = representation == PackFormat.ItemRepresentation.MODERN_ITEM_MODEL;
        boolean applied = modern && applyItemModelComponent(meta, itemModelKey(definition));
        if (!applied) {
            // Legacy fallback: every item owns the same stable base predicate
            // (geometry models, raw models and framed items all serialize an
            // override at this value; a frame state adds its index to it).
            meta.setCustomModelData(ItemModelAsset.frameBase(definition.namespace(), definition.id()));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * The display stack for a registered item asset (world rendering path):
     * backing material + model component + display name, per the active
     * representation strategy.
     */
    public ItemStack create(ItemModelAsset item) {
        ItemStack stack = new ItemStack(item.backingMaterial());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        if (!item.displayName().isBlank()) {
            meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', item.displayName()));
        }
        boolean modern = representation == PackFormat.ItemRepresentation.MODERN_ITEM_MODEL;
        boolean applied = modern && applyItemModelComponent(meta, assetModelKey(item));
        if (!applied) {
            meta.setCustomModelData(item.customModelData());
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * The display stack for a pack model at an arbitrary key path — the world
     * rendering path for models that are not item definitions (an entity rig's
     * per-part models live under {@code item/entity/...}, whose path contains
     * a slash and therefore cannot be an {@link ItemModelAsset}).
     *
     * <p>The modern representation names the model in the {@code item_model}
     * component. The legacy representation can only reach a model through a
     * reserved {@code custom_model_data} host override, which the caller
     * registers through {@code PackApi#registerDisplayModel} and resolves via
     * {@link #create(ItemModelAsset)} — an arbitrary hash of the path would be
     * a predicate no host file dispatches, so a legacy caller that comes here
     * gets {@code null} and keeps whatever it was rendering.</p>
     *
     * @param material        the invisible carrier item
     * @param namespace       model namespace
     * @param componentPath   {@code item_model} path, slashes allowed
     * @return the carrier stack with the model applied, or {@code null} when
     *         this server cannot present that path
     */
    public ItemStack createModelDisplay(org.bukkit.Material material, String namespace, String componentPath) {
        if (!supportsItemModelComponent()) {
            return null;
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        try {
            if (!applyItemModelComponent(meta, new NamespacedKey(namespace, componentPath))) {
                return null;
            }
        } catch (IllegalArgumentException invalidKey) {
            return null;
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /** True when this server exposes the modern {@code item_model} component. */
    public boolean supportsItemModelComponent() {
        return representation == PackFormat.ItemRepresentation.MODERN_ITEM_MODEL && SET_ITEM_MODEL != null;
    }

    /**
     * The {@code item_model} component value for a definition — the asset
     * identity {@code <namespace>:<id>} that also keys the generated
     * {@code assets/<ns>/items/<id>.json}.
     *
     * <p>The component resolves to {@code assets/<ns>/items/<path>.json}, so
     * the path is the item id alone: the {@code item/} segment belongs to the
     * <em>model</em> namespace ({@code assets/<ns>/models/item/<id>.json}),
     * not to the item definition. Pointing the component at
     * {@code ns:item/<id>} resolves to a file that does not exist, and the
     * client silently falls back to the vanilla carrier — no custom model, no
     * frames.</p>
     */
    @SuppressWarnings("deprecation")
    static NamespacedKey itemModelKey(PackItemDefinition definition) {
        return new NamespacedKey(definition.namespace(), definition.id());
    }

    /** Same component value for a registered asset. */
    @SuppressWarnings("deprecation")
    static NamespacedKey assetModelKey(ItemModelAsset item) {
        return new NamespacedKey(item.namespace(), item.itemId());
    }

    /** Reflectively applies {@code ItemMeta#setItemModel(NamespacedKey)} (Paper 1.21.2+). */
    private static boolean applyItemModelComponent(ItemMeta meta, NamespacedKey key) {
        MethodHandle handle = SET_ITEM_MODEL;
        if (handle == null) {
            return false;
        }
        try {
            handle.invokeExact(meta, key);
            return true;
        } catch (Throwable failure) {
            DebugLogger.warning("[PackItemFactory] setItemModel failed, using custom model data: "
                    + failure.getMessage());
            return false;
        }
    }

    private static MethodHandle resolveSetItemModel() {
        try {
            return MethodHandles.lookup().findVirtual(ItemMeta.class, "setItemModel",
                    MethodType.methodType(void.class, NamespacedKey.class));
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }
}