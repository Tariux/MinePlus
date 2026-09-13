package com.mineplus.pack.asset;

import com.mineplus.pack.block.PackBlockCarrier;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.block.data.BlockData;

/**
 * The carrier-state binding of one registered pack block: which vanilla
 * blockstate renders the block's generated model.
 *
 * <p>This asset owns no pack entry. It is a logical registration (its
 * {@link #zipEntryPath()} only keys the registry and the artifact graph hash);
 * {@code PackCompiler} aggregates every binding per carrier into the generated
 * {@code assets/minecraft/blockstates/<carrier>.json} host file, which
 * reproduces every vanilla state and redirects only the allocated ones.</p>
 *
 * <p>Geometry lives in a separate {@link BlockModelAsset}; textures register
 * through the ordinary texture pipeline. This asset is the dispatch, nothing
 * else — the same separation the item axis has between its model asset and its
 * item definition.</p>
 */
public final class PackBlockAsset extends PackAsset {

    private final String modelKey;
    private final PackBlockCarrier carrier;
    private final int slot;

    /**
     * @param namespace block namespace (the {@code <ns>} of {@code <ns>:<id>})
     * @param id        block id
     * @param owner     registering module name
     * @param modelKey  key of the registered virtual model supplying geometry
     * @param carrier   the carrier pool the slot belongs to
     * @param slot      allocated state slot inside the carrier
     */
    public PackBlockAsset(
            String namespace,
            String id,
            String owner,
            String modelKey,
            PackBlockCarrier carrier,
            int slot
    ) {
        super(namespace, "block/" + requireToken(id), owner);
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey").trim().toLowerCase(Locale.ROOT);
        this.carrier = Objects.requireNonNull(carrier, "carrier");
        if (slot < 0 || slot >= carrier.stateCount()) {
            throw new IllegalArgumentException("Pack block slot out of range for " + carrier + ": " + slot);
        }
        this.slot = slot;
    }

    /** The block id segment ({@code <ns>:<this>}). */
    public String blockId() {
        return path().substring("block/".length());
    }

    public String modelKey() {
        return modelKey;
    }

    public PackBlockCarrier carrier() {
        return carrier;
    }

    public int slot() {
        return slot;
    }

    /** Allocated carrier state, e.g. {@code note=3,instrument=zombie,powered=false}. */
    public String stateString() {
        return carrier.stateString(slot);
    }

    /** The generated geometry model this carrier state renders, e.g. {@code fun:block/alchemy_table}. */
    public String modelReference() {
        return namespace() + ":" + path();
    }

    /**
     * The display block state. Bukkit world mutation API — call on the main
     * thread only (the renderer), never from the async compiler.
     */
    public BlockData blockData() {
        // createBlockData requires the bracketed state form; the bare
        // "key=value,..." string is rejected with the material prefixed.
        return carrier.material().createBlockData("[" + stateString() + "]");
    }

    @Override
    public String zipEntryPath() {
        // Logical registration key, not a written pack entry: the compiler
        // aggregates bindings into the carrier host file. Kept out of assets/
        // so it can never be mistaken for a real entry.
        return "blockstate/" + namespace() + "/" + blockId();
    }

    @Override
    public byte[] serialize() {
        return hashSource();
    }

    @Override
    protected byte[] hashSource() {
        return (modelKey + "|" + carrier.name() + "|" + carrier.materialId() + "|" + slot)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String requireToken(String id) {
        String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || !normalized.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid pack block id: '" + id + "'");
        }
        return normalized;
    }
}
