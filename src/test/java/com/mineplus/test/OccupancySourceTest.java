package com.mineplus.test;

import com.mineplus.infrastructure.collision.mask.OccupancyMask;
import com.mineplus.infrastructure.collision.mask.RasterMode;
import com.mineplus.infrastructure.collision.mask.VoxelMask;
import com.mineplus.infrastructure.collision.source.AutoOccupancySource;
import com.mineplus.infrastructure.collision.source.Blueprint;
import com.mineplus.infrastructure.collision.source.BlueprintException;
import com.mineplus.infrastructure.collision.source.BlueprintOccupancySource;
import com.mineplus.infrastructure.collision.source.CellDirective;
import com.mineplus.infrastructure.collision.source.DirectiveType;
import com.mineplus.infrastructure.collision.source.DisplayTransform;
import com.mineplus.infrastructure.collision.source.FillOccupancySource;
import com.mineplus.infrastructure.collision.source.HybridOccupancySource;
import com.mineplus.infrastructure.collision.source.ModelResolver;
import com.mineplus.infrastructure.collision.source.OccupancySource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Plain-Java checks for occupancy masks and the three occupancy sources. */
public final class OccupancySourceTest {

    private static final List<String> failures = new ArrayList<>();

    private OccupancySourceTest() {
    }

    public static void main(String[] args) {
        autoCube();
        autoStairModel();
        autoRotations();
        autoThinPlane();
        autoDisplayScale();
        autoParentChain();
        blueprints();
        blueprintErrors();
        hybridAndFill();
        occupancyMaskMath();

        if (failures.isEmpty()) {
            System.out.println("OccupancySourceTest: all checks passed");
            return;
        }
        System.out.println("OccupancySourceTest: " + failures.size() + " failure(s)");
        for (String failure : failures) {
            System.out.println(" - " + failure);
        }
        System.exit(1);
    }

    private static AutoOccupancySource auto(String json) {
        return new AutoOccupancySource(json, "test", DisplayTransform.IDENTITY, 16,
                RasterMode.CENTERED, false, ModelResolver.NONE, 8);
    }

    private static void autoCube() {
        String json = """
                {"elements":[{"from":[0,0,0],"to":[16,16,16]}]}
                """;
        OccupancySource.OccupancyResult result = auto(json).build();
        check("cube is one block", result.mask().blockSize()[0] == 1
                && result.mask().blockSize()[1] == 1 && result.mask().blockSize()[2] == 1,
                java.util.Arrays.toString(result.mask().blockSize()));
        check("cube fills the mask", result.mask().isFull(), result.mask());
        check("cube has no warnings", result.warnings().isEmpty(), result.warnings());
        check("cube block mask is the full voxel mask",
                result.mask().blockMask(0, 0, 0).isFull(), result.mask().blockMask(0, 0, 0));
    }

    private static void autoStairModel() {
        // Lower slab plus a back block: a stair profile 2 wide (X), 1 deep (Z), 1 tall.
        String json = """
                {"elements":[
                  {"from":[0,0,0],"to":[16,8,16]},
                  {"from":[0,8,0],"to":[16,16,8]}
                ]}
                """;
        OccupancyMask mask = auto(json).build().mask();
        check("stair model is 1 block", mask.blocksX() == 1 && mask.blocksY() == 1,
                mask.describe());
        check("stair model front-bottom is solid", mask.get(8, 2, 14), mask);
        check("stair model back-top is solid", mask.get(8, 14, 2), mask);
        check("stair model front-top is air", !mask.get(8, 14, 14), mask);
        check("stair model lacks the top-front eighth",
                mask.count() == 16 * 16 * 16 - 16 * 8 * 8, mask.count());
    }

