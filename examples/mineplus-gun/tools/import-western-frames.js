#!/usr/bin/env node
/*
 * Imports the author's Western gunpacks (vanilla resource-pack item models)
 * into MineplusGun frame resources.
 *
 * Source packs (local authoring material, never committed):
 *   <gunpacks>/western_1   western pistol + rifle
 *   <gunpacks>/western_2   lever rifle + coach shotgun
 *   <gunpacks>/western_3   musketmod flintlocks + blunderbuss
 *
 * Usage:
 *   node tools/import-western-frames.js [gunpacks-root]
 *
 * What is imported, per weapon, with the frame set detected from the file
 * names in the source folder (no hand-maintained frame list to drift):
 *   idle    <- <stem>_idle       (or <stem> for the musketmod models)
 *   reloadN <- <stem>_loading_N  (every loading frame, in numeric order)
 *   loaded  <- <stem>_primed     (or <stem>_loaded)
 *   fire    <- loaded with one authored recoil kick on the hand transforms
 *
 * A pack whose loading sequence grows or shrinks is picked up by re-running
 * the import; frames that no longer exist in the source are removed.
 *
 * The `display` block is copied verbatim from the source. Hand-smoothing it
 * was how the previous import produced poses the pack never had (a rifle that
 * rotated 48 degrees in third person instead of 20, a left hand that mirrored
 * the right). The pack is the authority on how its own gun is held; the only
 * pose this tool authors is `fire`, because the packs ship no shooting frame.
 *
 * Cleanup: Blockbench decoration (`format_version`, `credit`, `texture_size`,
 * `groups`) and mod-item `overrides` are stripped; texture references are
 * repointed at this module's own per-weapon texture file; dangling `#missing`
 * faces fall back to the model's first texture.
 */
'use strict';

const fs = require('fs');
const path = require('path');

const moduleRoot = path.resolve(__dirname, '..');
const outRoot = path.join(moduleRoot, 'src', 'main', 'resources', 'defaults', 'frames');
const textureOutDir = path.join(outRoot, 'textures');

const gunpacks = process.argv[2] || 'C:/Users/Tariux/Desktop/gunpacks';
const pack1 = path.join(gunpacks, 'western_1', 'assets', 'minecraft');
const pack2 = path.join(gunpacks, 'western_2', 'assets', 'minecraft');
const pack3 = path.join(gunpacks, 'western_3', 'assets', 'musketmod');

const weapons = [
    {
        id: 'western_pistol', texture: 'western_pistol.png', stem: 'pistol',
        base: path.join(pack1, 'models', 'item', 'pistol'),
        tex: path.join(pack1, 'textures', 'item', 'pistol.png')
    },
    {
        id: 'western_rifle', texture: 'western_rifle.png', stem: 'rifle',
        base: path.join(pack1, 'models', 'item', 'rifle'),
        tex: path.join(pack1, 'textures', 'item', 'rifle.png')
    },
    {
        id: 'lever_rifle', texture: 'lever_rifle.png', stem: 'rifle',
        base: path.join(pack2, 'models', 'item', 'rifle'),
        tex: path.join(pack2, 'textures', 'item', 'rifle.png')
    },
    {
        id: 'coach_shotgun', texture: 'coach_shotgun.png', stem: 'shotgun',
        base: path.join(pack2, 'models', 'item', 'shotgun'),
        tex: path.join(pack2, 'textures', 'item', 'shotgun.png')
    },
    {
        id: 'flintlock_pistol', texture: 'flintlock_pistol.png', stem: 'pistol',
        base: path.join(pack3, 'models', 'item'),
        tex: path.join(pack3, 'textures', 'item', 'pistol.png')
    },
    {
        id: 'flintlock_musket', texture: 'flintlock_musket.png', stem: 'musket',
        base: path.join(pack3, 'models', 'item'),
        tex: path.join(pack3, 'textures', 'item', 'musket.png')
    },
    {
        id: 'scope_musket', texture: 'scope_musket.png', stem: 'musket_with_scope',
        base: path.join(pack3, 'models', 'item'),
        tex: path.join(pack3, 'textures', 'item', 'musket_with_scope.png')
    },
    {
        id: 'bayonet_musket', texture: 'bayonet_musket.png', stem: 'musket_with_bayonet',
        base: path.join(pack3, 'models', 'item'),
        tex: path.join(pack3, 'textures', 'item', 'musket_with_bayonet.png')
    },
    {
        id: 'blunderbuss', texture: 'blunderbuss.png', stem: 'blunderbuss',
        base: path.join(pack3, 'models', 'item'),
        tex: path.join(pack3, 'textures', 'item', 'blunderbuss.png')
    }
];

/**
 * The source frame set of one weapon, read from the file names in its folder:
 * `<stem>_idle` (else `<stem>`), every `<stem>_loading_N` in numeric order,
 * and `<stem>_primed` (else `<stem>_loaded`). The pack's naming is the only
 * authority — a pack that adds a sixth loading pose gets a sixth reload frame
 * without touching this tool.
 */
