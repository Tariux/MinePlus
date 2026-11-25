package com.mineplus.test;

import com.mineplus.infrastructure.core.pipeline.AssetPipeline;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plain-Java checks for C8: dependency-ordered builds, cycle and unknown
 * dependency detection, and incremental rebuilds.
 *
 * Run: java com.mineplus.test.AssetPipelineTest — exit 0 means all passed.
 */
public final class AssetPipelineTest {

    private static final List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) {
        dependencyOrdering();
        cycleAndUnknownDetection();
        incrementalRebuilds();
        teaModelBakesInsideVanillaWindow();

        if (failures.isEmpty()) {
            System.out.println("AssetPipelineTest: all checks passed");
            System.exit(0);
        }
        System.err.println("AssetPipelineTest: " + failures.size() + " failure(s)");
        failures.forEach(f -> System.err.println("  - " + f));
        System.exit(1);
    }

    private static void dependencyOrdering() {
        AssetPipeline pipeline = new AssetPipeline();
        List<String> built = new java.util.ArrayList<>();
        pipeline.define("texture:sword", List.of(), (n, d) -> {
            built.add(n);
            return "png:" + n;
        });
        pipeline.define("model:sword", List.of("texture:sword"), (n, d) -> {
            built.add(n);
            return d.get("texture:sword") + "+json";
        });
        pipeline.define("item:sword", List.of("model:sword"), (n, d) -> {
            built.add(n);
            return d.get("model:sword") + "+def";
        });

        List<String> order = pipeline.buildAll();
        check("texture built before model",
                built.indexOf("texture:sword") < built.indexOf("model:sword"));
        check("model built before item",
                built.indexOf("model:sword") < built.indexOf("item:sword"));
        check("order covers all", order.size() == 3);
        check("dependency output forwarded",
                pipeline.output("item:sword").equals("png:texture:sword+json+def"));
    }

    private static void cycleAndUnknownDetection() {
        AssetPipeline cyclic = new AssetPipeline();
        cyclic.define("a", List.of("b"), (n, d) -> "a");
        cyclic.define("b", List.of("a"), (n, d) -> "b");
        boolean cycle = false;
        try {
            cyclic.buildAll();
        } catch (AssetPipeline.PipelineException e) {
            cycle = true;
        }
        check("cycle detected", cycle);

        AssetPipeline unknown = new AssetPipeline();
        unknown.define("orphan", List.of("ghost"), (n, d) -> "x");
        boolean missing = false;
        try {
            unknown.buildAll();
        } catch (AssetPipeline.PipelineException e) {
            missing = true;
        }
        check("unknown dependency detected", missing);
    }

    private static void incrementalRebuilds() {
        AssetPipeline pipeline = new AssetPipeline();
        AtomicInteger builds = new AtomicInteger();
        pipeline.define("base", List.of(), (n, d) -> {
            builds.incrementAndGet();
            return "base-out";
        });
        pipeline.define("derived", List.of("base"), (n, d) -> {
            builds.incrementAndGet();
            return "derived-out";
        });

        pipeline.buildIncremental(n -> "v1");
        check("first incremental builds all", builds.get() == 2);

        pipeline.buildIncremental(n -> "v1");
        check("unchanged inputs skip rebuild", builds.get() == 2);

        pipeline.buildIncremental(n -> n.equals("base") ? "v2" : "v1");
        check("changed dependency cascades", builds.get() == 4);
        check("outputs refreshed", pipeline.output("derived").equals("derived-out"));
    }

    /**
     * Regression: the tea module's bbmodel is CENTER-origin, so the Core
     * expects it authored around (0,0) on x/z (-8..8) — the pack writer
     * applies the vanilla (-8, 0, -8) element shift itself. A model
     * re-authored into 0..16 was shifted twice and rendered half a block
     * diagonally off, placing the cup between four blocks. The baked rest
     * model must straddle the origin on x/z, and the drink clip must move
     * the cup toward +z (toward the first-person face — the earlier -z
     * authoring read as tipping away in game) and lift it.
     */
    private static void teaModelBakesInsideVanillaWindow() {
        java.io.File source = new java.io.File(
                "examples/mineplus-fun/src/main/resources/defaults/models/tea.bbmodel");
        if (!source.isFile()) {
            check("tea bbmodel installed", false);
            return;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(source.toPath());
            var model = com.mineplus.infrastructure.mob.custom.model.BbModelParser.parse(
                    "tea", bytes, reference -> null);
            var rest = com.mineplus.pack.item.BbClipBaker.rest(model,
                    new com.mineplus.pack.item.BbClipBaker.Style(
                            new com.google.gson.JsonObject(), "fun:item/tea", true)).models();
            check("tea rest model baked", !rest.isEmpty());
            if (rest.isEmpty()) {
                return;
            }
            var elements = rest.get(0).getAsJsonObject().getAsJsonArray("elements");
            check("tea rest model has cubes", elements.size() > 0);
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (var entry : elements) {
                var e = entry.getAsJsonObject();
                minX = Math.min(minX, e.getAsJsonArray("from").get(0).getAsFloat());
                maxX = Math.max(maxX, e.getAsJsonArray("to").get(0).getAsFloat());
                minZ = Math.min(minZ, e.getAsJsonArray("from").get(2).getAsFloat());
                maxZ = Math.max(maxZ, e.getAsJsonArray("to").get(2).getAsFloat());
            }
            check("tea rest model centered on origin x (got " + minX + ".." + maxX + ")",
                    minX >= -8.0f && maxX <= 8.0f && minX < 0.0f && maxX > 0.0f);
            check("tea rest model centered on origin z (got " + minZ + ".." + maxZ + ")",
                    minZ >= -8.0f && maxZ <= 8.0f && minZ < 0.0f && maxZ > 0.0f);
            boolean drinkApproachesFace = false;
            for (var animation : model.animations().values()) {
                if (!animation.name().toLowerCase().contains("drink")) {
                    continue;
                }
                for (var track : animation.trackEntries().values()) {
                    var position = track.position();
                    if (position == null || position.keyCount() == 0) {
                        continue;
                    }
                    float[] out = new float[3];
                    for (int i = 0; i < position.keyCount(); i++) {
                        position.sample(position.firstTime() + i, out);
                        if (out[2] > 0) {
                            drinkApproachesFace = true;
                        }
                    }
                }
            }
            check("drink clip moves the cup toward the face (+z)", drinkApproachesFace);
            boolean steamLooping = model.animations().values().stream()
                    .filter(a -> a.name().toLowerCase().contains("steam"))
                    .allMatch(a -> a.lengthSeconds() > 0);
            check("steam clip has a real duration", steamLooping);
        } catch (Exception e) {
            failures.add("tea bake probe: " + e);
        }
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
