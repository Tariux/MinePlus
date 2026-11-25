package com.mineplus.examples;

import com.mineplus.infrastructure.core.animation.AnimationStateMachine;
import com.mineplus.infrastructure.core.boss.BossFight;
import com.mineplus.infrastructure.core.minigame.GameMap;
import com.mineplus.infrastructure.core.minigame.GameStateMachine;
import com.mineplus.infrastructure.core.minigame.InstanceManager;
import com.mineplus.infrastructure.core.minigame.MinigameAdapter;
import com.mineplus.infrastructure.core.network.EnergyGrid;
import com.mineplus.infrastructure.core.network.FileSpoolTransport;
import com.mineplus.infrastructure.core.network.MessageBus;
import com.mineplus.infrastructure.core.network.RedisTransport;
import com.mineplus.infrastructure.core.pipeline.AssetPipeline;
import com.mineplus.infrastructure.core.reload.HotReloader;
import com.mineplus.infrastructure.core.research.ResearchProgressStore;
import com.mineplus.infrastructure.core.research.ResearchTree;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Toolkit subsystems (mega-plan Phases C &amp; D) — the pure-Java building
 * blocks behind research trees, minigames, cross-server messaging, energy
 * grids, boss fights, hot reload and asset pipelines.
 *
 * <p>These classes are plain Java on purpose: construct them directly from
 * your module, unit-test them off-thread, and wire the Bukkit side (ticks,
 * teleports, scoreboards) through the small adapter seams shown below.
 * Copy the method bodies you need; each is a complete, working call pattern.
 */
public final class ToolkitExample {

    private ToolkitExample() {
    }

    // ------------------------------------------------------------------
    // C4 — Research tree: prerequisite graph with budget-gated unlocks
    // ------------------------------------------------------------------

    /** Defines a small tree and unlocks nodes against a mutable budget. */
    public void researchDemo(String ownerId) {
        ResearchTree tree = new ResearchTree();
        tree.define("roots", 0);                       // root: no prerequisites
        tree.define("press", 25, "roots");
        tree.define("crusher", 40, "roots", "press");  // multiple prerequisites

        int[] budget = {100};                          // host's currency box
        tree.unlock(ownerId, "roots", budget);         // budget[0] -> 100
        tree.unlock(ownerId, "press", budget);         // budget[0] -> 75
        // tree.unlock(ownerId, "crusher", budget);   // throws until "press" is unlocked

        // For GUIs: nodes whose prerequisites are met but not yet unlocked.
        for (ResearchTree.Node node : tree.availableFor(ownerId)) {
            System.out.println("Can buy: " + node.id() + " for " + node.cost());
        }

        // Definition-time validation: unknown prerequisites and cycles throw
        // immediately, so a typo never reaches players.
        try {
            tree.define("loop", 10, "loop");
        } catch (IllegalArgumentException expected) {
            // research cycle detected
        }
    }

    // ------------------------------------------------------------------
    // C4 — Research persistence: save/restore per-owner unlock sets
    // ------------------------------------------------------------------

    /**
     * File-backed progress store (SQLite variant: SqliteResearchRepository,
     * same load/save shape). Save on change, restore into the tree at startup.
     */
    public void researchPersistenceDemo(java.nio.file.Path file, Logger logger) {
        ResearchTree tree = new ResearchTree();
        tree.define("roots", 0);
        tree.define("press", 25, "roots");

        ResearchProgressStore store = new ResearchProgressStore(file, logger);
        int[] budget = {100};
        tree.unlock("player-a", "roots", budget);
        tree.unlock("player-a", "press", budget);
        store.save(Map.of("player-a", Set.of("roots", "press")));

        // On startup, rebuild the tree from the saved snapshot:
        tree.restore(store.load());
        System.out.println("restored unlocked=" + tree.isUnlocked("player-a", "press"));
        // No datasource? Same seam: ResearchProgressStore.fileFallback(file, logger).
    }

    // ------------------------------------------------------------------
    // D1 — Minigame toolkit: state machine + map + Bukkit adapter
    // ------------------------------------------------------------------

