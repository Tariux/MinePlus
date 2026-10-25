package com.mineplus.gun.weapon;

import com.mineplus.gun.MineplusGunPlugin;
import com.mineplus.infrastructure.registry.ItemRegistry;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Builds the {@link ItemStack} for a weapon: Core pack-item identity plus the
 * module's runtime PDC block ({@code mpgun:weapon}, {@code mpgun:ammo}).
 *
 * <p>With {@code PACK.ENABLED: false} the Core returns the vanilla backing
 * item; identity and gameplay still work.
 */
public final class WeaponItemFactory {

    public static final String NAMESPACE = "mineplusgun";

    private final MineplusGunPlugin plugin;
    private final ItemRegistry itemRegistry;
    private final com.mineplus.pack.PackApi packApi;

    private final NamespacedKey weaponKey;
    private final NamespacedKey ammoKey;
    private final NamespacedKey uidKey;

    public WeaponItemFactory(MineplusGunPlugin plugin, ItemRegistry itemRegistry, com.mineplus.pack.PackApi packApi) {
        this.plugin = plugin;
        this.itemRegistry = itemRegistry;
        this.packApi = packApi;
        this.weaponKey = new NamespacedKey(plugin, "weapon");
        this.ammoKey = new NamespacedKey(plugin, "ammo");
        this.uidKey = new NamespacedKey(plugin, "uid");
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
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', definition.displayName()));
            meta.setLore(defaultLore(definition));
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(weaponKey, PersistentDataType.STRING, definition.id());
            pdc.set(uidKey, PersistentDataType.STRING, java.util.UUID.randomUUID().toString());
            if (definition.usesAmmo()) {
                pdc.set(ammoKey, PersistentDataType.INTEGER, definition.magazine());
            }
            stack.setItemMeta(meta);
        }
        return stack;
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
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        String id = meta.getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
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
        if (stack == null || !stack.hasItemMeta()) {
            return -1;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return -1;
        }
        Integer ammo = meta.getPersistentDataContainer().get(ammoKey, PersistentDataType.INTEGER);
        return ammo == null ? -1 : ammo;
    }

    /** Writes the rounds carried by the stack (clamped at zero). */
    public void writeAmmo(ItemStack stack, int rounds) {
        if (stack == null || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.INTEGER, Math.max(0, rounds));
        stack.setItemMeta(meta);
    }

    /**
     * The stable per-stack identity used to key the in-memory magazine, or
     * {@code null} when the stack predates the uid tag.
     */
    public String readUid(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        String uid = meta.getPersistentDataContainer().get(uidKey, PersistentDataType.STRING);
        return uid == null || uid.isBlank() ? null : uid;
    }

    /** Stamps a uid for a legacy stack; the caller must write the stack back. */
    public void writeUid(ItemStack stack, String uid) {
        if (stack == null || uid == null || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(uidKey, PersistentDataType.STRING, uid);
        stack.setItemMeta(meta);
    }

    public MineplusGunPlugin plugin() {
        return plugin;
    }
}
