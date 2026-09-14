package com.mineplus.gun.hud;

import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.weapon.WeaponDefinition;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

/**
 * Per-player ammo HUD. Defaults to the action bar; a boss bar mode is
 * available per config. Players may toggle it off with {@code /mpgun hud}.
 */
public final class AmmoHud {

    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private volatile GunConfig config;

    public AmmoHud(GunConfig config) {
        this.config = config;
    }

    public void setConfig(GunConfig config) {
        this.config = config;
    }

    /** Toggles the HUD for one player; returns the new "enabled" state. */
    public boolean toggle(UUID playerId) {
        if (hidden.remove(playerId)) {
            return true;
        }
        hidden.add(playerId);
        clear(playerId);
        return false;
    }

    public boolean isEnabled(UUID playerId) {
        return !hidden.contains(playerId);
    }

    /** Pushes the current ammo state for the held weapon. */
    public void update(Player player, WeaponDefinition weapon, int mag, int reserve, boolean reloading) {
        GunConfig current = config;
        if (current == null || current.settings().hudMode() == GunConfig.HudMode.OFF
                || hidden.contains(player.getUniqueId())) {
            return;
        }
        String text = HudText.render(current.settings().hudTemplate(), weapon, mag, reserve, reloading);
        if (current.settings().hudMode() == GunConfig.HudMode.BOSSBAR) {
            updateBossBar(player, weapon, mag, reloading, text, current.settings().lowAmmoThreshold());
        } else {
            sendActionBar(player, text);
        }
    }

    private void updateBossBar(Player player, WeaponDefinition weapon, int mag, boolean reloading,
                               String text, int lowThreshold) {
        BossBar bar = bossBars.computeIfAbsent(player.getUniqueId(), ignored -> {
            BossBar created = Bukkit.createBossBar(text, BarColor.GREEN, BarStyle.SEGMENTED_10);
            created.addPlayer(player);
            return created;
        });
        bar.setTitle(text);
        int capacity = Math.max(1, weapon.magazine());
        float progress = Math.max(0.0f, Math.min(1.0f, mag / (float) capacity));
        bar.setProgress(progress);
        bar.setColor(reloading ? BarColor.YELLOW
                : mag <= lowThreshold ? BarColor.RED : BarColor.GREEN);
        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }
    }

    /** Removes any boss bar tied to the player. */
    public void clear(UUID playerId) {
        BossBar bar = bossBars.remove(playerId);
        if (bar != null) {
            bar.removeAll();
        }
    }

    public void clearAll() {
        for (BossBar bar : bossBars.values()) {
            bar.removeAll();
        }
        bossBars.clear();
        hidden.clear();
    }

    /** Flash the HUD red for a dry fire. */
    public void flashEmpty(Player player, WeaponDefinition weapon) {
        GunConfig current = config;
        if (current == null || current.settings().hudMode() == GunConfig.HudMode.OFF
                || hidden.contains(player.getUniqueId())) {
            return;
        }
        if (current.settings().hudMode() == GunConfig.HudMode.BOSSBAR) {
            updateBossBar(player, weapon, 0, false,
                    ChatColor.RED + weapon.displayName() + ChatColor.DARK_GRAY + " | " + ChatColor.RED + "EMPTY",
                    current.settings().lowAmmoThreshold());
        } else {
            sendActionBar(player, ChatColor.RED + weapon.displayName() + ChatColor.DARK_GRAY + " | "
                    + ChatColor.RED + "EMPTY");
        }
    }

    private static void sendActionBar(Player player, String text) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
    }
}
