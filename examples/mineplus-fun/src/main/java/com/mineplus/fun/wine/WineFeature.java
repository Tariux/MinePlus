package com.mineplus.fun.wine;

import com.mineplus.fun.ModuleFeature;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Wires the Strad Wine Bottle showcase into the Mineplus Core engine — the
 * reference demonstration of <b>texel surface baking</b>.
 *
 * <p>The bottle's {@code strad_wine.png} texture is a hand-drawn 16x16 sprite
 * (dark blue glass, red wine label). Because the PNG ships next to
 * {@code strad-wine.bbmodel} in the Core's {@code models/} folder, the Core's
 * load-time baker decomposes every face into texels, quantizes each texel to the
 * nearest visually-flat vanilla block, and emits one thin plate per merged
 * same-color run — the sprite is reconstructed pixel-by-pixel out of vanilla
 * concretes/terracottas with zero resource pack.
 *
 * <p>The shipped model was pruned to its four <i>visible</i> cubes (neck, body,
 * label band, cork): the source file contained two cubes fully nested inside the
 * label band and a zero-depth decal embedded in the neck — redundant in an opaque
 * renderer (the band's base display occludes them) and a source of layered
 * artifacts. The full bake is ~338 merged plates across 23 faces.
 *
 * <p>{@code strad-wine.meta.json} opts the model in explicitly
 * ({@code "texelMode": "AUTO"}) and raises the per-instance plate budget to 384 —
 * above the global default of 150, exactly the "decorative model wants more
 * plates" case the per-model budget override exists for.
 *
 * <p>No hooks, GUI, or listeners: this feature exists to install the resources
 * (the JSON multiblock definition registers the {@code wine_bottle} type during
 * the module's coordinated reload) and expose {@code /wine place|remove|status}.
 */
public final class WineFeature extends ModuleFeature {

    public WineFeature(JavaPlugin plugin, com.mineplus.infrastructure.PluginContext context) {
        super(plugin, context);
    }

    @Override
    public String id() {
        return "wine";
    }

    @Override
    protected void onEnable() {
        var support = context.moduleSupport();
        // Model, texture PNG, and texel meta ship as one unit: the PNG must sit
        // next to the bbmodel under its texture name (strad_wine, derived from
        // the bbmodel's relative_path) for the baker to pick it up. Overwrite =
        // true: a module update must be able to fix rendering.
        support.installDefault(plugin, "defaults/models/strad-wine.bbmodel", "models/strad-wine.bbmodel", true);
        support.installDefault(plugin, "defaults/models/strad_wine.png", "models/strad_wine.png", true);
        support.installDefault(plugin, "defaults/models/strad-wine.meta.json", "models/strad-wine.meta.json", true);
        support.installDefault(plugin, "defaults/multiblocks/wine.json", "multiblocks/wine.json", false);
    }

    @Override
    protected com.mineplus.infrastructure.command.SubCommand command() {
        return new WineSubCommand(context);
    }
}
