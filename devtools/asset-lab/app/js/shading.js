// Mineplus client-lighting model — the numbers the game actually renders with.
//
// Everything here is traceable, not tuned to look plausible:
//   - per-face multipliers: TexelPalette.SHADE_FACTORS
//     (src/main/java/com/mineplus/infrastructure/virtual/texel/TexelPalette.java:202-203,
//      UP 1.0, NORTH/SOUTH 0.8, EAST/WEST 0.6, DOWN 0.5) applied to the LINEAR
//      sample, because that is what the client does before it gamma-encodes.
//   - brightness override: VirtualBlockManager.BRIGHTNESS_BY_LEVEL (line 590-596)
//     is `new Display.Brightness(level, 15)` — block light = emission, SKY PINNED
//     TO 15 — and it is only applied when the effective emission is > 0
//     (spawnDisplayEntity, lines 609-612), so a non-emissive model inherits the
//     world's natural light and can go dark at night or in a cave.
//   - shading OFF for pack-emitted models: PackModelEmitter (lines 308, 343) and
//     ModelJsonWriter (lines 121, 187) write `ambientocclusion: false` and element
//     `shade: false` into every model they ship, so mob rigs, pack blocks and
//     baked item frames render FLAT in game — no face multipliers at all.
//   - light response: each level below the top is 80% as bright as the one above
//     it (minecraft.wiki/w/Light), the block-light axis tops out at 14, and the
//     internal sky level is 15 at clear noon, 12 in rain, 10 in a thunderstorm
//     and 4 at midnight. Full darkness keeps ~5% brightness.
//
// The one thing this file cannot be exact about is the client's lightmap *tint*,
// which the vanilla shader generates per dimension and weather at runtime; it is
// therefore off by default (the lightmap is monochromatic at neutral settings)
// and exposed as an option rather than presented as ground truth.

import * as THREE from 'three';

/** Vanilla directional diffuse multipliers, per model-space face name. */
export const FACE_SHADE = {
    up: 1.0,
    down: 0.5,
    north: 0.8,
    south: 0.8,
    east: 0.6,
    west: 0.6,
};

/** BoxGeometry group order: +x, -x, +y, -y, +z, -z. */
export const BOX_FACE_ORDER = ['east', 'west', 'up', 'down', 'south', 'north'];

/** Block light tops out at level 14 (wiki: block light "effectively starts at 14"). */
export const SKY_TOP = 15;
export const BLOCK_TOP = 14;

/** The client keeps a floor under complete darkness instead of rendering pure black. */
export const DARKNESS_FLOOR = 0.05;

/** Sky light levels the game uses for the same moment in time, for the presets. */
export const LIGHT_PRESETS = {
    day: { label: 'Day · clear noon (sky 15)', sky: 15, block: 0 },
    rain: { label: 'Day · rain (sky 12)', sky: 12, block: 0 },
    dusk: { label: 'Dusk (sky 10)', sky: 10, block: 0 },
    night: { label: 'Night · moon (sky 4)', sky: 4, block: 0 },
    newmoon: { label: 'Night · no moon (sky 0)', sky: 0, block: 0 },
    torch: { label: 'Cave · torch (block 14)', sky: 0, block: 14 },
    glow: { label: 'Cave · glowstone (block 15)', sky: 0, block: 15 },
    dark: { label: 'Pitch black (floor only)', sky: 0, block: 0 },
};

/**
 * One light level as a LINEAR brightness multiplier: 1.0 at the top of its axis,
 * 80% as bright for every level below it.
 */
export function levelBrightness(level, top) {
    const clamped = Math.max(0, Math.min(top, Math.round(level)));
    return Math.pow(0.8, top - clamped);
}

/**
 * The light a display actually renders with.
 *
 * A `Display.Brightness` override is absolute, not additive: it replaces the
 * world light at the display's position (which is why the engine pins sky to 15
 * and an emissive model does not darken at night).
 *
 * @param display the daemon's emitted display (`brightness` may be null)
 * @param world   {sky, block} — the sampled world light
 */
