package com.mineplus.test;

import com.mineplus.infrastructure.core.block.BlockAction;
import com.mineplus.infrastructure.core.block.BlockDefinition;
import com.mineplus.infrastructure.core.block.BlockDefinitionLoader;
import com.mineplus.infrastructure.core.block.BlockLoadReport;
import com.mineplus.infrastructure.core.block.BlockPlacementRules;
import com.mineplus.infrastructure.core.block.BlockRegistry;
import com.mineplus.infrastructure.render.RenderMode;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Plain-Java checks for the blocks/*.json loader and registry. */
public final class BlockDefinitionTest {

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mineplus-blocks-" + UUID.randomUUID());
        try {
            File models = dir(root.resolve("models"));
            File blocks = dir(root.resolve("blocks"));
            write(models.toPath().resolve("water_wheel.bbmodel"), "{}");
            write(models.toPath().resolve("nest/shaft.bbmodel"), "{}");

            validDefinitionLoads(blocks, models);
            badFileIsIsolated(blocks, models);
            duplicateIsRejected(blocks, models);
            invalidCollisionModeIsRejected(blocks, models);
            missingModelIsRejected(blocks, models);
            autoRenderResolves(blocks, models);
            unqualifiedIdGetsNamespace(blocks, models);
            lightIsClampedWithWarning(blocks, models);
            placementRulesAreEnforced();
            actionsParseAndValidate(blocks, models);
            shippedExampleDefinitionsLoad();
        } finally {
            delete(root.toFile());
        }

