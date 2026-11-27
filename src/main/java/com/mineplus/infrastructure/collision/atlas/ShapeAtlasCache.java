package com.mineplus.infrastructure.collision.atlas;

import com.mineplus.infrastructure.collision.mask.VoxelMask;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Binary cache for a {@link ShapeAtlas}.
 *
 * <p>Format (all big-endian): magic {@code MPSA}, format version, the
 * {@link AtlasKey} fields, entry count, then per entry the state, material,
 * bounds, flags, booleans, cost and 64 mask words. A cache is only accepted
 * when its key equals the requested key; otherwise it is ignored and rebuilt.
 * Writes go to a sibling {@code .tmp} file and are moved into place, so a crash
 * never leaves a half-written atlas behind.</p>
 *
 * <p>The loader is strict about structure but tolerant of a corrupt file: the
 * bad file is renamed to {@code .corrupt} and an empty result is returned.</p>
 */
public final class ShapeAtlasCache {

    private static final int MAGIC = 0x4D505341;
    private static final int FORMAT = 1;
    private static final Logger LOGGER = Logger.getLogger("Mineplus");

    private ShapeAtlasCache() {
    }

    /** Saves the atlas atomically. */
    public static void save(Path file, ShapeAtlas atlas) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(temp)))) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeUTF(atlas.key().mcVersion());
            out.writeUTF(atlas.key().provider());
            out.writeUTF(atlas.key().poolHash());
            out.writeInt(atlas.key().resolution());
            out.writeUTF(atlas.key().rasterMode().name());
            out.writeInt(atlas.entries().size());
            for (ShapeEntry entry : atlas.entries()) {
                out.writeUTF(entry.state());
                out.writeUTF(entry.material());
                double[] bounds = entry.collisionBounds();
                for (int i = 0; i < 6; i++) {
                    out.writeDouble(i < bounds.length ? bounds[i] : 0.0);
                }
                out.writeInt(ShapeFlag.toBits(entry.flags()));
                out.writeBoolean(entry.hasCollision());
                out.writeBoolean(entry.hasOutline());
                out.writeBoolean(entry.fullCube());
                out.writeBoolean(entry.visibleByDefault());
                out.writeBoolean(entry.invisibleInPack());
                out.writeInt(entry.backendCost());
                out.writeInt(entry.bitCount());
                for (long word : entry.mask().words()) {
                    out.writeLong(word);
                }
            }
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Loads a cache when it exists and matches the key. */
    public static Optional<ShapeAtlas> load(Path file, AtlasKey key) {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) {
                return corrupt(file, "bad magic");
            }
            int format = in.readInt();
            if (format != FORMAT) {
                return corrupt(file, "format " + format + " is not " + FORMAT);
            }
            AtlasKey stored = new AtlasKey(in.readUTF(), in.readUTF(), in.readUTF(),
                    in.readInt(), com.mineplus.infrastructure.collision.mask.RasterMode
                            .fromKey(in.readUTF(), null));
            if (!stored.equals(key)) {
                LOGGER.info("[collision] atlas cache ignored: key changed (stored "
                        + stored.describe() + ", running " + key.describe() + ")");
                return Optional.empty();
            }
            int count = in.readInt();
            if (count < 0 || count > 1_000_000) {
                return corrupt(file, "entry count " + count);
            }
            List<ShapeEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String state = in.readUTF();
                String material = in.readUTF();
                double[] bounds = new double[6];
                for (int b = 0; b < 6; b++) {
                    bounds[b] = in.readDouble();
                }
                Set<ShapeFlag> flags = ShapeFlag.fromBits(in.readInt());
                boolean hasCollision = in.readBoolean();
                boolean hasOutline = in.readBoolean();
                boolean fullCube = in.readBoolean();
                boolean visible = in.readBoolean();
                boolean invisibleInPack = in.readBoolean();
                int cost = in.readInt();
                int bitCount = in.readInt();
                long[] words = new long[VoxelMask.wordCount()];
                for (int w = 0; w < words.length; w++) {
                    words[w] = in.readLong();
                }
                VoxelMask mask = VoxelMask.fromWords(words);
                if (mask.count() != bitCount) {
                    return corrupt(file, "mask count mismatch for " + state);
                }
                entries.add(new ShapeEntry(state, material, mask, bitCount, bounds,
                        hasCollision, hasOutline, fullCube, flags, visible, invisibleInPack,
                        cost));
            }
            return Optional.of(new ShapeAtlas(key, entries));
        } catch (EOFException truncated) {
            return corrupt(file, "truncated");
        } catch (IOException | RuntimeException broken) {
            return corrupt(file, broken.getMessage());
        }
    }

    private static Optional<ShapeAtlas> corrupt(Path file, String reason) {
        LOGGER.log(Level.WARNING, "[collision] atlas cache unusable (" + reason
                + "); rebuilding. File: " + file.getFileName());
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Leaving the file is harmless: the key/probe check rejects it anyway.
        }
        return Optional.empty();
    }
}
