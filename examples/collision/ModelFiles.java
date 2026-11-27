package com.mineplus.examples.collision;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.collision.source.ModelResolver;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Reads installed model JSON the same way the framework's own commands do. */
final class ModelFiles {

    private ModelFiles() {
    }

    static String read(PluginContext context, String modelKey) throws IOException {
        File file = context.virtualBlockManager().getModelSourceFile(modelKey);
        if (file == null) {
            throw new IOException("no installed model named '" + modelKey + "'");
        }
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    static ModelResolver resolver(PluginContext context) {
        return name -> {
            File file = context.virtualBlockManager().getModelSourceFile(name);
            if (file == null) {
                return null;
            }
            try {
                return Files.readString(file.toPath(), StandardCharsets.UTF_8);
            } catch (IOException broken) {
                return null;
            }
        };
    }
}
