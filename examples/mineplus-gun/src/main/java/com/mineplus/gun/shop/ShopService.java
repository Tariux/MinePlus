package com.mineplus.gun.shop;

import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.weapon.WeaponDefinition;
import com.mineplus.gun.weapon.WeaponItemFactory;
import com.mineplus.gun.weapon.WeaponRegistry;
import java.util.Map;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Price lookup, permission gating and delivery for the shop. The bypass rule
 * is explicit: {@code mineplusgun.bypass.cost} (or an op when
 * {@code shop.admin-bypass-payment} is on) skips balance checks and
 * withdrawals entirely.
 */
public final class ShopService {

    public enum Result {
        SUCCESS,
        DENIED,
        INSUFFICIENT_FUNDS,
        UNAVAILABLE,
        ERROR
    }

    private final GunServicesProvider services;
    private final EconomyBridge economy;

    /** Minimal view of the module services the shop needs (avoids a cyclic dep). */
    public interface GunServicesProvider {
        WeaponRegistry registry();
        WeaponItemFactory itemFactory();
        GunConfig config();
    }

    public ShopService(GunServicesProvider services, EconomyBridge economy) {
        this.services = services;
        this.economy = economy;
    }

    public boolean useAllowed(Player player) {
        return player.hasPermission("mineplusgun.shop.use");
    }

    public boolean buyAllowed(Player player, WeaponDefinition weapon) {
        return player.hasPermission("mineplusgun.shop.buy")
                || player.hasPermission("mineplusgun.shop.buy." + weapon.type().name().toLowerCase(java.util.Locale.ROOT))
                || player.hasPermission("mineplusgun.shop.buy." + weapon.id());
    }

    public boolean bypassPayment(Player player) {
        GunConfig config = services.config();
        if (player.hasPermission("mineplusgun.bypass.cost")) {
            return true;
        }
        return config != null && config.shop().adminBypassPayment() && player.isOp();
    }

    /** The effective, multiplier-applied price text for display. */
    public String formatPrice(double rawPrice) {
        GunConfig config = services.config();
        double price = config == null ? rawPrice : config.economy().apply(rawPrice);
        return economy.format(price);
    }

    /** Purchase flow. The buyer is messaged with the outcome. */
    public Result purchase(Player buyer, WeaponDefinition weapon) {
        if (!buyAllowed(buyer, weapon)) {
            buyer.sendMessage(ChatColor.RED + "You do not have permission to buy this weapon.");
            return Result.DENIED;
        }

        boolean free = true;
        double price = 0.0;
        GunConfig config = services.config();
        if (config != null && config.economy().mode() == GunConfig.EconomyMode.VAULT) {
            free = false;
            price = config.economy().apply(weapon.price());
        }

        if (!bypassPayment(buyer) && !free) {
            if (!economy.available()) {
                // No economy provider: degrade to free delivery rather than fail.
                free = true;
            } else {
                if (!economy.has(buyer, price)) {
                    buyer.sendMessage(ChatColor.RED + "You cannot afford this ("
                            + economy.format(price) + ").");
                    return Result.INSUFFICIENT_FUNDS;
                }
                if (!economy.withdraw(buyer, price)) {
                    buyer.sendMessage(ChatColor.RED + "Payment failed; nothing was delivered.");
                    return Result.ERROR;
                }
            }
        }

        deliver(buyer, weapon);
        if (free || bypassPayment(buyer)) {
            buyer.sendMessage(ChatColor.GREEN + "Received " + ChatColor.WHITE + weapon.displayName()
                    + ChatColor.GREEN + ".");
        } else {
            buyer.sendMessage(ChatColor.GREEN + "Purchased " + ChatColor.WHITE + weapon.displayName()
                    + ChatColor.GREEN + " for " + economy.format(price) + ".");
        }
        return Result.SUCCESS;
    }

    /** Grants a weapon to a player (inventory, overflowing to a drop). */
    public void deliver(Player player, WeaponDefinition weapon) {
        WeaponItemFactory factory = services.itemFactory();
        ItemStack stack = factory == null ? null : factory.create(weapon);
        if (stack == null || stack.getType().isAir()) {
            player.sendMessage(ChatColor.RED + "This weapon could not be created.");
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        for (ItemStack overflow : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }
}
