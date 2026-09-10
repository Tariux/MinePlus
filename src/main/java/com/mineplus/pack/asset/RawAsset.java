package com.mineplus.pack.asset;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * A verbatim file asset (sounds, language files, fonts, custom JSON) copied
 * byte-identical into the pack. Content hash = the file bytes.
 */
public final class RawAsset extends PackAsset {

    private final File file;

    /**
     * @param namespace asset namespace
     * @param path      output path under {@code assets/<ns>/}
     * @param owner     registering module name
     * @param file      the file to embed
     */
    public RawAsset(String namespace, String path, String owner, File file) {
        super(namespace, path, owner);
        this.file = Objects.requireNonNull(file, "file");
        if (!file.isFile()) {
            throw new IllegalArgumentException("Raw asset file does not exist: " + file.getAbsolutePath());
        }
    }

    public File file() {
        return file;
    }

    @Override
    public String zipEntryPath() {
        return "assets/" + namespace() + "/" + path();
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
