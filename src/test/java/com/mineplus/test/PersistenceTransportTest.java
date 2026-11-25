package com.mineplus.test;

import com.mineplus.infrastructure.core.network.FileSpoolTransport;
import com.mineplus.infrastructure.core.network.MessageBus;
import com.mineplus.infrastructure.core.research.ResearchProgressStore;
import com.mineplus.infrastructure.core.research.ResearchTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Plain-Java checks for the persistence/transports slice: file-spool
 * cross-process delivery (D2) and research progress persistence (C4).
 *
 * Run: java com.mineplus.test.PersistenceTransportTest — exit 0 = all passed.
 */
public final class PersistenceTransportTest {

    private static final java.util.List<String> failures = new java.util.ArrayList<>();
    private static final Logger LOG = Logger.getLogger("test");

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("spool");
        Path researchFile = dir.resolve("research.tsv");
        try {
            spoolDelivery(dir);
            spoolMalformedTolerated(dir);
            researchPersistence(researchFile);
        } finally {
            deleteRecursive(dir);
        }

        if (failures.isEmpty()) {
            System.out.println("PersistenceTransportTest: all checks passed");
            System.exit(0);
        }
        System.err.println("PersistenceTransportTest: " + failures.size() + " failure(s)");
        failures.forEach(f -> System.err.println("  - " + f));
        System.exit(1);
    }

    private static void spoolDelivery(Path dir) throws Exception {
        FileSpoolTransport out = new FileSpoolTransport(dir, "alpha", LOG);
        FileSpoolTransport in = new FileSpoolTransport(dir, "beta", LOG);
        in.watch(dir.resolve("outbox-alpha"));

        MessageBus sender = new MessageBus(out, "alpha");
        MessageBus receiver = new MessageBus(in, "beta");

        AtomicInteger got = new AtomicInteger();
        receiver.on("chat", m -> got.incrementAndGet());
        sender.send("chat", null, "hello spool");
        check("nothing delivered before drain", got.get() == 0);

        in.drain();
        check("delivered after drain", got.get() == 1);
        in.drain();
        check("no double delivery", got.get() == 1);

        sender.send("chat", "gamma", "private");
        in.drain();
        check("target filter across spool", got.get() == 1);
    }

    private static void spoolMalformedTolerated(Path dir) throws Exception {
        FileSpoolTransport in = new FileSpoolTransport(dir, "gamma", LOG);
        in.watch(dir.resolve("outbox-alpha"));
        Path junk = dir.resolve("outbox-alpha").resolve("junk.msg");
        Files.writeString(junk, "not a valid message");

        MessageBus bus = new MessageBus(in, "gamma");
        AtomicInteger got = new AtomicInteger();
        bus.on("chat", m -> got.incrementAndGet());
        in.drain();
        check("malformed dropped, no crash", got.get() == 0 && !Files.exists(junk));
    }

    private static void researchPersistence(Path file) {
        ResearchTree tree = new ResearchTree();
        tree.define("root", 10);
        tree.define("child", 20, "root");

        ResearchProgressStore store = new ResearchProgressStore(file, LOG);
        int[] budget = {100};
        tree.unlock("player-a", "root", budget);
        tree.unlock("player-a", "child", budget);
        store.save(progressOf(tree, "player-a"));

        // Fresh tree + store simulate a restart.
        ResearchTree reloaded = new ResearchTree();
        reloaded.define("root", 10);
        reloaded.define("child", 20, "root");
        Map<String, Set<String>> loaded = store.load();
        check("owner loaded", loaded.containsKey("player-a"));
        check("both nodes restored", loaded.get("player-a").containsAll(Set.of("root", "child")));
        reloaded.restore(loaded);
        check("tree functional after restore", reloaded.isUnlocked("player-a", "child"));
    }

    private static Map<String, Set<String>> progressOf(ResearchTree tree, String owner) {
        // Snapshot an owner's unlocked set for saving.
        Set<String> unlocked = new java.util.HashSet<>();
        for (ResearchTree.Node node : tree.nodes()) {
            if (tree.isUnlocked(owner, node.id())) {
                unlocked.add(node.id());
            }
        }
        return Map.of(owner, unlocked);
    }

    private static void deleteRecursive(Path dir) throws Exception {
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                            // temp dir cleanup, best effort
                        }
                    });
        }
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
