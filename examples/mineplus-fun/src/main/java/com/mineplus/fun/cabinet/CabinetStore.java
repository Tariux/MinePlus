package com.mineplus.fun.cabinet;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

/**
 * Persistent cabinet storage: the 18 GUI slots serialized into the instance's
 * {@code stateData} as Base64-encoded {@link ItemStack#serializeAsBytes()}
 * payloads, so cabinet contents survive restarts together with the multiblock.
 */
public final class CabinetStore {

    private CabinetStore() {
    }

    public static ItemStack loadSlot(MultiBlockInstance instance, int slot) {
        String encoded = instance.stateData().get(key(slot));
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
            return item.getAmount() > 0 ? item : null;
        } catch (IllegalArgumentException corruptedEntry) {
            return null;
        }
    }

    public static void saveSlot(MultiBlockInstance instance, int slot, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            instance.mutableStateData().remove(key(slot));
            return;
        }
        instance.mutableStateData().put(
                key(slot),
                Base64.getEncoder().encodeToString(item.serializeAsBytes())
        );
    }

    /** Drops all stored items at the cabinet's anchor block and clears the state keys. */
    public static void dropAll(MultiBlockInstance instance) {
        List<ItemStack> stored = new ArrayList<>();
        for (int slot = 0; slot < CabinetKeys.STORAGE_SLOTS; slot++) {
            ItemStack item = loadSlot(instance, slot);
            if (item != null) {
                stored.add(item);
            }
            instance.mutableStateData().remove(key(slot));
        }

        if (stored.isEmpty()) {
            return;
        }
        World world = Bukkit.getWorld(instance.coordinate().worldName());
        if (world == null) {
            return;
        }
        Location dropLocation = new Location(
                world,
                instance.coordinate().x() + 0.5,
                instance.coordinate().y() + 0.5,
                instance.coordinate().z() + 0.5
        );
        for (ItemStack item : stored) {
            world.dropItemNaturally(dropLocation, item);
        }
    }

    public static int countItems(MultiBlockInstance instance) {
        int count = 0;
        for (int slot = 0; slot < CabinetKeys.STORAGE_SLOTS; slot++) {
            if (loadSlot(instance, slot) != null) {
                count++;
            }
        }
        return count;
    }

    private static String key(int slot) {
        return CabinetKeys.STATE_SLOT_PREFIX + slot;
    }
}
