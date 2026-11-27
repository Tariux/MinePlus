package com.mineplus.test;

import com.mineplus.infrastructure.core.block.BlockStore;
import com.mineplus.infrastructure.core.block.PlacedBlock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Plain-Java checks for placed-block persistence. */
public final class BlockStoreTest {

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mineplus-store-" + UUID.randomUUID());
        try {
            persistsAcrossInstances(root.resolve("state.json").toFile());
            removesAndWritesThrough(root.resolve("state2.json").toFile());
            corruptFileIsKeptAside(root.resolve("state3.json").toFile());
        } finally {
            delete(root.toFile());
        }

        if (failures.isEmpty()) {
            System.out.println("BlockStoreTest: all checks passed");
            return;
        }
        for (String failure : failures) {
            System.err.println("BlockStoreTest: " + failure);
        }
        System.exit(1);
    }

    private static void persistsAcrossInstances(File file) {
        UUID id = UUID.randomUUID();
        BlockStore first = new BlockStore(file, message -> { });
        first.load();
        first.put(record(id, "mineplus:shaft", 4, 65, -9));
        first.save();

        BlockStore second = new BlockStore(file, message -> { });
        second.load();
        expect("record survives reload", second.size() == 1, "size=" + second.size());
        PlacedBlock restored = second.get(id);
        expect("instance id kept", restored != null, "missing record");
        if (restored == null) {
            return;
        }
        expect("definition kept", restored.definitionId().equals("mineplus:shaft"),
                restored.definitionId());
        expect("cells kept", restored.x() == 4 && restored.y() == 65 && restored.z() == -9,
                restored.x() + "," + restored.y() + "," + restored.z());
        expect("rotation kept", restored.qy() == 0.5f && restored.qw() == 0.5f,
                restored.qy() + "/" + restored.qw());
        PlacedBlock at = second.at("world", 4, 65, -9);
        expect("lookup by anchor", at != null, "not found");
    }

    private static void removesAndWritesThrough(File file) {
        BlockStore store = new BlockStore(file, message -> { });
        store.load();
        UUID id = UUID.randomUUID();
        store.put(record(id, "mineplus:wheel", 0, 64, 0));
        store.save();
        expect("remove returns record", store.remove(id) != null, "null on remove");
        expect("store empty after remove", store.size() == 0, "size=" + store.size());
        store.save();

        BlockStore reloaded = new BlockStore(file, message -> { });
        reloaded.load();
        expect("removal persisted", reloaded.size() == 0, "size=" + reloaded.size());
    }

    private static void corruptFileIsKeptAside(File file) throws Exception {
        Files.writeString(file.toPath(), "{not json", StandardCharsets.UTF_8);
        List<String> warnings = new ArrayList<>();
        BlockStore store = new BlockStore(file, warnings::add);
        store.load();
        expect("corrupt store loads empty", store.size() == 0, "size=" + store.size());
        expect("corrupt store warns", !warnings.isEmpty(), "no warning");
        expect("corrupt file kept as .bak",
                new File(file.getParentFile(), file.getName() + ".bak").isFile(), "no .bak");
    }

    private static PlacedBlock record(UUID id, String definitionId, int x, int y, int z) {
        return new PlacedBlock(id, definitionId, "shaft", "world", x, y, z, "UP",
                0f, 0.5f, 0f, 0.5f, "00000000-0000-0000-0000-000000000000", 1L);
    }

    private static void expect(String name, boolean condition, String detail) {
        if (!condition) {
            failures.add(name + " (" + detail + ")");
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
    }
}
