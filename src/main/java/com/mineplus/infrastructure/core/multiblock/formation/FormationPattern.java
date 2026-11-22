package com.mineplus.infrastructure.core.multiblock.formation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An immutable multi-block formation pattern: a set of relative offsets around
 * an anchor, each expecting a specific vanilla block material. Patterns are
 * authored once by a module and matched against the real world by
 * {@link FormationMatcher}.
 *
 * <p>Offsets are stored as {@code x,y,z -> material-name} strings (uppercase
 * Bukkit {@link org.bukkit.Material} names). The anchor itself is offset
 * {@code 0,0,0}; its expected material is part of the map like any other cell.
 *
 * <p>Patterns are yaw-symmetric by construction: a pattern defined along one
 * facing is matched in all four 90° rotations unless the author marks it
 * {@link #rotationLocked}.
 */
public final class FormationPattern {

    private final String id;
    private final Map<String, String> cells;
    private final boolean rotationLocked;

    private FormationPattern(String id, Map<String, String> cells, boolean rotationLocked) {
        this.id = id;
        this.cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
        this.rotationLocked = rotationLocked;
    }

    /** Starts a builder for a pattern with the given registry id. */
    public static Builder of(String id) {
        return new Builder(id);
    }

    public String id() {
        return id;
    }

    /** Relative offsets ({@code "x,y,z"}) mapped to expected {@link org.bukkit.Material} names. */
    public Map<String, String> cells() {
        return cells;
    }

    /** When {@code true} the matcher will not try the 90° rotated variants. */
    public boolean rotationLocked() {
        return rotationLocked;
    }

    /** The pattern's bounding size in each axis (for quick world scans). */
    public int[] bounds() {
        int minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
        for (String key : cells.keySet()) {
            String[] parts = key.split(",");
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        return new int[] { maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1 };
    }

    public static final class Builder {
        private final String id;
        private final Map<String, String> cells = new LinkedHashMap<>();
        private boolean rotationLocked;

        private Builder(String id) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("formation id must not be blank");
            }
            this.id = id;
        }

        /** Adds one cell at the given offset expecting the given material. */
        public Builder cell(int x, int y, int z, String materialName) {
            if (materialName == null || materialName.isBlank()) {
                throw new IllegalArgumentException("material must not be blank");
            }
            cells.put(x + "," + y + "," + z, materialName.toUpperCase(java.util.Locale.ROOT));
            return this;
        }

        /** Prevents the matcher from trying the 90°-rotated variants of this pattern. */
        public Builder rotationLocked() {
            this.rotationLocked = true;
            return this;
        }

        public FormationPattern build() {
            if (cells.isEmpty()) {
                throw new IllegalStateException("formation pattern '" + id + "' has no cells");
            }
            return new FormationPattern(id, cells, rotationLocked);
        }
    }
}
