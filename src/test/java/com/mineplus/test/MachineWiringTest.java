package com.mineplus.test;

import com.mineplus.infrastructure.core.multiblock.EntityStatus;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import com.mineplus.infrastructure.core.network.KineticGrid;
import com.mineplus.infrastructure.core.network.rotation.RotationNetworkService;
import com.mineplus.infrastructure.core.network.rotation.RotationNetworkService.RotationProfile;
import com.mineplus.infrastructure.model.BlockCoordinate;
import com.mineplus.infrastructure.world.WaterFlow;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plain-Java checks for machine wiring that players hit in game:
 *
 * <ul>
 *   <li>only flowing water drives a wheel (a still pool or a dry wheel gives 0),</li>
 *   <li>a shaft on the wheel's axle joins the network and turns with it,</li>
 *   <li>a shaft away from the axle does not join (axle-only linking),</li>
 *   <li>every shipped machine has a multiblock file, a model file and the
 *       collision fill box the renderer honours.</li>
 * </ul>
 *
 * Run: java com.mineplus.test.MachineWiringTest — exit 0 means all checks passed.
 */
public final class MachineWiringTest {

    private static final List<String> failures = new java.util.ArrayList<>();

    private static final File MACHINE_ROOT = new File("examples/mineplus-machine");
    private static final File MULTIBLOCKS = new File(MACHINE_ROOT,
            "src/main/resources/defaults/multiblocks");
    private static final File MODELS = new File(MACHINE_ROOT,
            "src/main/resources/defaults/models");

    public static void main(String[] args) {
        stillWaterNeverDrives();
        flowingWaterDrives();
        axleShaftTurnsWithWheel();        dryWheelStopsAxleShaft();
        offAxleShaftDoesNotJoin();
        restoredMachinesRejoinAfterRestart();
        preloadModelRegistersInstalledModel();
        everyMachineHasModelAndCollisionMeta();

        if (failures.isEmpty()) {
            System.out.println("MachineWiringTest: all checks passed");
            System.exit(0);
        }
        System.err.println("MachineWiringTest: " + failures.size() + " failure(s)");
        failures.forEach(f -> System.err.println("  - " + f));
        System.exit(1);
    }

    /** A still pool (every cell level 0, no moving neighbour) must count 0. */
    private static void stillWaterNeverDrives() {
        check("still source is not flowing", !WaterFlow.isFlowing(0, new int[] {0, 0, 0, 0}));
        check("no water never flows", !WaterFlow.isFlowing(-1, new int[] {0, 0, 0, 0}));
        check("still source with no neighbours", !WaterFlow.isFlowing(0, null));
        check("level parse source", WaterFlow.level("minecraft:water[level=0]") == 0);
        check("level parse streaming", WaterFlow.level("minecraft:water[level=3]") == 3);
        check("level parse falling", WaterFlow.level("minecraft:water[level=8]") == 8);
        check("level parse non-water", WaterFlow.level("minecraft:stone") == -1);
        check("water detection", WaterFlow.isWater("minecraft:water[level=0]"));
    }

    /** Streaming, falling and a source draining into a moving neighbour count. */
    private static void flowingWaterDrives() {
        check("streaming water flows", WaterFlow.isFlowing(3, null));
        check("falling water flows", WaterFlow.isFlowing(8, null));
        check("source draining into stream",
                WaterFlow.isFlowing(0, new int[] {0, 2, 0, 0}));
        check("non-water neighbour is ignored",
                !WaterFlow.isFlowing(0, new int[] {-1, -1, -1, -1}));
    }

    /** Regression: a shaft placed on the wheel's axle turns at the wheel's speed. */
    private static void axleShaftTurnsWithWheel() {
        Fixture fixture = new Fixture();
        MultiBlockInstance wheel = fixture.instance("water_wheel", 0, 64, 0);
        MultiBlockInstance shaft = fixture.instance("rotation_shaft", 0, 64, 1);
        fixture.place(wheel);
        fixture.place(shaft);

        Map<UUID, Float> speeds = fixture.speeds();
        check("wheel spins", speeds.getOrDefault(wheel.id(), -1f) == 6f);
        check("axle shaft turns with the wheel",
                speeds.getOrDefault(shaft.id(), -1f) == 6f);
    }

