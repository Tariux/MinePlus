package com.mineplus.fun.tea;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.mob.custom.model.BbModelParser;
import com.mineplus.infrastructure.mob.custom.model.ModelData;
import com.mineplus.pack.PackApi;
import com.mineplus.pack.item.BbClipBaker;
import com.mineplus.pack.item.ItemFrames;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Bakes the tea bbmodel's clips into resource-pack frame models.
 *
 * <p>The tea item is a framed pack item: its geometry is not compiled from the
 * virtual model, it comes from verbatim model JSONs this class bakes out of the
 * installed {@code models/tea.bbmodel} and registers as raw pack assets under
 * {@code models/item/tea/}. Each clip becomes a numbered frame sequence
 * ({@code tea_drink_0, tea_drink_1, …}); {@link ItemFrames} wires the
 * sequences into named clips the runtime animator walks through, so the cup
 * animates through the resource pack itself — the same look in hand, in a
 * display and in an inventory, with no virtual-engine texel bake involved.
 */
final class TeaBaker {

    /** Where the baked frame models are staged (module data folder). */
    private static final String STAGE_ROOT = "pack/fun/models/item/tea";
    /** The pack path the frame models register under (with extension, like the gun). */
    private static final String PACK_ROOT = "models/item/tea";
    /** The texture reference every baked frame points at. */
    private static final String TEXTURE_REFERENCE = TeaKeys.NAMESPACE + ":item/tea";
    /** Poses sampled per second of authored clip length. */
    private static final int POSES_PER_SECOND = 20;
    private static final int MIN_POSES = 3;
    private static final int MAX_POSES = 16;

    private TeaBaker() {
    }

    /**
     * Bakes every clip of the installed tea model and registers the frames
     * with the pack.
     *
     * @return the frame spec for the tea item, or {@code null} when the model
     * is missing or carries no geometry (the item then falls back to the
     * vanilla potion look and the animations are unavailable)
     */
    static ItemFrames bake(PluginContext context, Logger log) {
        File source = context.moduleSupport().dataFile("models/tea.bbmodel");
        File texture = context.moduleSupport().dataFile("models/tea.png");
        if (!source.isFile()) {
            log.warning("[Tea] models/tea.bbmodel is not installed; skipping frame bake.");
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(source.toPath());
            ModelData model = BbModelParser.parse("tea", bytes, reference -> null);
            BbClipBaker.Style style = new BbClipBaker.Style(displayOf(bytes),
                    TEXTURE_REFERENCE, true);
            JsonObject rest = first(BbClipBaker.rest(model, style));
            if (rest == null) {
                log.warning("[Tea] tea.bbmodel has no cube geometry to bake.");
                return null;
            }
            // The texture the baked frames reference must ship in the pack.
            if (texture.isFile()) {
                context.packApi().registerTexture(TeaKeys.NAMESPACE, "item/tea", texture);
            }
            File stage = context.moduleSupport().dataFile(STAGE_ROOT);
            stage.mkdirs();

            String restStem = write(context.packApi(), stage, "tea_rest", rest);
            ItemFrames.Builder builder = ItemFrames.builder(reference(restStem));
            int clips = 0;
            for (String clip : BbClipBaker.clips(model)) {
                float seconds = BbClipBaker.lengthSeconds(model, clip);
                int poses = Math.max(MIN_POSES, Math.min(MAX_POSES,
                        Math.round(seconds * POSES_PER_SECOND) + 1));
                List<JsonObject> frames = distinct(
                        BbClipBaker.poses(model, style, clip, poses).models(), rest);
                if (frames.isEmpty()) {
                    log.warning("[Tea] clip '" + clip + "' produced no distinct poses; skipped.");
                    continue;
                }
                List<String> references = new ArrayList<>();
                for (int i = 0; i < frames.size(); i++) {
                    references.add(reference(write(context.packApi(), stage, stem(clip, i), frames.get(i))));
                }
                // steam_idle is authored as a loop; every other clip plays once.
                boolean loop = clip.toLowerCase(Locale.ROOT).contains("steam");
                builder.clip(clip.toLowerCase(Locale.ROOT), references, 0, loop);
                clips++;
            }
            log.info("[Tea] Baked " + (clips + 1) + " pack frame model group(s) from tea.bbmodel.");
            return builder.build();
        } catch (IOException e) {
            log.warning("[Tea] Failed to bake tea frames: " + e.getMessage());
            return null;
        }
    }

    private static String stem(String clip, int index) {
        return "tea_" + clip.toLowerCase(Locale.ROOT).replace(' ', '_') + "_" + index;
    }

    private static String reference(String stem) {
        return TeaKeys.NAMESPACE + ":item/tea/" + stem;
    }

    /** The bbmodel's authored display block, kept verbatim, or an empty block. */
    private static JsonObject displayOf(byte[] bytes) {
        try {
            JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (root.isJsonObject()) {
                JsonElement display = root.getAsJsonObject().get("display");
                if (display != null && display.isJsonObject() && display.getAsJsonObject().size() > 0) {
                    return display.getAsJsonObject().deepCopy();
                }
            }
        } catch (Exception ignored) {
            // fall through to vanilla defaults
        }
        return new JsonObject();
    }

    private static JsonObject first(BbClipBaker.Result result) {
        return result.models().isEmpty() ? null : result.models().get(0);
    }

    /** Drops frames identical to the resting model so an idle-looking pose never flickers. */
    private static List<JsonObject> distinct(List<JsonObject> frames, JsonObject rest) {
        Set<String> seen = new LinkedHashSet<>();
        seen.add(rest.toString());
        List<JsonObject> distinct = new ArrayList<>();
        for (JsonObject frame : frames) {
            if (seen.add(frame.toString())) {
                distinct.add(frame);
            }
        }
        return distinct;
    }

    /** Writes one staged frame model and registers it as a raw pack asset. */
    private static String write(PackApi packApi, File stage, String stem, JsonObject model) throws IOException {
        File file = new File(stage, stem + ".json");
        Files.writeString(file.toPath(), model.toString(), StandardCharsets.UTF_8);
        // Raw asset paths are output paths under assets/<ns>/ and must keep the
        // .json extension — the model reference "fun:item/tea/<stem>" only
        // resolves when the file lands at models/item/tea/<stem>.json.
        packApi.registerRawAsset(TeaKeys.NAMESPACE, PACK_ROOT + "/" + stem + ".json", file);
        return stem;
    }
}
