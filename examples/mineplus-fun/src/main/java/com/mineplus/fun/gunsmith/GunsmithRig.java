package com.mineplus.fun.gunsmith;

import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The gun test rig items: a bow ("Pistol") and a crossbow ("Rifle") stamped
 * with the overlay's legacy {@code custom_model_data} value and a PDC
 * ammunition counter. Both fire through {@link GunsmithGunListener}'s
 * hitscan — the vanilla projectile launch is always cancelled, so the rig
 * never actually launches an arrow (the vanilla draw/charge merely requires
 * one to exist in the inventory, and the rifle's charge refunds it).
 */
final class GunsmithRig {

    private final NamespacedKey gunKey;
    private final NamespacedKey ammoKey;

    GunsmithRig(JavaPlugin plugin) {
        this.gunKey = new NamespacedKey(plugin, GunsmithKeys.PDC_GUN);
        this.ammoKey = new NamespacedKey(plugin, GunsmithKeys.PDC_AMMO);
    }

    /** A fresh gun of the given material with a full magazine. */
    ItemStack create(Material material) {
        boolean rifle = material == Material.CROSSBOW;
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(rifle ? ChatColor.GOLD + "Rifle" : ChatColor.GOLD + "Pistol");
            meta.setUnbreakable(true);
            meta.setCustomModelData(GunsmithKeys.LEGACY_CUSTOM_MODEL_DATA);
            meta.getPersistentDataContainer().set(gunKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.INTEGER, GunsmithKeys.MAGAZINE);
            writeLore(meta, GunsmithKeys.MAGAZINE, rifle);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** True when the stack is one of this module's guns (PDC identity, never name/lore). */
    boolean isGun(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(gunKey, PersistentDataType.BYTE);
    }

    /** Remaining rounds, or 0 for anything that is not a gun. */
    int rounds(ItemStack item) {
        if (!isGun(item)) {
            return 0;
        }
        Integer rounds = item.getItemMeta().getPersistentDataContainer().get(ammoKey, PersistentDataType.INTEGER);
        return rounds == null ? 0 : rounds;
    }

    /** True when the stack is a rifle (crossbow) gun. */
    boolean isRifle(ItemStack item) {
        return isGun(item) && item.getType() == Material.CROSSBOW;
    }

    /**
     * Spends one round if any remain, updating the PDC counter and the lore
     * line so the magazine level stays visible. Returns false when empty.
     */
    boolean spendRound(ItemStack gun) {
        int rounds = rounds(gun);
        if (rounds <= 0) {
            return false;
        }
        int remaining = rounds - 1;
        ItemMeta meta = gun.getItemMeta();
        if (meta == null) {
            return false;
        }
        meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.INTEGER, remaining);
        writeLore(meta, remaining, gun.getType() == Material.CROSSBOW);
        gun.setItemMeta(meta);
        return true;
    }

    /** Rewrites the gun's lore with the current magazine level. */
    private void writeLore(ItemMeta meta, int rounds, boolean rifle) {
        meta.setLore(List.of(
                ChatColor.GRAY + (rifle
                        ? "Hold right-click to charge, right-click to fire"
                        : "Draw and release to fire"),
                ChatColor.GRAY + "Rounds: " + ChatColor.WHITE + rounds + ChatColor.GRAY + "/" + GunsmithKeys.MAGAZINE,
                ChatColor.DARK_GRAY + (rifle
                        ? "Precise hitscan - keep an arrow in hand to charge."
                        : "Quick hitscan - keep an arrow in hand to draw.")
        ));
    }
}
