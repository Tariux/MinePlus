package com.mineplus.pack.asset;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * A texture PNG asset copied verbatim into the pack. Content hash = the PNG
 * file bytes.
 */
public final class TextureAsset extends PackAsset {

    private final File file;

    /**
     * @param namespace asset namespace
     * @param path      output path under {@code assets/<ns>/textures/} (without {@code .png})
     * @param owner     registering module name
     * @param file      the PNG file to embed
     */
    public TextureAsset(String namespace, String path, String owner, File file) {
        super(namespace, path, owner);
        this.file = Objects.requireNonNull(file, "file");
        if (!file.isFile()) {
            throw new IllegalArgumentException("Texture file does not exist: " + file.getAbsolutePath());
        }
    }

    public File file() {
        return file;
    }

    /**
     * Canonical pack texture path for a model texture name — the same
     * last-segment semantics the Core's texture resolver uses (namespace
     * prefix, directories and {@code .png}/{@code .mcmeta} extensions
     * stripped). Both the model's serialized texture references and the
     * registered {@code assets/<ns>/textures/<path>.png} entries go through
     * this, so they can never disagree — the "model renders, texture
     * missing" failure mode.
     */
    public static String normalizePath(String name) {
        if (name == null) {
            return "";
        }
        String key = name.trim().toLowerCase(java.util.Locale.ROOT).replace('\\', '/');
        if (key.endsWith(".png")) {
            key = key.substring(0, key.length() - 4);
        }
        if (key.endsWith(".mcmeta")) {
            key = key.substring(0, key.length() - 7);
        }
        if (key.contains(":")) {
            key = key.substring(key.lastIndexOf(':') + 1);
        }
        if (key.contains("/")) {
            key = key.substring(key.lastIndexOf('/') + 1);
        }
        return key;
    }

    @Override
    public String zipEntryPath() {
        return "assets/" + namespace() + "/textures/" + path() + ".png";
    }

    @Override
    public byte[] serialize() throws IOException {
        return fileBytes(file);
    }

    @Override
    protected byte[] hashSource() throws IOException {
        return fileBytes(file);
    }
}
