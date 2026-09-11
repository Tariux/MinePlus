package com.mineplus.pack.compile;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.pack.PackFormat;
import com.mineplus.pack.PackSettings;
import com.mineplus.pack.asset.ItemModelAsset;
import com.mineplus.pack.asset.ModelAsset;
import com.mineplus.pack.asset.PackAsset;
import com.mineplus.pack.asset.RawAsset;
import com.mineplus.pack.asset.TextureAsset;
import com.mineplus.util.DebugLogger;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Deterministic, dependency-aware pack compiler.
 *
 * <p>Pipeline: ordered registry snapshot -> per-asset content hashes ->
 * graph hash (artifact identity) -> cache lookup -> on miss, serialize each
 * asset (memoized by content hash, so unchanged assets are never
 * re-serialized) and assemble one zip with fixed entry order, timestamps and
 * compression. Same registrations always produce byte-identical artifacts.</p>
 *
 * <p>Dependency awareness: the registry holds only what modules actually
 * registered (models referenced by items, their textures, raw assets); the
 * compiler includes exactly those assets and their transitive texture
 * references — never every file on disk.</p>
 *
 * <p>Failure isolation: an asset that fails to serialize is skipped with a
 * warning; the rest of the pack compiles. A failed compile never destroys the
 * previous artifact.</p>
 */
public final class PackCompiler {

    /** Memoized serialization output keyed by asset content hash; bounded, session-scoped. */
    private static final int SERIALIZED_CACHE_LIMIT = 1024;
    private final Map<String, byte[]> serializedCache = new ConcurrentHashMap<>();

    private final VirtualBlockManager virtualBlockManager;
    private final PackCache cache;
    private final PackSettings settings;

    public PackCompiler(VirtualBlockManager virtualBlockManager, PackCache cache, PackSettings settings) {
        this.virtualBlockManager = virtualBlockManager;
        this.cache = cache;
        this.settings = settings;
    }

