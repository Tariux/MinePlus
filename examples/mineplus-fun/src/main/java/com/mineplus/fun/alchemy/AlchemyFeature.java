package com.mineplus.fun.alchemy;

import com.mineplus.fun.ModuleFeature;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.pack.block.PackBlockCarrier;
import com.mineplus.pack.block.PackBlockDefinition;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Pack-block showcase: <b>the alchemy table</b>, a multiblock placed and
 * collision-owned by the ordinary lifecycle but rendered through the generated
 * resource pack as a single {@code BlockDisplay} carrying an allocated carrier
 * state.
 *
 * <p>What makes this different from the virtual axis: the pack block does not
 * mosaic the model out of vanilla block displays and does not bake texels. It
 * serializes the same imported {@code VirtualModel} into a vanilla block
 * element model in block space, redirects one carrier state
 * ({@code note_block}, whose uniform model and {@code MODEL} render type let a
 * {@code BlockDisplay} draw the override) to that model, and spawns one block
 * display. The multiblock lifecycle still owns placement, barriers, break
 * handling and persistence — the pack subsystem only draws.</p>
 *
 * <p>With the pack subsystem disabled, the level's {@code pack}+{@code block}
 * backend degrades to the virtual engine in one place
 * ({@code MultiBlockLevel.effectiveBackend}), so the table still renders —
 * through the texel/virtual pipeline — exactly like every other Mineplus
 * machine.</p>
 */
public final class AlchemyFeature extends ModuleFeature {

    public AlchemyFeature(JavaPlugin plugin, PluginContext context) {
        super(plugin, context);
    }

    @Override
    public String id() {
        return "alchemy";
    }

    @Override
    protected void onEnable() {
        var support = context.moduleSupport();
        support.installDefault(plugin,
                "defaults/models/alchemy-table.bbmodel", "models/alchemy-table.bbmodel", true);
        support.installDefault(plugin,
                "defaults/models/Alchemy_Texture.png", "models/Alchemy_Texture.png", true);
        support.installDefault(plugin,
                "defaults/multiblocks/alchemy_table.json", "multiblocks/alchemy_table.json", false);

        // Registration is order-insensitive: the model is not loaded yet, so
        // the geometry/texture assets attach during the coordinated
        // reload-driven pack recompile, exactly like pack items.
        context.packApi().registerBlock(PackBlockDefinition.builder(
                        AlchemyKeys.NAMESPACE, AlchemyKeys.BLOCK_ID, AlchemyKeys.MODEL_KEY)
                .geometryModelKey(AlchemyKeys.GEOMETRY_MODEL_KEY)
                .displayName("Alchemy Table")
                .carrier(PackBlockCarrier.NOTE_BLOCK)
                .build());
    }

    @Override
    protected com.mineplus.infrastructure.command.SubCommand command() {
        return new AlchemySubCommand(context);
    }
}
