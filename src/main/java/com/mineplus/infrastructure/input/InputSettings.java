package com.mineplus.infrastructure.input;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Inbound input settings (the {@code INPUT} section of {@code settings.mp.yml}).
 *
 * <p>Off by default: with {@code FAST_PATH: false} the input service installs no
 * Netty handler and touches no channel, so packet handling is exactly the
 * historical event path.</p>
 *
 * @param fastPath read swing/use/interact packets at network arrival instead of
 *                 waiting for the next tick's Bukkit event
 */
public record InputSettings(boolean fastPath) {

    public static InputSettings defaults() {
        return new InputSettings(false);
    }

    public static InputSettings parse(FileConfiguration yaml, InputSettings fallback) {
        InputSettings defaults = fallback == null ? defaults() : fallback;
        if (!yaml.isConfigurationSection("INPUT")) {
            return defaults;
        }
        var section = yaml.getConfigurationSection("INPUT");
        return new InputSettings(section.getBoolean("FAST_PATH", defaults.fastPath()));
    }
}
