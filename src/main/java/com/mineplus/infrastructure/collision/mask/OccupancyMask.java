package com.mineplus.infrastructure.collision.mask;

import java.util.Arrays;
import java.util.Locale;

/**
 * A model-space occupancy grid: sub-voxels at a configurable resolution,
 * aligned to the block grid, with the model origin at the grid's minimum corner.
 *
 * <p>Unlike {@link VoxelMask}, which describes exactly one block, this grid
 * spans {@code sizeX × sizeY × sizeZ} blocks and is what occupancy sources
 * produce and the fitter consumes. Cell bit order is
 * {@code (y * sizeZ + z) * sizeX + x}. Block dimensions are derived from the
 * cell dimensions, so a rotation may swap the X and Z extents.</p>
 *
 * <p>Yaw rotations of exactly 90° are lossless; other angles are resampled onto
 * the rotated grid with a centre sample, which is what the visual model does
 * (ItemDisplay rotates the mesh, the collision grid follows as closely as the
 * resolution allows). Instances are mutable and not synchronised, like
 * {@link VoxelMask}.</p>
 */
public final class OccupancyMask {

    /** Highest resolution the fitter samples into one block. */
    public static final int FIT_RESOLUTION = 16;

    private final int resolution;
    private int sizeX;
    private int sizeY;
    private int sizeZ;
    private long[] bits;
    private int count;

    public OccupancyMask(int resolution, int blocksX, int blocksY, int blocksZ) {
        if (resolution < 1 || resolution > 64) {
            throw new IllegalArgumentException("resolution must be 1..64, got " + resolution);
        }
        if (blocksX < 1 || blocksY < 1 || blocksZ < 1) {
            throw new IllegalArgumentException("occupancy grid needs at least one block per axis");
        }
        this.resolution = resolution;
        this.sizeX = blocksX * resolution;
        this.sizeY = blocksY * resolution;
        this.sizeZ = blocksZ * resolution;
        this.bits = new long[wordCount(sizeX, sizeY, sizeZ)];
    }

    /** Empty grid with the given block dimensions. */
    public static OccupancyMask empty(int resolution, int blocksX, int blocksY, int blocksZ) {
        return new OccupancyMask(resolution, blocksX, blocksY, blocksZ);
    }

    public int resolution() {
        return resolution;
    }

    /** Block dimensions {@code {x,y,z}}. */
    public int[] blockSize() {
        return new int[] {blocksX(), blocksY(), blocksZ()};
    }

    public int blocksX() {
        return Math.max(1, sizeX / resolution);
    }

    public int blocksY() {
        return Math.max(1, sizeY / resolution);
    }

    public int blocksZ() {
        return Math.max(1, sizeZ / resolution);
    }

    public int cellsX() {
        return sizeX;
    }

    public int cellsY() {
        return sizeY;
    }

    public int cellsZ() {
        return sizeZ;
    }

    public int count() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public boolean isFull() {
        return count == sizeX * sizeY * sizeZ;
    }