        if (failures.isEmpty()) {
            System.out.println("BlockDefinitionTest: all checks passed");
            return;
        }
        for (String failure : failures) {
            System.err.println("BlockDefinitionTest: " + failure);
        }
        System.exit(1);
    }

    private static void validDefinitionLoads(File blocks, File models) throws IOException {
        clear(blocks);
        write(blocks.toPath().resolve("mineplus/water_wheel.json"), """
                {
                  "displayName": "Water Wheel",
                  "visuals": {
                    "model": "water_wheel",
                    "animations": { "autoplay": ["spin"] },
                    "rotation": { "snap": 90 }
                  },
                  "collision": { "mode": "geometry" },
                  "occupancy": { "width": 1, "height": 2, "depth": 2 },
                  "breaking": {
                    "hardness": 1.5,
                    "tool": { "type": "axe", "tier": "wooden" },
                    "drops": [{ "item": "mineplus:water_wheel", "count": 1 }]
                  }
                }
                """);
        Loaded loaded = load(blocks, models);
        expect("valid file accepted", loaded.report.accepted() == 1,
                "accepted=" + loaded.report.accepted() + " " + loaded.report.summary());
        expect("valid file has no errors", !loaded.report.hasErrors(),
                String.valueOf(loaded.report.errors()));
        BlockDefinition wheel = loaded.registry.get("mineplus:water_wheel");
        expect("definition registered", wheel != null, "missing mineplus:water_wheel");
        if (wheel == null) {
            return;
        }
        expect("autoplay drives VIRTUAL", wheel.renderMode() == RenderMode.VIRTUAL,
                "renderMode=" + wheel.renderMode());
        expect("footprint kept", wheel.width() == 1 && wheel.height() == 2 && wheel.depth() == 2,
                wheel.width() + "x" + wheel.height() + "x" + wheel.depth());
        expect("hardness kept", Math.abs(wheel.breaking().hardness() - 1.5) < 1e-6,
                "hardness=" + wheel.breaking().hardness());
        expect("tool kept", wheel.breaking().tool().type().equals("axe")
                && wheel.breaking().tool().tier().equals("wooden"), wheel.breaking().tool().toString());
        expect("display name kept", wheel.displayName().equals("Water Wheel"), wheel.displayName());
    }

    private static void badFileIsIsolated(File blocks, File models) throws IOException {
        clear(blocks);
        write(blocks.toPath().resolve("mineplus/good.json"), minimal("good", "water_wheel"));
        write(blocks.toPath().resolve("mineplus/broken.json"), "{ not json");
        Loaded loaded = load(blocks, models);
        expect("broken JSON reported", loaded.report.hasErrors(), "no error for broken JSON");
        expect("good file survives broken neighbour", loaded.report.accepted() == 1,
                "accepted=" + loaded.report.accepted());
    }

    private static void duplicateIsRejected(File blocks, File models) throws IOException {
        clear(blocks);
        write(blocks.toPath().resolve("mineplus/first.json"), minimal("wheel", "water_wheel"));
        write(blocks.toPath().resolve("mineplus/second.json"), minimal("wheel", "water_wheel"));
        Loaded loaded = load(blocks, models);
        expect("duplicate id rejected", loaded.report.accepted() == 1,
                "accepted=" + loaded.report.accepted());
        expect("duplicate reported as error", loaded.report.errors().stream()
                .anyMatch(p -> p.message().contains("duplicate")), String.valueOf(loaded.report.errors()));
    }

    private static void invalidCollisionModeIsRejected(File blocks, File models) throws IOException {
        clear(blocks);
        write(blocks.toPath().resolve("mineplus/odd.json"), """
                {
                  "visuals": { "model": "water_wheel" },
                  "collision": { "mode": "triangles" }
                }
                """);
        Loaded loaded = load(blocks, models);
        expect("unknown collision mode rejected", loaded.report.accepted() == 0,
                "accepted=" + loaded.report.accepted());
    }

    private static void missingModelIsRejected(File blocks, File models) throws IOException {
        clear(blocks);
        write(blocks.toPath().resolve("mineplus/ghost.json"), minimal("ghost", "not_a_model"));
        Loaded loaded = load(blocks, models);
        expect("missing model rejected", loaded.report.accepted() == 0,
                "accepted=" + loaded.report.accepted());
        expect("missing model reported", loaded.report.errors().stream()
                .anyMatch(p -> p.message().contains("not found")), String.valueOf(loaded.report.errors()));
    }

    private static void autoRenderResolves(File blocks, File models) throws IOException {
        clear(blocks);
        write(blocks.toPath().resolve("mineplus/static_block.json"), minimal("static_block", "water_wheel"));
        write(blocks.toPath().resolve("mineplus/nested.json"), """
                {
                  "visuals": { "model": "nest/shaft", "renderMode": "auto" }
                }
                """);
        Loaded loaded = load(blocks, models);
        BlockDefinition plain = loaded.registry.get("mineplus:static_block");
        BlockDefinition nested = loaded.registry.get("mineplus:nested");
        expect("static auto is PACK_BLOCK", plain != null && plain.renderMode() == RenderMode.PACK_BLOCK,
                plain == null ? "missing" : "renderMode=" + plain.renderMode());
        expect("nested model resolves", nested != null, "missing mineplus:nested");
        expect("explicit render mode kept", nested != null
                && nested.renderMode() == RenderMode.PACK_BLOCK,
                nested == null ? "missing" : "renderMode=" + nested.renderMode());
    }

    private static void unqualifiedIdGetsNamespace(File blocks, File models) throws IOException {
        clear(blocks);
        write(blocks.toPath().resolve("acme/widget.json"), minimal("", "water_wheel"));
        Loaded loaded = load(blocks, models);
        expect("folder namespace applied", loaded.registry.contains("acme:widget"),
                loaded.registry.all().toString());
    }

    private static void lightIsClampedWithWarning(File blocks, File models) throws IOException {
        clear(blocks);
        write(blocks.toPath().resolve("mineplus/lamp.json"), """
                {
                  "visuals": { "model": "water_wheel", "light": 12 }
                }
                """);
        Loaded loaded = load(blocks, models);
        BlockDefinition lamp = loaded.registry.get("mineplus:lamp");
        expect("light clamped to 0", lamp != null && lamp.light() == 0,
                lamp == null ? "missing" : "light=" + lamp.light());
        expect("light warning reported", loaded.report.warnings().stream()
                .anyMatch(p -> p.message().contains("clamped")), String.valueOf(loaded.report.warnings()));
    }

    private static void placementRulesAreEnforced() {
        expect("ground needs a floor",
                BlockPlacementRules.supportProblem("ground", "UP", true) != null, "allowed");
        expect("ground on a floor passes",
                BlockPlacementRules.supportProblem("ground", "UP", false) == null, "refused");
        expect("ground cannot hang from a ceiling",
                BlockPlacementRules.supportProblem("ground", "DOWN", false) != null, "allowed");
        expect("wall refuses top faces",
                BlockPlacementRules.supportProblem("wall", "UP", false) != null, "allowed");
        expect("wall on a side face passes",
                BlockPlacementRules.supportProblem("wall", "NORTH", false) == null, "refused");
        expect("ceiling refuses side faces",
                BlockPlacementRules.supportProblem("ceiling", "EAST", false) != null, "allowed");
        expect("ceiling under a block passes",
                BlockPlacementRules.supportProblem("ceiling", "DOWN", false) == null, "refused");
        expect("any support never refuses",
                BlockPlacementRules.supportProblem("any", "DOWN", true) == null, "refused");
    }

    private static void actionsParseAndValidate(File blocks, File models) throws IOException {
        expect("message action parsed",
                BlockAction.parse("message:hi").kind() == BlockAction.Kind.MESSAGE, "wrong kind");
        expect("sound action parsed",
                BlockAction.parse("sound:block.anvil.place").kind() == BlockAction.Kind.SOUND, "wrong kind");
        expect("command action parsed",
                BlockAction.parse("command:say hi").kind() == BlockAction.Kind.COMMAND, "wrong kind");
        expect("unknown action flagged",
                BlockAction.parse("teleport:away").kind() == BlockAction.Kind.UNKNOWN, "accepted");
        expect("empty message flagged",
                BlockAction.parse("message:").kind() == BlockAction.Kind.UNKNOWN, "accepted");

        clear(blocks);
        write(blocks.toPath().resolve("mineplus/button.json"), """
                {
                  "visuals": { "model": "water_wheel" },
                  "events": {
                    "onPlace": ["message:hi", "teleport:away"],
                    "onBreak": ["command:say done"],
                    "onInteract": ["sound:block.anvil.place"]
                  }
                }
                """);
        Loaded loaded = load(blocks, models);
        BlockDefinition button = loaded.registry.get("mineplus:button");
        expect("definition with events accepted", button != null, "missing");
        if (button == null) {
            return;
        }
        expect("valid onPlace actions kept", button.events().onPlace().size() == 1,
                String.valueOf(button.events().onPlace()));
        expect("invalid action warned", loaded.report.warnings().stream()
                .anyMatch(p -> p.message().contains("events.onPlace")),
                String.valueOf(loaded.report.warnings()));
        expect("onBreak action kept", button.events().onBreak().size() == 1,
                String.valueOf(button.events().onBreak()));
        expect("onInteract action kept", button.events().onInteract().size() == 1,
                String.valueOf(button.events().onInteract()));
    }

    private static void shippedExampleDefinitionsLoad() {
        File blocks = new File("examples/mineplus-machine/src/main/resources/defaults/blocks");
        File models = new File("examples/mineplus-machine/src/main/resources/defaults/models");
        if (!blocks.isDirectory() || !models.isDirectory()) {
            return;
        }
        Loaded loaded = load(blocks, models);
        expect("shipped examples accepted", loaded.report.accepted() == 3,
                "accepted=" + loaded.report.accepted() + " " + loaded.report.errors());
        expect("shipped examples have no errors", !loaded.report.hasErrors(),
                String.valueOf(loaded.report.errors()));
        expect("shipped wheel resolves", loaded.registry.contains("mineplus:water_wheel_weak"),
                loaded.registry.all().toString());
        expect("shipped shaft resolves", loaded.registry.contains("mineplus:shaft"),
                loaded.registry.all().toString());
    }

    private record Loaded(BlockRegistry registry, BlockLoadReport report) {
    }

    private static Loaded load(File blocks, File models) {
        BlockRegistry registry = new BlockRegistry();
        BlockDefinitionLoader loader = new BlockDefinitionLoader(registry, message -> { });
        BlockLoadReport report = loader.load(blocks, models);
        return new Loaded(registry, report);
    }

    private static String minimal(String id, String model) {
        String idLine = id.isBlank() ? "" : "\"id\": \"" + id + "\",";
        return "{" + idLine + "\"visuals\": { \"model\": \"" + model + "\" }}";
    }

    private static void expect(String name, boolean condition, String detail) {
        if (!condition) {
            failures.add(name + " (" + detail + ")");
        }
    }

    private static File dir(Path path) throws IOException {
        Files.createDirectories(path);
        return path.toFile();
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static void clear(File folder) {
        delete(folder);
        folder.mkdirs();
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