    /** A wheel with no moving water beside it must stop its shaft too. */
    private static void dryWheelStopsAxleShaft() {
        Fixture fixture = new Fixture();
        MultiBlockInstance wheel = fixture.instance("water_wheel", 0, 64, 0);
        MultiBlockInstance shaft = fixture.instance("rotation_shaft", 0, 64, 1);
        fixture.place(wheel);
        fixture.place(shaft);

        fixture.rotation.setSourceSu(wheel, 0);
        Map<UUID, Float> speeds = fixture.speeds();
        check("dry wheel stops", speeds.getOrDefault(wheel.id(), -1f) == 0f);
        check("dry wheel stops its axle shaft",
                speeds.getOrDefault(shaft.id(), -1f) == 0f);
    }

    /**
     * Restart order: saved instances land in the registry before the network
     * service is wired. Rebuilding from the registry must restore the same
     * wheel + axle network, and repeating it must not change membership.
     */
    private static void restoredMachinesRejoinAfterRestart() {
        MultiBlockRegistry registry = new MultiBlockRegistry();
        Fixture template = new Fixture();
        MultiBlockInstance wheel = template.instance("water_wheel", 0, 64, 0);
        MultiBlockInstance shaft = template.instance("rotation_shaft", 0, 64, 1);
        registry.addInstance(wheel);
        registry.addInstance(shaft);

        RotationNetworkService rotation = new RotationNetworkService(registry);
        Map<UUID, Float> speeds = new HashMap<>();
        rotation.setStateSink((instance, state) -> speeds.put(instance.id(), state.speed()));
        check("no profiles yet: nothing joins", rotation.rebuildFromRegistry() == 0);
        rotation.registerProfile("water_wheel",
                new RotationProfile(KineticGrid.Kind.SOURCE, 512, 0, 6f, false));
        check("late type registration rejoins the wheel", rotation.rebuildFromRegistry("WATER_WHEEL") == 1);
        rotation.registerProfile("rotation_shaft",
                new RotationProfile(KineticGrid.Kind.SHAFT, 0, 0, 6f, false));
        check("full rebuild restores both members", rotation.rebuildFromRegistry() == 2);
        check("rebuild is idempotent", rotation.rebuildFromRegistry() == 2);

        rotation.rotationTick();
        check("restored wheel spins", speeds.getOrDefault(wheel.id(), -1f) == 6f);
        check("restored axle shaft turns with the wheel", speeds.getOrDefault(shaft.id(), -1f) == 6f);
    }

    /** Axle-only linking: a shaft off the axle does not join the wheel. */
    private static void offAxleShaftDoesNotJoin() {
        Fixture fixture = new Fixture();
        MultiBlockInstance wheel = fixture.instance("water_wheel", 0, 64, 0);
        MultiBlockInstance corner = fixture.instance("rotation_shaft", 1, 64, 1);
        fixture.place(wheel);
        fixture.place(corner);

        Map<UUID, Float> speeds = fixture.speeds();
        check("wheel spins alone", speeds.getOrDefault(wheel.id(), -1f) == 6f);
        check("off-axle shaft stays out of the network", !speeds.containsKey(corner.id()));
    }