    private static void autoRotations() {
        String quarter = """
                {"elements":[{"from":[0,0,0],"to":[8,8,8],
                  "rotation":{"origin":[0,0,0],"axis":"y","angle":90}}]}
                """;
        OccupancyMask mask = auto(quarter).build().mask();
        check("90° rotation keeps the volume", mask.count() == 8 * 8 * 8, mask.count());
        check("90° rotation keeps a 1-block grid", mask.blocksX() == 1, mask.describe());

        String diagonal = """
                {"elements":[{"from":[0,0,0],"to":[8,8,8],
                  "rotation":{"origin":[0,0,0],"axis":"y","angle":45}}]}
                """;
        OccupancyMask conservative = new AutoOccupancySource(diagonal, "diag",
                DisplayTransform.IDENTITY, 16, RasterMode.CONSERVATIVE, false,
                ModelResolver.NONE, 8).build().mask();
        OccupancyMask centered = new AutoOccupancySource(diagonal, "diag",
                DisplayTransform.IDENTITY, 16, RasterMode.CENTERED, false,
                ModelResolver.NONE, 8).build().mask();
        check("45° rotation produces volume", !conservative.isEmpty(), conservative);
        check("conservative never has less volume than centred",
                conservative.count() >= centered.count(),
                conservative.count() + " vs " + centered.count());
    }

    private static void autoThinPlane() {
        String plane = """
                {"elements":[{"from":[0,16,0],"to":[16,16,16]}]}
                """;
        OccupancySource.OccupancyResult skipped = auto(plane).build();
        check("flat plane is skipped by default", skipped.mask().isEmpty(), skipped.mask());
        check("flat plane warning is reported", skipped.warnings().stream()
                .anyMatch(warning -> warning.contains("flat plane")), skipped.warnings());

        OccupancySource.OccupancyResult included = new AutoOccupancySource(plane, "plane",
                DisplayTransform.IDENTITY, 16, RasterMode.CENTERED, true,
                ModelResolver.NONE, 8).build();
        check("flat plane becomes a one-voxel slab when enabled",
                included.mask().count() == 16 * 16 && !included.mask().isEmpty(),
                included.mask());

        String line = """
                {"elements":[{"from":[0,16,16],"to":[16,16,16]}]}
                """;
        OccupancySource.OccupancyResult lineResult = new AutoOccupancySource(line, "line",
                DisplayTransform.IDENTITY, 16, RasterMode.CONSERVATIVE, true,
                ModelResolver.NONE, 8).build();
        check("a line has no volume even with planes", lineResult.mask().isEmpty(),
                lineResult.mask());
    }

    private static void autoDisplayScale() {
        String json = """
                {"elements":[{"from":[0,0,0],"to":[16,16,16]}]}
                """;
        OccupancyMask scaled = new AutoOccupancySource(json, "scaled",
                DisplayTransform.scaled(2), 16, RasterMode.CENTERED, false,
                ModelResolver.NONE, 8).build().mask();
        check("scale 2 makes a 2-block model", scaled.blocksX() == 2 && scaled.blocksY() == 2
                && scaled.blocksZ() == 2, scaled.describe());
        check("scale 2 fills eight blocks", scaled.isFull(), scaled.count());
        double[] forward = DisplayTransform.scaled(2).forward(16, 0, 0);
        check("display forward maps one block to two", Math.abs(forward[0] - 2.0) < 1e-9,
                forward[0]);
        double[] inverse = DisplayTransform.scaled(2).inverse(2, 0, 0);
        check("display inverse is exact", Math.abs(inverse[0] - 16.0) < 1e-9, inverse[0]);
    }

    private static void autoParentChain() {
        String parent = """
                {"elements":[{"from":[0,0,0],"to":[16,8,16]}]}
                """;
        String child = """
                {"parent":"minecraft:block/base",
                 "elements":[{"from":[0,8,0],"to":[16,16,16]}]}
                """;
        OccupancySource.OccupancyResult result = new AutoOccupancySource(child, "child",
                DisplayTransform.IDENTITY, 16, RasterMode.CENTERED, false,
                ModelResolver.of(Map.of("base", parent)), 8).build();
        check("parent elements are merged", result.mask().count() == 16 * 16 * 16,
                result.mask().count());
        check("missing parent warns", new AutoOccupancySource(child, "child",
                DisplayTransform.IDENTITY, 16, RasterMode.CENTERED, false,
                ModelResolver.NONE, 8).build().warnings().stream()
                .anyMatch(warning -> warning.contains("not found")), "no warning");

        String cycle = """
                {"parent":"a","elements":[]}
                """;
        OccupancySource.OccupancyResult cycleResult = new AutoOccupancySource(cycle, "a",
                DisplayTransform.IDENTITY, 16, RasterMode.CENTERED, false,
                ModelResolver.of(Map.of("a", cycle)), 8).build();
        check("parent cycles stop with a warning", cycleResult.warnings().stream()
                .anyMatch(warning -> warning.contains("cycle")), cycleResult.warnings());
    }

