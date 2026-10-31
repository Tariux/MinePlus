package com.mineplus.pack.compile;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.pack.PackFormat;
import com.mineplus.pack.PackSettings;
import com.mineplus.pack.asset.AppearanceMode;
import com.mineplus.pack.asset.BlockModelAsset;
import com.mineplus.pack.asset.GeneratedAsset;
import com.mineplus.pack.asset.ItemModelAsset;
import com.mineplus.pack.asset.ModelAsset;
import com.mineplus.pack.asset.PackAsset;
import com.mineplus.pack.asset.PackBlockAsset;
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
    private volatile com.mineplus.infrastructure.perf.PerfRegistry perf;
    /** Non-fatal content diagnostics of the last compile ({} when validation is off). */
    private volatile ModelAssetValidator.Report lastReport = ModelAssetValidator.Report.EMPTY;

    public PackCompiler(VirtualBlockManager virtualBlockManager, PackCache cache, PackSettings settings) {
        this.virtualBlockManager = virtualBlockManager;
        this.cache = cache;
        this.settings = settings;
    }

    /** Telemetry sink for compile durations (may be {@code null}). */
    public void setPerfRegistry(com.mineplus.infrastructure.perf.PerfRegistry perf) {
        this.perf = perf;
    }

    /** Timed wrapper around the compile body: reports duration and run count. */
    public PackArtifact compile(List<PackAsset> snapshot) {
        long started = System.nanoTime();
        PackArtifact artifact = compileInternal(snapshot);
        com.mineplus.infrastructure.perf.PerfRegistry registry = perf;
        if (registry != null) {
            registry.setGauge(com.mineplus.infrastructure.perf.PerfRegistry.Gauge.PACK_COMPILE_MILLIS,
                    (System.nanoTime() - started) / 1_000_000L);
            registry.add(com.mineplus.infrastructure.perf.PerfRegistry.Counter.PACK_COMPILES, 1);
        }
        return artifact;
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
    public PackArtifact compileInternal(List<PackAsset> snapshot) {
        if (snapshot.isEmpty()) {
            return null;
        }
        String bukkitVersion = org.bukkit.Bukkit.getBukkitVersion();
        int packFormat = PackFormat.packFormat(bukkitVersion, settings.packFormatOverride());
        AppearanceMode.Resolved appearance = settings.appearanceMode().resolve(
                PackFormat.itemRepresentation(bukkitVersion),
                com.mineplus.pack.item.ItemFrameWriter.supportsStringState());
        PackFormat.ItemRepresentation representation = appearance.representation();
        AppearanceMode.Dispatch dispatch = appearance.dispatch();
        ModelAssetValidator validator = new ModelAssetValidator(
                virtualBlockManager, settings.validateAssets());
        long start = System.nanoTime();
        List<PackAsset> failed = new ArrayList<>();

        try {
            // Build the exact byte set first, then hash it. The artifact
            // identity must reflect what is actually written: a formerly
            // failing asset that now serializes (or any serialization-format
            // change) yields a new hash, a new filename and a new client URL,
            // instead of a stale cached artifact being resurrected under an
            // unchanged asset-graph hash.
            Map<String, byte[]> entries = new TreeMap<>();
            List<PackBlockAsset> blockAssets = new ArrayList<>();
            for (PackAsset asset : snapshot) {
                // Pack block bindings own no entry of their own; they are
                // aggregated into the carrier blockstate host files below.
                if (asset instanceof PackBlockAsset blockAsset) {
                    blockAssets.add(blockAsset);
                    continue;
                }
                byte[] bytes = serialized(asset, dispatch);
                if (bytes == null) {
                    failed.add(asset);
                    continue;
                }
                putEntry(entries, asset.zipEntryPath(), bytes, validator);
            }
            if (representation == PackFormat.ItemRepresentation.LEGACY_CUSTOM_MODEL_DATA) {
                // Legacy items live in shared per-material host files, not their
                // own entries; drop the modern definitions and emit the hosts.
                for (ItemModelAsset item : itemsOnly(snapshot)) {
                    entries.remove(item.modernEntryPath());
                }
                for (Map.Entry<String, byte[]> legacy : ItemModelWriter.legacyHostFiles(itemsOnly(snapshot)).entrySet()) {
                    putEntry(entries, legacy.getKey(), legacy.getValue(), validator);
                }
            }
            // Block host files are representation-independent: a BlockDisplay
            // has no per-entity model dispatch on any client version.
            for (Map.Entry<String, byte[]> host : BlockStateWriter.hostFiles(blockAssets).entrySet()) {
                putEntry(entries, host.getKey(), host.getValue(), validator);
            }

            // The client-window gate runs on the artifact's own bytes, after
            // every writer, before hashing: the corrected geometry is what the
            // artifact hash, the filename and the client download all describe.
            ModelGeometryGuard.Report guard = ModelGeometryGuard.enforce(entries);
            validator.checkAssets(snapshot);
            validator.checkArtifactEntries(entries, guard);
            lastReport = validator.report(snapshot.size(), failed.size());
            logWarnings(lastReport);

            if (entries.isEmpty()) {
                // Nothing survived: do not publish an empty artifact; keep the previous one.
                DebugLogger.warning("[PackCompiler] All assets failed to serialize; keeping previous artifact.");
                return null;
            }

            String hash = contentHash(entries, packFormat, representation);
            PackArtifact cached = cache.find(hash).orElse(null);
            if (cached != null) {
                DebugLogger.info("[PackCompiler] Cache hit " + cached.artifactName()
                        + " — no recompilation, no client re-download.");
                return cached;
            }

            cache.ensureFolder();
            File target = cache.artifactFile(hash);
            File partial = new File(cache.folder(), target.getName() + ".partial");
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(partial))) {
                zip.setLevel(6);
                writeEntry(zip, "pack.mcmeta", packMetaJson(packFormat).getBytes(StandardCharsets.UTF_8));
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    writeEntry(zip, entry.getKey(), entry.getValue());
                }
            }

            // Content-addressed filename: normally a fresh name, but replace
            // defensively — Windows renameTo cannot overwrite an existing file.
            java.nio.file.Files.move(partial.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            PackArtifact artifact = new PackArtifact(
                    target, hash, java.util.HexFormat.of().formatHex(PackCache.sha1(target)),
                    packFormat, representation,
                    entries.size(), target.length(),
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

    /**
     * SHA-256 over the exact artifact entries (path + bytes) plus the compile
     * context (pack format, item representation) — the content-addressed
     * artifact identity and filename.
     */
    private static String contentHash(
            Map<String, byte[]> entries,
            int packFormat,
            PackFormat.ItemRepresentation representation
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("pack_format=" + packFormat + ";representation=" + representation)
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.getValue());
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
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

    /** Adds one entry, reporting a path another asset already wrote. */
    private static void putEntry(Map<String, byte[]> entries, String path, byte[] bytes,
                                 ModelAssetValidator validator) {
        if (entries.put(path, bytes) != null) {
            validator.entryCollision(path);
        }
    }

    /** Debug log of the last validation pass: a bounded sample plus a total. */
    private static void logWarnings(ModelAssetValidator.Report report) {
        List<String> warnings = report.warnings();
        if (warnings.isEmpty()) {
            return;
        }
        int shown = Math.min(12, warnings.size());
        for (int index = 0; index < shown; index++) {
            DebugLogger.warning("[PackValidator] " + warnings.get(index));
        }
        if (warnings.size() > shown) {
            DebugLogger.warning("[PackValidator] " + (warnings.size() - shown)
                    + " further warning(s); see /mineplus pack status.");
        }
    }

    /** Content diagnostics of the last compile. */
    public ModelAssetValidator.Report lastReport() {
        return lastReport;
    }

    /** Serialized bytes with per-content-hash memoization; null when the asset fails. */
    private byte[] serialized(PackAsset asset, AppearanceMode.Dispatch dispatch) {
        String hash;
        try {
            hash = asset.contentHash();
        } catch (Exception exception) {
            return null;
        }
        // Include the output path: the same model registered as both an item
        // and a block shares its source-file hash but serializes differently.
        // The appearance dispatch is part of the output too (a plain model and
        // its range/select wrapper share one asset hash).
        String cacheKey = hash + "|" + asset.zipEntryPath() + "|" + dispatch;
        byte[] cached = serializedCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        byte[] bytes;
        try {
            if (asset instanceof ModelAsset model) {
                // Prefer the model captured at registration: the registry is
                // reloaded around compiles and can race to null.
                VirtualModel virtualModel = model.capturedModel() != null
                        ? model.capturedModel()
                        : virtualBlockManager.getModel(model.modelKey());
                if (virtualModel == null) {
                    DebugLogger.warning("[PackCompiler] Asset '" + asset.id() + "' model key '"
                            + model.modelKey() + "' is not loaded; skipping its geometry.");
                    return null;
                }
                ModelMeta meta = virtualBlockManager.getModelMeta(model.modelKey());
                if (meta != null && meta.display() != null && !meta.display().isEmpty()) {
                    // Display override precedence: meta-file over bbmodel.
                    virtualModel = virtualModel.withDisplay(virtualModel.display().merge(meta.display()));
                }
                if (meta != null && meta.itemParent() != null && !meta.itemParent().isBlank()) {
                    // Item model parent override (e.g. minecraft:item/handheld for natural held transforms).
                    virtualModel = virtualModel.withItemParent(meta.itemParent());
                }
                ModelMeta.OriginMode originMode = ModelMeta.OriginMode.forModel(
                        virtualModel.modelFormat(), virtualModel.cubes());
                ModelMeta.OriginMode metaOrigin = meta == null ? null : meta.originMode();
                if (metaOrigin != null && metaOrigin != ModelMeta.OriginMode.AUTO) {
                    originMode = metaOrigin;
                } else if (!(asset instanceof BlockModelAsset)) {
                    // Item/legacy axes historically serialized with the GRID
                    // shift (meta-less origin resolved to null); keep their
                    // output byte-identical while the block axis uses the
                    // correctly resolved center/grid origin.
                    originMode = null;
                }
                bytes = model.serialize(virtualModel, originMode, model.namespace());
            } else if (asset instanceof ItemModelAsset item) {
                bytes = item.serialize(dispatch);
            } else if (asset instanceof GeneratedAsset generated) {
                // Synthesized files (bone rigs, custom mob models and their
                // textures) carry their bytes directly: they have no on-disk
                // source and no virtual model to serialize.
                bytes = generated.serialize();
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
        serializedCache.put(cacheKey, bytes);
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
