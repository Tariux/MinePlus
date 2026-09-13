package com.mineplus.pack.compile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mineplus.pack.asset.PackBlockAsset;
import com.mineplus.pack.block.PackBlockCarrier;
import com.mineplus.util.DebugLogger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Emits the carrier blockstate <em>host files</em> for registered pack blocks:
 * {@code assets/minecraft/blockstates/<carrier>.json}.
 *
 * <p>Vanilla blockstates cannot dispatch on entity data the way the modern
 * {@code item_model} component does, so a pack block's model is delivered by
 * redirecting one allocated state of a carrier block that renders with the
 * client's {@code MODEL} render type. The host file starts from the carrier's
 * complete vanilla state table (every unallocated state keeps rendering exactly
 * what vanilla renders) and overrides only the states the pack allocated.</p>
 *
 * <p>Deterministic: state keys are emitted in sorted order, carriers in enum
 * order, so identical registrations always produce byte-identical files. This
 * is the block peer of {@code ItemModelWriter}'s legacy host files.</p>
 */
public final class BlockStateWriter {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private BlockStateWriter() {
    }

    /**
     * One host file per carrier used by at least one registered pack block.
     *
     * @return {@code zip entry path -> bytes}, sorted by carrier.
     */
    public static Map<String, byte[]> hostFiles(List<PackBlockAsset> blocks) {
        Map<PackBlockCarrier, List<PackBlockAsset>> byCarrier = new EnumMap<>(PackBlockCarrier.class);
        for (PackBlockAsset block : blocks) {
            byCarrier.computeIfAbsent(block.carrier(), carrier -> new ArrayList<>()).add(block);
        }

        Map<String, byte[]> files = new TreeMap<>();
        for (Map.Entry<PackBlockCarrier, List<PackBlockAsset>> carrierEntry : byCarrier.entrySet()) {
            PackBlockCarrier carrier = carrierEntry.getKey();
            // Sorted state keys keep the file byte-identical across runs.
            TreeMap<String, String> stateToModel = new TreeMap<>(carrier.vanillaVariants());
            if (stateToModel.isEmpty()) {
                // Carrier unavailable (state enumeration failed): never emit an
                // empty host file, which would blank every vanilla note block.
                DebugLogger.warning("[PackCompiler] Carrier " + carrier.materialId()
                        + " has no enumerated states; skipping its blockstate host file.");
                continue;
            }
            for (PackBlockAsset block : carrierEntry.getValue()) {
                String previous = stateToModel.put(block.stateString(), block.modelReference());
                if (previous != null && !previous.equals(block.modelReference())) {
                    // Allocation prevents this; surface an explicit state clash
                    // loudly instead of silently dropping one block's model.
                    DebugLogger.warning("[PackCompiler] Carrier state clash on " + carrier.materialId()
                            + "[" + block.stateString() + "]: '" + previous + "' replaced by '"
                            + block.modelReference() + "'.");
                }
            }

            JsonObject variants = new JsonObject();
            for (Map.Entry<String, String> state : stateToModel.entrySet()) {
                JsonObject model = new JsonObject();
                model.addProperty("model", state.getValue());
                variants.add(state.getKey(), model);
            }
            JsonObject root = new JsonObject();
            root.add("variants", variants);
            files.put("assets/minecraft/blockstates/" + carrier.materialKey() + ".json",
                    GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        }
        return files;
    }
}
