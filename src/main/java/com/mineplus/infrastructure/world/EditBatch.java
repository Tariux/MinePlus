package com.mineplus.infrastructure.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An ordered set of block edits submitted as one unit (a crater, a structure
 * pass, a machine teardown). Duplicate positions inside a batch are coalesced
 * with last-wins semantics, so a pass that writes the same cell twice does not
 * pay for two block updates.
 *
 * <p>Immutable; a {@link Builder} assembles one.</p>
 */
public final class EditBatch {

    private final List<BlockEdit> edits;

    private EditBatch(List<BlockEdit> edits) {
        this.edits = List.copyOf(edits);
    }

    /** Batch of the given edits, duplicate positions coalesced (last wins). */
    public static EditBatch of(List<BlockEdit> edits) {
        return new EditBatch(coalesce(edits));
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<BlockEdit> edits() {
        return edits;
    }

    public int size() {
        return edits.size();
    }

    public boolean isEmpty() {
        return edits.isEmpty();
    }

    private static List<BlockEdit> coalesce(List<BlockEdit> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        // LinkedHashMap keeps the first write's slot and takes the last
        // write's value: stable order, one update per cell.
        Map<Long, BlockEdit> byCell = new LinkedHashMap<>(Math.max(16, raw.size()));
        for (BlockEdit edit : raw) {
            if (edit != null) {
                byCell.put(cellKey(edit), edit);
            }
        }
        return new ArrayList<>(byCell.values());
    }

    /** Packs a cell position into one long key (X/Z 26 bits, Y 12 bits, sign-extended). */
    private static long cellKey(BlockEdit edit) {
        return ((long) (edit.x() & 0x3FFFFFF)) << 38
                | ((long) (edit.z() & 0x3FFFFFF)) << 12
                | (edit.y() & 0xFFF);
    }

    public static final class Builder {

        private final List<BlockEdit> edits = new ArrayList<>();

        public Builder add(BlockEdit edit) {
            if (edit != null) {
                edits.add(edit);
            }
            return this;
        }

        public Builder add(List<BlockEdit> batch) {
            if (batch != null) {
                for (BlockEdit edit : batch) {
                    add(edit);
                }
            }
            return this;
        }

        public Builder add(int x, int y, int z, org.bukkit.block.data.BlockData data) {
            return add(BlockEdit.of(x, y, z, data));
        }

        public Builder add(int x, int y, int z, org.bukkit.block.data.BlockData data,
                           boolean physics, BlockEdit.Priority priority) {
            return add(BlockEdit.of(x, y, z, data, physics, priority));
        }

        public int size() {
            return edits.size();
        }

        public EditBatch build() {
            return EditBatch.of(edits);
        }
    }
}
