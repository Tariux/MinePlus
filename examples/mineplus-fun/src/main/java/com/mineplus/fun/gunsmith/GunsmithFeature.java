package com.mineplus.fun.gunsmith;

import com.mineplus.fun.ModuleFeature;
import java.io.File;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Phase 2 <b>item/texture/animation axis</b> field test: a pre-authored
 * native resource pack tree registered through the raw-asset route.
 *
 * <p>The tree (shipped verbatim under {@code defaults/pack/gun/}) is a
 * <b>deliberate vanilla overlay</b> under the {@code minecraft} namespace —
 * the asset's own design: for pack players the vanilla bow becomes a pistol
 * and the crossbow a rifle, with the draw/charge frame animation authored
 * entirely in the pack's {@code items/*.json} definitions
 * ({@code condition(using_item)} + {@code range_dispatch(use_duration} /
 * {@code crossbow/pull} + {@code charge_type)} predicates) — the client
 * animates with <b>zero plugin code</b> while the player uses the item.
 * {@code sounds.json} redirects {@code entity.arrow.shoot} to the gun
 * sound. Registering under a foreign namespace is exactly what the
 * raw-asset route exists for, and this feature does it on purpose.
 *
 * <p>Server/client coverage: {@code items/*.json} item definitions only work
 * on clients <b>1.21.4+</b>. The tree additionally ships vanilla-replica
 * {@code models/item/bow.json} + {@code models/item/crossbow.json} with
 * {@code custom_model_data} predicate overrides (value
 * {@link GunsmithKeys#LEGACY_CUSTOM_MODEL_DATA}), so legacy clients
 * (1.21–1.21.3) also get the pistol/rifle models and draw frames — but only
 * for items stamped with that value ({@code /gunsmith give} does it); unset
 * vanilla items stay perfectly vanilla on every version. Sound overrides
 * apply on all client versions. Players must also have the generated pack
 * applied (player pack state {@code APPLIED}) to see any of it.</p>
 *
 * <p>No multiblock, hook, GUI, or listeners: {@code /gunsmith give|status}
 * hands out the test rig and reports subsystem state. With
 * {@code PACK.ENABLED: false} the registrations become no-ops and the game
 * stays byte-identical to vanilla behavior.
 */
public final class GunsmithFeature extends ModuleFeature {

    public GunsmithFeature(JavaPlugin plugin, com.mineplus.infrastructure.PluginContext context) {
        super(plugin, context);
    }

    @Override
    public String id() {
        return "gunsmith";
    }

    @Override
    protected void onEnable() {
        var support = context.moduleSupport();
        var pack = context.packApi();
        File coreDataFolder = context.plugin().getDataFolder();

        int registered = 0;
        for (String rel : GunsmithKeys.MANIFEST) {
            String resource = GunsmithKeys.RESOURCE_ROOT + rel;
            String target = GunsmithKeys.INSTALL_ROOT + rel;
            // Stage the file in the Core's data folder first (verbatim, overwrite =
            // true so module updates ship fixed assets), then register the installed
            // file — RawAsset requires the file to exist and copies its bytes as-is.
            if (!support.installDefault(plugin, resource, target, true)) {
                plugin.getLogger().warning("[Gunsmith] Could not install pack asset " + rel + "; skipping.");
                continue;
            }
            File installed = new File(coreDataFolder, target);
            if (!installed.isFile()) {
                plugin.getLogger().warning("[Gunsmith] Installed pack asset missing on disk: " + rel + "; skipping.");
                continue;
            }
            // Deliberate vanilla overlay: the tree keeps its native minecraft
            // namespace, so entries land at assets/minecraft/<rel> in the artifact.
            pack.registerRawAsset(GunsmithKeys.NAMESPACE, rel, installed);
            registered++;
        }
        if (pack.isAvailable()) {
            plugin.getLogger().info("[Gunsmith] Registered " + registered + "/" + GunsmithKeys.MANIFEST.size()
                    + " vanilla-overlay pack assets under namespace '" + GunsmithKeys.NAMESPACE + "'.");
        } else {
            plugin.getLogger().info("[Gunsmith] Pack subsystem disabled (PACK.ENABLED: false) — "
                    + registered + "/" + GunsmithKeys.MANIFEST.size()
                    + " overlay assets staged under '" + GunsmithKeys.INSTALL_ROOT
                    + "' but not compiled; enable the subsystem to ship them.");
        }
    }

    @Override
    protected com.mineplus.infrastructure.command.SubCommand command() {
        return new GunsmithSubCommand(context);
    }
}