export function displayLight(display, world) {
    const override = display?.brightness;
    if (override) {
        return { sky: override.sky, block: override.block, emissive: true, overridden: true };
    }
    let sky = world.sky;
    let block = world.block;
    // PACK.LIGHTING policy, as com.mineplus.pack.PackLighting applies it:
    // NATURAL never overrides, AUTO overrides only genuinely emissive models,
    // EMISSIVE/FULLBRIGHT override everything with the model's own maximum.
    switch (world.policy) {
        case 'NATURAL':
            break;
        case 'EMISSIVE':
        case 'FULLBRIGHT': {
            const level = Math.max(0, Math.min(15, world.policyMaxEmission ?? display?.lightEmission ?? 15));
            sky = 15;
            block = level;
            break;
        }
        case 'AUTO':
        default: {
            const emission = Math.max(display?.lightEmission ?? 0, 0);
            if (emission > 0) {
                sky = 15;
                block = Math.min(emission, 15);
            }
            break;
        }
    }
    return { sky, block, emissive: false, overridden: false };
}

/** Combined linear light multiplier for one display: sky + block, clamped, floored. */
export function lightScale(light) {
    const skyPart = levelBrightness(light.sky, SKY_TOP);
    const blockPart = levelBrightness(light.block, BLOCK_TOP);
    const total = Math.min(1.0, skyPart + blockPart);
    return Math.max(DARKNESS_FLOOR, total);
}

/**
 * Night keeps a cool cast and torchlight a warm one in the client's lightmap.
 * Off by default (monochromatic) because the exact tint is generated by the
 * vanilla shader from dimension effects and weather, which this tool cannot read.
 */
export function lightTint(light, enabled) {
    if (!enabled) return [1, 1, 1];
    const skyPart = levelBrightness(light.sky, SKY_TOP);
    const blockPart = levelBrightness(light.block, BLOCK_TOP);
    const total = Math.max(1e-3, skyPart + blockPart);
    const skyWeight = skyPart / total;
    const blockWeight = blockPart / total;
    return [
        clamp01(skyWeight * 0.86 + blockWeight * 1.12 + (1 - skyWeight - blockWeight) * 0.5),
        clamp01(skyWeight * 0.94 + blockWeight * 0.94),
        clamp01(skyWeight * 1.16 + blockWeight * 0.74),
    ];
}

function clamp01(v) {
    return v < 0 ? 0 : v > 1 ? 1 : v;
}

/**
 * Writes the vanilla per-face multipliers into a box geometry's vertex colors.
 * Three.js treats the color attribute as already-linear, which is exactly the
 * space the client multiplies in, so a value of 0.6 here is a 0.6 multiplier on
 * the linearized sample — no lights, no normals, no view dependence.
 */
export function shadeBoxGeometry(geometry, shadeEnabled) {
    const count = geometry.attributes.position.count; // 24 vertices, 4 per face
    const colors = new Float32Array(count * 3);
    for (let face = 0; face < BOX_FACE_ORDER.length; face++) {
        const factor = shadeEnabled ? FACE_SHADE[BOX_FACE_ORDER[face]] : 1.0;
        for (let i = 0; i < 4; i++) {
            const v = face * 4 + i;
            colors[v * 3] = factor;
            colors[v * 3 + 1] = factor;
            colors[v * 3 + 2] = factor;
        }
    }
    geometry.setAttribute('color', new THREE.BufferAttribute(colors, 3));
    return geometry;
}

/**
 * The missing-texture guard the client shows: `missingno` — a magenta/black
 * checker. A flat magenta quad hides the fact that only *some* faces are
 * unresolvable, which is the bug this tool exists to catch.
 */
