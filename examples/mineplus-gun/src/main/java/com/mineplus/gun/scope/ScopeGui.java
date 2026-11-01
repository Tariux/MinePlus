package com.mineplus.gun.scope;

import com.mineplus.gun.GunServices;
import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.weapon.WeaponDefinition;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The reticle overlay a scoped weapon opens when its scope trigger is pressed.
 *
 * <p>A server cannot rotate or zoom a modern client's view, so the scope is
 * drawn the only way the vanilla client will show it full-screen: an inventory
 * whose black frame leaves one clear cross through the middle. That cross is
 * centred on the screen, which is where the player's own sight line already is,
 * so the gap in the frame is the sight picture and the click inside it is the
 * shot.</p>
 *
 * <p>Every click in the container is swallowed — the overlay is a sight, not a
 * chest, and a stray shift-click must never move an item while the player is
 * aiming. The runtime owns the scope state; closing this screen is what
 * unscopes.</p>
 */
public final class ScopeGui implements InventoryHolder {

    /** Five rows put the middle cell of the middle row exactly on the screen centre. */
    private static final int ROWS = 5;
    /** The corner cell that carries the weapon name and its round count. */
    private static final int INFO_SLOT = 0;
    /** The column of the sight line; the middle row is computed from {@link #ROWS}. */
    private static final int SIGHT_COLUMN = 4;

    private final GunServices services;
    private final Player viewer;
    private final WeaponDefinition weapon;
    private final Inventory inventory;
    private final boolean[] sightLine;

    private ScopeGui(GunServices services, Player viewer, WeaponDefinition weapon,
                     int loaded, int reserve) {
        this.services = services;
        this.viewer = viewer;
        this.weapon = weapon;

        int size = ROWS * 9;
        this.inventory = Bukkit.createInventory(this, size, title(weapon));
        this.sightLine = new boolean[size];
        int middleRow = ROWS / 2;

        ItemStack frame = frame();
        for (int slot = 0; slot < size; slot++) {
            boolean clear = slot / 9 == middleRow || slot % 9 == SIGHT_COLUMN;
            sightLine[slot] = clear;
            if (slot != INFO_SLOT) {
                inventory.setItem(slot, clear ? null : frame);
            }
        }
        setRounds(loaded, reserve);
    }

    /**
     * Opens the overlay for one player.
     *
     * @param loaded  the rounds live in the gun, for the corner readout
     * @param reserve the rounds the player is carrying, same reason
     * @return the live overlay, or {@code null} when some other screen already
     *         owns the client — a shop is never hijacked by a scope.
     */
    public static ScopeGui open(GunServices services, Player viewer, WeaponDefinition weapon,
                                int loaded, int reserve) {
        if (services == null || viewer == null || weapon == null || viewer.isDead()) {
            return null;
        }
        if (viewer.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING) {
            return null;
        }
        ScopeGui gui = new ScopeGui(services, viewer, weapon, loaded, reserve);
        viewer.openInventory(gui.getInventory());
        return gui;
    }

    private static String title(WeaponDefinition weapon) {
        String name = weapon.displayName();
        return name == null || name.isBlank() ? "Scope" : name;
    }

    private ItemStack frame() {
        ItemStack stack = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** The corner readout: this gun, the rounds in it, the rounds on the player. */
    public void setRounds(int loaded, int reserve) {
        GunConfig config = services.config();
        Material carrier = config == null ? Material.GOLD_NUGGET : config.ammo().material();
        ItemStack stack = new ItemStack(carrier);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(weapon.displayName() + ChatColor.WHITE + " " + loaded
                    + ChatColor.DARK_GRAY + " in the gun");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Reserve: " + ChatColor.WHITE + reserve);
            lore.add(ChatColor.DARK_GRAY + "Click the sight line to fire, close to unscope");
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        inventory.setItem(INFO_SLOT, stack);
    }

    /** Handles one click: always swallowed, and a click on the sight line is a shot. */
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize() || !sightLine[slot]) {
            return;
        }
        if (services.runtime() != null) {
            services.runtime().fireScoped(viewer);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