    /** Builds a game instance: lifecycle, teams, map regions, spawn markers. */
    public MinigameAdapter buildArena() {
        GameStateMachine state = new GameStateMachine(4);  // 4 players per team
        state.createTeam("red");
        state.createTeam("blue");
        state.onStateEnter(() -> {
            if (state.state() == GameStateMachine.State.RUNNING) {
                // entry actions run on every transition into a state
            }
        });

        GameMap map = new GameMap();
        map.addRegion(new GameMap.Region("arena", "world", 0, 0, 0, 64, 32, 64));
        // Round-robin claiming: each call hands out the next "spawn" marker,
        // so spawn distribution needs no per-game logic.
        map.addMarker(new GameMap.Marker("spawn", "world", 2, 8, 2));
        map.addMarker(new GameMap.Marker("spawn", "world", 30, 8, 30));
        map.setScoreboard(List.of(
                new GameMap.Line("title", "Sky Wars", 0),
                new GameMap.Line("state", "Waiting", 1)));

        MinigameAdapter adapter = new MinigameAdapter(state, map,
                (player, lines) -> { /* render sidebar for player */ },
                (player, marker) -> { /* teleport player to marker */ });

        // Drive the lifecycle; illegal transitions throw.
        UUID player = UUID.randomUUID();
        state.join(player, null);                          // null team = fills the emptiest
        adapter.bindPlayer(player);
        state.transition(GameStateMachine.State.STARTING);
        state.transition(GameStateMachine.State.RUNNING);  // entry actions fire
        adapter.sendToSpawn(player);                       // claims the next marker
        state.addScore("red", 10);
        state.eliminate("blue");                           // true when one team remains

        // Per-tick glue: register as a lifecycle post-tick pass so scoreboard
        // refreshes follow the machine heartbeat:
        //   lifecycle.addPostTickPass(adapter.tickPass());
        return adapter;
    }

    // ------------------------------------------------------------------
    // D1 — Instance manager: definitions create tracked game instances
    // ------------------------------------------------------------------

    public void instanceDemo(InstanceManager instances) {
        instances.register(new InstanceManager.Definition("skywars", 4, List.of("red", "blue")));
        InstanceManager.Instance game = instances.create("skywars");
        game.state().transition(GameStateMachine.State.STARTING);
        instances.onStop(instance -> System.out.println("stopped: " + instance.id()));
        // instances.stop(game.id()) — force-close still goes through legal transitions
        // After a restart: instances.restore(oldId, "skywars", savedSnapshot) rebuilds it.
    }

    // ------------------------------------------------------------------
    // D2 — Message bus: channel pub/sub with pluggable transports
    // ------------------------------------------------------------------

    /** In-memory bus for a single server; swap the transport for cross-server. */
    public void messageBusDemo(MessageBus bus) {
        Runnable unsubscribe = bus.on("trade", message ->
                System.out.println("trade payload: " + message.payload()));
        bus.send("trade", null, "{\"from\":\"a\",\"item\":\"gear\"}"); // null target = broadcast
        unsubscribe.run();
    }

    /** Cross-server: Redis pub/sub transport over plain sockets, no client jar. */
    public MessageBus redisBus(Logger logger) {
        RedisTransport transport = new RedisTransport("127.0.0.1", 6379, logger);
        return new MessageBus(transport, "lobby-1");
    }

    /**
     * Cross-process (multi-server on one host): file spool transport, same
     * seam as Redis, no extra dependency. Each server watches its peers'
     * outboxes and drains on a timer.
     */
    public MessageBus spoolBus(java.nio.file.Path spoolDir, Logger logger) throws java.io.IOException {
        FileSpoolTransport transport = new FileSpoolTransport(spoolDir, "lobby-1", logger);
        // The peer runs the same code with its own serverId; register its outbox:
        transport.watch(spoolDir.resolve("outbox-lobby-2"));
        return new MessageBus(transport, "lobby-1");
    }

    // ------------------------------------------------------------------
    // C5 — Animation state machine: guard-based transitions with blending
    // ------------------------------------------------------------------

