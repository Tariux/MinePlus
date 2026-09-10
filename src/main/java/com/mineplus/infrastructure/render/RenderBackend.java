package com.mineplus.infrastructure.render;

import java.util.Locale;

/**
 * The rendering axis a piece of content renders through — the one shared
 * vocabulary both rendering subsystems and all content definitions speak.
 *
 * <p>This is the backend-selection choke point: content definitions
 * (e.g. multiblock levels) declare a backend, and
 * {@code ModelRenderingManager} routes rendering to it. No other subsystem
 * branches on the choice.</p>
 *
 * <ul>
 *   <li>{@link #VIRTUAL} — the packless virtual rendering engine (default):
 *       bbmodel geometry reconstructed out of vanilla block displays; works on
 *       completely vanilla clients.</li>
 *   <li>{@link #PACK} — client-assisted rendering through the generated
 *       resource pack: the content's registered item model renders at full
 *       client fidelity for players with the pack; players without the pack
 *       see the backing vanilla item.</li>
 *   <li>{@link #VIRTUAL_PLUS_PACK} — the virtual render always (collision and
 *       packless visuals), plus the pack representation layered on top for
 *       players with the pack.</li>
 * </ul>
 *
 * <p>When the pack subsystem is disabled, {@code PACK} and
 * {@code VIRTUAL_PLUS_PACK} degrade to {@link #VIRTUAL} — declared in one
 * place, never scattered.</p>
 */
public enum RenderBackend {

    VIRTUAL,
    PACK,
    VIRTUAL_PLUS_PACK;

    /** Parses a definition/config value; unknown or blank values fall back to {@link #VIRTUAL}. */
    public static RenderBackend fromKey(String key) {
        if (key == null || key.isBlank()) {
            return VIRTUAL;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace('+', '_');
        return switch (normalized) {
            case "pack", "pack_only", "resource_pack" -> PACK;
            case "virtual_pack", "virtual_plus_pack", "both", "hybrid" -> VIRTUAL_PLUS_PACK;
            default -> VIRTUAL;
        };
    }

    /** True when this backend wants the virtual render to exist. */
    public boolean includesVirtual() {
        return this != PACK;
    }

    /** True when this backend wants the pack representation to exist. */
    public boolean includesPack() {
        return this != VIRTUAL;
    }
}