    /** Cell lookup; out-of-range reads false. */
    public boolean get(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return false;
        }
        int index = (y * sizeZ + z) * sizeX + x;
        return (bits[index >>> 6] & (1L << (index & 63))) != 0L;
    }

    /** Cell write; out-of-range writes are ignored. */
    public OccupancyMask set(int x, int y, int z, boolean solid) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return this;
        }
        int index = (y * sizeZ + z) * sizeX + x;
        int word = index >>> 6;
        long bit = 1L << (index & 63);
        boolean present = (bits[word] & bit) != 0L;
        if (solid && !present) {
            bits[word] |= bit;
            count++;
        } else if (!solid && present) {
            bits[word] &= ~bit;
            count--;
        }
        return this;
    }

    /** Inclusive cell-space fill, clamped to the grid. */
    public OccupancyMask fill(int x0, int y0, int z0, int x1, int y1, int z1, boolean solid) {
        int minX = Math.max(0, Math.min(x0, x1));
        int minY = Math.max(0, Math.min(y0, y1));
        int minZ = Math.max(0, Math.min(z0, z1));
        int maxX = Math.min(sizeX - 1, Math.max(x0, x1));
        int maxY = Math.min(sizeY - 1, Math.max(y0, y1));
        int maxZ = Math.min(sizeZ - 1, Math.max(z0, z1));
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    set(x, y, z, solid);
                }
            }
        }
        return this;
    }

    /** Clears an inclusive cell-space box. */
    public OccupancyMask clear(int x0, int y0, int z0, int x1, int y1, int z1) {
        return fill(x0, y0, z0, x1, y1, z1, false);
    }

    /**
     * Fills from block-space box coordinates in block units
     * ({@code [0, blocks]}), rounding outward so no surface is lost.
     */
    public OccupancyMask fillBlockBox(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        return fill((int) Math.floor(minX * resolution), (int) Math.floor(minY * resolution),
                (int) Math.floor(minZ * resolution), (int) Math.ceil(maxX * resolution) - 1,
                (int) Math.ceil(maxY * resolution) - 1, (int) Math.ceil(maxZ * resolution) - 1,
                true);
    }

    public OccupancyMask copy() {
        OccupancyMask copy = new OccupancyMask(resolution, blocksX(), blocksY(), blocksZ());
        copy.bits = Arrays.copyOf(bits, bits.length);
        copy.sizeX = sizeX;
        copy.sizeY = sizeY;
        copy.sizeZ = sizeZ;
        copy.count = count;
        return copy;
    }

    /** Union in place. */
    public OccupancyMask or(OccupancyMask other) {
        requireCompatible(other);
        for (int i = 0; i < bits.length; i++) {
            bits[i] |= other.bits[i];
        }
        recount();
        return this;
    }

    /** Intersection in place. */
    public OccupancyMask and(OccupancyMask other) {
        requireCompatible(other);
        for (int i = 0; i < bits.length; i++) {
            bits[i] &= other.bits[i];
        }
        recount();
        return this;
    }

    /** Difference in place. */
    public OccupancyMask andNot(OccupancyMask other) {
        requireCompatible(other);
        for (int i = 0; i < bits.length; i++) {
            bits[i] &= ~other.bits[i];
        }
        recount();
        return this;
    }

    /** Copies every solid cell of {@code other} that this grid does not already have. */
    public OccupancyMask overlay(OccupancyMask other) {
        return or(other);
    }

    private void requireCompatible(OccupancyMask other) {
        if (other.resolution != resolution || other.sizeX != sizeX
                || other.sizeY != sizeY || other.sizeZ != sizeZ) {
            throw new IllegalArgumentException("occupancy grids differ: this "
                    + blocksX() + "×" + blocksY() + "×" + blocksZ() + "@" + resolution + ", other "
                    + other.blocksX() + "×" + other.blocksY() + "×" + other.blocksZ() + "@"
                    + other.resolution);
        }
    }

    private void recount() {
        int total = 0;
        for (long word : bits) {
            total += Long.bitCount(word);
        }
        count = total;
    }

    /** Inclusive cell bounds or null when empty. */
    public int[] bounds() {
        if (count == 0) {
            return null;
        }
        int minX = sizeX;
        int minY = sizeY;
        int minZ = sizeZ;
        int maxX = -1;
        int maxY = -1;
        int maxZ = -1;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
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

    /** Top surface height of one block column in block units, 0 when empty. */
    public double topSurfaceAt(int blockX, int blockZ) {
        for (int y = sizeY - 1; y >= 0; y--) {
            for (int cz = blockZ * resolution; cz < (blockZ + 1) * resolution; cz++) {
                for (int cx = blockX * resolution; cx < (blockX + 1) * resolution; cx++) {
                    if (get(cx, y, cz)) {
                        return (y + 1) / (double) resolution;
                    }
                }
            }
        }
        return 0.0;
    }

    /**
     * Samples one block cell into a 16³ fitter mask. When the source resolution
     * is coarser than 16 the sample is replicated; when finer it is decimated by
     * centre sampling.
     */
    public VoxelMask blockMask(int blockX, int blockY, int blockZ) {
        VoxelMask mask = VoxelMask.empty();
        double ratio = resolution / (double) FIT_RESOLUTION;
        for (int y = 0; y < FIT_RESOLUTION; y++) {
            for (int z = 0; z < FIT_RESOLUTION; z++) {
                for (int x = 0; x < FIT_RESOLUTION; x++) {
                    int sx = (int) Math.floor((blockX * FIT_RESOLUTION + x + 0.5) * ratio);
                    int sy = (int) Math.floor((blockY * FIT_RESOLUTION + y + 0.5) * ratio);
                    int sz = (int) Math.floor((blockZ * FIT_RESOLUTION + z + 0.5) * ratio);
                    if (get(sx, sy, sz)) {
                        mask.set(x, y, z, true);
                    }
                }
            }
        }
        return mask;
    }

    /** Rotates around the vertical axis, exact for multiples of 90°. */
    public OccupancyMask rotateY90(int quarters) {
        int turns = Math.floorMod(quarters, 4);
        if (turns == 0) {
            return this;
        }
        if (turns == 2) {
            OccupancyMask rotated = new OccupancyMask(resolution, blocksX(), blocksY(), blocksZ());
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    for (int x = 0; x < sizeX; x++) {
                        if (get(x, y, z)) {
                            rotated.set(sizeX - 1 - x, y, sizeZ - 1 - z, true);
                        }
                    }
                }
            }
            adopt(rotated);
            return this;
        }
        OccupancyMask rotated = new OccupancyMask(resolution, blocksZ(), blocksY(), blocksX());
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    if (get(x, y, z)) {
                        if (turns == 1) {
                            rotated.set(rotated.sizeX - 1 - z, y, x, true);
                        } else {
                            rotated.set(z, y, rotated.sizeZ - 1 - x, true);
                        }
                    }
                }
            }
        }
        adopt(rotated);
        return this;
    }

    /**
     * Rotates by an arbitrary yaw in degrees around the grid's vertical centre.
     * Exact for multiples of 90°; otherwise resampled with inverse mapping.
     */
    public OccupancyMask rotateYaw(double degrees) {
        double normalized = ((degrees % 360) + 360) % 360;
        if (Math.abs(normalized - Math.round(normalized / 90.0) * 90.0) < 1e-6) {
            return rotateY90((int) Math.round(normalized / 90.0));
        }
        double radians = Math.toRadians(normalized);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double centreX = sizeX / 2.0;
        double centreZ = sizeZ / 2.0;
        int square = Math.max(blocksX(), blocksZ());
        OccupancyMask rotated = new OccupancyMask(resolution, square, blocksY(), square);
        double newCentreX = rotated.sizeX / 2.0;
        double newCentreZ = rotated.sizeZ / 2.0;
        for (int y = 0; y < rotated.sizeY; y++) {
            for (int z = 0; z < rotated.sizeZ; z++) {
                for (int x = 0; x < rotated.sizeX; x++) {
                    double dx = x + 0.5 - newCentreX;
                    double dz = z + 0.5 - newCentreZ;
                    double sourceX = cos * dx + sin * dz + centreX;
                    double sourceZ = -sin * dx + cos * dz + centreZ;
                    int sx = (int) Math.floor(sourceX);
                    int sz = (int) Math.floor(sourceZ);
                    if (get(sx, y, sz)) {
                        rotated.set(x, y, z, true);
                    }
                }
            }
        }
        adopt(rotated);
        return this;
    }

    /** Mirrors east/west. */
    public OccupancyMask mirrorX() {
        OccupancyMask mirrored = new OccupancyMask(resolution, blocksX(), blocksY(), blocksZ());
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    if (get(x, y, z)) {
                        mirrored.set(sizeX - 1 - x, y, z, true);
                    }
                }
            }
        }
        adopt(mirrored);
        return this;
    }

    /** Shrinks each solid run by {@code layers} cells on all six sides. */
    public OccupancyMask inset(int layers) {
        if (layers <= 0) {
            return this;
        }
        OccupancyMask source = copy();
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
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

    /** Grows solid cells by {@code layers} cells on all six sides. */
    public OccupancyMask dilate(int layers) {
        if (layers <= 0) {
            return this;
        }
        OccupancyMask source = copy();
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    if (source.get(x, y, z)) {
                        fill(x - layers, y - layers, z - layers,
                                x + layers, y + layers, z + layers, true);
                    }
                }
            }
        }
        return this;
    }

    /** Convenience for single-block models. */
    public VoxelMask toSingleBlockMask() {
        if (blocksX() != 1 || blocksY() != 1 || blocksZ() != 1) {
            throw new IllegalStateException("not a single-block occupancy grid: "
                    + blocksX() + "×" + blocksY() + "×" + blocksZ());
        }
        return blockMask(0, 0, 0);
    }

    private void adopt(OccupancyMask source) {
        this.bits = source.bits;
        this.sizeX = source.sizeX;
        this.sizeY = source.sizeY;
        this.sizeZ = source.sizeZ;
        this.count = source.count;
    }

    private static int wordCount(int sizeX, int sizeY, int sizeZ) {
        long cells = (long) sizeX * sizeY * sizeZ;
        if (cells <= 0 || cells > Integer.MAX_VALUE * 64L) {
            throw new IllegalArgumentException("occupancy grid too large: " + cells + " cells");
        }
        return (int) ((cells + 63) / 64);
    }

    /** Compact description for debug output. */
    public String describe() {
        return blocksX() + "×" + blocksY() + "×" + blocksZ() + " @" + resolution
                + " (" + count + " cells)";
    }

    @Override
    public String toString() {
        return "OccupancyMask{" + describe().toLowerCase(Locale.ROOT) + '}';
    }
}
