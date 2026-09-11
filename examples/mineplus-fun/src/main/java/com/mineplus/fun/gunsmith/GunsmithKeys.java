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
 *
 * <p>The tree deliberately does <b>not</b> override any vanilla sound or
 * texture: {@code sounds.json} only registers the additive
 * {@code minecraft:gun.fire} event (the plugin plays it explicitly), and no
 * vanilla {@code .ogg} or projectile texture is shipped — a vanilla bow
 * sounds and looks exactly like a vanilla bow unless the player holds a
 * stamped test-rig gun.</p>
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

    /** The pack-registered sound the plugin plays on every gun shot. */
    public static final String FIRE_SOUND = "minecraft:gun.fire";

    /** PDC key (byte 1) marking a gunsmith test-rig gun (bow or crossbow). */
    public static final String PDC_GUN = "gunsmith-gun";

    /** PDC key (integer) holding the gun's remaining rounds. */
    public static final String PDC_AMMO = "gunsmith-ammo";

    /** Rounds a fresh gun from {@code /gunsmith give} holds. */
    public static final int MAGAZINE = 12;

    /** Vanilla items the overlay affects (status reporting). */
    public static final List<String> AFFECTED_ITEMS = List.of(
            "minecraft:bow -> pistol (draw frames via using_item/use_duration; legacy: pulling/pull + CMD 1)",
            "minecraft:crossbow -> rifle (pull frames via crossbow/pull + charge_type; legacy: charged/pull + CMD 1)",
            "minecraft:gun.fire -> sounds/custom/gun_bow.ogg (additive event, played by the plugin only)"
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
            "textures/item/pistol.png",
            "textures/item/rifle.png"
    );

    private GunsmithKeys() {
    }
}
