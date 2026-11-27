package com.mineplus.infrastructure.collision.mask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A collision/occupancy mask over a 16×16×16 sub-voxel grid of one block.
 *
 * <p>Cells are addressed with {@code x} (east), {@code y} (up) and {@code z}
 * (south), each in {@code [0, 16)}. The bit index is
 * {@code (y * 16 + z) * 16 + x}, so a layer slice reads left-to-right and
 * north-to-south. A mask is mutable; {@link #copy()} snapshots it, and the
 * combinators mutate and return {@code this} so allocations stay out of the
 * fitter's inner loop.</p>
 *
 * <p>Masks are not internally synchronised. Publish them as immutable and work
 * on copies; the fitter does exactly that.</p>
 */
public final class VoxelMask {

    /** Sub-voxels per block edge. */
    public static final int RESOLUTION = 16;
    /** {@code RESOLUTION^3}. */
    public static final int CELLS = RESOLUTION * RESOLUTION * RESOLUTION;
    private static final int WORDS = CELLS / Long.SIZE;
    private static final double EPSILON = 1.0e-7;

    private final long[] words;
    private int count;

    public VoxelMask() {
        this.words = new long[WORDS];
    }

    private VoxelMask(long[] words) {
        this.words = words;
        int total = 0;
        for (long word : words) {
            total += Long.bitCount(word);
        }
        this.count = total;
    }

    /** An empty mask. */
    public static VoxelMask empty() {
        return new VoxelMask();
    }

    /** A mask with every cell solid. */
    public static VoxelMask full() {
        VoxelMask mask = new VoxelMask();
        Arrays.fill(mask.words, -1L);
        mask.count = CELLS;
        return mask;
    }

    /** Rebuilds a mask from packed words (64 longs); the array is copied. */
    public static VoxelMask fromWords(long[] packed) {
        if (packed == null || packed.length != WORDS) {
            throw new IllegalArgumentException("Expected " + WORDS + " words, got "
                    + (packed == null ? "null" : packed.length));
        }
        return new VoxelMask(Arrays.copyOf(packed, WORDS));
    }

    /** Copies of the packed words, for the atlas cache. */
    public long[] words() {
        return Arrays.copyOf(words, WORDS);
    }

    /** Number of packed words. */
    public static int wordCount() {
        return WORDS;
    }

    /** Bit index of a cell. */
    public static int index(int x, int y, int z) {
        return (y * RESOLUTION + z) * RESOLUTION + x;
    }

    private static boolean valid(int value) {
        return value >= 0 && value < RESOLUTION;
    }

    /** True when the cell is solid; out-of-range cells read as empty. */
    public boolean get(int x, int y, int z) {
        if (!valid(x) || !valid(y) || !valid(z)) {
            return false;
        }
        int index = index(x, y, z);
        return (words[index >>> 6] & (1L << (index & 63))) != 0L;
    }

    /** Sets one cell; out-of-range coordinates are ignored. */
    public VoxelMask set(int x, int y, int z, boolean solid) {
        if (!valid(x) || !valid(y) || !valid(z)) {
            return this;
        }
        int index = index(x, y, z);
        int word = index >>> 6;
        long bit = 1L << (index & 63);
        boolean present = (words[word] & bit) != 0L;
        if (solid && !present) {
            words[word] |= bit;
            count++;
        } else if (!solid && present) {
            words[word] &= ~bit;
            count--;
        }
        return this;
    }

    /** Fills an inclusive cell box; coordinates are clamped to the grid. */
    public VoxelMask fill(int x0, int y0, int z0, int x1, int y1, int z1, boolean solid) {
        int minX = Math.max(0, Math.min(x0, x1));
        int minY = Math.max(0, Math.min(y0, y1));
        int minZ = Math.max(0, Math.min(z0, z1));
        int maxX = Math.min(RESOLUTION - 1, Math.max(x0, x1));
        int maxY = Math.min(RESOLUTION - 1, Math.max(y0, y1));
        int maxZ = Math.min(RESOLUTION - 1, Math.max(z0, z1));
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    set(x, y, z, solid);
                }
            }
        }
        return this;
    }

    /** Number of solid cells. */
    public int count() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public boolean isFull() {
        return count == CELLS;
    }

    /** Solid fraction in {@code [0,1]}. */
    public double fillRatio() {
        return count / (double) CELLS;
    }

    public VoxelMask copy() {
        return new VoxelMask(Arrays.copyOf(words, WORDS));
    }

    /** Union: adds every solid cell of {@code other}. */
    public VoxelMask or(VoxelMask other) {
        for (int i = 0; i < WORDS; i++) {
            words[i] |= other.words[i];
        }
        recount();
        return this;
    }

    /** Intersection: keeps cells solid in both masks. */
    public VoxelMask and(VoxelMask other) {
        for (int i = 0; i < WORDS; i++) {
            words[i] &= other.words[i];
        }
        recount();
        return this;
    }

    /** Difference: removes every cell solid in {@code other}. */
    public VoxelMask andNot(VoxelMask other) {
        for (int i = 0; i < WORDS; i++) {
            words[i] &= ~other.words[i];
        }
        recount();
        return this;
    }

    /** Number of cells solid in both masks. */
    public int intersectionCount(VoxelMask other) {
        int total = 0;
        for (int i = 0; i < WORDS; i++) {
            total += Long.bitCount(words[i] & other.words[i]);
        }
        return total;
    }

    /** Number of cells solid in exactly one mask. */
    public int symmetricDifference(VoxelMask other) {
        int total = 0;
        for (int i = 0; i < WORDS; i++) {
            total += Long.bitCount(words[i] ^ other.words[i]);
        }
        return total;
    }

    private void recount() {
        int total = 0;
        for (long word : words) {
            total += Long.bitCount(word);
        }
        count = total;
    }

    /** Inclusive cell bounds {@code {minX,minY,minZ,maxX,maxY,maxZ}} or null. */
    public int[] bounds() {
        if (count == 0) {
            return null;
        }
        int minX = RESOLUTION;
        int minY = RESOLUTION;
        int minZ = RESOLUTION;
        int maxX = -1;
        int maxY = -1;
        int maxZ = -1;
        for (int y = 0; y < RESOLUTION; y++) {
            for (int z = 0; z < RESOLUTION; z++) {
                for (int x = 0; x < RESOLUTION; x++) {
                    if (get(x, y, z)) {
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        minZ = Math.min(minZ, z);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                        maxZ = Math.max(maxZ, z);
                    }
                }
            }
        }
        return new int[] {minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** Highest solid layer, or {@code -1} when empty. */
    public int topY() {
        for (int y = RESOLUTION - 1; y >= 0; y--) {
            for (int z = 0; z < RESOLUTION; z++) {
                for (int x = 0; x < RESOLUTION; x++) {
                    if (get(x, y, z)) {
                        return y;
                    }
                }
            }
        }
        return -1;
    }

    /** Top surface height in block units ({@code (topY+1)/16}), 0 when empty. */
    public double topSurface() {
        int top = topY();
        return top < 0 ? 0.0 : (top + 1) / (double) RESOLUTION;
    }

    /** Rotates the mask around the cell's vertical axis, 90° per quarter turn. */
    public VoxelMask rotateY90(int quarters) {
        int turns = Math.floorMod(quarters, 4);
        for (int turn = 0; turn < turns; turn++) {
            VoxelMask rotated = new VoxelMask();
            for (int y = 0; y < RESOLUTION; y++) {
                for (int z = 0; z < RESOLUTION; z++) {
                    for (int x = 0; x < RESOLUTION; x++) {
                        if (get(x, y, z)) {
                            rotated.set(RESOLUTION - 1 - z, y, x, true);
                        }
                    }
                }
            }
            wordsDeepCopyFrom(rotated);
        }
        return this;
    }

    private void wordsDeepCopyFrom(VoxelMask source) {
        System.arraycopy(source.words, 0, words, 0, WORDS);
        count = source.count;
    }

    /** Mirrors the mask on the east/west axis. */
    public VoxelMask mirrorX() {
        VoxelMask mirrored = new VoxelMask();
        for (int y = 0; y < RESOLUTION; y++) {
            for (int z = 0; z < RESOLUTION; z++) {
                for (int x = 0; x < RESOLUTION; x++) {
                    if (get(x, y, z)) {
                        mirrored.set(RESOLUTION - 1 - x, y, z, true);
                    }
                }
            }
        }
        wordsDeepCopyFrom(mirrored);
        return this;
    }

    /** Removes a border of {@code layers} cells (shrink). */
    public VoxelMask inset(int layers) {
        if (layers <= 0) {
            return this;
        }
        VoxelMask source = copy();
        for (int y = 0; y < RESOLUTION; y++) {
            for (int z = 0; z < RESOLUTION; z++) {
                for (int x = 0; x < RESOLUTION; x++) {
                    boolean keep = source.get(x, y, z)
                            && source.get(x - layers, y, z) && source.get(x + layers, y, z)
                            && source.get(x, y - layers, z) && source.get(x, y + layers, z)
                            && source.get(x, y, z - layers) && source.get(x, y, z + layers);
                    set(x, y, z, keep);
                }
            }
        }
        return this;
    }

    /** Adds a border of {@code layers} cells (grow), clipped to the block. */
    public VoxelMask dilate(int layers) {
        if (layers <= 0) {
            return this;
        }
        VoxelMask source = copy();
        for (int y = 0; y < RESOLUTION; y++) {
            for (int z = 0; z < RESOLUTION; z++) {
                for (int x = 0; x < RESOLUTION; x++) {
                    if (source.get(x, y, z)) {
                        fill(x - layers, y - layers, z - layers,
                                x + layers, y + layers, z + layers, true);
                    }
                }
            }
        }
        return this;
    }

    /**
     * Rasterises collision boxes given in block-local units ({@code 0..1}) into
     * a fresh mask.
     */
    public static VoxelMask rasterize(List<double[]> boxes, RasterMode mode) {
        VoxelMask mask = new VoxelMask();
        if (boxes == null || boxes.isEmpty()) {
            return mask;
        }
        double unit = 1.0 / RESOLUTION;
        for (int y = 0; y < RESOLUTION; y++) {
            for (int z = 0; z < RESOLUTION; z++) {
                for (int x = 0; x < RESOLUTION; x++) {
                    double minX = x * unit;
                    double minY = y * unit;
                    double minZ = z * unit;
                    double maxX = minX + unit;
                    double maxY = minY + unit;
                    double maxZ = minZ + unit;
                    boolean solid = switch (mode) {
                        case CONSERVATIVE -> overlapsAny(boxes, minX, minY, minZ, maxX, maxY, maxZ);
                        case CENTERED -> containsAnyCenter(boxes,
                                (minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5);
                        case MAJORITY -> majorityInside(boxes, minX, minY, minZ, maxX, maxY, maxZ);
                    };
                    if (solid) {
                        mask.set(x, y, z, true);
                    }
                }
            }
        }
        return mask;
    }

    private static boolean overlapsAny(List<double[]> boxes,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        for (double[] box : boxes) {
            if (box[3] <= minX + EPSILON || box[0] >= maxX - EPSILON
                    || box[4] <= minY + EPSILON || box[1] >= maxY - EPSILON
                    || box[5] <= minZ + EPSILON || box[2] >= maxZ - EPSILON) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean containsAnyCenter(List<double[]> boxes, double x, double y, double z) {
        for (double[] box : boxes) {
            if (x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4]
                    && z >= box[2] && z <= box[5]) {
                return true;
            }
        }
        return false;
    }

    private static boolean majorityInside(List<double[]> boxes,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        int inside = 0;
        for (int sy = 0; sy < 2; sy++) {
            for (int sz = 0; sz < 2; sz++) {
                for (int sx = 0; sx < 2; sx++) {
                    double x = sx == 0 ? minX + (maxX - minX) * 0.25 : minX + (maxX - minX) * 0.75;
                    double y = sy == 0 ? minY + (maxY - minY) * 0.25 : minY + (maxY - minY) * 0.75;
                    double z = sz == 0 ? minZ + (maxZ - minZ) * 0.25 : minZ + (maxZ - minZ) * 0.75;
                    if (containsAnyCenter(boxes, x, y, z)) {
                        inside++;
                    }
                }
            }
        }
        return inside >= 5;
    }

    /** Collision boxes (block-local units) covering every solid cell. */
    public List<double[]> toBoxes() {
        List<double[]> boxes = new ArrayList<>();
        int[] bounds = bounds();
        if (bounds == null) {
            return boxes;
        }
        double unit = 1.0 / RESOLUTION;
        // Coalesce runs along x per (y,z) to keep the list short.
        for (int y = bounds[1]; y <= bounds[4]; y++) {
            for (int z = bounds[2]; z <= bounds[5]; z++) {
                int runStart = -1;
                for (int x = bounds[0]; x <= bounds[3] + 1; x++) {
                    boolean solid = x <= bounds[3] && get(x, y, z);
                    if (solid && runStart < 0) {
                        runStart = x;
                    } else if (!solid && runStart >= 0) {
                        boxes.add(new double[] {runStart * unit, y * unit, z * unit,
                                x * unit, (y + 1) * unit, (z + 1) * unit});
                        runStart = -1;
                    }
                }
            }
        }
        return boxes;
    }

    /** A one-layer ASCII view, for debug output and test failures. */
    public String asciiSlice(int y) {
        StringBuilder builder = new StringBuilder(RESOLUTION * (RESOLUTION + 1));
        for (int z = 0; z < RESOLUTION; z++) {
            for (int x = 0; x < RESOLUTION; x++) {
                builder.append(get(x, y, z) ? '#' : '.');
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VoxelMask mask && Arrays.equals(words, mask.words);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(words);
    }

    @Override
    public String toString() {
        int[] bounds = bounds();
        return "VoxelMask{" + count + "/" + CELLS + " cells, bounds="
                + (bounds == null ? "none" : Arrays.toString(bounds)) + "}";
    }
}
