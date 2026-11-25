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
import java.util.Map;
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
 * display and in an inventory, with no virtual-engine texel bake.
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
    /**
     * How many authored steam cycles one baked sequence replays. The pack plays
     * a sequence on the item cooldown's clock, and a cooldown is per material —
     * every re-arm sweeps the vanilla cooldown overlay over every potion in the
     * player's inventory. Repeating the loop inside one long sequence makes that
     * flash rare instead of constant.
     */
    private static final int STEAM_REPEATS = 6;

    /**
     * Hand poses for slots the bbmodel may not author. Authored slots always
     * win ({@link #displayWithHands}); only missing hand slots come from here
     * so the cup never falls back to the vanilla display transform (which
     * swallows the model in first person).
     */
    private static final String HAND_DISPLAY_DEFAULTS = """
            {
             "firstperson_righthand":{"rotation":[0,0,0],"translation":[0,2,0.5],"scale":[0.6,0.6,0.6]},
             "firstperson_lefthand":{"rotation":[0,0,0],"translation":[0,2,0.5],"scale":[0.6,0.6,0.6]},
             "thirdperson_righthand":{"rotation":[0,0,0],"translation":[0,2,0.5],"scale":[0.65,0.65,0.65]},
             "thirdperson_lefthand":{"rotation":[0,0,0],"translation":[0,2,0.5],"scale":[0.65,0.65,0.65]}
            }
            """;

    /**
     * Maps authored clip names onto the Core's client-clock clip vocabulary
     * ({@code reload}/{@code fire}/{@code equip}/{@code cycle}). Only these
     * names get a cooldown-driven sequence in the pack, so an unmapped name
     * leaves the clip server-writable only — which a held stack cannot do
     * without flickering (the gun module's bake doc spells this out).
     *
     * <ul>
     *   <li>{@code drink_firstperson} → {@code reload}: gated on its own first
     *       frame, so the right-click handler writes that state once and starts
     *       the cooldown; the drink then plays entirely on the client's clock.</li>
     *   <li>{@code steam_idle} → {@code fire}: gated on the resting state, so
     *       the stack can sit at rest forever and the runtime just re-arms the
     *       cooldown each cycle — zero stack rewrites while held.</li>
     * </ul>
     */
    private static final Map<String, String> CLIP_NAME_MAPPING = Map.of(
            "drink_firstperson", "reload",
            "steam_idle", "fire");

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
            BbClipBaker.Style style = new BbClipBaker.Style(displayWithHands(bytes),
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
                BakedClip baked = bakeClip(context.packApi(), stage, model, style, clip, rest, log);
                if (baked == null) {
                    continue;
                }
                builder.clip(baked.registeredName(), baked.references(),
                        baked.ticks(), baked.loop());
                clips++;
            }
            log.info("[Tea] Baked " + (clips + 1) + " pack frame model group(s) from tea.bbmodel.");
            return builder.build();
        } catch (IOException e) {
            log.warning("[Tea] Failed to bake tea frames: " + e.getMessage());
            return null;
        }
    }

    /** One clip compiled into pack frame references plus its client clock. */
    private record BakedClip(String registeredName, List<String> references, int ticks, boolean loop) {
    }

    /**
     * Compiles one authored clip: samples poses at {@link #POSES_PER_SECOND},
     * drops frames identical to the resting model, writes each distinct frame,
     * maps the clip onto the client-clock vocabulary and stretches looping
     * clips over {@link #STEAM_REPEATS} repeats.
     */
    private static BakedClip bakeClip(PackApi packApi, File stage, ModelData model,
                                      BbClipBaker.Style style, String clip,
                                      JsonObject rest, Logger log) throws IOException {
        String lower = clip.toLowerCase(Locale.ROOT);
        float seconds = BbClipBaker.lengthSeconds(model, clip);
        int poses = Math.max(MIN_POSES, Math.min(MAX_POSES,
                Math.round(seconds * POSES_PER_SECOND) + 1));
        List<JsonObject> frames = distinct(
                BbClipBaker.poses(model, style, clip, poses).models(), rest);
        if (frames.isEmpty()) {
            log.warning("[Tea] clip '" + clip + "' produced no distinct poses; skipped.");
            return null;
        }
        List<String> references = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            references.add(reference(write(packApi, stage, stem(clip, i), frames.get(i))));
        }
        boolean loop = lower.contains("steam");
        int ticks = Math.max(1, Math.round(seconds * POSES_PER_SECOND));
        if (loop) {
            // Looping clips replay STEAM_REPEATS times inside one sequence so
            // the runtime re-arms the item cooldown rarely (each re-arm
            // flashes the cooldown overlay on every potion in the inventory).
            List<String> timed = new ArrayList<>();
            for (int repeat = 0; repeat < STEAM_REPEATS; repeat++) {
                timed.addAll(references);
            }
            references = timed;
            ticks *= STEAM_REPEATS;
        }
        return new BakedClip(CLIP_NAME_MAPPING.getOrDefault(lower, lower), references, ticks, loop);
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

    /**
     * The authored display block plus the hand slots the bbmodel omits. Authored
     * slots always win; only missing hand slots come from {@link
     * #HAND_DISPLAY_DEFAULTS} so the cup holds like a cup instead of collapsing
     * onto the vanilla first-person transform.
     */
    private static JsonObject displayWithHands(byte[] bytes) {
        JsonObject display = displayOf(bytes);
        JsonObject defaults = JsonParser.parseString(HAND_DISPLAY_DEFAULTS).getAsJsonObject();
        for (Map.Entry<String, JsonElement> slot : defaults.entrySet()) {
            if (!display.has(slot.getKey())) {
                display.add(slot.getKey(), slot.getValue().deepCopy());
            }
        }
        return display;
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
