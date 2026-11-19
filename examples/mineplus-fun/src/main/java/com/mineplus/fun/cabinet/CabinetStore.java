package com.mineplus.fun.cabinet;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * Persistent cabinet storage: the 18 GUI slots serialized into the instance's
 * {@code stateData} as Base64 payloads, so cabinet contents survive restarts
 * together with the multiblock.
 *
 * <p>The encoding is {@link BukkitObjectOutputStream} over the item stack, not
 * Paper's {@code ItemStack#serializeAsBytes}. The module compiles against the
 * Spigot stubs, where that Paper-only pair does not exist, so calling it would
 * fail the build — and, before the classpath was tightened, would have thrown
 * {@code NoSuchMethodError} the first time a cabinet saved on a Spigot server.
 * The Bukkit object stream is part of the shared API surface and round-trips
 * item metadata, NBT and Mineplus identity tags unchanged.</p>
 */
public final class CabinetStore {

    private CabinetStore() {
    }

    public static ItemStack loadSlot(MultiBlockInstance instance, int slot) {
        String encoded = instance.stateData().get(key(slot));
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        ItemStack item = decode(encoded);
        return item != null && item.getAmount() > 0 ? item : null;
    }

    public static void saveSlot(MultiBlockInstance instance, int slot, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            instance.mutableStateData().remove(key(slot));
            return;
        }
        instance.mutableStateData().put(key(slot), encode(item));
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

    /** One slot as Base64, or {@code null} when the stack cannot be encoded. */
    private static String encode(ItemStack item) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeObject(item);
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException failure) {
            return null;
        }
    }

    /** The inverse of {@link #encode}; a corrupt or legacy payload reads as empty. */
    private static ItemStack decode(String encoded) {
        try (ByteArrayInputStream bytes =
                        new ByteArrayInputStream(Base64.getDecoder().decode(encoded));
                BukkitObjectInputStream in = new BukkitObjectInputStream(bytes)) {
            Object read = in.readObject();
            return read instanceof ItemStack item ? item : null;
        } catch (IOException | ClassNotFoundException | IllegalArgumentException corrupted) {
            return null;
        }
    }

    private static String key(int slot) {
        return CabinetKeys.STATE_SLOT_PREFIX + slot;
    }
}
