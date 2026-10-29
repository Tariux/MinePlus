package com.mineplus.pack.compile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineplus.pack.asset.ItemModelAsset;
import com.mineplus.util.DebugLogger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.Material;

/**
 * Emits the item-model representation files for registered custom items.
 *
 * <p>Modern (1.21.4+): one {@code assets/<ns>/items/<id>.json} per item
 * pointing at the geometry model. Strictly additive — vanilla items never
 * carry the {@code item_model} component, so nothing vanilla changes.</p>
 *
 * <p>Legacy (pre-1.21.4): one shared host file per backing material
 * ({@code assets/minecraft/models/item/<material>.json}) whose overrides
 * redirect only items carrying the item's stable {@code custom_model_data}
 * value. The host file regenerates the vanilla base with predicate overrides;
 * unset vanilla items (no custom model data) are untouched. This is the
 * era-typical cross-plugin conflict surface — modern mode is preferred
 * wherever available.</p>
 */
public final class ItemModelWriter {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ItemModelWriter() {
    }

    /**
     * Modern item model definition JSON. A geometry item emits
     * {@code {"model":{"type":"minecraft:model","model":"ns:item/id"}}}; a
     * raw-model item without frames emits the same shape toward its registered
     * reference; a framed item emits a {@code range_dispatch} over the item's
     * {@code custom_model_data} states, with the resting model as fallback.
     *
     * <p><b>Hierarchy.</b> The outer dispatch is the server's state channel:
     * the runtime writes a state number onto the stack and the pack renders
     * that state, on every representation. States that gate an animation
     * ({@link com.mineplus.pack.item.ItemFrames.ClipPlan}) do not render one
     * model, though — they render a nested dispatch over the client's
     * {@code minecraft:cooldown} property. The runtime starts the sequence by
     * setting a cooldown of exactly the clip's length, so the client walks the
     * frames every rendered frame instead of the server writing one stack per
     * tick. A state with no sequence renders its model directly, and the
     * legacy representation keeps rendering the state-per-frame list.</p>
     *
     * <p>Framed items also set {@code hand_animation_on_swap: false}: their
     * presentation changes through states and cooldowns, and the vanilla
     * down-and-up equip bob would otherwise replay on every gate write.</p>
     */
    public static String modernDefinitionJson(ItemModelAsset item) {
        JsonObject root = new JsonObject();
        if (item.isFramed()) {
            root.addProperty("hand_animation_on_swap", false);
            root.add("model", framedDispatch(item));
        } else {
            JsonObject model = new JsonObject();
            model.addProperty("type", "minecraft:model");
            String reference = item.fallbackModelReference() != null
                    ? item.fallbackModelReference()
                    : item.modelReference();
            model.addProperty("model", reference);
            root.add("model", model);
        }
        return GSON.toJson(root);
    }

    /**
     * {@code range_dispatch} on {@code custom_model_data} (float index 0):
     * entries in threshold order, resting-state fallback. Thresholds are the
     * item's {@code frameStackValue} per state — the same value the runtime
     * writes and the legacy host predicates read, so one write drives both
     * representations.
     *
     * <p>The resting state is an explicit entry and not only the fallback:
     * the dispatch then selects the same model under either threshold
     * convention (the highest entry the value clears, or the lowest entry the
     * value reaches), so the pack does not depend on how the client reads a
     * threshold. Without it, a stack sitting on the resting value could land
     * on the first animation pose instead of the resting one.</p>
     */
    private static JsonObject framedDispatch(ItemModelAsset item) {
        JsonObject dispatch = new JsonObject();
        dispatch.addProperty("type", "minecraft:range_dispatch");
        dispatch.addProperty("property", "minecraft:custom_model_data");
        dispatch.addProperty("index", 0);
        String fallbackReference = item.fallbackModelReference() != null
                ? item.fallbackModelReference()
                : item.modelReference();
        Map<Integer, JsonObject> sequences = sequenceDispatches(item, fallbackReference);

        JsonArray entries = new JsonArray();
        entries.add(frameEntry(item.frameStackValue(0),
                sequences.containsKey(0) ? sequences.get(0) : modelObject(fallbackReference)));
        for (ItemModelAsset.Frame frame : item.frames()) {
            int state = Math.round(frame.threshold());
            entries.add(frameEntry(item.frameStackValue(state),
                    sequences.containsKey(state) ? sequences.get(state) : modelObject(frame.modelReference())));
        }
        dispatch.add("entries", entries);
        dispatch.add("fallback", modelObject(fallbackReference));
        return dispatch;
    }

    /** One nested client-clock dispatch per gated state, keyed by state number. */
    private static Map<Integer, JsonObject> sequenceDispatches(ItemModelAsset item, String restingReference) {
        Map<Integer, JsonObject> byGate = new LinkedHashMap<>();
        for (com.mineplus.pack.item.ItemFrames.Sequence sequence : item.clipPlan().sequences()) {
            if (!sequence.animates() || byGate.containsKey(sequence.gateState())) {
                continue;
            }
            byGate.put(sequence.gateState(), cooldownDispatch(sequence, restingReference));
        }
        return byGate;
    }

