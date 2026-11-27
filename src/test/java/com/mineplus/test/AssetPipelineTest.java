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
        anchorModeParsing();
        texturePipelineDefaults();
        packTexturePieces();

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

    /**
     * The tea meta declares {@code anchorMode: FRAME}: small items anchor on
     * one invisible Interaction entity with no barrier blocks, sized from the
     * model's bounding box and clamped inside the 1x1x1 anchor cell. Machines
     * without the field keep the LATTICE default.
     */
    private static void anchorModeParsing() {
        check("FRAME meta parsed",
                loadAnchorMeta("{\"originMode\": \"CENTER\", \"anchorMode\": \"FRAME\"}")
                        == com.mineplus.infrastructure.virtual.AnchorMode.FRAME);
        check("missing anchorMode defaults to LATTICE",
                loadAnchorMeta("{\"originMode\": \"CENTER\"}")
                        == com.mineplus.infrastructure.virtual.AnchorMode.LATTICE);
        check("unknown anchorMode falls back to LATTICE",
                loadAnchorMeta("{\"anchorMode\": \"not-a-mode\"}")
                        == com.mineplus.infrastructure.virtual.AnchorMode.LATTICE);
    }

    private static com.mineplus.infrastructure.virtual.AnchorMode loadAnchorMeta(String json) {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("anchor-meta");
            java.nio.file.Path model = dir.resolve("probe.bbmodel");
            java.nio.file.Files.writeString(model, "{}");
            java.nio.file.Files.writeString(dir.resolve("probe.meta.json"), json);
            return com.mineplus.infrastructure.virtual.ModelMeta.load(model.toFile()).resolvedAnchorMode();
        } catch (Exception e) {
            failures.add("anchor meta parse: " + e);
            return com.mineplus.infrastructure.virtual.AnchorMode.LATTICE;
        }
    }

    /**
     * Texel AUTO plates only art the client cannot otherwise see: a face whose
     * texture name is a vanilla block keeps that block (water wheel = wood), a
     * custom name with pixels is plated (guns), and a meta override still wins.
     * Vanilla and legacy concatenated names must resolve so that skip works.
     */
    /**
     * Pack texture ids are model-scoped so two models may embed different art
     * under the same name; memory-backed textures must export their bytes.
     */
    private static void packTexturePieces() {
        check("pack id is <model>_<texture>",
                "water_wheel_oak_planks".equals(
                        com.mineplus.pack.PackSystem.packTextureName("water_wheel", "oak_planks")));
        check("namespace prefix is dropped from the model id",
                "water_wheel_oak_planks".equals(
                        com.mineplus.pack.PackSystem.packTextureName("mineplus:water_wheel", "oak_planks")));
        check("unsafe characters are sanitised",
                "iron_gear_t e x t".equals(
                        com.mineplus.pack.PackSystem.packTextureName("iron gear", "t e x t.png")));
        try {
            var asset = new com.mineplus.pack.asset.TextureAsset(
                    "mineplus", "item/water_wheel_oak_planks", "test", new byte[]{1, 2, 3});
            check("memory-backed texture exports its bytes",
                    java.util.Arrays.equals(asset.serialize(), new byte[]{1, 2, 3}));
        } catch (Exception e) {
            check("memory-backed texture exports its bytes: " + e, false);
        }
    }

    private static void texturePipelineDefaults() {
        var settings = com.mineplus.infrastructure.virtual.texel.TexelBakingSettings.defaults();
        check("texel default mode is AUTO",
                settings.effectiveMode(com.mineplus.infrastructure.virtual.ModelMeta.empty())
                        == com.mineplus.infrastructure.virtual.ModelMeta.TexelMode.AUTO);
        check("meta texelMode OFF disables plates for that model",
                settings.effectiveMode(loadTexelMeta("{\"texelMode\": \"OFF\"}"))
                        == com.mineplus.infrastructure.virtual.ModelMeta.TexelMode.OFF);
        check("global OFF disables plates everywhere",
                new com.mineplus.infrastructure.virtual.texel.TexelBakingSettings(
                        false,
                        com.mineplus.infrastructure.virtual.ModelMeta.TexelMode.AUTO,
                        settings.detail(), settings.maxPlatesPerFace(), settings.maxPlatesPerInstance(),
                        settings.maxGridEdge(), settings.uniformAreaDetection(), settings.uniformAreaMinSize(),
                        settings.uniformAreaOklabThreshold(), settings.adaptiveBudgeting(),
                        settings.budgetFallbackToSimpleColor(), settings.reuseSymmetricFaces())
                        .effectiveMode(com.mineplus.infrastructure.virtual.ModelMeta.empty())
                        == com.mineplus.infrastructure.virtual.ModelMeta.TexelMode.OFF);

        check("vanilla texture name resolves (oak_planks)",
                com.mineplus.infrastructure.virtual.TextureMaterialResolver
                        .resolveDetailed("oak_planks").material() == org.bukkit.Material.OAK_PLANKS);
        check("legacy concatenated name resolves (blackwool)",
                com.mineplus.infrastructure.virtual.TextureMaterialResolver
                        .resolveDetailed("blackwool").material() == org.bukkit.Material.BLACK_WOOL);
        check("legacy multi-word name resolves (orangeconcretepowder)",
                com.mineplus.infrastructure.virtual.TextureMaterialResolver
                        .resolveDetailed("orangeconcretepowder").material()
                        == org.bukkit.Material.ORANGE_CONCRETE_POWDER);
        check("reversed word order resolves (spruce_log -> log_spruce)",
                com.mineplus.infrastructure.virtual.TextureMaterialResolver
                        .resolveDetailed("spruce_log").material() == org.bukkit.Material.SPRUCE_LOG);
        check("planned vanilla name resolves (oak_planks)",
                com.mineplus.infrastructure.virtual.TextureMaterialResolver
                        .resolveDetailed("oak_planks").material() == org.bukkit.Material.OAK_PLANKS);
        check("short metal name resolves (iron)",
                com.mineplus.infrastructure.virtual.TextureMaterialResolver
                        .resolveDetailed("iron").material() == org.bukkit.Material.IRON_BLOCK);

        var auto = com.mineplus.infrastructure.virtual.ModelMeta.TexelMode.AUTO;
        for (String wheelTexture : new String[]{"oak_planks", "spruce_log", "stone", "iron"}) {
            check("AUTO keeps the block for wheel texture " + wheelTexture,
                    com.mineplus.infrastructure.virtual.texel.TexelSurfaceBaker
                            .keepsVanillaBlock(wheelTexture, auto));
        }
        check("AUTO plates custom art (gun_body)",
                !com.mineplus.infrastructure.virtual.texel.TexelSurfaceBaker
                        .keepsVanillaBlock("gun_body", auto));
        check("ON plates even vanilla-named art (oak_planks)",
                !com.mineplus.infrastructure.virtual.texel.TexelSurfaceBaker
                        .keepsVanillaBlock("oak_planks",
                                com.mineplus.infrastructure.virtual.ModelMeta.TexelMode.ON));
        check("pixel-less legacy name is not marked fallback",
                !com.mineplus.infrastructure.virtual.TextureMaterialResolver
                        .resolveDetailed("blackwool").isFallback());
    }

    private static com.mineplus.infrastructure.virtual.ModelMeta loadTexelMeta(String json) {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("texel-meta");
            java.nio.file.Path model = dir.resolve("probe.bbmodel");
            java.nio.file.Files.writeString(model, "{}");
            java.nio.file.Files.writeString(dir.resolve("probe.meta.json"), json);
            return com.mineplus.infrastructure.virtual.ModelMeta.load(model.toFile());
        } catch (Exception e) {
            failures.add("texel meta parse: " + e);
            return com.mineplus.infrastructure.virtual.ModelMeta.empty();
        }
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
