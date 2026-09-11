package com.mineplus.pack;

/**
 * How compiled pack artifacts reach players. Independent of compilation: the
 * {@code PackDeliveryService} only needs a URL to hand to the client.
 */
public enum PackDeliveryMode {

    /** No delivery; the server operator distributes the artifact manually. */
    DISABLED,

    /** The plugin serves artifact bytes from a small local HTTP endpoint. */
    LOCAL,

    /** The operator hosts the artifact externally; only a static URL is used. */
    STATIC_URL;

    /** Parses a config value; unknown or blank values fall back to {@link #LOCAL}. */
    public static PackDeliveryMode fromKey(String key) {
        if (key == null || key.isBlank()) {
            return LOCAL;
        }
        return switch (key.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_')) {
            case "DISABLED", "OFF", "MANUAL", "NONE" -> DISABLED;
            case "LOCAL", "LOCAL_BYTE_ENDPOINT", "LOCAL_ENDPOINT" -> LOCAL;
            case "STATIC_URL", "STATIC", "URL" -> STATIC_URL;
            // An unrecognized value must not silently disable delivery: the
            // optimal default keeps the automatic endpoint alive and the
            // misconfigured key surfaces through /mineplus pack status.
            default -> LOCAL;
        };
    }
}