function detectFrames(weapon) {
    const present = new Set(fs.readdirSync(weapon.base)
        .filter((name) => name.endsWith('.json'))
        .map((name) => name.slice(0, -'.json'.length)));

    const number = (name) => Number(name.slice(name.lastIndexOf('_') + 1));
    const reload = [...present]
        .filter((name) => name.startsWith(weapon.stem + '_loading_'))
        .sort((a, b) => number(a) - number(b));

    const idle = present.has(weapon.stem + '_idle') ? weapon.stem + '_idle' : weapon.stem;
    const loaded = present.has(weapon.stem + '_primed')
        ? weapon.stem + '_primed'
        : weapon.stem + '_loaded';

    if (!present.has(idle)) {
        throw new Error('No idle model for ' + weapon.id + ': ' + weapon.base + '/' + idle + '.json');
    }
    if (!present.has(loaded)) {
        throw new Error('No loaded model for ' + weapon.id + ': ' + weapon.base + '/' + loaded + '.json');
    }
    return { idle, loaded, reload };
}

/**
 * The one authored pose: the loaded model kicked back and up. The deltas come
 * from the hand-authored western rifle recoil that was visually validated in
 * game (third person lifts 5 degrees and rocks back half a unit; first person
 * lifts 12 and pushes into the shoulder by one). Applying the same kick to
 * every gun keeps four weapons from having four different ideas of recoil.
 */
const RECOIL = {
    thirdperson_righthand: { rotation: [5, 0, 0], translation: [0, -0.5, 0.5], scale: [0, 0, 0] },
    thirdperson_lefthand: { rotation: [5, 0, 0], translation: [0, -0.5, 0.5], scale: [0, 0, 0] },
    firstperson_righthand: { rotation: [12, 0, 0], translation: [0, -1, 1], scale: [0, 0, 0] },
    firstperson_lefthand: { rotation: [12, 0, 0], translation: [0, -1, 1], scale: [0, 0, 0] }
};

const DROP_KEYS = ['format_version', 'credit', 'texture_size', 'groups', 'overrides'];

function readModel(file) {
    if (!fs.existsSync(file)) {
        throw new Error('Missing source model: ' + file);
    }
    return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function clean(model, textureStem) {
    const out = {};
    for (const [key, value] of Object.entries(model)) {
        if (!DROP_KEYS.includes(key)) {
            out[key] = value;
        }
    }
    const textureKeys = Object.keys(model.textures || {});
    out.textures = {};
    for (const key of textureKeys) {
        out.textures[key] = 'mineplusgun:item/' + textureStem;
    }
    if (Array.isArray(out.elements)) {
        for (const element of out.elements) {
            for (const face of Object.values(element.faces || {})) {
                if (!face || face.texture == null) {
                    continue;
                }
                if (face.texture === '#missing' || !textureKeys.includes(face.texture.slice(1))) {
                    face.texture = '#' + textureKeys[0];
                }
            }
        }
    }
    return out;
}

function recoil(display) {
    const kicked = JSON.parse(JSON.stringify(display || {}));
    for (const [key, delta] of Object.entries(RECOIL)) {
        const pose = kicked[key];
        if (!pose) {
            continue;
        }
        if (pose.rotation && delta.rotation) {
            pose.rotation = pose.rotation.map((value, index) => round(value + delta.rotation[index]));
        }
        if (pose.translation && delta.translation) {
            pose.translation = pose.translation.map((value, index) => round(value + delta.translation[index]));
        }
    }
    return kicked;
}

function round(value) {
    return Math.round(value * 1e4) / 1e4;
}

function writeModel(dir, stem, model) {
    const target = path.join(dir, stem + '.json');
    fs.writeFileSync(target, JSON.stringify(model, null, 2) + '\n', 'utf8');
}

let imported = 0;
for (const weapon of weapons) {
    const frames = detectFrames(weapon);
    const weaponDir = path.join(outRoot, weapon.id);
    fs.mkdirSync(weaponDir, { recursive: true });
    fs.copyFileSync(weapon.tex, path.join(textureOutDir, weapon.texture));

    const textureStem = weapon.texture.replace(/\.png$/, '');
    const idle = clean(readModel(path.join(weapon.base, frames.idle + '.json')), textureStem);
    writeModel(weaponDir, 'idle', idle);
    imported++;

    frames.reload.forEach((sourceStem, index) => {
        const frame = clean(readModel(path.join(weapon.base, sourceStem + '.json')), textureStem);
        writeModel(weaponDir, 'reload' + index, frame);
        imported++;
    });

    const loaded = clean(readModel(path.join(weapon.base, frames.loaded + '.json')), textureStem);
    writeModel(weaponDir, 'loaded', loaded);
    imported++;

    const fire = JSON.parse(JSON.stringify(loaded));
    fire.display = recoil(loaded.display);
    writeModel(weaponDir, 'fire', fire);
    imported++;

    const stale = fs.readdirSync(weaponDir)
        .filter((name) => name.endsWith('.json'))
        .map((name) => name.replace(/\.json$/, ''))
        .filter((stem) => !['idle', 'loaded', 'fire', ...frames.reload.map((_, i) => 'reload' + i)].includes(stem));
    for (const stem of stale) {
        fs.unlinkSync(path.join(weaponDir, stem + '.json'));
        console.log('  removed stale frame ' + weapon.id + '/' + stem);
    }

    console.log('[' + weapon.id + '] ' + frames.reload.length + ' reload frame(s), texture ' + weapon.texture);
}
console.log('Imported ' + imported + ' frame model(s) into ' + outRoot);
