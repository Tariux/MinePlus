package com.mineplus.infrastructure.virtual;

import java.util.Locale;

/**
 * The {@code mineplus} metadata block of an imported model (v1 template stamp).
 *
 * <p>The Blockbench plugin (and any external tool, e.g. the devtools MCP server)
 * writes this top-level block so a file can be recognized as a Mine Plus
 * template by anything that reads JSON:
 *
 * <pre>{@code
 * "mineplus": {
 *   "format": 1,
 *   "plugin": "mineplus_generic_display",
 *   "template": "mineplus:guns/peacemaker",
 *   "kind": "item"
 * }
 * }</pre>
 *
 * <p>The streaming importer skips unknown keys, so the block is purely
 * additive: the same file parses identically with or without it. Detection is
 * best-effort — a block with missing fields is still a template with nulls.
 */
public record MineplusTemplate(int format, String plugin, String id, String kind, String title) {

    /** Metadata schema version written by the current Blockbench plugin. */
    public static final int CURRENT_FORMAT = 1;

    /** Parses the streaming-captured fields; tolerates nulls and bad kinds. */
    public static MineplusTemplate of(Integer format, String plugin, String id, String kind, String title) {
        String normalizedKind = kind == null || kind.isBlank() ? null
                : kind.trim().toLowerCase(Locale.ROOT);
        return new MineplusTemplate(
                format == null ? CURRENT_FORMAT : format,
                plugin == null || plugin.isBlank() ? null : plugin.trim(),
                id == null || id.isBlank() ? null : id.trim(),
                normalizedKind,
                title == null || title.isBlank() ? null : title.trim());
    }

    /** Human-readable summary used in import logs. */
    public String describe() {
        StringBuilder out = new StringBuilder("Mine Plus template");
        if (id != null) {
            out.append(" '").append(id).append('\'');
        }
        if (kind != null) {
            out.append(" (").append(kind).append(')');
        }
        out.append(", format v").append(format);
        if (plugin != null) {
            out.append(", by ").append(plugin);
        }
        return out.toString();
    }
}
