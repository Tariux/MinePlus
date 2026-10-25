package com.mineplus.gun.runtime;

import com.mineplus.gun.weapon.WeaponDefinition;
import com.mineplus.gun.weapon.WeaponItemFactory;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Magazine state lives in the weapon stack's PDC ({@code mpgun:ammo}), but
 * while a weapon is in use the authoritative count lives <em>in memory</em>
 * ({@link Magazine}).
 *
 * <p>The item's PDC is only rewritten when the stack leaves the shooter's hand
 * (see {@link #flush}). Writing components onto the held item every shot makes
 * the client treat it as a different ItemStack and play the item-swap equip
 * animation, so the held stack is never touched per shot; the count is flushed
 * on switch / drop / quit / disable and reloaded from the PDC on next use.
 * Magazine-ready for a later phase that inserts magazine items.
 */
public final class AmmoModel {

    private final WeaponItemFactory itemFactory;

    public AmmoModel(WeaponItemFactory itemFactory) {
        this.itemFactory = itemFactory;
    }

    /** The live magazine of one held stack (identified by its PDC uid). */
    public static final class Magazine {
        private final String uid;
        private int rounds;

        Magazine(String uid, int rounds) {
            this.uid = uid;
            this.rounds = rounds;
        }

        public String uid() {
            return uid;
        }

        public int rounds() {
            return rounds;
        }

        public void rounds(int value) {
            this.rounds = Math.max(0, value);
        }

        public int spend() {
            rounds = Math.max(0, rounds - 1);
            return rounds;
        }

        public void refill(int capacity) {
            rounds = Math.max(0, capacity);
        }
    }

    /**
     * Opens (or reopens) the live magazine for {@code stack}: seeds the count
     * from the stack PDC the first time, after which the returned object is the
     * single source of truth. A stack without a uid (issued before the uid tag
     * existed) is tagged once, which the caller must write back.
     */
    public Magazine open(ItemStack stack, WeaponDefinition weapon) {
        String uid = itemFactory.readUid(stack);
        if (uid == null) {
            uid = UUID.randomUUID().toString();
            itemFactory.writeUid(stack, uid);
        }
        int stored = itemFactory.readAmmo(stack);
        int rounds = stored < 0 ? (weapon.usesAmmo() ? weapon.magazine() : 0) : stored;
        return new Magazine(uid, rounds);
    }

    /**
     * Writes a live magazine back onto the matching stack anywhere in the
     * player's inventory. Silent when the stack is gone (dropped into lava,
     * lost on death) — a lost magazine is not worth an error.
     */
    public void flush(Player player, Magazine magazine) {
        if (player == null || magazine == null) {
            return;
        }
        ItemStack[] contents = player.getInventory().getContents();
        for (ItemStack candidate : contents) {
            if (candidate == null || candidate.getType().isAir()) {
                continue;
            }
            if (magazine.uid.equals(itemFactory.readUid(candidate))) {
                itemFactory.writeAmmo(candidate, magazine.rounds);
                return;
            }
        }
    }

    /** Rounds in the stack, read straight from PDC (shop/command paths). */
    public int rounds(ItemStack stack, WeaponDefinition weapon) {
        int stored = itemFactory.readAmmo(stack);
        if (stored < 0) {
            return weapon.usesAmmo() ? weapon.magazine() : 0;
        }
        return stored;
    }

    public boolean isEmpty(ItemStack stack, WeaponDefinition weapon) {
        return weapon.usesAmmo() && rounds(stack, weapon) <= 0;
    }

    /** Refills the stack PDC directly (shop/command paths). */
    public int reload(ItemStack stack, WeaponDefinition weapon) {
        itemFactory.writeAmmo(stack, weapon.magazine());
        return weapon.magazine();
    }

    public void set(ItemStack stack, int rounds) {
        itemFactory.writeAmmo(stack, rounds);
    }
}
