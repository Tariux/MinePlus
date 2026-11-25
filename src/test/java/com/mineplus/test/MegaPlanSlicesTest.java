package com.mineplus.test;

import com.mineplus.infrastructure.core.animation.AnimationStateMachine;
import com.mineplus.infrastructure.core.animation.AnimationStateMachine.UnknownStateException;
import com.mineplus.infrastructure.core.minigame.InstanceManager;
import com.mineplus.infrastructure.core.minigame.GameStateMachine.State;
import com.mineplus.infrastructure.core.network.CommandRelay;
import com.mineplus.infrastructure.core.network.MessageBus;
import com.mineplus.infrastructure.core.research.ResearchTree;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plain-Java checks for the remaining C and D slices: research tree
 * (C4), animation state machine (C5), message bus (D2), instance
 * manager (D3), command relay (D4), and the SDK facade (Phase E).
 *
 * Run: java com.mineplus.test.MegaPlanSlicesTest — exit 0 means all passed.
 */
public final class MegaPlanSlicesTest {

    private static final java.util.List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) {
        researchTree();
        animationMachine();
        messageBus();
        instanceManager();
        commandRelay();
        sdkFacade();

        if (failures.isEmpty()) {
            System.out.println("MegaPlanSlicesTest: all checks passed");
            System.exit(0);
        }
        System.err.println("MegaPlanSlicesTest: " + failures.size() + " failure(s)");
        failures.forEach(f -> System.err.println("  - " + f));
        System.exit(1);
    }

    private static void researchTree() {
        ResearchTree tree = new ResearchTree();
        tree.define("root", 10);
        tree.define("child", 20, "root");
        tree.define("grandchild", 30, "child");

        boolean cycle = false;
        try {
            tree.define("back", 5, "back");
        } catch (RuntimeException e) {
            cycle = true;
        }
        check("cycle/self-prereq rejected", cycle);

        int[] budget = {100};
        boolean blocked = false;
        try {
            tree.unlock("p1", "child", budget);
        } catch (ResearchTree.UnlockException e) {
            blocked = true;
        }
        check("prerequisite gate", blocked);

        tree.unlock("p1", "root", budget);
        check("cost deducted", budget[0] == 90);
        tree.unlock("p1", "root", budget); // idempotent
        check("re-unlock free", budget[0] == 90);
        tree.unlock("p1", "child", budget);
        check("chain unlock", budget[0] == 70 && tree.isUnlocked("p1", "child"));

        int[] broke = {5};
        boolean poor = false;
        try {
            tree.unlock("p1", "grandchild", broke);
        } catch (ResearchTree.UnlockException e) {
            poor = e.getMessage().startsWith("Insufficient");
        }
        check("budget gate", poor);
        check("available list skips locked chain",
                tree.availableFor("p1").stream().allMatch(n -> n.id().equals("grandchild")));
    }

    private static void animationMachine() {
        AnimationStateMachine machine = new AnimationStateMachine()
                .define("idle", "anim:idle", true, 1f)
                .define("walk", "anim:walk", true, 1f)
                .define("attack", "anim:attack", false, 1.5f);
        machine.transition("idle", "walk", "moving", 6);
        machine.transition("walk", "attack", "attacking", 0);
        machine.transition("attack", "idle", "calm", 4);
        machine.guard("moving", () -> true);
        machine.guard("attacking", () -> true);
        machine.guard("calm", () -> true);
        machine.start("idle");

        machine.tick();
        check("firing guard transitions", machine.current().name().equals("walk"));
        machine.tick();
        check("chained transition", machine.current().name().equals("attack"));
        check("blend window zero on 0-blend edge",
                machine.blendTicksRemaining() == 0);

        // Blocked-guard path: from attack, calm fires; but with calm off it stays.
        AnimationStateMachine blocked = new AnimationStateMachine()
                .define("idle", "a", true, 1f)
                .define("walk", "b", true, 1f);
        blocked.transition("idle", "walk", "never", 0);
        blocked.guard("never", () -> false);
        blocked.start("idle");
        blocked.tick();
        check("blocked guard keeps state", blocked.current().name().equals("idle"));

        boolean unknown = false;
        try {
            machine.transition("ghost", "idle", "moving", 0);
        } catch (UnknownStateException e) {
            unknown = true;
        }
        check("unknown state rejected", unknown);
    }

    private static void messageBus() {
        MessageBus busA = new MessageBus(new MessageBus.InMemoryTransport(), "alpha");
        MessageBus busB = new MessageBus(new MessageBus.InMemoryTransport(), "beta");
        // Wire the two buses together (one-way fan-out, like a real broker
        // delivering each message once per node).
        MessageBus.InMemoryTransport tA = new MessageBus.InMemoryTransport();
        MessageBus.InMemoryTransport tB = new MessageBus.InMemoryTransport();
        MessageBus a = new MessageBus(tA, "alpha");
        MessageBus b = new MessageBus(tB, "beta");
        tA.subscribe(tB::publish);

        AtomicInteger received = new AtomicInteger();
        b.on("chat", m -> received.incrementAndGet());
        a.send("chat", null, "hello");
        check("cross-bus delivery", received.get() == 1);
        a.send("chat", "gamma", "private"); // not for beta
        check("target filter", received.get() == 1);
        a.send("chat", "beta", "direct");
        check("targeted delivery", received.get() == 2);
    }

    private static void instanceManager() {
        InstanceManager manager = new InstanceManager();
        manager.register(new com.mineplus.infrastructure.core.minigame.InstanceManager.Definition(
                "skywars", 4, List.of("red", "blue")));
        AtomicInteger created = new AtomicInteger();
        AtomicInteger stopped = new AtomicInteger();
        manager.onCreate(i -> created.incrementAndGet());
        manager.onStop(i -> stopped.incrementAndGet());

        var instance = manager.create("skywars");
        check("created in LOBBY", instance.state().state() == State.LOBBY);
        check("teams built", instance.state().teams().size() == 2);
        check("create listener fired", created.get() == 1);

        instance.state().transition(State.STARTING);
        instance.state().transition(State.RUNNING);
        manager.stop(instance.id());
        check("stop listener fired", stopped.get() == 1);
        check("instance removed", manager.instance(instance.id()) == null);

        boolean unknown = false;
        try {
            manager.create("missing");
        } catch (InstanceManager.InstanceException e) {
            unknown = true;
        }
        check("unknown type rejected", unknown);
    }

    private static void commandRelay() {
        MessageBus.InMemoryTransport tA = new MessageBus.InMemoryTransport();
        MessageBus.InMemoryTransport tB = new MessageBus.InMemoryTransport();
        MessageBus a = new MessageBus(tA, "alpha");
        MessageBus b = new MessageBus(tB, "beta");
        tA.subscribe(tB::publish);

        CommandRelay relayA = new CommandRelay(a, (server, cmd) -> true);
        CommandRelay relayB = new CommandRelay(b, (server, cmd) -> !cmd.equals("secret"));

        AtomicInteger executed = new AtomicInteger();
        relayB.registerHandler("ping", cmd -> executed.incrementAndGet());

        relayA.send("beta", "name=ping", null);
        check("handler executed", executed.get() == 1);

        boolean[] denied = {false};
        CommandRelay relayA2 = new CommandRelay(a, (server, cmd) -> true);
        // denial observed indirectly: no execution, result says denied
        relayB.registerHandler("secret", cmd -> denied[0] = true);
        relayA.send("beta", "name=secret", null);
        check("permission gate blocks handler", !denied[0]);
    }

    private static void sdkFacade() {
        check("api version is 1", com.mineplus.sdk.MineplusSdk.API_VERSION == 1);
        check("compat check passes for v1",
                com.mineplus.sdk.MineplusSdk.checkCompatibility(1) == null);
        check("compat check fails for v2",
                com.mineplus.sdk.MineplusSdk.checkCompatibility(2) != null);
        long key = com.mineplus.sdk.MineplusSdk.packPosition(3, 4, 5);
        check("packing delegates",
                com.mineplus.sdk.MineplusSdk.unpackX(key) == 3
                        && com.mineplus.sdk.MineplusSdk.unpackY(key) == 4
                        && com.mineplus.sdk.MineplusSdk.unpackZ(key) == 5);
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
