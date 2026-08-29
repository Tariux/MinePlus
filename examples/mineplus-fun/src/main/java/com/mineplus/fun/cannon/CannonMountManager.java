package com.mineplus.fun.cannon;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Gunner's seat for level-2 cannons: the player rides an invisible marker
 * armor stand spawned on the barrel, which pins their position to the cannon
 * (riding neutralises WASD movement) while leaving the view free for aiming.
 *
 * <p>On mount the gunner receives the "Cannon Lanyard" bow in their main hand
 * plus a "Cannon Match" arrow in a free slot. The vanilla bow draw requires an
 * arrow somewhere in the inventory, so the match is what lets the lanyard draw;
 * the actual shot is intercepted in {@link CannonAimListener} and never
 * consumes the match - the cannon's TNT store is the real ammunition. Both
 * marker items are reclaimed on dismount, and stale ones are stripped from
 * joining players so an interrupted session (crash, kick) never leaks them.
 *
 * <p>Seats are non-persistent entities tagged with the cannon instance id; a
 * purge on enable removes any stand orphaned by an unclean shutdown.
 */
public final class CannonMountManager implements Listener {

    /**
     * Seat point in model pixels: on top of the barrel (main barrel box spans
     * pixels x -10..13 with top face y=14), behind the sight post (x 7..9,
     * y 14..16) toward the breech, on the bore centreline z=0.
     */
    private static final Vector3f SEAT_PIXELS = new Vector3f(11.0f, 14.0f, 0.0f);

    /**
     * World-space lift applied to the seat stand. The model is 3x1x1, so every
     * barrier collision cell sits in the anchor block's own Y layer; lifting
     * the stand to ~y+1.05 keeps stand and rider clear of those cells while
     * still seating the rider visually on the barrel top. Tunable in-game.
     */
    private static final double SEAT_LIFT = 0.175D;

    /** A mounted player + the seat entity they ride, keyed by player uuid. */
    public record MountSession(UUID instanceId, UUID seatEntityId) {
    }

    private final JavaPlugin plugin;
    private final Map<UUID, MountSession> sessions;
    private final Map<UUID, UUID> gunnerByInstance;
    private final NamespacedKey lanyardKey;
    private final NamespacedKey matchKey;
    private final NamespacedKey seatKey;

