package com.mineplus.pack.compile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineplus.pack.asset.ItemModelAsset;
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

    /** Modern item model definition JSON: {@code {"model":{"type":"minecraft:model","model":"ns:item/id"}}}. */
    public static String modernDefinitionJson(ItemModelAsset item) {
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", item.modelReference());

        JsonObject root = new JsonObject();
        root.add("model", model);
        return GSON.toJson(root);
    }

    /**
     * Legacy host files: backing material -> regenerated vanilla item model
     * with sorted predicate overrides for every item on that material.
     * Deterministic: hosts keyed by material id, overrides sorted by predicate
     * value.
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

            JsonArray overrides = new JsonArray();
            for (ItemModelAsset item : sorted) {
                JsonObject predicate = new JsonObject();
                predicate.addProperty("custom_model_data", item.customModelData());
                JsonObject override = new JsonObject();
                override.add("predicate", predicate);
                override.addProperty("model", item.modelReference());
                overrides.add(override);
            }
            root.add("overrides", overrides);

            files.put("assets/minecraft/models/item/" + materialKey + ".json",
                    GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        }
        return files;
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