    private static void blueprints() {
        String json = """
                {
                  "schema": 1,
                  "resolution": 16,
                  "size": [2, 2, 1],
                  "regions": [
                    {"type": "fill", "from": [0,0,0], "to": [1,1,0], "directive": "SOLID"},
                    {"type": "layer", "y": 1, "directive": "TOP_SURFACE"},
                    {"type": "box", "from": [1,0,0], "to": [1,0,0], "directive": "STEP"}
                  ],
                  "cells": [
                    {"x": 0, "y": 0, "z": 0, "directive": "PASSABLE_HITBOX"},
                    {"x": 1, "y": 1, "z": 0, "directive":
                      "FORCE_BLOCK:minecraft:oak_slab[type=bottom,waterlogged=false]"}
                  ]
                }
                """;
        Blueprint blueprint = Blueprint.parse(json, "gate.blueprint.json");
        OccupancyMask mask = blueprint.toMask();
        check("blueprint size is 2×2×1", mask.blocksX() == 2 && mask.blocksY() == 2
                && mask.blocksZ() == 1, mask.describe());
        check("blueprint passable cell is cleared", !mask.get(8, 8, 8), mask);
        check("blueprint force-block cell stays solid", mask.get(24, 24, 8), mask);
        CellDirective forced = blueprint.directives().get(1, 1, 0);
        check("directive force state survives",
                forced.type() == DirectiveType.FORCE_BLOCK && forced.forceState().contains("oak_slab"),
                forced);
        check("directive lookup by coordinates",
                blueprint.directives().get(0, 0, 0).type() == DirectiveType.PASSABLE_HITBOX,
                blueprint.directives().get(0, 0, 0));
        check("blueprint cells are eight (2×2×1 full, then override)",
                blueprint.cellCount() == 4, blueprint.cellCount());
        check("blueprint reports overrides as warnings",
                !blueprint.warnings().isEmpty() && blueprint.warnings().stream()
                        .allMatch(warning -> warning.contains("redefined")),
                blueprint.warnings());
    }

    private static void blueprintErrors() {
        expectBlueprintError("unknown directive",
                "{\"size\":[1,1,1],\"cells\":[{\"x\":0,\"y\":0,\"z\":0,\"directive\":\"NOPE\"}]}",
                "unknown directive");
        expectBlueprintError("malformed JSON",
                "{\"size\":[1,1,1],}", "invalid JSON");
        expectBlueprintError("bad size",
                "{\"size\":[0,1,1]}", "at least 1");
        expectBlueprintError("newer schema",
                "{\"schema\":99,\"size\":[1,1,1]}", "newer than");
        expectBlueprintError("force block without state",
                "{\"size\":[1,1,1],\"cells\":[{\"x\":0,\"y\":0,\"z\":0,"
                        + "\"directive\":\"FORCE_BLOCK\"}]}", "FORCE_BLOCK");

        Blueprint outOfRange = Blueprint.parse(
                "{\"size\":[1,1,1],\"cells\":[{\"x\":5,\"y\":0,\"z\":0,\"directive\":\"SOLID\"}]}",
                "range.json");
        check("out-of-range cells warn and are dropped",
                outOfRange.warnings().stream().anyMatch(warning -> warning.contains("outside")),
                outOfRange.warnings());
        check("out-of-range cells are not stored", outOfRange.cellCount() == 0,
                outOfRange.cellCount());

        Blueprint conflict = Blueprint.parse(
                "{\"size\":[1,1,1],\"resolve\":true,"
                        + "\"cells\":[{\"x\":0,\"y\":0,\"z\":0,\"directive\":\"SOLID\"},"
                        + "{\"x\":0,\"y\":0,\"z\":0,\"directive\":\"EMPTY\"}]}", "conflict.json");
        check("conflicting cells warn", conflict.warnings().stream()
                .anyMatch(warning -> warning.contains("redefined")), conflict.warnings());
        check("last directive wins",
                conflict.directives().get(0, 0, 0).type() == DirectiveType.EMPTY,
                conflict.directives().get(0, 0, 0));
    }

