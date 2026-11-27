package com.mineplus.infrastructure.collision.source;

import java.util.Locale;

/**
 * What a blueprint says about one block cell.
 *
 * <ul>
 *   <li>{@code SOLID} — the cell must collide.</li>
 *   <li>{@code EMPTY} — the cell must not collide (and is not clickable).</li>
 *   <li>{@code FORCE_BLOCK:<state>} — use exactly this block state, whatever
 *       the fitter would score.</li>
 *   <li>{@code PASSABLE_HITBOX} — no collision, but the cell is clickable
 *       (routes to an Interaction entity or a passable outline state).</li>
 *   <li>{@code TOP_SURFACE} — the cell is a standing surface: always a real
 *       block, chosen for a flat top.</li>
 *   <li>{@code STEP} — the cell is a walk-up step: real block, top at 0.5.</li>
 * </ul>
 */
public enum DirectiveType {
    SOLID,
    EMPTY,
    FORCE_BLOCK,
    PASSABLE_HITBOX,
    TOP_SURFACE,
    STEP;

    /** Parses a directive token, e.g. {@code FORCE_BLOCK:minecraft:oak_slab}. */
    public static CellDirective parse(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("empty directive");
        }
        String trimmed = token.trim();
        int colon = trimmed.indexOf(':');
        String head = (colon < 0 ? trimmed : trimmed.substring(0, colon))
                .toUpperCase(Locale.ROOT);
        DirectiveType type;
        try {
            type = valueOf(head);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("unknown directive '" + trimmed
                    + "'; expected SOLID, EMPTY, FORCE_BLOCK:<state>, PASSABLE_HITBOX,"
                    + " TOP_SURFACE or STEP");
        }
        if (type == FORCE_BLOCK) {
            String state = colon < 0 ? "" : trimmed.substring(colon + 1).trim();
            if (state.isEmpty()) {
                throw new IllegalArgumentException(
                        "FORCE_BLOCK needs a block state after the colon");
            }
            return new CellDirective(type, state);
        }
        if (colon >= 0) {
            throw new IllegalArgumentException("directive " + type + " takes no argument");
        }
        return new CellDirective(type, null);
    }
}
