package com.mineplus.infrastructure.render;

import java.util.Locale;

/**
 * The visual primitive a content definition renders through, selected next to
 * {@link RenderBackend}. Backend says <em>which subsystem draws</em> (virtual
 * engine vs generated pack); kind says <em>what representation is drawn</em>
 * within that subsystem:
 *
 * <ul>
 *   <li>{@link #MODEL} (default) — the geometry model rendered as an
 *       {@code ItemDisplay} holding a custom item (the pack item axis).</li>
 *   <li>{@link #BLOCK} — the geometry model rendered as a single
 *       {@code BlockDisplay} carrying an allocated pack-block carrier state
 *       (the pack block axis). The client resolves the carrier's overridden
 *       blockstate model; collision still comes from the virtual lattice.</li>
 * </ul>
 *
 * <p>Only meaningful for pack backends; the virtual engine always renders the
 * model kind.</p>
 */
public enum RenderKind {

    MODEL,
    BLOCK;

    /** Parses a definition/config value; unknown or blank values fall back to {@link #MODEL}. */
    public static RenderKind fromKey(String key) {
        if (key == null || key.isBlank()) {
            return MODEL;
        }
        return switch (key.trim().toLowerCase(Locale.ROOT)) {
            case "block", "block_display", "display" -> BLOCK;
            default -> MODEL;
        };
    }
}
