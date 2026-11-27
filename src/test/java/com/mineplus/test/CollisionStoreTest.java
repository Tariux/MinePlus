package com.mineplus.test;

import com.mineplus.infrastructure.collision.fit.BackendType;
import com.mineplus.infrastructure.collision.runtime.CollisionStore;
import com.mineplus.infrastructure.collision.runtime.PlacedFurniture;
import com.mineplus.infrastructure.collision.runtime.PlacedPart;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persistence checks for the model collision index. */
public final class CollisionStoreTest {

    private static final List<String> failures = new ArrayList<>();

    private CollisionStoreTest() {
    }

    public static void main(String[] args) throws Exception {
        roundTrip();
        removalPersists();
        corruptFileIsQuarantined();
        newerFormatIsIgnored();

        if (failures.isEmpty()) {
            System.out.println("CollisionStoreTest: all checks passed");
            return;
        }
        System.out.println("CollisionStoreTest: " + failures.size() + " failure(s)");
        for (String failure : failures) {
            System.out.println(" - " + failure);
        }
        System.exit(1);
    }

    private static void roundTrip() throws Exception {
        Path dir = Files.createTempDirectory("mineplus-store");
        try {
            Path file = dir.resolve("collision.json");
            CollisionStore store = new CollisionStore(file, message -> {
            });
            PlacedFurniture furniture = sample();
            store.add(furniture);
            store.save();
            check("store file written", Files.exists(file), "missing file");

            CollisionStore reloaded = new CollisionStore(file, message -> {
            });
            reloaded.load();
            check("store reloads one model", reloaded.furniture().size() == 1,
                    reloaded.furniture().size());
            PlacedFurniture back = reloaded.byId(furniture.id());
            check("model id survives", back != null, "missing model");
            if (back == null) {
                return;
            }
            check("origin survives", back.originX() == 10 && back.originY() == 64
                    && back.originZ() == -3, back.originX() + "," + back.originY() + ","
                    + back.originZ());
            check("parts survive", back.parts().size() == 3, back.parts().size());
            PlacedPart block = back.parts().get(0);
            check("block part survives", block.backend() == BackendType.REAL_BLOCK
                    && "minecraft:barrier".equals(block.state())
                    && "minecraft:air".equals(block.originalState()), block.state());
            PlacedPart ghost = back.parts().get(1);
            check("ghost part has no entity", ghost.backend() == BackendType.GHOST_BLOCK
                    && ghost.entityId() == null, ghost);
            PlacedPart entity = back.parts().get(2);
            check("entity part keeps its uuid", entity.backend() == BackendType.INTERACTION_ONLY
                    && entity.entityId().equals(UUID.fromString(
                            "00000000-0000-0000-0000-0000000000aa")), entity.entityId());
        } finally {
            deleteTree(dir);
        }
    }

    private static void removalPersists() throws Exception {
        Path dir = Files.createTempDirectory("mineplus-store");
        try {
            Path file = dir.resolve("collision.json");
            CollisionStore store = new CollisionStore(file, message -> {
            });
            PlacedFurniture furniture = sample();
            store.add(furniture);
            store.save();
            check("remove reports success", store.remove(furniture.id()), "remove failed");
            check("remove drops the model", store.byId(furniture.id()) == null, "still present");
            store.save();

            CollisionStore reloaded = new CollisionStore(file, message -> {
            });
            reloaded.load();
            check("removal is durable", reloaded.furniture().isEmpty(),
                    reloaded.furniture().size());
        } finally {
            deleteTree(dir);
        }
    }

    private static void corruptFileIsQuarantined() throws Exception {
        Path dir = Files.createTempDirectory("mineplus-store");
        try {
            Path file = dir.resolve("collision.json");
            Files.writeString(file, "{not json", StandardCharsets.UTF_8);
            List<String> warnings = new ArrayList<>();
            CollisionStore store = new CollisionStore(file, warnings::add);
            store.load();
            check("corrupt store starts empty", store.furniture().isEmpty(),
                    store.furniture().size());
            check("corrupt store is reported", !warnings.isEmpty(), warnings);
            check("corrupt store is kept aside",
                    Files.exists(dir.resolve("collision.json.corrupt")), "no backup");
        } finally {
            deleteTree(dir);
        }
    }

    private static void newerFormatIsIgnored() throws Exception {
        Path dir = Files.createTempDirectory("mineplus-store");
        try {
            Path file = dir.resolve("collision.json");
            Files.writeString(file, "{\"format\":99,\"furniture\":[]}", StandardCharsets.UTF_8);
            List<String> warnings = new ArrayList<>();
            CollisionStore store = new CollisionStore(file, warnings::add);
            store.load();
            check("newer format is refused", store.furniture().isEmpty(),
                    store.furniture().size());
            check("newer format warns", warnings.stream().anyMatch(w -> w.contains("newer")),
                    warnings);
            check("newer format file is kept", Files.exists(file), "file deleted");
        } finally {
            deleteTree(dir);
        }
    }

    private static PlacedFurniture sample() {
        PlacedFurniture furniture = new PlacedFurniture(UUID.randomUUID(), "sample:chair",
                "world", 10, 64, -3, 90, "atlas-1", "FURNITURE/abc", 16);
        PlacedPart block = new PlacedPart(BackendType.REAL_BLOCK, 0, 0, 0, "minecraft:barrier");
        block.setOriginalState("minecraft:air");
        furniture.add(block);
        furniture.add(new PlacedPart(BackendType.GHOST_BLOCK, 1, 0, 0,
                "minecraft:oak_slab[type=bottom]"));
        PlacedPart interaction = new PlacedPart(BackendType.INTERACTION_ONLY, 0, 1, 0, null);
        interaction.setEntityType("INTERACTION");
        interaction.setEntityId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
        furniture.add(interaction);
        return furniture;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(String message, boolean condition, Object detail) {
        if (!condition) {
            failures.add(message + " [" + detail + ']');
        }
    }
}
