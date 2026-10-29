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
 *
 * <p><b>One service, many modules.</b> The service is shared by the whole
 * server, so each module owns a <em>slot</em>: its own mode and template. Two
 * modules showing an action bar at the same time still fight over the single
 * action bar line — that is the client's limit, not the service's — but they
 * no longer fight over the mode or the template.</p>
 */
public final class HudService {

    /** The slot used when a caller does not name one. */
    public static final String DEFAULT_SLOT = "default";

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

    private static final Settings DEFAULT_SETTINGS = new Settings(Mode.ACTIONBAR, null);

    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
    /**
     * One boss bar per player <em>per slot</em>. Keying only by player made two
     * modules sharing the service write into one bar — the last module to
     * update owned the title, and either module clearing on quit removed the
     * other's bar as well.
     */
    private final Map<UUID, Map<String, BossBar>> bars = new ConcurrentHashMap<>();
    private final Map<String, Settings> slots = new ConcurrentHashMap<>();

    /** Applies new HUD settings (mode + template); players keep their toggle. */
    public void apply(Settings settings) {
        apply(DEFAULT_SLOT, settings);
    }

    /**
     * Applies the settings of one slot. A slot that was never configured falls
     * back to the built-in defaults (action bar, plain title), never to another
     * module's slot.
     */
    public void apply(String slot, Settings settings) {
        String key = slot == null || slot.isBlank() ? DEFAULT_SLOT : slot;
        slots.put(key, settings == null ? DEFAULT_SETTINGS : settings);
    }

    public Settings settings() {
        return settings(DEFAULT_SLOT);
    }

    /** The settings of one slot, or the built-in defaults when it has none. */
    public Settings settings(String slot) {
        String key = slot == null || slot.isBlank() ? DEFAULT_SLOT : slot;
        Settings current = slots.get(key);
        return current == null ? DEFAULT_SETTINGS : current;
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
        update(player, DEFAULT_SLOT, title, progress, tone);
    }

    /** Pushes one HUD frame into a slot, using that slot's mode and template. */
    public void update(Player player, String slot, String title, float progress, Tone tone) {
        Settings current = settings(slot);
        if (current.mode() == Mode.OFF || hidden.contains(player.getUniqueId())) {
            return;
        }
        if (current.mode() == Mode.BOSSBAR) {
            bossBar(player, slot, title, progress, tone);
        } else {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    TextComponent.fromLegacyText(title));
        }
    }

    /** Pushes a transient emphasized frame (dry fire, warnings) in red. */
    public void flash(Player player, String text) {
        flash(player, DEFAULT_SLOT, text);
    }

    /** Pushes a transient emphasized frame into a slot, in red. */
    public void flash(Player player, String slot, String text) {
        update(player, slot, ChatColor.RED + text, 0.0f, Tone.LOW);
    }

    /** Removes every boss bar tied to one player, in every slot. */
    public void clear(UUID playerId) {
        Map<String, BossBar> owned = bars.remove(playerId);
        if (owned != null) {
            for (BossBar bar : owned.values()) {
                bar.removeAll();
            }
        }
    }

    /**
     * Removes every boss bar this service owns. The per-player toggles are
     * preferences, not state: one module shutting down must not re-enable the
     * HUD of another one that the player had turned off.
     */
    public void clearAll() {
        for (Map<String, BossBar> owned : bars.values()) {
            for (BossBar bar : owned.values()) {
                bar.removeAll();
            }
        }
        bars.clear();
    }

    private void bossBar(Player player, String slot, String title, float progress, Tone tone) {
        String key = slot == null || slot.isBlank() ? DEFAULT_SLOT : slot;
        BossBar bar = bars.computeIfAbsent(player.getUniqueId(),
                        ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, ignored -> {
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
