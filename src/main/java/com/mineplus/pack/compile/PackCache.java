package com.mineplus.pack.compile;

import com.mineplus.util.DebugLogger;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Artifact cache: hash-named zips under {@code <dataFolder>/packs/}. Same
 * registration graph -> same artifact name -> cache hit, so unchanged content
 * never recompiles and clients never re-download. Prunes to the newest
 * {@code MAX_ARTIFACTS} files, newest first by last-modified time.
 */
public final class PackCache {

    private final File folder;

    public PackCache(File dataFolder) {
        this.folder = new File(dataFolder, "packs");
    }

    public File folder() {
        return folder;
    }

    /** The file an artifact with this graph hash would live at (no existence check). */
    public File artifactFile(String graphHash) {
        String name = "mp-" + graphHash.substring(0, Math.min(12, graphHash.length())) + ".zip";
        return new File(folder, name);
    }

    public Optional<PackArtifact> find(String graphHash) {
        File file = artifactFile(graphHash);
        if (!file.isFile()) {
            return Optional.empty();
        }
        return Optional.of(readArtifact(file, graphHash));
    }

    /** Recovers artifact metadata (sha1, size) from the file on disk. */
    public PackArtifact readArtifact(File file, String graphHash) {
        try {
            byte[] sha1 = sha1(file);
            return new PackArtifact(file, graphHash, hex(sha1), 0,
                    com.mineplus.pack.PackFormat.ItemRepresentation.MODERN_ITEM_MODEL,
                    0, file.length(), 0L);
        } catch (IOException exception) {
            DebugLogger.warning("[PackCache] Failed to hash artifact " + file.getName() + ": "
                    + exception.getMessage());
            return null;
        }
    }

    public void ensureFolder() throws IOException {
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Could not create pack cache folder " + folder.getAbsolutePath());
        }
    }

    /** Keeps the newest {@code maxArtifacts} artifacts; the current one is pinned. */
    public void prune(int maxArtifacts, File keep) {
        File[] files = folder.listFiles((dir, name) -> name.startsWith("mp-") && name.endsWith(".zip"));
        if (files == null || files.length <= maxArtifacts) {
            return;
        }
        List<File> sorted = new java.util.ArrayList<>(Arrays.asList(files));
        sorted.sort(Comparator.comparingLong(File::lastModified).reversed());
        for (int i = maxArtifacts; i < sorted.size(); i++) {
            File stale = sorted.get(i);
            if (stale.equals(keep)) {
                continue;
            }
            try {
                Files.deleteIfExists(stale.toPath());
                DebugLogger.info("[PackCache] Pruned stale artifact " + stale.getName() + ".");
            } catch (IOException exception) {
                DebugLogger.warning("[PackCache] Could not prune " + stale.getName() + ": "
                        + exception.getMessage());
            }
        }
    }

    static byte[] sha1(File file) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(file.toPath())) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 unavailable", exception);
        }
    }

    static String hex(byte[] data) {
        return java.util.HexFormat.of().formatHex(data);
    }
}
