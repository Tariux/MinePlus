package com.mineplus.gun.feature;

import com.mineplus.gun.GunServices;
import com.mineplus.gun.ModuleFeature;
import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.config.WeaponConfigLoader;
import com.mineplus.gun.weapon.WeaponDefinition;
import com.mineplus.gun.weapon.WeaponItemFactory;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.pack.item.PackItemDefinition;
import java.io.File;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns presentation: installs weapon models/textures/meta and additive sounds
 * into the Core's data folder, then registers every weapon as a Core pack item
 * (additive {@code item_model}; legacy {@code CustomModelData} fallback).
 * Nothing vanilla is ever overridden.
 */
public final class WeaponFeature extends ModuleFeature {

    private static final List<String> SOUND_FILES = List.of(
            "shot1.ogg", "shot2.ogg", "shot3.ogg", "shot4.ogg",
            "reload_start.ogg", "reload_end.ogg", "empty.ogg");
    private static final String SOUND_RESOURCE_ROOT = "defaults/sounds/mineplusgun/";
    private static final String SOUND_INSTALL_ROOT = "pack/mineplusgun/sounds/";

    private final GunServices services;

    public WeaponFeature(JavaPlugin plugin, PluginContext context, GunServices services) {
        super(plugin, context);
        this.services = services;
    }

    @Override
    public String id() {
        return "weapons";
    }

    @Override
    protected void onEnable() {
        // The module's own config lives in plugins/MineplusGun/config.yml.
        plugin.saveDefaultConfig();
        installModels();
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
        registerItems();
    }

    private void installModels() {
        int installed = 0;
        for (WeaponDefinition weapon : services.config().weapons()) {
            String stem = stem(weapon.modelKey());
            if (stem == null) {
                continue;
            }
            String resourceBase = "defaults/models/gun/" + stem;
            String targetBase = "models/gun/" + stem;
            boolean model = installIfPresent(resourceBase + ".bbmodel", targetBase + ".bbmodel");
            installIfPresent(resourceBase + ".png", targetBase + ".png");
            installIfPresent(resourceBase + ".meta.json", targetBase + ".meta.json");
            if (model) {
                installed++;
            }
        }
        plugin.getLogger().info("[MineplusGun] Installed " + installed + " weapon model(s) into the Core data folder.");
    }

    private boolean installIfPresent(String resource, String target) {
        if (plugin.getResource(resource) == null) {
            return false;
        }
        return context.moduleSupport().installDefault(plugin, resource, target, true);
    }

    private void installSounds() {
        File dataFolder = context.plugin().getDataFolder();
        for (String sound : SOUND_FILES) {
            if (context.moduleSupport().installDefault(plugin,
                    SOUND_RESOURCE_ROOT + sound, SOUND_INSTALL_ROOT + sound, true)) {
                File installed = new File(dataFolder, SOUND_INSTALL_ROOT + sound);
                if (installed.isFile()) {
                    context.packApi().registerRawAsset(WeaponItemFactory.NAMESPACE, "sounds/" + sound, installed);
                }
            }
        }
        if (context.moduleSupport().installDefault(plugin,
                "defaults/sounds/sounds.json", "pack/mineplusgun/sounds.json", true)) {
            File installed = new File(dataFolder, "pack/mineplusgun/sounds.json");
            if (installed.isFile()) {
                context.packApi().registerRawAsset(WeaponItemFactory.NAMESPACE, "sounds.json", installed);
            }
        }
    }

    /** Registers every configured weapon as a Core pack item. */
    public void registerItems() {
        int registered = 0;
        for (WeaponDefinition weapon : services.config().weapons()) {
            PackItemDefinition definition = PackItemDefinition.builder(
                            WeaponItemFactory.NAMESPACE, weapon.id(), weapon.backingMaterial(), weapon.modelKey())
                    .displayName(weapon.displayName())
                    .category(weapon.category())
                    .descriptionLines(List.of())
                    .build();
            context.packApi().registerItem(definition);
            registered++;
        }
        plugin.getLogger().info("[MineplusGun] Registered " + registered + " weapon item(s).");
    }

    private static String stem(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            return null;
        }
        String normalized = modelKey.replace('\\', '/');
        if (!normalized.startsWith("gun/")) {
            return null;
        }
        return normalized.substring("gun/".length());
    }
}
