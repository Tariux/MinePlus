package com.mineplus.pack.asset;

import java.io.IOException;
import java.util.Objects;

/**
 * An in-memory generated file asset: bytes a subsystem composed itself (a
 * synthesized model JSON, a derived item definition) rather than a file on
 * disk. The file bytes are the source of truth, so the content hash is the
 * hash of exactly what ships — no intermediate representation can drift from
 * the artifact.
 *
 * <p>Ownership is explicit: generated assets belong to the subsystem that
 * composed them, so a second registration of the same entry with different
 * bytes is reported as a genuine conflict at the registry.</p>
 */
public final class GeneratedAsset extends PackAsset {

    private final byte[] bytes;

    /**
     * @param namespace asset namespace
     * @param path      output path under {@code assets/<ns>/}
     * @param owner     subsystem that generated the bytes
     * @param bytes     the exact file contents
     */
    public GeneratedAsset(String namespace, String path, String owner, byte[] bytes) {
        super(namespace, path, owner);
        Objects.requireNonNull(bytes, "bytes");
        this.bytes = bytes.clone();
    }

    @Override
    public String zipEntryPath() {
        return "assets/" + namespace() + "/" + path();
    }

    @Override
    public byte[] serialize() {
        return bytes.clone();
    }

    @Override
    protected byte[] hashSource() throws IOException {
        return bytes.clone();
    }
}
