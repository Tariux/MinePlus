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
     * Regression: the tea module's bbmodel must be authored in the vanilla
     * 0..16 box (the pack-item render anchors the box center on the display).
     * A center-authored model (-8..8) rendered half a block diagonally off,
     * placing the cup between four blocks. The baked rest model must land
     * inside 0..16 on x/z so the display sits centered in its 1x1x1 block.
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
            check("tea rest model inside 0..16 on x (got " + minX + ".." + maxX + ")",
                    minX >= 0.0f && maxX <= 16.0f);
            check("tea rest model inside 0..16 on z (got " + minZ + ".." + maxZ + ")",
                    minZ >= 0.0f && maxZ <= 16.0f);
            check("tea rest model spans the box center on x",
                    minX < 8.0f && maxX > 8.0f);
            check("tea rest model spans the box center on z",
                    minZ < 8.0f && maxZ > 8.0f);
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
