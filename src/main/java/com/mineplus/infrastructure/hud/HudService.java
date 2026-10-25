package com.mineplus.infrastructure.hud;

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
 * The shared player HUD bar: an action bar (default) or a boss bar driven by
 * a configurable template, with per-player toggle, a low-value tone and a
 * busy (e.g. reload-while) tone. Template placeholders are free-form — the
 * caller fills whatever the module needs:
 *
 * <pre>{@code hud.update(player, HudService.render(template, Map.of(
 *         "weapon", name, "mag", "3", "reserve", "12", "mode", "SEMI")),
 *         3f / 6f, HudService.Tone.NORMAL);}</pre>
 *
 * <p>Functionality every module kept re-rolling; presentation state stays
 * owned here (hidden players, boss bar lifecycle, cleanup on quit is the
 * caller's job via {@link #clear}).</p>
 */
public final class HudService {

    /** Where the HUD renders. */
    public enum Mode { ACTIONBAR, BOSSBAR, OFF }

    /** The semantic state of the shown value, mapped to a boss bar color. */
    public enum Tone { NORMAL, LOW, BUSY }

    /** HUD configuration; the template is a legacy '&'-colored string. */
    public record Settings(Mode mode, String template) {
        public Settings {
            mode = mode == null ? Mode.ACTIONBAR : mode;
            template = template == null || template.isBlank()
                    ? "{title} {value}"
                    : template;
        }
    }

    private static final BarColor[] TONE_COLORS = {
            BarColor.GREEN,   // Tone.NORMAL
            BarColor.RED,     // Tone.LOW
            BarColor.YELLOW,  // Tone.BUSY
    };

    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    private volatile Settings settings = new Settings(Mode.ACTIONBAR, null);

    /** Applies new HUD settings (mode + template); players keep their toggle. */
    public void apply(Settings settings) {
        this.settings = settings == null ? new Settings(Mode.ACTIONBAR, null) : settings;
    }

    public Settings settings() {
        return settings;
    }

    /** Toggles the HUD for one player; returns the new enabled state. */
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

    /**
     * Pushes one HUD frame. {@code progress} fills the boss bar
     * ({@code 0..1}); a negative value keeps the bar at its last fill.
     */
    public void update(Player player, String title, float progress, Tone tone) {
        Settings current = settings;
        if (current.mode() == Mode.OFF || hidden.contains(player.getUniqueId())) {
            return;
        }
        if (current.mode() == Mode.BOSSBAR) {
            bossBar(player, title, progress, tone);
        } else {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    TextComponent.fromLegacyText(title));
        }
    }

    /** Pushes a transient emphasized frame (dry fire, warnings) in red. */
    public void flash(Player player, String text) {
        update(player, ChatColor.RED + text, 0.0f, Tone.LOW);
    }

    /** Removes any boss bar tied to one player. */
    public void clear(UUID playerId) {
        BossBar bar = bars.remove(playerId);
        if (bar != null) {
            bar.removeAll();
        }
    }

    public void clearAll() {
        for (BossBar bar : bars.values()) {
            bar.removeAll();
        }
        bars.clear();
        hidden.clear();
    }

    private void bossBar(Player player, String title, float progress, Tone tone) {
        BossBar bar = bars.computeIfAbsent(player.getUniqueId(), ignored -> {
            BossBar created = Bukkit.createBossBar(title, BarColor.GREEN, BarStyle.SEGMENTED_10);
            created.addPlayer(player);
            return created;
        });
        bar.setTitle(title);
        if (progress >= 0.0f) {
            bar.setProgress(Math.max(0.0f, Math.min(1.0f, progress)));
        }
        bar.setColor(TONE_COLORS[tone.ordinal()]);
        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }
    }

    /** Fills a legacy-colored template with placeholders and translates color codes. */
    public static String render(String template, Map<String, String> placeholders) {
        String rendered = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return ChatColor.translateAlternateColorCodes('&', rendered);
    }
}
