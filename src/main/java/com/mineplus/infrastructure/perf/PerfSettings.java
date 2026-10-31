package com.mineplus.infrastructure.perf;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Runtime performance telemetry settings (the {@code PERFORMANCE} section of
 * {@code settings.mp.yml}). Immutable so hot paths read it without locking.
 */
public record PerfSettings(
        boolean telemetry,
        boolean adaptive,
        int poseBudgetMs
) {

    public PerfSettings {
        poseBudgetMs = Math.max(1, poseBudgetMs);
    }

    public static PerfSettings defaults() {
        return new PerfSettings(true, true, 3);
    }

    public long poseBudgetNanos() {
        return poseBudgetMs * 1_000_000L;
    }

    public static PerfSettings parse(FileConfiguration yaml, PerfSettings fallback) {
        ConfigurationSection section = yaml == null ? null : yaml.getConfigurationSection("PERFORMANCE");
        if (section == null) {
            return fallback;
        }
        PerfSettings d = defaults();
        return new PerfSettings(
                section.getBoolean("TELEMETRY", d.telemetry()),
                section.getBoolean("ADAPTIVE", d.adaptive()),
                Math.max(1, section.getInt("POSE_BUDGET_MS", d.poseBudgetMs())));
    }
}
