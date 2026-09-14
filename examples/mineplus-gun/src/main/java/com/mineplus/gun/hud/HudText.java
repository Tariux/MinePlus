package com.mineplus.gun.hud;

import com.mineplus.gun.weapon.WeaponDefinition;
import org.bukkit.ChatColor;

/** Fills the configurable ammo-HUD template. */
public final class HudText {

    private HudText() {
    }

    /**
     * @param template the configured template
     * @param weapon   the weapon in hand
     * @param mag      rounds in the magazine
     * @param reserve  reserve rounds ({@code -1} renders {@code ∞})
     * @param reloading whether a reload is in progress
     */
    public static String render(String template, WeaponDefinition weapon, int mag, int reserve, boolean reloading) {
        String mode = reloading ? "RELOAD" : weapon.fireMode().name();
        String reserveText = reserve < 0 ? "∞" : String.valueOf(reserve);
        String rendered = template
                .replace("{weapon}", weapon.displayName())
                .replace("{mag}", String.valueOf(mag))
                .replace("{reserve}", reserveText)
                .replace("{mode}", mode);
        return ChatColor.translateAlternateColorCodes('&', rendered);
    }
}
