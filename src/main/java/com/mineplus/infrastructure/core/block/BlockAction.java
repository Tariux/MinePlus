package com.mineplus.infrastructure.core.block;

import java.util.Locale;

/**
 * Definition event actions: {@code message:<text>}, {@code sound:<key>},
 * {@code command:<command>}. Parsed without Bukkit so definitions can be
 * validated at load time.
 */
public record BlockAction(Kind kind, String value) {

    public enum Kind {
        MESSAGE,
        SOUND,
        COMMAND,
        UNKNOWN
    }

    public static BlockAction parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new BlockAction(Kind.UNKNOWN, "");
        }
        int colon = raw.indexOf(':');
        String prefix = colon < 0 ? raw : raw.substring(0, colon);
        String value = colon < 0 ? "" : raw.substring(colon + 1).trim();
        return switch (prefix.trim().toLowerCase(Locale.ROOT)) {
            case "message" -> new BlockAction(value.isBlank() ? Kind.UNKNOWN : Kind.MESSAGE, value);
            case "sound" -> new BlockAction(value.isBlank() ? Kind.UNKNOWN : Kind.SOUND, value);
            case "command" -> new BlockAction(value.isBlank() ? Kind.UNKNOWN : Kind.COMMAND, value);
            default -> new BlockAction(Kind.UNKNOWN, raw.trim());
        };
    }
}
