package com.mineplus.gun.shop;

import com.mineplus.gun.MineplusGunPlugin;
import com.mineplus.gun.GunServices;
import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.weapon.WeaponDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The weapon shop GUI: an {@link InventoryHolder} listing every visible
 * weapon, grouped by class with pagination. Clicking a weapon runs the
 * purchase flow in {@link ShopService}.
 */
public final class ShopGui implements InventoryHolder {

    private final MineplusGunPlugin plugin;
    private final GunServices services;
    private final Player viewer;
    private final int page;
    private final Inventory inventory;
    private final Map<Integer, WeaponDefinition> slotWeapons = new LinkedHashMap<>();
    private final Map<Integer, Integer> navigation = new LinkedHashMap<>();

    private ShopGui(MineplusGunPlugin plugin, GunServices services, Player viewer, int page) {
        this.plugin = plugin;
        this.services = services;
        this.viewer = viewer;
        this.page = Math.max(0, page);

        GunConfig config = services.config();
        GunConfig.Shop shop = config == null ? new GunConfig.Shop("Weapon Store", 6, true) : config.shop();
        this.inventory = Bukkit.createInventory(this, shop.rows() * 9,
                ChatColor.translateAlternateColorCodes('&', shop.title()));
        render();
    }

    public static void open(MineplusGunPlugin plugin, GunServices services, Player viewer, int page) {
        viewer.openInventory(new ShopGui(plugin, services, viewer, page).getInventory());
    }

    private List<WeaponDefinition> ordered() {
        List<WeaponDefinition> list = new ArrayList<>(services.registry().visible());
        list.sort(Comparator
                .comparing((WeaponDefinition w) -> w.type().ordinal())
                .thenComparingDouble(WeaponDefinition::price)
                .thenComparing(WeaponDefinition::id));
        return list;
    }

    private void render() {
        inventory.clear();
        slotWeapons.clear();
        navigation.clear();

        int rows = inventory.getSize() / 9;
        int contentSlots = Math.max(9, (rows - 1) * 9);
        List<WeaponDefinition> weapons = ordered();
        int pages = Math.max(1, (int) Math.ceil(weapons.size() / (double) contentSlots));
        int currentPage = Math.min(page, pages - 1);

        ItemStack filler = filler();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        int start = currentPage * contentSlots;
        int end = Math.min(weapons.size(), start + contentSlots);
        for (int i = start; i < end; i++) {
            int slot = i - start;
            WeaponDefinition weapon = weapons.get(i);
            inventory.setItem(slot, icon(weapon));
            slotWeapons.put(slot, weapon);
        }

        if (pages > 1) {
            int base = (rows - 1) * 9;
            if (currentPage > 0) {
                inventory.setItem(base, navItem(Material.ARROW, ChatColor.YELLOW + "Previous page"));
                navigation.put(base, currentPage - 1);
            }
            inventory.setItem(base + 4, navItem(Material.PAPER,
                    ChatColor.YELLOW + "Page " + (currentPage + 1) + "/" + pages));
            if (currentPage + 1 < pages) {
                inventory.setItem(base + 8, navItem(Material.ARROW, ChatColor.YELLOW + "Next page"));
                navigation.put(base + 8, currentPage + 1);
            }
        }
    }

    private ItemStack icon(WeaponDefinition weapon) {
        ItemStack stack = services.itemFactory().create(weapon);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
            lore.add("");
            GunConfig config = services.config();
            boolean vault = config != null && config.economy().mode() == GunConfig.EconomyMode.VAULT;
            if (vault && weapon.price() > 0) {
                lore.add(ChatColor.GOLD + "Price: " + ChatColor.WHITE
                        + services.shopService().formatPrice(weapon.price()));
            } else {
                lore.add(ChatColor.GREEN + "Free");
            }
            lore.add(ChatColor.GRAY + "Click to buy");
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack filler() {
        ItemStack stack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack navItem(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Handles one click inside this GUI; always cancels the event. */
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }

        Integer targetPage = navigation.get(slot);
        if (targetPage != null) {
            if (navigation.containsKey(slot) && targetPage == page) {
                return;
            }
            // The paper label is a nav entry too but leads to the same page.
            open(plugin, services, viewer, targetPage);
            return;
        }

        WeaponDefinition weapon = slotWeapons.get(slot);
        if (weapon == null) {
            return;
        }
        ShopService.Result result = services.shopService().purchase(viewer, weapon);
        if (result == ShopService.Result.SUCCESS) {
            // Refresh so an item-count change is reflected and the buyer can
            // keep buying without reopening.
            open(plugin, services, viewer, page);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
