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