    /**
     * SHA-256 over the ordered (entry path, asset content hash) graph plus the
     * compile context (pack format, item representation) — the artifact
     * identity. Version-strategy changes therefore produce new artifacts
     * instead of resurrecting stale ones.
     */
    public static String graphHash(List<PackAsset> assets, int packFormat, PackFormat.ItemRepresentation representation) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (PackAsset asset : assets) {
                digest.update(asset.zipEntryPath().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try {
                    digest.update(asset.contentHash().getBytes(StandardCharsets.UTF_8));
                } catch (IOException exception) {
                    digest.update(asset.id().getBytes(StandardCharsets.UTF_8));
                }
                digest.update((byte) 0);
            }
            digest.update(("pack_format=" + packFormat + ";representation=" + representation)
                    .getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /**
     * Compiles (or reuses) the artifact for the registry snapshot.
     *
     * @return the artifact, or {@code null} when nothing was compilable
     */
    public PackArtifact compile(List<PackAsset> snapshot) {
        if (snapshot.isEmpty()) {
            return null;
        }
        String bukkitVersion = org.bukkit.Bukkit.getBukkitVersion();
        int packFormat = PackFormat.packFormat(bukkitVersion, settings.packFormatOverride());
        PackFormat.ItemRepresentation representation = PackFormat.itemRepresentation(bukkitVersion);
        String hash = graphHash(snapshot, packFormat, representation);
        PackArtifact cached = cache.find(hash).orElse(null);
        if (cached != null) {
            DebugLogger.info("[PackCompiler] Cache hit " + cached.artifactName()
                    + " — no recompilation, no client re-download.");
            return cached;
        }

        long start = System.nanoTime();
        List<PackAsset> failed = new ArrayList<>();

        try {
            cache.ensureFolder();
            File target = cache.artifactFile(hash);
            File partial = new File(cache.folder(), target.getName() + ".partial");
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(partial))) {
                zip.setLevel(6);
                writeEntry(zip, "pack.mcmeta", packMetaJson(packFormat).getBytes(StandardCharsets.UTF_8));

                // Deterministic order: sorted zip entry paths across all assets.
                Map<String, byte[]> entries = new TreeMap<>();
                for (PackAsset asset : snapshot) {
                    byte[] bytes = serialized(asset);
                    if (bytes == null) {
                        failed.add(asset);
                        continue;
                    }
                    entries.put(asset.zipEntryPath(), bytes);
                }
                if (representation == PackFormat.ItemRepresentation.LEGACY_CUSTOM_MODEL_DATA) {
                    // Legacy items live in shared per-material host files, not their
                    // own entries; drop the modern definitions and emit the hosts.
                    for (ItemModelAsset item : itemsOnly(snapshot)) {
                        entries.remove(item.modernEntryPath());
                    }
                    for (Map.Entry<String, byte[]> legacy : ItemModelWriter.legacyHostFiles(itemsOnly(snapshot)).entrySet()) {
                        entries.put(legacy.getKey(), legacy.getValue());
                    }
                }
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    writeEntry(zip, entry.getKey(), entry.getValue());
                }
            }

            if (entriesAllFailed(snapshot, failed)) {
                // Nothing survived: do not publish an empty artifact; keep the previous one.
                partial.delete();
                DebugLogger.warning("[PackCompiler] All assets failed to serialize; keeping previous artifact.");
                return null;
            }

            if (!partial.renameTo(target)) {
                // Windows/FS edge: renameTo fails silently — surface it instead
                // of serving an artifact that was never published.
                partial.delete();
                throw new IOException("Could not publish artifact " + target.getName()
                        + " (rename of the partial file failed)");
            }
            PackArtifact artifact = new PackArtifact(
                    target, hash, java.util.HexFormat.of().formatHex(PackCache.sha1(target)),
                    packFormat, representation,
                    snapshot.size() - failed.size(), target.length(),
                    (System.nanoTime() - start) / 1_000_000L);
            cache.prune(settings.maxCachedArtifacts(), target);
            DebugLogger.info("[PackCompiler] Compiled " + artifact.artifactName() + ": "
                    + artifact.assetCount() + " asset(s), " + artifact.byteSize() + " bytes, "
                    + artifact.compileMillis() + " ms (pack_format " + packFormat + ", " + representation + ").");
            for (PackAsset asset : failed) {
                DebugLogger.warning("[PackCompiler] Asset '" + asset.id() + "' (owner '" + asset.owner()
                        + "') failed to serialize and was skipped.");
            }
            return artifact;
        } catch (IOException exception) {
            DebugLogger.warning("[PackCompiler] Compilation failed, previous artifact kept: "
                    + exception.getMessage());
            return null;
        }
    }

    private boolean entriesAllFailed(List<PackAsset> snapshot, List<PackAsset> failed) {
        return failed.size() >= snapshot.size();
    }

    private List<ItemModelAsset> itemsOnly(List<PackAsset> snapshot) {
        List<ItemModelAsset> items = new ArrayList<>();
        for (PackAsset asset : snapshot) {
            if (asset instanceof ItemModelAsset item) {
                items.add(item);
            }
        }
        return items;
    }

    /** Serialized bytes with per-content-hash memoization; null when the asset fails. */
    private byte[] serialized(PackAsset asset) {
        String hash;
        try {
            hash = asset.contentHash();
        } catch (Exception exception) {
            return null;
        }
        byte[] cached = serializedCache.get(hash);
        if (cached != null) {
            return cached;
        }
        byte[] bytes;
        try {
            if (asset instanceof ModelAsset model) {
                VirtualModel virtualModel = virtualBlockManager.getModel(model.modelKey());
                if (virtualModel == null) {
                    return null;
                }
                ModelMeta meta = virtualBlockManager.getModelMeta(model.modelKey());
                ModelMeta.OriginMode originMode = ModelMeta.OriginMode.forModel(
                        virtualModel.modelFormat(), virtualModel.cubes());
                if (meta != null && meta.originMode() != ModelMeta.OriginMode.AUTO) {
                    originMode = meta.originMode();
                }
                bytes = model.serialize(virtualModel, originMode, model.namespace());
            } else if (asset instanceof ItemModelAsset) {
                bytes = asset.serialize();
            } else if (asset instanceof TextureAsset || asset instanceof RawAsset) {
                bytes = asset.serialize();
            } else {
                return null;
            }
        } catch (Exception exception) {
            DebugLogger.warning("[PackCompiler] Serializing '" + asset.id() + "' failed: "
                    + exception.getMessage());
            return null;
        }
        if (serializedCache.size() >= SERIALIZED_CACHE_LIMIT) {
            serializedCache.clear();
        }
        serializedCache.put(hash, bytes);
        return bytes;
    }

    private static String packMetaJson(int packFormat) {
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", packFormat);
        // 1.20.2+ clients accept any pack whose format lies inside the
        // declared supported range; without it, a client whose format drifted
        // above the detected value rejects the pack after download ("failed
        // to apply resource pack"). The generous ceiling is safe: the pack's
        // content (models, textures, sounds, item definitions) is forward
        // compatible in practice, and pre-1.20.2 clients simply ignore the
        // field and use pack_format.
        int ceiling = Math.max(packFormat, PackFormat.latestKnownPackFormat());
        JsonObject supportedFormats = new JsonObject();
        supportedFormats.addProperty("min_inclusive", packFormat);
        supportedFormats.addProperty("max_inclusive", ceiling);
        pack.add("supported_formats", supportedFormats);
        pack.addProperty("description", "Mineplus generated content pack");
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return new GsonBuilder().disableHtmlEscaping().create().toJson(root);
    }

    private static void writeEntry(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }
}
