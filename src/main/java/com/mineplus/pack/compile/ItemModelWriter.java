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
     * <p>The frame state is <em>server-driven</em> on every server version:
     * the runtime writes the state number onto the stack and the pack renders
     * it. The alternative — a client-side ramp over the item's use gesture —
     * ties the presentation to the carrier item's vanilla use action, which
     * differs per material and per client version, and it cannot express a
     * state the server did not ask for (a reload that aborts, a weapon that
     * runs dry).</p>
     */
    public static String modernDefinitionJson(ItemModelAsset item) {
        JsonObject root = new JsonObject();
        if (item.isFramed()) {
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
        JsonArray entries = new JsonArray();
        entries.add(frameEntry(item.frameStackValue(0), fallbackReference));
        for (ItemModelAsset.Frame frame : item.frames()) {
            entries.add(frameEntry(item.frameStackValue(Math.round(frame.threshold())), frame.modelReference()));
        }
        dispatch.add("entries", entries);
        dispatch.add("fallback", frameEntry(0.0f, fallbackReference).getAsJsonObject("model"));
        return dispatch;
    }

    private static JsonObject frameEntry(float threshold, String modelReference) {
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", modelReference);
        JsonObject entry = new JsonObject();
        entry.addProperty("threshold", threshold);
        entry.add("model", model);
        return entry;
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
