package com.mineplus.infrastructure.render;

import java.util.Locale;

/**
 * The unified rendering vocabulary: one enum that names both <em>which
 * subsystem draws</em> and <em>what primitive is drawn</em>, collapsing the
 * previously separate {@link RenderBackend} and {@link RenderKind} axes into a
 * single declarative choice.
 *
 * <p>This is the recommended value for new content. {@code renderBackend} and
 * {@code renderKind} remain fully supported and are mapped onto a mode by
 * {@link #of(RenderBackend, RenderKind)}; the existing behavior is unchanged.</p>
 *
 * <ul>
 *   <li>{@link #VIRTUAL} — the packless virtual engine ({@code VIRTUAL} + {@code MODEL}).</li>
 *   <li>{@link #PACK_ITEM} — pack item model ({@code PACK} + {@code MODEL}).</li>
 *   <li>{@link #PACK_BLOCK} — pack block display ({@code PACK} + {@code BLOCK}).</li>
 *   <li>{@link #HYBRID_ITEM} — virtual render plus the pack item layered on top
 *       ({@code VIRTUAL_PLUS_PACK} + {@code MODEL}); the {@code hybrid} key maps here.</li>
 *   <li>{@link #HYBRID_BLOCK} — virtual render plus the pack block layered on top
 *       ({@code VIRTUAL_PLUS_PACK} + {@code BLOCK}).</li>
 * </ul>
 *
 * <p><b>Limitation:</b> a mode selects one primitive for the whole rendered
 * level. Mixing backends or primitives per cube/face is not supported by the
 * engine — the pack axis draws an entire model client-side as a single model or
 * block, while the virtual axis draws per-cube displays. A level therefore
 * renders entirely through its selected mode.</p>
 */
public enum RenderMode {

    VIRTUAL(RenderBackend.VIRTUAL, RenderKind.MODEL),
    PACK_ITEM(RenderBackend.PACK, RenderKind.MODEL),
    PACK_BLOCK(RenderBackend.PACK, RenderKind.BLOCK),
    HYBRID_ITEM(RenderBackend.VIRTUAL_PLUS_PACK, RenderKind.MODEL),
    HYBRID_BLOCK(RenderBackend.VIRTUAL_PLUS_PACK, RenderKind.BLOCK);

    private final RenderBackend backend;
    private final RenderKind kind;

    RenderMode(RenderBackend backend, RenderKind kind) {
        this.backend = backend;
        this.kind = kind;
    }

    /** The subsystem this mode renders through. */
    public RenderBackend backend() {
        return backend;
    }

    /** The primitive this mode draws within its subsystem. */
    public RenderKind kind() {
        return kind;
    }

    /** The stable lowercase config/JSON key for this mode. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Maps the legacy backend/kind pair onto its canonical mode. */
    public static RenderMode of(RenderBackend backend, RenderKind kind) {
        RenderBackend resolvedBackend = backend == null ? RenderBackend.VIRTUAL : backend;
        RenderKind resolvedKind = kind == null ? RenderKind.MODEL : kind;
        for (RenderMode mode : values()) {
            if (mode.backend == resolvedBackend && mode.kind == resolvedKind) {
                return mode;
            }
        }
        return VIRTUAL;
    }

    /**
     * Parses a {@code renderMode} config/JSON value. New keys map directly;
     * legacy {@code renderBackend}/{@code renderKind} keys are also accepted so
     * a single field can carry either vocabulary. Unknown or blank values fall
     * back to {@link #VIRTUAL}.
     */
    public static RenderMode fromKey(String key) {
        if (key == null || key.isBlank()) {
            return VIRTUAL;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace('+', '_').replace(' ', '_');
        return switch (normalized) {
            case "pack", "pack_item", "pack_item_model", "resource_pack", "pack_only", "item" -> PACK_ITEM;
            case "pack_block", "block", "block_display", "display" -> PACK_BLOCK;
            case "hybrid", "hybrid_item", "virtual_pack", "virtual_plus_pack", "both" -> HYBRID_ITEM;
            case "hybrid_block", "virtual_pack_block", "virtual_plus_pack_block" -> HYBRID_BLOCK;
            default -> VIRTUAL;
        };
    }
}