    public CannonMountManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.sessions = new HashMap<>();
        this.gunnerByInstance = new HashMap<>();
        this.lanyardKey = new NamespacedKey(plugin, CannonKeys.PDC_LANYARD);
        this.matchKey = new NamespacedKey(plugin, CannonKeys.PDC_MATCH);
        this.seatKey = new NamespacedKey(plugin, CannonKeys.PDC_SEAT);
    }

    /** Pairs an aim listener with the session of the player it is firing for. */
    public MountSession session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public boolean isLanyard(ItemStack item) {
        return isTagged(item, lanyardKey);
    }

    public boolean isMatch(ItemStack item) {
        return isTagged(item, matchKey);
    }

    /** True if the given entity is one of our seat stands. */
    public boolean isSeat(Entity entity) {
        return entity != null
                && entity instanceof ArmorStand
                && entity.getPersistentDataContainer().has(seatKey, PersistentDataType.STRING);
    }

    /** Puts the player on the cannon's gunner's seat, handing them the lanyard. */
    public void mount(Player player, MultiBlockInstance instance) {
        if (instance == null) {
            return;
        }
        if (instance.level() < CannonKeys.LEVEL_AIMED) {
            player.sendMessage(ChatColor.RED + "Upgrade the cannon to Cannon II to unlock the gunner's seat.");
            return;
        }
        if (sessions.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You are already serving a cannon. Dismount first.");
            return;
        }

        UUID currentGunner = gunnerByInstance.get(instance.id());
        if (currentGunner != null) {
            Player seated = Bukkit.getPlayer(currentGunner);
            if (seated != null && seated.isOnline()) {
                player.sendMessage(ChatColor.RED + "This cannon is already served by " + seated.getName() + ".");
                return;
            }
            releaseInstance(instance.id(), currentGunner);
            sessions.remove(currentGunner);
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand != null && !mainHand.getType().isAir()) {
            player.sendMessage(ChatColor.RED + "Sheathe your main hand to take the gunner's seat.");
            return;
        }

        boolean offHandFree = isAir(player.getInventory().getItemInOffHand());
        if (!offHandFree && player.getInventory().firstEmpty() == -1) {
            player.sendMessage(ChatColor.RED + "Free an inventory slot for the cannon match first.");
            return;
        }

        World world = Bukkit.getWorld(instance.coordinate().worldName());
        if (world == null) {
            return;
        }

        ArmorStand seat = world.spawn(seatLocation(instance), ArmorStand.class);
        seat.setMarker(true);
        seat.setInvisible(true);
        seat.setSmall(true);
        seat.setGravity(false);
        seat.setInvulnerable(true);
        seat.setBasePlate(false);
        seat.setPersistent(false);
        seat.getPersistentDataContainer().set(seatKey, PersistentDataType.STRING, instance.id().toString());

        if (!seat.addPassenger(player)) {
            seat.remove();
            player.sendMessage(ChatColor.RED + "You cannot take the gunner's seat here.");
            return;
        }

        sessions.put(player.getUniqueId(), new MountSession(instance.id(), seat.getUniqueId()));
        gunnerByInstance.put(instance.id(), player.getUniqueId());

        player.getInventory().setItemInMainHand(createLanyard());
        if (offHandFree) {
            player.getInventory().setItemInOffHand(createMatch());
        } else {
            player.getInventory().addItem(createMatch());
        }

        player.playSound(player.getLocation(), Sound.ENTITY_HORSE_SADDLE, 0.8F, 1.0F);
        player.sendMessage(ChatColor.GOLD + "You take the gunner's seat of the cannon.");
        player.sendMessage(ChatColor.GRAY + "Draw the " + ChatColor.WHITE + "Cannon Lanyard" + ChatColor.GRAY
                + " like a bow to aim and fire - the longer you draw, the harder the shot.");
        player.sendMessage(ChatColor.GRAY + "The cannon's TNT is your ammunition. Sneak to dismount.");
    }

    /** Dismounts the player and reclaims the marker items (idempotent). */
    public void dismount(Player player) {
        dismount(player, ChatColor.GRAY + "You step down from the cannon.");
    }

    /** Same as {@link #dismount(Player)} but with a caller-chosen message. */
    public void dismount(Player player, String message) {
        MountSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        releaseInstance(session.instanceId(), player.getUniqueId());
        removeSeat(session.seatEntityId());
        reclaimMarkers(player);
        player.sendMessage(message);
    }

    /** Drops a stale session whose seat entity disappeared out from under the player. */
    public void dropStaleSession(Player player) {
        MountSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        releaseInstance(session.instanceId(), player.getUniqueId());
        removeSeat(session.seatEntityId());
        reclaimMarkers(player);
    }

    /** Ejects whoever serves the given cannon (if anyone) - used on break/remove. */
    public void ejectInstance(UUID instanceId) {
        UUID gunnerId = gunnerByInstance.get(instanceId);
        if (gunnerId == null) {
            return;
        }
        MountSession session = sessions.remove(gunnerId);
        releaseInstance(instanceId, gunnerId);
        if (session != null) {
            removeSeat(session.seatEntityId());
        }

        Player gunner = Bukkit.getPlayer(gunnerId);
        if (gunner != null && gunner.isOnline()) {
            reclaimMarkers(gunner);
            gunner.sendMessage(ChatColor.RED + "The cannon beneath you is gone.");
        }
    }

    /** Removes any seat stands left over from an unclean shutdown. */
    public void purgeOrphanSeats() {
        for (World world : Bukkit.getWorlds()) {
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                if (stand.getPersistentDataContainer().has(seatKey, PersistentDataType.STRING)) {
                    stand.remove();
                }
            }
        }
    }

    /** Full cleanup on plugin disable: unseat every gunner, remove every stand. */
    public void shutdown() {
        for (Iterator<Map.Entry<UUID, MountSession>> iterator = sessions.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<UUID, MountSession> entry = iterator.next();
            iterator.remove();
            MountSession session = entry.getValue();
            releaseInstance(session.instanceId(), entry.getKey());
            removeSeat(session.seatEntityId());

            Player gunner = Bukkit.getPlayer(entry.getKey());
            if (gunner != null && gunner.isOnline()) {
                reclaimMarkers(gunner);
                gunner.sendMessage(ChatColor.GRAY + "The gunner's seat vanishes.");
            }
        }
        purgeOrphanSeats();
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player) || !isSeat(event.getDismounted())) {
            return;
        }
        dismount(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        MountSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        releaseInstance(session.instanceId(), player.getUniqueId());
        removeSeat(session.seatEntityId());
        reclaimMarkers(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        reclaimMarkers(event.getPlayer());
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (isLanyard(item) || isMatch(item)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.GRAY + "The gunner's tools stay with the cannon.");
        }
    }

    private void releaseInstance(UUID instanceId, UUID gunnerId) {
        if (gunnerId.equals(gunnerByInstance.get(instanceId))) {
            gunnerByInstance.remove(instanceId);
        }
    }

    private void removeSeat(UUID seatEntityId) {
        Entity seat = Bukkit.getEntity(seatEntityId);
        if (seat != null) {
            seat.remove();
        }
    }

    /** Removes the lanyard bow and every cannon match from the player's inventory. */
    private void reclaimMarkers(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (isLanyard(mainHand)) {
            player.getInventory().setItemInMainHand(null);
        }

        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (isMatch(offHand)) {
            player.getInventory().setItemInOffHand(null);
        }

        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            if (isMatch(storage[slot])) {
                player.getInventory().setItem(slot, null);
            }
        }
        player.updateInventory();
    }

    private Location seatLocation(MultiBlockInstance instance) {
        World world = Bukkit.getWorld(instance.coordinate().worldName());
        if (world == null) {
            return null;
        }

        Vector3f seatOffset = new Vector3f(SEAT_PIXELS).mul(1.0f / 16.0f).sub(0.0f, 0.5f, 0.0f);
        Quaternionf rotation = instance.rotation();
        if (rotation != null) {
            rotation.transform(seatOffset);
        }

        return new Location(
                world,
                instance.coordinate().x() + 0.5D + seatOffset.x,
                instance.coordinate().y() + 0.5D + seatOffset.y + SEAT_LIFT,
                instance.coordinate().z() + 0.5D + seatOffset.z
        );
    }

    private ItemStack createLanyard() {
        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta meta = bow.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Cannon Lanyard");
        meta.setLore(List.of(
                ChatColor.GRAY + "Draw like a bow to aim and fire",
                ChatColor.GRAY + "the cannon. The TNT loaded into",
                ChatColor.GRAY + "the cannon is your ammunition.",
                ChatColor.DARK_GRAY + "Sneak to leave the gunner's seat."
        ));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(lanyardKey, PersistentDataType.BYTE, (byte) 1);
        bow.setItemMeta(meta);
        return bow;
    }

    private ItemStack createMatch() {
        ItemStack arrow = new ItemStack(Material.ARROW);
        ItemMeta meta = arrow.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Cannon Match");
        meta.setLore(List.of(
                ChatColor.GRAY + "Primes the Cannon Lanyard's draw.",
                ChatColor.DARK_GRAY + "Never consumed."
        ));
        meta.getPersistentDataContainer().set(matchKey, PersistentDataType.BYTE, (byte) 1);
        arrow.setItemMeta(meta);
        return arrow;
    }

    private boolean isTagged(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private boolean isAir(ItemStack item) {
        return item == null || item.getType().isAir();
    }
}