export function missingTexture() {
    const canvas = document.createElement('canvas');
    canvas.width = 16;
    canvas.height = 16;
    const ctx = canvas.getContext('2d');
    for (let y = 0; y < 8; y++) {
        for (let x = 0; x < 8; x++) {
            ctx.fillStyle = (x + y) % 2 === 0 ? '#ff00ff' : '#000000';
            ctx.fillRect(x * 2, y * 2, 2, 2);
        }
    }
    const texture = new THREE.CanvasTexture(canvas);
    texture.magFilter = THREE.NearestFilter;
    texture.minFilter = THREE.NearestFilter;
    texture.colorSpace = THREE.SRGBColorSpace;
    texture.needsUpdate = true;
    return texture;
}

/**
 * Material cache keyed by everything that changes a client-side appearance:
 * texture (or flat color), the shading class, and the light the display renders
 * under. Keying on light is what lets an emissive plate sit next to a natural
 * one without every mesh needing its own material.
 */
export class MaterialFactory {
    constructor() {
        this.cache = new Map();
        this.missing = null;
    }

    missingTexture() {
        if (!this.missing) this.missing = missingTexture();
        return this.missing;
    }

    /**
     * @param spec {map, rgb, alphaTest, shade, light, tint, wireframe}
     *   - `map` a THREE.Texture (sRGB) or null for a flat color
     *   - `rgb` the base color as 0xRRGGBB sRGB (ignored when map is set)
     *   - `shade` whether the *material* color carries the light multiplier
     *     (per-vertex face shading is handled by shadeBoxGeometry)
     */
    material(spec) {
        const light = spec.light ?? { sky: 15, block: 0 };
        const scale = lightScale(light);
        const tint = lightTint(light, spec.tint);
        const color = spec.map ? null : (spec.rgb ?? 0x808080);
        const key = [
            spec.map ? spec.map.uuid : 'rgb:' + color,
            spec.alphaTest ?? 0.1,
            scale.toFixed(4),
            tint.map(v => v.toFixed(3)).join(','),
            spec.side ?? THREE.FrontSide,
        ].join('|');
        let material = this.cache.get(key);
        if (material) return material;
        material = new THREE.MeshBasicMaterial({
            map: spec.map ?? null,
            color: 0xffffff,
            vertexColors: true,
            transparent: false,
            side: spec.side ?? THREE.FrontSide,
            alphaTest: spec.alphaTest ?? 0.1,
            // The client's own texture filtering for block/item atlases at
            // Nearest mip-map-off settings — smoothing here would hide UV bugs.
            magFilter: THREE.NearestFilter,
        });
        if (!spec.map) {
            // THREE.Color converts the sRGB hex to the linear working space,
            // which is where the multipliers belong.
            const base = new THREE.Color(color);
            material.color.setRGB(
                base.r * scale * tint[0],
                base.g * scale * tint[1],
                base.b * scale * tint[2],
                THREE.LinearSRGBColorSpace,
            );
        } else {
            material.color.setRGB(scale * tint[0], scale * tint[1], scale * tint[2],
                THREE.LinearSRGBColorSpace);
        }
        this.cache.set(key, material);
        return material;
    }

    dispose() {
        for (const material of this.cache.values()) material.dispose();
        this.cache.clear();
    }
}

/** Lighting state the panels share; `apply` mutates every material in place. */
export class Lighting {
    constructor() {
        this.sky = 15;
        this.block = 0;
        this.policy = 'AUTO';
        this.policyMaxEmission = 15;
        this.tint = false;
        // Face multipliers on/off: a dev comparing against the *reference*
        // texture wants them off, a dev checking what the client shows wants
        // them on. The pack path ignores them entirely — its models ship
        // `shade: false`, and that is not a user choice.
        this.faceShade = true;
        this.presets = LIGHT_PRESETS;
        this.listeners = new Set();
    }

    world() {
        return {
            sky: this.sky,
            block: this.block,
            policy: this.policy,
            policyMaxEmission: this.policyMaxEmission,
        };
    }

    set(patch) {
        Object.assign(this, patch);
        for (const listener of this.listeners) listener(this);
    }

    onChange(listener) {
        this.listeners.add(listener);
    }
}
