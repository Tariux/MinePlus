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
     * reference; a framed item with staged load frames emits a
     * <em>composed</em> definition — the client's own draw gesture
     * ({@code using_item} → {@code use_duration} ramp over the staged frames,
     * settling on the loaded model) while not using, the resting
     * {@code range_dispatch} over the item's {@code custom_model_data}
     * states. A framed item without staged frames keeps the plain dispatch.
     */
    public static String modernDefinitionJson(ItemModelAsset item) {
        JsonObject root = new JsonObject();
        if (item.isFramed() && !item.reloadFrames().isEmpty()) {
            JsonObject condition = new JsonObject();
            condition.addProperty("type", "minecraft:condition");
            condition.addProperty("property", "minecraft:using_item");
            condition.add("on_true", drawDispatch(item));
            condition.add("on_false", framedDispatch(item));
            root.add("model", condition);
        } else if (item.isFramed()) {
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
     * The held-use (draw) ramp: {@code use_duration} (scale 0.05, the vanilla
     * crossbow convention) walks the staged load frames while the player holds
     * right-click and settles on the loaded model at the full charge. Fallback
     * is the resting idle model.
     */
    private static JsonObject drawDispatch(ItemModelAsset item) {
        JsonObject dispatch = new JsonObject();
        dispatch.addProperty("type", "minecraft:range_dispatch");
        dispatch.addProperty("property", "minecraft:use_duration");
        dispatch.addProperty("scale", 0.05);
        JsonArray entries = new JsonArray();
        List<ItemModelAsset.Frame> staged = item.reloadFrames();
        int stagedCount = staged.size();
        for (int index = 0; index < stagedCount; index++) {
            entries.add(frameEntry(RAMP_STAGED_END * (index + 1) / stagedCount, staged.get(index).modelReference()));
        }
        String loadedReference = loadedReference(item);
        if (loadedReference != null) {
            entries.add(frameEntry(RAMP_LOADED, loadedReference));
        }
        dispatch.add("entries", entries);
        String fallbackReference = item.fallbackModelReference() != null
                ? item.fallbackModelReference()
                : item.modelReference();
        dispatch.add("fallback", frameEntry(0.0f, fallbackReference).getAsJsonObject("model"));
        return dispatch;
    }

    /** The item's loaded-state model, or its idle model when none is declared. */
    private static String loadedReference(ItemModelAsset item) {
        String loaded = item.loadedModelReference();
        return loaded != null ? loaded
                : item.fallbackModelReference() != null ? item.fallbackModelReference() : item.modelReference();
    }

    /**
     * {@code range_dispatch} on {@code custom_model_data} (float index 0):
     * entries in threshold order, resting-state fallback. Thresholds are the
     * item's {@code frameStackValue} per state — the same value the runtime
     * writes and the legacy host predicates read, so one write drives both
     * representations.
     */
    private static JsonObject framedDispatch(ItemModelAsset item) {
        JsonObject dispatch = new JsonObject();
        dispatch.addProperty("type", "minecraft:range_dispatch");
        dispatch.addProperty("property", "minecraft:custom_model_data");
        dispatch.addProperty("index", 0);
        JsonArray entries = new JsonArray();
        for (ItemModelAsset.Frame frame : item.frames()) {
            entries.add(frameEntry(item.frameStackValue(Math.round(frame.threshold())), frame.modelReference()));
        }
        dispatch.add("entries", entries);
        String fallbackReference = item.fallbackModelReference() != null
                ? item.fallbackModelReference()
                : item.modelReference();
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

    /** Draw-ramp thresholds (seconds, with the 0.05 scale): staged walk over [0, 0.75], full charge at 0.9 s. */
    private static final float RAMP_STAGED_END = 0.75f;
    private static final float RAMP_LOADED = 0.9f;

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
                    // Draw ramp last, so while the player holds right-click on a
                    // usable carrier the staged frames play out over the pull
                    // progress and settle on the loaded model. Every ramp entry
                    // is also gated on the item's own custom_model_data states:
                    // several framed items share one carrier host file, and an
                    // un-gated pulling predicate would make the LAST item's ramp
                    // shadow every other weapon's draw animation (and its
                    // recoil pose) on the same material.
                    List<ItemModelAsset.Frame> staged = item.reloadFrames();
                    if (!staged.isEmpty() && isUsableCarrier(material)) {
                        for (int index = 0; index < staged.size(); index++) {
                            addPullOverride(overrideList, item, (float) index / staged.size(),
                                    staged.get(index).modelReference());
                        }
                        addPullOverride(overrideList, item, 1.0f, loadedReference(item));
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
     * The draw-ramp overrides of one framed item on a usable carrier. While
     * the client's draw gesture is active ({@code pulling}) the stack's
     * custom-model-data is whatever the runtime last wrote (idle, loaded, or
     * any mid-reload state), so the ramp is emitted once per writable state of
     * the item, each entry additionally gated on that state's
     * {@code custom_model_data} and ascending {@code pull} threshold.
     * Predicate fields combine conjunctively, so the ramp of one item can
     * never fire while a different item on the same carrier is drawn.
     */
    private static void addPullOverride(JsonArray overrides, ItemModelAsset item, float pull,
                                        String modelReference) {
        if (modelReference == null || modelReference.isBlank()) {
            return;
        }
        for (int state : writableStates(item)) {
            JsonObject predicate = new JsonObject();
            predicate.addProperty("pulling", 1);
            predicate.addProperty("custom_model_data", item.frameStackValue(state));
            if (pull > 0.0f) {
                predicate.addProperty("pull", Math.min(1.0f, pull));
            }
            JsonObject override = new JsonObject();
            override.add("predicate", predicate);
            override.addProperty("model", modelReference);
            overrides.add(override);
        }
    }

    /**
     * Every custom-model-data state the runtime can put on a framed stack:
     * the resting state (0), each animation state (1..N), and the loaded
     * state (N+1 when present) — the contiguous set the state space spans.
     */
    private static List<Integer> writableStates(ItemModelAsset item) {
        int max = 0;
        for (ItemModelAsset.Frame frame : item.frames()) {
            max = Math.max(max, Math.round(frame.threshold()));
        }
        List<Integer> states = new ArrayList<>();
        for (int state = 0; state <= max; state++) {
            states.add(state);
        }
        return states;
    }

    /** Materials whose client draw state exposes the {@code pulling}/{@code pull} predicates. */
    static boolean isUsableCarrier(Material material) {
        return material == Material.BOW || material == Material.CROSSBOW;
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
