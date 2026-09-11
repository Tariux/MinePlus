package com.mineplus.fun.gunsmith;

import java.util.List;

/**
 * Fixed manifest of the gun pack tree shipped under
 * {@code defaults/pack/gun/} — every entry is a path relative to the pack
 * namespace root, installed to {@code pack-src/gun/<rel>} in the Core's
 * data folder and registered verbatim under the {@code minecraft}
 * namespace (a deliberate vanilla overlay: for pack players the vanilla
 * bow/crossbow presentations become a pistol/rifle). Modern clients
 * (1.21.4+) animate the draw frames through the pack's {@code items/*.json}
 * item definitions; legacy clients (1.21–1.21.3) get the same frames through
 * {@code custom_model_data} predicate overrides in the shipped
 * {@code models/item/bow.json} / {@code models/item/crossbow.json} (vanilla
 * replicas plus the gun entries, so unset vanilla items stay vanilla).
 */
public final class GunsmithKeys {

    public static final String NAMESPACE = "minecraft";
    public static final String RESOURCE_ROOT = "defaults/pack/gun/";
    public static final String INSTALL_ROOT = "pack-src/gun/";

    /**
     * Legacy {@code custom_model_data} predicate value selecting the gun
     * models on pre-1.21.4 clients — the value the {@code models/item/bow.json}
     * and {@code models/item/crossbow.json} overrides in the tree match, and
     * {@code /gunsmith give} stamps on the test rig. Never zero (zero would
     * match every vanilla item).
     */
    public static final int LEGACY_CUSTOM_MODEL_DATA = 1;

    /** Vanilla items the overlay affects (status reporting). */
    public static final List<String> AFFECTED_ITEMS = List.of(
            "minecraft:bow -> pistol (draw frames via using_item/use_duration; legacy: pulling/pull + CMD 1)",
            "minecraft:crossbow -> rifle (pull frames via crossbow/pull + charge_type; legacy: charged/pull + CMD 1)",
            "entity.arrow.shoot sound -> sounds/custom/gun_bow.ogg"
    );

    public static final List<String> MANIFEST = List.of(
            "items/bow.json",
            "items/crossbow.json",
            "models/item/bow.json",
            "models/item/crossbow.json",
            "models/item/pistol/pistol_idle.json",
            "models/item/pistol/pistol_loading_0.json",
            "models/item/pistol/pistol_loading_1.json",
            "models/item/pistol/pistol_loading_2.json",
            "models/item/pistol/pistol_loading_3.json",
            "models/item/pistol/pistol_loading_4.json",
            "models/item/pistol/pistol_primed.json",
            "models/item/rifle/rifle_idle.json",
            "models/item/rifle/rifle_loading_0.json",
            "models/item/rifle/rifle_loading_1.json",
            "models/item/rifle/rifle_loading_2.json",
            "models/item/rifle/rifle_loading_3.json",
            "models/item/rifle/rifle_loading_4.json",
            "models/item/rifle/rifle_loading_5.json",
            "models/item/rifle/rifle_primed.json",
            "sounds.json",
            "sounds/custom/gun_bow.ogg",
            "sounds/item/crossbow/loading_end.ogg",
            "sounds/item/crossbow/loading_middle1.ogg",
            "sounds/item/crossbow/loading_middle2.ogg",
            "sounds/item/crossbow/loading_middle3.ogg",
            "sounds/item/crossbow/loading_middle4.ogg",
            "sounds/item/crossbow/loading_start.ogg",
            "sounds/item/crossbow/quick_charge/quick1_1.ogg",
            "sounds/item/crossbow/quick_charge/quick1_2.ogg",
            "sounds/item/crossbow/quick_charge/quick1_3.ogg",
            "sounds/item/crossbow/quick_charge/quick2_1.ogg",
            "sounds/item/crossbow/quick_charge/quick2_2.ogg",
            "sounds/item/crossbow/quick_charge/quick2_3.ogg",
            "sounds/item/crossbow/quick_charge/quick3_1.ogg",
            "sounds/item/crossbow/quick_charge/quick3_2.ogg",
            "sounds/item/crossbow/quick_charge/quick3_3.ogg",
            "sounds/item/crossbow/shoot1.ogg",
            "sounds/item/crossbow/shoot2.ogg",
            "sounds/item/crossbow/shoot3.ogg",
            "sounds/item/crossbow/shoot4.ogg",
            "textures/entity/projectiles/arrow.png",
            "textures/entity/projectiles/spectral_arrow.png",
            "textures/entity/projectiles/tipped_arrow.png",
            "textures/item/pistol.png",
            "textures/item/rifle.png"
    );

    private GunsmithKeys() {
    }
}
