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
 *
 * <p>The Core service is shared by the whole server, so this module works in
 * its own slot: another module's HUD mode or template never leaks into the
 * ammo bar, and this module never overwrites theirs.</p>
 */
public final class AmmoHud {

    /** The slot this module owns in the shared Core HUD service. */
    private static final String SLOT = "mineplusgun";

    private final HudService hud;
    private volatile GunConfig config;

    public AmmoHud(HudService hud, GunConfig config) {
        this.hud = hud;
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

    /**
     * Pushes the current ammo state for the held weapon.
     *
     * <p>The bar is deliberately quiet: the template renders the numbers, the
     * magazine is tinted by how much is left, and a state word is appended
     * only while something is actually happening (loading, scoping). The
     * fire mode is no longer part of the resting line — it never changed
     * between shots and only made the bar noisy.</p>
     *
     * @param progress boss-bar fill in {@code [0,1]} (reload progress while
     *                 reloading, otherwise the magazine fill)
     */
    public void update(Player player, WeaponDefinition weapon, int mag, int reserve, boolean reloading,
                       boolean scoping, float progress) {
        GunConfig current = config;
        if (current == null || !weapon.usesAmmo()) {
            return;
        }
        boolean low = !reloading && mag <= current.settings().lowAmmoThreshold();
        String magColor = (reloading ? ChatColor.GRAY : low ? ChatColor.RED : ChatColor.WHITE).toString();
        Map<String, String> placeholders = Map.of(
                "weapon", weapon.displayName(),
                "mag", magColor + mag,
                "reserve", String.valueOf(reserve),
                "mode", modeLabel(weapon, scoping));
        String title = HudService.render(current.settings().hudTemplate(), placeholders);
        if (reloading) {
            title += ChatColor.DARK_GRAY + " | " + ChatColor.AQUA + "RELOADING";
        } else if (scoping) {
            title += ChatColor.DARK_GRAY + " | " + ChatColor.AQUA + "SCOPE";
        }
        HudService.Tone tone = reloading ? HudService.Tone.BUSY
                : low ? HudService.Tone.LOW
                : HudService.Tone.NORMAL;
        hud.update(player, SLOT, title, Math.max(0.0f, Math.min(1.0f, progress)), tone);
    }

    /** Convenience overload: the magazine fill is the bar's progress. */
    public void update(Player player, WeaponDefinition weapon, int mag, int reserve, boolean reloading,
                       boolean scoping) {
        float progress = Math.max(0.0f, Math.min(1.0f, mag / (float) Math.max(1, weapon.magazine())));
        update(player, weapon, mag, reserve, reloading, scoping, progress);
    }

    /**
     * Flashes the HUD red for a dry fire; names the reserve supply when there
     * is nothing left to load.
     *
     * @param reserve how many rounds the reserve can still supply
     * @param supply  the reserve supply's display name
     */
    public void flashEmpty(Player player, WeaponDefinition weapon, int reserve, String supply) {
        String suffix = reserve <= 0
                ? ChatColor.DARK_GRAY + " | " + ChatColor.RED + "no " + supply
                : ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "swap hands to reload";
        hud.flash(player, SLOT, ChatColor.RED + weapon.displayName() + ChatColor.DARK_GRAY + " | "
                + ChatColor.RED + "EMPTY" + suffix);
    }

    /** Flashes the HUD when a reload cannot start: the reserve supply is gone. */
    public void flashNoReserve(Player player, String supply) {
        hud.flash(player, SLOT, ChatColor.RED + "NO AMMUNITION" + ChatColor.DARK_GRAY + " | "
                + ChatColor.GRAY + supply + " required");
    }

    /**
     * Flashes the HUD when a reload is asked for on an already loaded weapon:
     * the gesture was consumed, so the player needs to know why nothing
     * happened instead of watching a reload that never started.
     */
    public void flashFull(Player player, WeaponDefinition weapon) {
        hud.flash(player, SLOT, ChatColor.RED + weapon.displayName() + ChatColor.DARK_GRAY + " | "
                + ChatColor.GREEN + "ALREADY LOADED");
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
        hud.apply(SLOT, new HudService.Settings(
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
