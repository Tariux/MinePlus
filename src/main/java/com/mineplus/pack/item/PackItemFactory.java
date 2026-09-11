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
            meta.setDisplayName(definition.displayName());
        }

        boolean modern = representation == PackFormat.ItemRepresentation.MODERN_ITEM_MODEL;
        boolean applied = modern && applyItemModelComponent(meta, itemModelKey(definition));
        if (!applied) {
            meta.setCustomModelData(stableCustomModelData(definition));
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
            meta.setDisplayName(item.displayName());
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
     * The {@code item_model} component value for a definition — the asset
     * identity {@code <namespace>:item/<id>} that also keys the generated
     * {@code assets/<ns>/items/<id>.json}.
     */
    @SuppressWarnings("deprecation")
    static NamespacedKey itemModelKey(PackItemDefinition definition) {
        return new NamespacedKey(definition.namespace(), "item/" + definition.id());
    }

    /** Same component value for a registered asset. */
    @SuppressWarnings("deprecation")
    static NamespacedKey assetModelKey(ItemModelAsset item) {
        return new NamespacedKey(item.namespace(), item.path());
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

    /**
     * Legacy predicate value, stable per item id (matches
     * {@link ItemModelAsset#customModelData()}): never zero — zero would match
     * every vanilla item — and deterministic across restarts so issued items
     * keep rendering. {@code floorMod} avoids the {@code Math.abs(Integer#MIN_VALUE)}
     * overflow that would produce an invalid negative predicate.
     */
    static int stableCustomModelData(PackItemDefinition definition) {
        int hash = (definition.namespace() + ":" + definition.id()).hashCode();
        return 1000 + Math.floorMod(hash, 900_000);
    }
}
