package com.mineplus.gun.weapon;

import com.mineplus.gun.MineplusGunPlugin;
import com.mineplus.infrastructure.registry.ItemRegistry;
import com.mineplus.infrastructure.registry.ItemState;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;

/**
 * Builds the {@link ItemStack} for a weapon: Core pack-item identity via the
 * pack API, plus the module's runtime state through the Core
 * {@link ItemState} slots (weapon id, live rounds, per-stack identity tag).
 *
 * <p>Lore is the weapon's stat block; the Core content layer supplies the
 * display name already. With {@code PACK.ENABLED: false} the Core returns
 * the vanilla carrier; identity and gameplay still work.</p>
 */
public final class WeaponItemFactory {

    public static final String NAMESPACE = "mineplusgun";

    private final MineplusGunPlugin plugin;
    private final ItemRegistry itemRegistry;
    private final com.mineplus.pack.PackApi packApi;

    private final ItemState weaponState;
    private final ItemState ammoState;
    private final ItemState identityTag;

    public WeaponItemFactory(MineplusGunPlugin plugin, ItemRegistry itemRegistry, com.mineplus.pack.PackApi packApi) {
        this.plugin = plugin;
        this.itemRegistry = itemRegistry;
        this.packApi = packApi;
        this.weaponState = new ItemState(plugin, "weapon");
        this.ammoState = new ItemState(plugin, "ammo");
        this.identityTag = ItemState.identityTag(plugin);
    }

    /** A fresh weapon stack with a full magazine. */
    public ItemStack create(WeaponDefinition definition) {
        ItemStack stack = packApi.createItem(NAMESPACE, definition.id());
        if (stack == null || stack.getType().isAir()) {
            // The pack subsystem is disabled or the item is not registered:
            // degrade to the vanilla carrier. Behaviour is identical; only the
            // custom model is missing.
            if (packApi.isAvailable()) {
                plugin.getLogger().warning("[MineplusGun] Pack item '" + NAMESPACE + ":" + definition.id()
                        + "' is not registered; issuing the vanilla carrier instead.");
            }
            stack = new ItemStack(definition.backingMaterial());
            var meta = stack.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', definition.displayName()));
                stack.setItemMeta(meta);
            }
        }

        weaponState.setString(stack, definition.id());
        identityTag.getOrCreateIdentity(stack);
        if (definition.usesAmmo()) {
            ammoState.set(stack, definition.magazine());
        }

        // The stat block lore is weapon gameplay, not content presentation.
        var meta = stack.getItemMeta();
        if (meta != null) {
            meta.setLore(defaultLore(definition));
            stack.setItemMeta(meta);
        }
        applyInitialFrame(stack, definition);
        return stack;
    }

    /**
     * Framed weapons leave the factory on their loaded state (a fresh magazine
     * is full); the custom-model-data stays unset otherwise, which the pack
     * dispatch reads as the resting idle model.
     */
    private void applyInitialFrame(ItemStack stack, WeaponDefinition definition) {
        if (!definition.hasFrames()) {
            return;
        }
        var frames = definition.frames();
        if (frames.loadedState() > 0 && definition.magazine() > 0) {
            com.mineplus.pack.item.ItemFrameWriter.apply(stack, frames.loadedState());
        }
    }

    private List<String> defaultLore(WeaponDefinition definition) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + definition.type().categoryLabel());
        lore.add(ChatColor.GRAY + "Damage: " + ChatColor.WHITE + format(definition.damage()));
        if (definition.usesAmmo()) {
            lore.add(ChatColor.GRAY + "Magazine: " + ChatColor.WHITE + definition.magazine());
        }
        return lore;
    }

    private static String format(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** The weapon id stamped on the stack, or {@code null}. */
    public String weaponId(ItemStack stack) {
        String id = weaponState.readString(stack);
        if (id != null && !id.isBlank()) {
            return id;
        }
        // Fallback: Core item identity (works even if this module's runtime PDC
        // was stripped, e.g. an item from a creative picker).
        String coreKey = itemRegistry.readItemKey(stack);
        if (coreKey == null) {
            return null;
        }
        int colon = coreKey.indexOf(':');
        return colon >= 0 ? coreKey.substring(colon + 1) : coreKey;
    }

    /** Rounds currently in the stack, or {@code -1} when it carries no ammo tag. */
    public int readAmmo(ItemStack stack) {
        Integer ammo = ammoState.read(stack);
        return ammo == null ? -1 : ammo;
    }

    /** Writes the rounds carried by the stack (clamped at zero). */
    public void writeAmmo(ItemStack stack, int rounds) {
        ammoState.set(stack, Math.max(0, rounds));
    }

    /**
     * The stable per-stack identity used to key the in-memory magazine, or
     * {@code null} when the stack carries none.
     */
    public String readUid(ItemStack stack) {
        String uid = identityTag.readString(stack);
        return uid == null || uid.isBlank() ? null : uid;
    }

    /** Stamps an identity tag for a legacy stack; the caller must write the stack back. */
    public void writeUid(ItemStack stack, String uid) {
        identityTag.setString(stack, uid);
    }

    public MineplusGunPlugin plugin() {
        return plugin;
    }
}