    private static void expectBlueprintError(String name, String json, String expectedText) {
        try {
            Blueprint.parse(json, name);
            check(name + " is rejected", false, "no exception");
        } catch (BlueprintException expected) {
            check(name + " message is helpful",
                    expected.getMessage().toLowerCase(java.util.Locale.ROOT)
                            .contains(expectedText.toLowerCase(java.util.Locale.ROOT)),
                    expected.getMessage());
        }
    }

    private static void hybridAndFill() {
        String baseJson = """
                {"elements":[{"from":[0,0,0],"to":[16,16,16]}]}
                """;
        String blueprintJson = """
                {"size":[1,1,1],"cells":[
                  {"x":0,"y":0,"z":0,"directive":"PASSABLE_HITBOX"}]}
                """;
        HybridOccupancySource hybrid = new HybridOccupancySource(
                new AutoOccupancySource(baseJson, "base", DisplayTransform.IDENTITY, 16,
                        RasterMode.CENTERED, false, ModelResolver.NONE, 8),
                BlueprintOccupancySource.fromText(blueprintJson, "override"));
        OccupancySource.OccupancyResult result = hybrid.build();
        check("hybrid lets the blueprint clear the base", result.mask().isEmpty(),
                result.mask());
        check("hybrid keeps directives", result.directives().get(0, 0, 0) != null,
                result.directives().size());

        OccupancySource.OccupancyResult fill = new FillOccupancySource(16, 2, 2, 1, 0).build();
        check("fill covers four blocks", fill.mask().count() == 4 * 4096, fill.mask().count());
        OccupancySource.OccupancyResult inset = new FillOccupancySource(16, 2, 2, 1, 0.25).build();
        check("fill inset removes cells", inset.mask().count() < fill.mask().count()
                && inset.mask().count() > 0, inset.mask().count());
        OccupancySource.OccupancyResult tooMuch =
                new FillOccupancySource(16, 1, 1, 1, 0.6).build();
        check("over-inset fill is empty with a warning",
                tooMuch.mask().isEmpty() && !tooMuch.warnings().isEmpty(), tooMuch.warnings());
    }

    private static void occupancyMaskMath() {
        OccupancyMask wide = OccupancyMask.empty(16, 2, 1, 1);
        wide.fill(0, 0, 0, 31, 15, 15, true);
        check("wide grid is two blocks", wide.blocksX() == 2, wide.describe());
        wide.rotateY90(1);
        check("rotation swaps X and Z", wide.blocksX() == 1 && wide.blocksZ() == 2,
                wide.describe());
        wide.rotateY90(3);
        check("four rotations restore dimensions", wide.blocksX() == 2 && wide.blocksZ() == 1,
                wide.describe());

        OccupancyMask half = OccupancyMask.empty(8, 1, 1, 1);
        half.fill(0, 0, 0, 7, 3, 7, true);
        VoxelMask upscaled = half.blockMask(0, 0, 0);
        check("resolution 8 upscales to a half block",
                upscaled.count() == 16 * 16 * 8, upscaled.count());

        OccupancyMask diagonal = OccupancyMask.empty(16, 2, 1, 2);
        diagonal.fillBlockBox(0, 0, 0, 1, 1, 1);
        OccupancyMask rotated = diagonal.copy().rotateYaw(45);
        check("arbitrary yaw keeps volume", rotated.count() > 0, rotated.count());

        OccupancyMask a = OccupancyMask.empty(16, 1, 1, 1);
        a.fill(0, 0, 0, 15, 15, 15, true);
        OccupancyMask b = OccupancyMask.empty(16, 1, 1, 1);
        b.fill(0, 0, 0, 7, 15, 15, true);
        check("andNot keeps the difference", a.copy().andNot(b).count() == 8 * 16 * 16, a);
        check("top surface reads block height",
                Math.abs(a.topSurfaceAt(0, 0) - 1.0) < 1e-9, a.topSurfaceAt(0, 0));
        check("grid describes itself", a.describe().contains("1×1×1"), a.describe());
    }

    private static void check(String name, boolean condition, Object detail) {
        if (!condition) {
            failures.add(name + " (got: " + detail + ")");
        }
    }
}
