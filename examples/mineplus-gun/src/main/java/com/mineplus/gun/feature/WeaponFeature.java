package com.mineplus.gun.feature;

import com.mineplus.gun.GunServices;
import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.config.WeaponConfigLoader;
import com.mineplus.gun.weapon.WeaponDefinition;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.module.ModuleFeature;
import java.io.File;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns presentation: installs weapon models, frame models, textures and
 * additive sounds, then registers every weapon. Content identity/presentation
 * come from the parsed Core {@code ContentItem}; this feature only installs
 * the module's resources and feeds the pack API:
 * <ul>
 *   <li>framed weapons (Western packs) — raw model JSONs + a texture per
 *       weapon, registered at {@code mineplusgun:item/<weapon-id>/<stem>},
 *       the exact references the parsed {@code ItemFrames} carries;</li>
 *   <li>virtual-model weapons (the knife) — {@code .bbmodel} installed into
 *       the Core's model folder with its textures;</li>
 *   <li>the additive sound set — sounds.json plus its {@code .ogg}s.</li>
 * </ul>
 * Nothing vanilla is ever overridden.
 */
public final class WeaponFeature extends ModuleFeature {

    private static final String SOUND_RESOURCE_ROOT = "defaults/sounds/mineplusgun/";
    private static final String SOUND_INSTALL_ROOT = "pack/mineplusgun/sounds/";

    /** Module resources of framed weapons, mirrored into the pack namespace. */
    private static final String FRAME_RESOURCE_ROOT = "defaults/frames/";
    private static final String FRAME_INSTALL_ROOT = "pack/mineplusgun/models/item/";
    private static final String FRAME_TEXTURE_RESOURCE_ROOT = "defaults/frames/textures/";
    private static final String FRAME_TEXTURE_INSTALL_ROOT = "pack/mineplusgun/textures/item/";

    private final GunServices services;

    public WeaponFeature(JavaPlugin plugin, PluginContext context, GunServices services) {
        super(plugin, context);
        this.services = services;
    }

    @Override
    public String id() {
        return "content";
    }

    @Override
    protected void onEnable() {
        // The module's own config lives in plugins/MineplusGun/config.yml.
        plugin.saveDefaultConfig();
        installModels();
        installFrames();
        installSounds();
        registerItems();
    }

    /** Re-reads config.yml and re-registers everything (used by {@code /mpgun reload}). */
    public void reload() {
        plugin.reloadConfig();
        GunConfig config = WeaponConfigLoader.load(plugin.getConfig());
        services.setConfig(config);
        services.registry().reload(config.weapons());
        services.ammoHud().setConfig(config);
        services.particleFx().updateBudget(config.ballistics().maxParticlesPerTick());
        installFrames();
        registerItems();
    }

    /** Installs the virtual-model weapons (knife) into the Core's model folder. */
    private void installModels() {
        int installed = 0;
        for (WeaponDefinition weapon : services.config().weapons()) {
            String modelKey = weapon.modelKey();
            if (modelKey == null) {
                continue;
            }
            String stem = stem(modelKey);
            if (stem == null) {
                continue;
            }
            installed += context.moduleSupport().installAll(plugin, "defaults/models/gun/",
                    "models/gun/" + stem + "/", List.of(stem + ".bbmodel", stem + ".png", stem + ".meta.json"));
        }
        plugin.getLogger().info("[MineplusGun] Installed " + installed + " virtual model file(s).");
    }

