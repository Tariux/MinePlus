package com.mineplus.pack;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Resource pack system settings (the {@code PACK} section of
 * {@code settings.mp.yml}).
 *
 * <p>Defaults keep the plugin byte-identical to a pre-pack build:
 * {@code enabled=false} compiles nothing, serves nothing, and pushes nothing.
 * Every other default is inert until {@code enabled=true}.</p>
 *
 * @param enabled            master switch; false = the whole subsystem is inert
 * @param deliveryMode       how artifacts reach players
 * @param localHost          bind address of the local byte endpoint (LOCAL mode)
 * @param localPort          bind port of the local byte endpoint (LOCAL mode)
 * @param publicUrl          external URL prefix override (reverse proxy in front of LOCAL); blank = derive from host:port
 * @param staticUrl          the externally hosted artifact URL (STATIC_URL mode); may contain {@code {hash}}
 * @param promptMessage      optional prompt shown with the pack request; blank = vanilla prompt
 * @param promptDelayTicks   delay between join and the automatic pack prompt
 * @param maxCachedArtifacts how many {@code mp-<hash>.zip} artifacts to keep before pruning
 * @param packFormatOverride non-zero overrides the detected {@code pack.mcmeta} pack_format
 */
public record PackSettings(
        boolean enabled,
        PackDeliveryMode deliveryMode,
        String localHost,
        int localPort,
        String publicUrl,
        String staticUrl,
        String promptMessage,
        int promptDelayTicks,
        int maxCachedArtifacts,
        int packFormatOverride
) {

    public PackSettings {
        localHost = localHost == null || localHost.isBlank() ? "0.0.0.0" : localHost.trim();
        localPort = Math.max(1, Math.min(65535, localPort));
        publicUrl = publicUrl == null ? "" : publicUrl.trim();
        staticUrl = staticUrl == null ? "" : staticUrl.trim();
        promptMessage = promptMessage == null ? "" : promptMessage;
        promptDelayTicks = Math.max(0, promptDelayTicks);
        maxCachedArtifacts = Math.max(1, maxCachedArtifacts);
        packFormatOverride = Math.max(0, packFormatOverride);
    }

    public static PackSettings defaults() {
        return new PackSettings(false, PackDeliveryMode.DISABLED, "0.0.0.0", 8163,
                "", "", "", 60, 8, 0);
    }

    public static PackSettings parse(FileConfiguration yaml, PackSettings fallback) {
        if (!yaml.isConfigurationSection("PACK")) {
            return fallback;
        }
        var root = yaml.getConfigurationSection("PACK");
        var delivery = root.getConfigurationSection("DELIVERY");
        var cache = root.getConfigurationSection("CACHE");
        PackSettings defaults = defaults();
        return new PackSettings(
                root.getBoolean("ENABLED", defaults.enabled()),
                PackDeliveryMode.fromKey(delivery == null
                        ? defaults.deliveryMode().name()
                        : delivery.getString("MODE", defaults.deliveryMode().name())),
                delivery == null ? defaults.localHost() : delivery.getString("LOCAL_HOST", defaults.localHost()),
                delivery == null ? defaults.localPort() : delivery.getInt("LOCAL_PORT", defaults.localPort()),
                delivery == null ? defaults.publicUrl() : delivery.getString("PUBLIC_URL", defaults.publicUrl()),
                delivery == null ? defaults.staticUrl() : delivery.getString("STATIC_URL", defaults.staticUrl()),
                delivery == null ? defaults.promptMessage() : delivery.getString("PROMPT_MESSAGE", defaults.promptMessage()),
                delivery == null ? defaults.promptDelayTicks() : delivery.getInt("PROMPT_DELAY_TICKS", defaults.promptDelayTicks()),
                cache == null ? defaults.maxCachedArtifacts() : cache.getInt("MAX_ARTIFACTS", defaults.maxCachedArtifacts()),
                root.getInt("PACK_FORMAT_OVERRIDE", defaults.packFormatOverride())
        );
    }
}
