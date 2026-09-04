package com.mineplus.fun.wine;

import com.mineplus.fun.ModuleFeature;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Wires the vinery wine bottle showcase into the Mineplus Core engine — the
 * reference demonstration of <b>texel surface baking</b> across a lineup of
 * sprites.
 *
 * <p>Every bottle's sprite (e.g. {@code strad_wine.png}) is a hand-drawn 16x16
 * PNG. Because each PNG ships next to its {@code <key>-wine.bbmodel} in the
 * Core's {@code models/} folder, the Core's load-time baker decomposes every
 * face into texels, quantizes each texel to the nearest visually-flat vanilla
 * block, and emits one thin plate per merged same-color run — each sprite is
 * reconstructed pixel-by-pixel out of vanilla concretes/terracottas with zero
 * resource pack.
 *
 * <p>The shipped models were pruned to their <i>visible</i> cubes (neck, body,
 * label band, cork where present): the source files contained cubes fully
 * nested inside the label band and zero-depth decals — redundant in an opaque
 * renderer and a source of layered artifacts. Intentional nesting (band
 * wrapping the body, cork seated in the neck) is handled by the Core's
 * occlusion culling. Geometry stays in vanilla {@code java_block} [0..16]
 * corner space, so the Core's AUTO origin detection anchors the bottles GRID
 * like any vanilla block model; the tall Stal bottle (18 pixels) simply
 * occupies the block above its anchor.
 *
 * <p>Each {@code <key>-wine.meta.json} opts its model in explicitly
 * ({@code "texelMode": "AUTO"}) and raises the per-instance plate budget above
 * the global default of 150 (384, or 768 for the gradient-heavy Stal sprite).
 * Lighting stays natural (no {@code texelBrightness} floor): the bottles read
 * through the palette's dark entries and vanilla's own directional face
 * shading rather than a forced glow.
 *
 * <p>No hooks, GUI, or listeners: this feature installs the resources (the
 * JSON multiblock definitions register the {@code wine_bottle*} types during
 * the module's coordinated reload) and exposes
 * {@code /wine place [variant] | flight | remove | clear | status} — the
 * tasting flight lays all five variants out side by side for bake comparison.
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
        for (WineVariant variant : WineVariant.values()) {
            // Model, texture PNG, and texel meta ship as one unit: the PNG must sit
            // next to the bbmodel under its texture name (<key>_wine, derived from
            // the bbmodel's relative_path) for the baker to pick it up. Overwrite =
            // true: a module update must be able to fix rendering.
            String model = "models/" + variant.key() + "-wine.bbmodel";
            String texture = "models/" + variant.key() + "_wine.png";
            String meta = "models/" + variant.key() + "-wine.meta.json";
            support.installDefault(plugin, "defaults/" + model, model, true);
            support.installDefault(plugin, "defaults/" + texture, texture, true);
            support.installDefault(plugin, "defaults/" + meta, meta, true);
        }
        // The strad showcase type predates the flight and keeps its historical
        // file name so existing servers keep their deployed copy; the flight
        // variants follow the wine-<key>.json convention.
        support.installDefault(plugin, "defaults/multiblocks/wine.json", "multiblocks/wine.json", false);
        support.installDefault(plugin, "defaults/multiblocks/wine-stal.json", "multiblocks/wine-stal.json", false);
        support.installDefault(plugin, "defaults/multiblocks/wine-red.json", "multiblocks/wine-red.json", false);
        support.installDefault(plugin, "defaults/multiblocks/wine-chenet.json", "multiblocks/wine-chenet.json", false);
        support.installDefault(plugin, "defaults/multiblocks/wine-solaris.json", "multiblocks/wine-solaris.json", false);
    }

    @Override
    protected com.mineplus.infrastructure.command.SubCommand command() {
        return new WineSubCommand(context);
    }
}