    /**
     * One sequence over {@code minecraft:cooldown} (the remaining fraction of
     * the player's cooldown on this item, {@code 1.0} when it starts, {@code 0.0}
     * once finished).
     *
     * <p>Frame {@code i} of {@code n} occupies the interval
     * {@code [1-(i+1)/n, 1-i/n)}, so its entry threshold is
     * {@code 1-(i+1)/n} and entries are emitted in reverse play order
     * (ascending thresholds). A one-frame sequence holds its pose above a
     * threshold of one thousandth, so the fallback still wins at exactly
     * zero.</p>
     */
    private static JsonObject cooldownDispatch(com.mineplus.pack.item.ItemFrames.Sequence sequence,
                                               String restingReference) {
        JsonObject dispatch = new JsonObject();
        dispatch.addProperty("type", "minecraft:range_dispatch");
        dispatch.addProperty("property", "minecraft:cooldown");

        List<String> frames = sequence.frames();
        int count = frames.size();
        JsonArray entries = new JsonArray();
        if (count == 1) {
            entries.add(frameEntry(0.001f, modelObject(frames.get(0))));
        } else {
            for (int index = count - 1; index >= 0; index--) {
                float threshold = (float) (1.0 - (index + 1) / (double) count);
                entries.add(frameEntry(threshold, modelObject(frames.get(index))));
            }
        }
        dispatch.add("entries", entries);
        String fallback = sequence.fallbackReference() != null
                ? sequence.fallbackReference()
                : restingReference;
        dispatch.add("fallback", modelObject(fallback));
        return dispatch;
    }

    private static JsonObject modelObject(String modelReference) {
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", modelReference);
        return model;
    }

    private static JsonObject frameEntry(float threshold, JsonObject model) {
        JsonObject entry = new JsonObject();
        entry.addProperty("threshold", threshold);
        entry.add("model", model);
        return entry;
    }

    private static JsonObject frameEntry(float threshold, String modelReference) {
        return frameEntry(threshold, modelObject(modelReference));
    }

    /**
     * Legacy host files: backing material -> regenerated vanilla item model
     * with sorted predicate overrides for every item on that material.
     * Deterministic: hosts keyed by material id, overrides sorted by predicate
     * value. Every item kind is representable: geometry items map their
     * identity value; raw-model and framed items map their registered model
     * references (a frame state gets one override per state at
     * {@code frameStackValue(state)}, plus the resting state at the base).
     */
    public static Map<String, byte[]> legacyHostFiles(List<ItemModelAsset> items) {
        Map<Material, List<ItemModelAsset>> byMaterial = new LinkedHashMap<>();
        for (ItemModelAsset item : items) {
            byMaterial.computeIfAbsent(item.backingMaterial(), material -> new ArrayList<>()).add(item);
        }

        Map<String, byte[]> files = new TreeMap<>();
        for (Map.Entry<Material, List<ItemModelAsset>> entry : byMaterial.entrySet()) {
            Material material = entry.getKey();
            String materialKey = materialKey(material);

            List<ItemModelAsset> sorted = new ArrayList<>(entry.getValue());
            sorted.sort(Comparator.comparingInt(ItemModelAsset::customModelData));

            JsonObject root = new JsonObject();
            root.addProperty("parent", vanillaItemParent(material));
            if (!material.isBlock()) {
                JsonObject textures = new JsonObject();
                textures.addProperty("layer0", "minecraft:item/" + materialKey);
                root.add("textures", textures);
            }

            JsonArray overrideList = new JsonArray();
            for (ItemModelAsset item : sorted) {
                if (item.isFramed()) {
                    // Plain state entries first: idle at the item base, then one
                    // predicate per frame state. (Vanilla picks the LAST override
                    // whose predicates are all satisfied.)
                    addOverride(overrideList, item.customModelData(), item.fallbackModelReference());
                    for (ItemModelAsset.Frame frame : item.frames()) {
                        addOverride(overrideList, item.frameStackValue(Math.round(frame.threshold())),
                                frame.modelReference());
                    }
                } else if (item.isRawModel()) {
                    addOverride(overrideList, item.customModelData(), item.fallbackModelReference());
                } else {
                    addOverride(overrideList, item.customModelData(), item.modelReference());
                }
            }
            root.add("overrides", overrideList);

            files.put("assets/minecraft/models/item/" + materialKey + ".json",
                    GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        }
        return files;
    }

    /**
     * One sorted predicate override; a colliding predicate keeps its first
     * owner (a genuine cross-module CMD collision — reported, never fatal).
     */
    private static void addOverride(JsonArray overrides, int customModelData, String modelReference) {
        if (modelReference == null || modelReference.isBlank()) {
            return;
        }
        for (int index = 0; index < overrides.size(); index++) {
            JsonObject existing = overrides.get(index).getAsJsonObject();
            if (existing.getAsJsonObject("predicate").has("custom_model_data")
                    && existing.getAsJsonObject("predicate").get("custom_model_data").getAsInt() == customModelData
                    && existing.getAsJsonObject("predicate").size() == 1) {
                String existingModel = existing.get("model").getAsString();
                if (!existingModel.equals(modelReference)) {
                    DebugLogger.warning("[Pack] Legacy override collision on custom_model_data="
                            + customModelData + ": '" + existingModel + "' wins over '" + modelReference + "'.");
                }
                return;
            }
        }
        JsonObject predicate = new JsonObject();
        predicate.addProperty("custom_model_data", customModelData);
        JsonObject override = new JsonObject();
        override.add("predicate", predicate);
        override.addProperty("model", modelReference);
        overrides.add(override);
    }

    /**
     * The registry key of a material. Modern Bukkit material enum names are
     * exactly the uppercase registry paths, so the enum name lowercased is
     * the key — avoiding the adventure {@code Key} type, whose examination
     * supertype is not on the compile classpath.
     */
    static String materialKey(Material material) {
        return material.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The vanilla base model of a backing material, reproduced so unset items
     * look exactly vanilla: block materials render their block model, items
     * the standard generated sprite.
     */
    private static String vanillaItemParent(Material material) {
        if (material.isBlock()) {
            return "minecraft:block/" + materialKey(material);
        }
        return "minecraft:item/generated";
    }
}
