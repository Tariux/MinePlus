package com.mineplus.gun.shop;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Vault economy bridge, resolved reflectively so the module compiles and runs
 * with or without Vault. All methods are safe no-ops when Vault (or an economy
 * provider) is absent.
 */
public final class EconomyBridge {

    private final Object economy;
    private final Method hasMethod;
    private final Method withdrawMethod;
    private final Method formatMethod;

    public EconomyBridge() {
        Object provider = null;
        Method has = null;
        Method withdraw = null;
        Method format = null;
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> registration =
                    Bukkit.getServicesManager().getRegistration((Class) economyClass);
            if (registration != null) {
                provider = registration.getProvider();
                has = economyClass.getMethod("has", OfflinePlayer.class, double.class);
                withdraw = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
                format = economyClass.getMethod("format", double.class);
            }
        } catch (Throwable ignored) {
            provider = null;
        }
        this.economy = provider;
        this.hasMethod = has;
        this.withdrawMethod = withdraw;
        this.formatMethod = format;
    }

    public boolean available() {
        return economy != null && hasMethod != null && withdrawMethod != null;
    }

    public boolean has(Player player, double amount) {
        if (!available() || amount <= 0) {
            return true;
        }
        try {
            Object result = hasMethod.invoke(economy, (OfflinePlayer) player, amount);
            return result instanceof Boolean allowed && allowed;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Withdraws {@code amount}; true on success. Never throws. */
    public boolean withdraw(Player player, double amount) {
        if (!available() || amount <= 0) {
            return true;
        }
        try {
            Object response = withdrawMethod.invoke(economy, (OfflinePlayer) player, amount);
            if (response == null) {
                return false;
            }
            Method success = response.getClass().getMethod("transactionSuccess");
            Object ok = success.invoke(response);
            return ok instanceof Boolean value && value;
        } catch (Throwable failure) {
            return false;
        }
    }

    public String format(double amount) {
        if (formatMethod != null && available()) {
            try {
                Object formatted = formatMethod.invoke(economy, amount);
                if (formatted instanceof String text) {
                    return text;
                }
            } catch (Throwable ignored) {
                // fall through to plain formatting
            }
        }
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.valueOf(amount);
    }
}
