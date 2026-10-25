package com.mineplus.gun.hud;

import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.weapon.WeaponDefinition;
import com.mineplus.infrastructure.hud.HudService;
import java.util.Map;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/**
 * The weapon ammo HUD: a thin module adapter over the Core {@link HudService}
 * (action bar / boss bar / off, per-player toggle, bar tones). The module
 * owns only the template placeholder values and the bar semantics — the
 * generic presentation machinery lives in the Core.
 */
public final class AmmoHud {

    private final HudService hud = new HudService();
    private volatile GunConfig config;

    public AmmoHud(GunConfig config) {
        this.config = config;
        applySettings(config);
    }

    public void setConfig(GunConfig config) {
        this.config = config;
        applySettings(config);
    }

    /** Toggles the HUD for one player; returns the new "enabled" state. */
    public boolean toggle(UUID playerId) {
        return hud.toggle(playerId);
    }

    public boolean isEnabled(UUID playerId) {
        return hud.isEnabled(playerId);
    }

    /** Pushes the current ammo state for the held weapon. */
    public void update(Player player, WeaponDefinition weapon, int mag, int reserve, boolean reloading,
                       boolean scoping) {
        GunConfig current = config;
        if (current == null || !weapon.usesAmmo()) {
            return;
        }
        Map<String, String> placeholders = Map.of(
                "weapon", weapon.displayName(),
                "mag", String.valueOf(mag),
                "reserve", String.valueOf(reserve),
                "mode", modeLabel(weapon, scoping));
        String title = HudService.render(current.settings().hudTemplate(), placeholders);
        float progress = Math.max(0.0f, Math.min(1.0f, mag / (float) Math.max(1, weapon.magazine())));
        HudService.Tone tone = reloading ? HudService.Tone.BUSY
                : mag <= current.settings().lowAmmoThreshold() ? HudService.Tone.LOW
                : HudService.Tone.NORMAL;
        hud.update(player, title, progress, tone);
    }

    /** Flashes the HUD red for a dry fire; names the nugget supply when there is none. */
    public void flashEmpty(Player player, WeaponDefinition weapon, int reserve) {
        String suffix = reserve <= 0
                ? ChatColor.DARK_GRAY + " | " + ChatColor.RED + "no gold nuggets"
                : ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "swap hands to reload";
        hud.flash(player, ChatColor.RED + weapon.displayName() + ChatColor.DARK_GRAY + " | "
                + ChatColor.RED + "EMPTY" + suffix);
    }

    /** Flashes the HUD when a reload cannot start: the reserve supply is gone. */
    public void flashNoReserve(Player player) {
        hud.flash(player, ChatColor.RED + "NO AMMUNITION" + ChatColor.DARK_GRAY + " | "
                + ChatColor.GRAY + "gold nuggets required");
    }

    /** Removes any boss bar tied to the player (on quit / world switch). */
    public void clear(UUID playerId) {
        hud.clear(playerId);
    }

    public void clearAll() {
        hud.clearAll();
    }

    private void applySettings(GunConfig config) {
        if (config == null) {
            return;
        }
        hud.apply(new HudService.Settings(
                toMode(config.settings().hudMode()),
                config.settings().hudTemplate()));
    }

    private static HudService.Mode toMode(GunConfig.HudMode mode) {
        return switch (mode) {
            case ACTIONBAR -> HudService.Mode.ACTIONBAR;
            case BOSSBAR -> HudService.Mode.BOSSBAR;
            case OFF -> HudService.Mode.OFF;
        };
    }

    private static String modeLabel(WeaponDefinition weapon, boolean scoping) {
        if (weapon.scope() && scoping) {
            return "SCOPE";
        }
        return weapon.fireMode().name();
    }
}
