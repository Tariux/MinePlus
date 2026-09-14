package com.mineplus.gun.runtime;

import com.mineplus.gun.weapon.WeaponDefinition;
import com.mineplus.gun.weapon.WeaponItemFactory;
import org.bukkit.inventory.ItemStack;

/**
 * Magazine state lives in the weapon stack's PDC ({@code mpgun:ammo}).
 * Magazine-ready for a later phase that inserts magazine items; v1 refills
 * from the shop/command.
 */
public final class AmmoModel {

    private final WeaponItemFactory itemFactory;

    public AmmoModel(WeaponItemFactory itemFactory) {
        this.itemFactory = itemFactory;
    }

    /** Rounds in the stack; an untagged magazine reads as full. */
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

    /** Consumes one round; returns the remaining count. */
    public int spend(ItemStack stack, WeaponDefinition weapon) {
        int remaining = Math.max(0, rounds(stack, weapon) - 1);
        itemFactory.writeAmmo(stack, remaining);
        return remaining;
    }

    /** Refills to magazine capacity; returns the new count. */
    public int reload(ItemStack stack, WeaponDefinition weapon) {
        itemFactory.writeAmmo(stack, weapon.magazine());
        return weapon.magazine();
    }

    public void set(ItemStack stack, int rounds) {
        itemFactory.writeAmmo(stack, rounds);
    }
}