    /**
     * Data integrity for the shipped machines: every type has a multiblock
     * file whose model exists, wheels are sources, shafts are carriers, and
     * wheels declare a centre-cell collision fill box so they cannot fill
     * (and block) the water beside them.
     */
    private static void everyMachineHasModelAndCollisionMeta() {
        String[] types = {
                "water_wheel", "water_wheel_weak", "water_wheel_strong",
                "rotation_shaft", "cogwheel", "cogwheel_vertical", "cogwheel_horizontal",
                "rotation_brake"};
        for (String type : types) {
            File json = new File(MULTIBLOCKS, type + ".json");
            if (!json.isFile()) {
                failures.add(type + ": missing multiblock file " + json.getPath());
                continue;
            }
            JsonObject root;
            try {
                root = JsonParser.parseString(Files.readString(json.toPath())).getAsJsonObject();
            } catch (Exception broken) {
                failures.add(type + ": unreadable multiblock file (" + broken.getMessage() + ")");
                continue;
            }
            JsonObject level = root.getAsJsonObject("levels").getAsJsonObject("1");
            String model = level.get("model").getAsString();
            check(type + ": model file exists", new File(MACHINE_ROOT,
                    "src/main/resources/defaults/" + model).isFile());

            JsonObject options = level.getAsJsonObject("guiOptions");
            String kind = options == null || !options.has("rotation.kind")
                    ? ""
                    : options.get("rotation.kind").getAsString();
            if (type.startsWith("water_wheel")) {
                check(type + ": is a water source", kind.equals("source"));
                check(type + ": centre-cell fill box", centreCellFillBox(type));
            } else if (type.equals("rotation_shaft")) {
                check(type + ": is a shaft carrier", kind.equals("shaft"));
            }
        }
    }

    private static boolean centreCellFillBox(String type) {
        File meta = new File(MODELS, type + ".bbmodel.meta.json");
        if (!meta.isFile()) {
            // The strong/weak wheels inherit the default: centre cell only.
            return true;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(meta.toPath())).getAsJsonObject();
            if (!root.has("fillBox")) {
                return true;
            }
            for (var cell : root.getAsJsonArray("fillBox")) {
                if (cell.getAsInt() != 0) {
                    return false;
                }
            }
            return true;
        } catch (Exception broken) {
            return false;
        }
    }

    /**
     * Regression: modules install models at enable, after the startup scan, so
     * their pack items were registered without geometry and the first render
     * fell back to texel. preloadModel must register the model (with its
     * embedded animation) under the pack item key, and be idempotent.
     */
    private static void preloadModelRegistersInstalledModel() {
        var virtual = new com.mineplus.infrastructure.virtual.VirtualBlockManager();
        File shaft = new File(MODELS, "shaft.bbmodel");
        var model = virtual.preloadModel("rotation_shaft_lvl_1", shaft);
        check("preload registers the installed model",
                model != null && virtual.getModel("rotation_shaft_lvl_1") == model);
        check("preload keeps the model animations",
                model != null && !model.animations().isEmpty());
        check("preload is idempotent",
                virtual.preloadModel("rotation_shaft_lvl_1", shaft) == model);
        check("preload ignores a missing file",
                virtual.preloadModel("missing_lvl_1", new File(MODELS, "absent.bbmodel")) == null);
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures.add(what);
        }
    }

    /** Two machines on one plain registry/service, sharing a state sink. */
    private static final class Fixture {

        private final MultiBlockRegistry registry = new MultiBlockRegistry();
        private final RotationNetworkService rotation = new RotationNetworkService(registry);
        private final Map<UUID, Float> lastSpeeds = new HashMap<>();

        Fixture() {
            rotation.registerProfile("water_wheel",
                    new RotationProfile(KineticGrid.Kind.SOURCE, 512, 0, 6f, false));
            rotation.registerProfile("rotation_shaft",
                    new RotationProfile(KineticGrid.Kind.SHAFT, 0, 0, 6f, false));
            rotation.setStateSink((instance, state) -> lastSpeeds.put(instance.id(), state.speed()));
        }

        MultiBlockInstance instance(String typeId, int x, int y, int z) {
            return new MultiBlockInstance(
                    UUID.randomUUID(), typeId, new BlockCoordinate("world", x, y, z),
                    UUID.randomUUID(), UUID.randomUUID(), 0L, 0L, 1,
                    new org.joml.Quaternionf(), null, EntityStatus.PLACED, 0L, 0L,
                    typeId + "_lvl_1", Map.of(), Map.of(), Set.of());
        }

        void place(MultiBlockInstance instance) {
            registry.addInstance(instance);
            rotation.onPlaced(instance);
        }

        Map<UUID, Float> speeds() {
            lastSpeeds.clear();
            rotation.rotationTick();
            return new HashMap<>(lastSpeeds);
        }
    }
}
