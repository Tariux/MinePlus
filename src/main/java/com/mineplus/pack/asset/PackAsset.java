package com.mineplus.pack.asset;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * One asset in the pack asset registry: a stable namespaced identity
 * ({@code namespace:path}), the owning module, and a deterministic content
 * hash over the underlying source of truth (source file bytes or definition
 * fields). The hash — not the serialized form — is the asset's cache and
 * artifact identity, so hashing stays cheap while serialization can be
 * arbitrarily expensive and still incremental.
 *
 * <p>Assets are immutable after registration.</p>
 */
public abstract class PackAsset {

    private final String namespace;
    private final String path;
    private final String owner;
    private volatile String contentHash;

    protected PackAsset(String namespace, String path, String owner) {
        this.namespace = requireAssetToken(namespace, "namespace");
        this.path = Objects.requireNonNull(path, "path").trim().toLowerCase(Locale.ROOT);
        if (this.path.isEmpty() || this.path.startsWith("/") || this.path.endsWith("/")
                || !this.path.matches("[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Invalid pack asset path: '" + path + "'");
        }
        this.owner = owner == null || owner.isBlank() ? "mineplus" : owner.trim();
    }

    /** Stable asset identity, e.g. {@code mineplus:item/strad_wine}. */
    public final String id() {
        return namespace + ":" + path;
    }

    public final String namespace() {
        return namespace;
    }

    public final String path() {
        return path;
    }

    /** Registering module/plugin name; used in conflict reports. */
    public final String owner() {
        return owner;
    }

    /** Full entry path inside the pack zip, e.g. {@code assets/mineplus/models/item/x.json}. */
    public abstract String zipEntryPath();

    /**
     * Deterministic serialized bytes of this asset as they appear in the zip.
     * Implementations must be pure: same asset state, same bytes.
     */
    public abstract byte[] serialize() throws IOException;

    /** SHA-256 over the asset's source of truth; cached after the first call. */
    public final String contentHash() throws IOException {
        String cached = contentHash;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (contentHash == null) {
                contentHash = sha256Hex(hashSource());
            }
            return contentHash;
        }
    }

    /** The cheap, identity-defining bytes this asset is hashed over. */
    protected abstract byte[] hashSource() throws IOException;

    protected static byte[] fileBytes(java.io.File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    static String requireAssetToken(String value, String what) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || !normalized.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid pack asset " + what + ": '" + value + "'");
        }
        return normalized;
    }
}