    /** Defines named states, guard conditions and blend windows. */
    public AnimationStateMachine buildAnimator() {
        AnimationStateMachine asm = new AnimationStateMachine();
        asm.define("idle", "misc:idle", true, 1.0f)
           .define("walk", "misc:walk", true, 1.0f)
           .define("attack", "misc:attack", false, 1.2f)
           .transition("idle", "walk", "moving", 6)      // 6-tick blend
           .transition("walk", "idle", "!moving", 6)
           .transition("idle", "attack", "attacking", 2)
           .transition("attack", "idle", "!attacking", 4)
           .guard("moving", this::isMoving);             // BooleanSupplier from host

        asm.start("idle");
        // Feed once per tick from your entity's tick loop:
        asm.tick();
        System.out.println("playing " + asm.current().animation()
                + " blendLeft=" + asm.blendTicksRemaining());
        return asm;
    }

    private boolean isMoving() {
        return false; // host supplies entity velocity / pathing state
    }

    // ------------------------------------------------------------------
    // C6 — Boss fights: health-threshold phases with named abilities
    // ------------------------------------------------------------------

    public BossFight buildBoss() {
        BossFight fight = new BossFight(this::bossHealthFraction, 40L); // 40-tick cooldown
        fight.phase("enraged", 50.0, false, "spawn-adds")   // abilities fire once on entry
             .phase("desperate", 20.0, true, "flurry")      // repeats under the shared cooldown
             .onPhaseChange(() -> { /* boss-bar title update */ })
             .ability("spawn-adds", (f, phase) -> { /* summon minions */ })
             .ability("flurry", (f, phase) -> { /* fast attacks */ });
        return fight;   // call fight.tick() from your entity's tick loop
    }

    private double bossHealthFraction() {
        return 1.0; // host supplies live health / max health
    }

    // ------------------------------------------------------------------
    // C7 — Hot reload: re-apply config to live subsystems, isolated steps
    // ------------------------------------------------------------------

    public HotReloader buildReloader(Logger logger) {
        HotReloader reloader = new HotReloader(logger);
        reloader.register("speeds", this::readSpeeds, (oldV, newV) -> { /* apply */ });
        HotReloader.ReloadReport report = reloader.reloadAll();
        System.out.println("applied=" + report.applied() + " failed=" + report.failed());
        return reloader; // a broken step is logged; earlier settings keep running
    }

    private List<Double> readSpeeds() {
        return List.of(1.0, 2.0);
    }

    // ------------------------------------------------------------------
    // C8 — Asset pipeline: dependency-ordered builds, incremental rebuilds
    // ------------------------------------------------------------------

    public void assetPipelineDemo() {
        AssetPipeline pipeline = new AssetPipeline();
        pipeline.define("atlas", List.of(), (name, deps) -> "atlas.png");   // no dependencies
        pipeline.define("pack", List.of("atlas"), (name, deps) ->           // built after atlas
                "pack.zip using " + deps.get("atlas"));
        pipeline.buildAll();                         // topological order
        // Later: rebuild only assets whose hash (or a dependency hash) changed.
        pipeline.buildIncremental(name -> String.valueOf(name.hashCode()));
        System.out.println("built " + pipeline.output("pack"));
        // Cycles and unknown dependencies are detected before any build runs.
    }

    // ------------------------------------------------------------------
    // C3 — Energy grid: a plain node graph with post-tick balancing
    // ------------------------------------------------------------------

    public void energyGridDemo() {
        EnergyGrid grid = new EnergyGrid();
        long generator = EnergyGrid.pack(0, 64, 0);
        long machine = EnergyGrid.pack(3, 64, 0);
        grid.put(generator, EnergyGrid.Role.SOURCE, 100, 0);
        grid.put(machine, EnergyGrid.Role.CONSUMER, 0, 60);
        grid.link(generator, machine);
        // Supply/demand distribution across the connected component; run once
        // per tick after machine ticks (the core wires this as a post-tick pass).
        for (EnergyGrid.Balance balance : grid.balance()) {
            System.out.println("node " + balance.key() + " received " + balance.received());
        }
    }
}
