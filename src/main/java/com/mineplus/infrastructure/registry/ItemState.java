package com.mineplus.infrastructure.registry;

import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * A typed PDC slot on any ItemStack, keyed once per module/plugin — the
 * shared way content items carry runtime numbers (ammunition, fuel, charges)
 * and a stable identity tag. One instance per slot:
 *
 * <pre>{@code
 * ItemState ammo = new ItemState(plugin, "ammo");
 * ammo.set(stack, 6);
 * int rounds = ammo.get(stack);
 *
 * ItemState identity = ItemState.identityTag(plugin);
 * String uid = identity.getOrCreateIdentity(stack); // lazily stamped
 * }</pre>
 *
 * <p>Keys live under the plugin's own namespace; values are integers plus
 * strings. Stack meta writes resend the stack — callers that update held
 * items continuously should keep the value authoritative in memory and write
 * it back on switch/quit, exactly like the gun module's magazine model.</p>
 */
public final class ItemState {

    private final NamespacedKey key;

    public ItemState(org.bukkit.plugin.Plugin plugin, String name) {
        this.key = new NamespacedKey(plugin, name.toLowerCase(java.util.Locale.ROOT));
    }

    /** A lazily-stamped per-stack UUID tag (dropped/given stacks keep theirs). */
    public static ItemState identityTag(org.bukkit.plugin.Plugin plugin) {
        return new ItemState(plugin, "identity_tag");
    }

    /** The stored integer, or {@code fallback} when unset. */
    public int get(ItemStack stack, int fallback) {
        Integer value = read(stack);
        return value == null ? fallback : value;
    }

    /** The stored integer, or {@code null} when unset. */
    public Integer read(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
    }

    /** Writes the integer (the caller decides when to persist the stack). */
    public void set(ItemStack stack, int value) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, value);
        stack.setItemMeta(meta);
    }

    /** The stored string, or {@code fallback} when unset/blank. */
    public String getString(ItemStack stack, String fallback) {
        String value = readString(stack);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** The stored string, or {@code null} when unset. */
    public String readString(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    public void setString(ItemStack stack, String value) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
        stack.setItemMeta(meta);
    }

    /** The stack's identity tag, stamping a fresh UUID the first time. */
    public String getOrCreateIdentity(ItemStack stack) {
        String existing = readString(stack);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String stamped = UUID.randomUUID().toString();
        setString(stack, stamped);
        return stamped;
    }
}