    /**
     * Installs every framed weapon's raw model JSONs and per-weapon texture,
     * then registers them as pack assets at
     * {@code mineplusgun:item/<weapon-id>/<stem>} — the references the parsed
     * {@code ItemFrames} spec carries. Re-registration with identical content
     * is a registry no-op, so {@code /mpgun reload} is cheap.
     */
    private void installFrames() {
        int frames = 0;
        int textures = 0;
        for (WeaponDefinition weapon : services.config().weapons()) {
            var model = weapon.model();
            if (model == null || !model.framed()) {
                continue;
            }
            String weaponId = weapon.id();
            List<String> stems = model.frameStems().stream().map(stem -> stem + ".json").toList();
            context.moduleSupport().installAll(plugin,
                    FRAME_RESOURCE_ROOT + weaponId + "/", FRAME_INSTALL_ROOT + weaponId + "/", stems);
            for (String file : stems) {
                File installed = context.moduleSupport()
                        .dataFile(FRAME_INSTALL_ROOT + weaponId + "/" + file);
                if (installed.isFile()) {
                    context.packApi().registerRawAsset(WeaponConfigLoader.NAMESPACE,
                            "models/item/" + weaponId + "/" + file, installed);
                    frames++;
                } else {
                    plugin.getLogger().warning("[MineplusGun] Weapon '" + weaponId
                            + "' is missing its frame model '" + file + "'.");
                }
            }

            String texture = model.texture();
            if (texture != null
                    && context.moduleSupport().installDefault(plugin,
                            FRAME_TEXTURE_RESOURCE_ROOT + texture, FRAME_TEXTURE_INSTALL_ROOT + texture, true)) {
                File installed = context.moduleSupport().dataFile(FRAME_TEXTURE_INSTALL_ROOT + texture);
                if (installed.isFile()) {
                    // The model JSONs reference mineplusgun:item/<weapon-id> —
                    // the pack path is extension-less (the .png is appended by
                    // the compiler), so the configured file name is normalized.
                    String normalized = com.mineplus.pack.asset.TextureAsset.normalizePath(texture);
                    context.packApi().registerTexture(WeaponConfigLoader.NAMESPACE,
                            "item/" + normalized, installed);
                    textures++;
                } else {
                    plugin.getLogger().warning("[MineplusGun] Weapon '" + weaponId
                            + "' is missing its frame texture '" + texture + "'.");
                }
            }
        }
        plugin.getLogger().info("[MineplusGun] Content installed: " + frames + " frame model(s), "
                + textures + " frame texture(s).");
    }

    private void installSounds() {
        for (String sound : List.of("shot1.ogg", "shot2.ogg", "shot3.ogg", "shot4.ogg",
                "heavy1.ogg", "heavy2.ogg", "heavy3.ogg", "reload_start.ogg", "reload_end.ogg", "empty.ogg")) {
            String target = SOUND_INSTALL_ROOT + sound;
            if (context.moduleSupport().installDefault(plugin, SOUND_RESOURCE_ROOT + sound, target, true)) {
                File installed = context.moduleSupport().dataFile(target);
                if (installed.isFile()) {
                    context.packApi().registerRawAsset(WeaponConfigLoader.NAMESPACE, "sounds/" + sound, installed);
                }
            }
        }
        String manifestTarget = "pack/mineplusgun/sounds.json";
        if (context.moduleSupport().installDefault(plugin, "defaults/sounds/sounds.json", manifestTarget, true)) {
            File installed = context.moduleSupport().dataFile(manifestTarget);
            if (installed.isFile()) {
                context.packApi().registerRawAsset(WeaponConfigLoader.NAMESPACE, "sounds.json", installed);
            }
        }
    }

    /**
     * Registers every configured weapon as a Core pack item — the
     * {@code ContentItem} already carries identity, carrier, display and
     * presentation; the pack layer derives the definition.
     */
    public void registerItems() {
        int registered = 0;
        for (WeaponDefinition weapon : services.config().weapons()) {
            context.packApi().registerItem(weapon.base().toPackItem());
            registered++;
        }
        plugin.getLogger().info("[MineplusGun] Registered " + registered + " weapon item(s).");
    }

    private static String stem(String modelKey) {
        String normalized = modelKey.replace('\\', '/');
        return normalized.startsWith("gun/") ? normalized.substring("gun/".length()) : null;
    }
}
