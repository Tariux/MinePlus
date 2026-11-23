#!/usr/bin/env node
// Mineplus Create — Model Harvester.
//
// Converts vanilla-style block-model JSON (devtools/create-harvest/vanilla/,
// original work) into the module's .bbmodel assets with spin/work clips,
// .meta.json texel budgets and 16x16 PNG textures, plus a validation report.
//
// Usage: node devtools/create-harvest/convert.mjs [--out <dir>] [--check]
//   --check  validate the existing output without rewriting it (exit 1 on any problem)
//
// Zero npm dependencies (node:zlib for PNG). Never reads or copies Create
// assets; the shipped models are original work.

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const here = dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const checkOnly = argv.includes('--check');
const outIdx = argv.indexOf('--out');
const manifestPath = join(here, 'manifest.json');

const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
const outDir = resolve(outIdx >= 0 ? argv[outIdx + 1] : join(here, '..', '..', manifest.outputDir));

/** Minimal truecolor 16x16 PNG writer (one solid colour + 4px darker border). */
function png16(rgb) {
    const [r, g, b] = rgb;
    const rows = [];
    for (let y = 0; y < 16; y++) {
        let row = Buffer.from([0]);
        for (let x = 0; x < 16; x++) {
            const edge = x === 0 || y === 0 || x === 15 || y === 15;
            const f = edge ? 0.72 : 1.0;
            row = Buffer.concat([row, Buffer.from([
                Math.round(r * f), Math.round(g * f), Math.round(b * f)])]);
        }
        rows.push(row);
    }
    const raw = Buffer.concat(rows);
    const crcTable = [...Array(256)].map((_, n) => {
        let c = n;
        for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        return c >>> 0;
    });
    const crc = (buf) => {
        let c = 0xffffffff;
        for (const byte of buf) c = crcTable[(c ^ byte) & 0xff] ^ (c >>> 8);
        return (c ^ 0xffffffff) >>> 0;
    };
    const chunk = (type, data) => {
        const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
        const body = Buffer.concat([Buffer.from(type), data]);
        const sum = Buffer.alloc(4); sum.writeUInt32BE(crc(body));
        return Buffer.concat([len, body, sum]);
    };
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(16, 0); ihdr.writeUInt32BE(16, 4);
    ihdr[8] = 8; ihdr[9] = 2; // 8-bit, truecolor
    return Buffer.concat([
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
        chunk('IHDR', ihdr),
        chunk('IDAT', deflateSync(raw)),
        chunk('IEND', Buffer.alloc(0)),
    ]);
}

let uuidCounter = 0;
function uuid() {
    uuidCounter += 1;
    const hex = (n) => n.toString(16).padStart(8, '0');
    const base = `1a2b3c${hex(uuidCounter)}`;
    return `${base.slice(0, 8)}-4c01-8b02-c03d-${base.slice(2)}${hex(uuidCounter + 0x10).slice(0, 12)}`.slice(0, 36);
}

/** Vanilla-style element (from/to, one texture name) → Blockbench element. */
function bbElement(el, textureIndex, uvSize) {
    const [x1, y1, z1] = el.from;
    const [x2, y2, z2] = el.to;
    const w = x2 - x1, h = y2 - y1, d = z2 - z1;
    const face = (u, v, uw, vh) => ({
        uv: [u, v, Math.min(u + uw, uvSize), Math.min(v + vh, uvSize)],
        texture: textureIndex,
    });
    const u = [x1, z1], uw = [w, d], vh = [h, w];
    return {
        name: 'cube',
        box_uv: false,
        render_order: 'default',
        locked: false,
        export: true,
        scope: 0,
        allow_mirror_modeling: true,
        shading_mode: 'standard',
        double_sided: false,
        from: [...el.from],
        to: [...el.to],
        autouv: 1,
        color: 2,
        origin: [(x1 + x2) / 2, (y1 + y2) / 2, (z1 + z2) / 2],
        faces: {
            north: face(u[0], u[1], uw[0], vh[0]),
            east: face(u[1], u[0], uw[1], vh[0]),
            south: face(u[0], u[1], uw[0], vh[0]),
            west: face(u[1], u[0], uw[1], vh[0]),
            up: face(u[0], u[1], uw[0], uw[1]),
            down: face(u[0], u[1], uw[0], uw[1]),
        },
        type: 'cube',
        uuid: uuid(),
    };
}

/** Loop clip rotating the whole group 360° around one axis (Create-style spin). */
function spinClip(groupUuid, axis, length) {
    const deg = { x: ['360', '0', '0'], y: ['0', '360', '0'], z: ['0', '0', '360'] }[axis];
    return {
        uuid: uuid(),
        name: 'spin',
        loop: 'loop',
        override: false,
        length: length,
        snapping: 24,
        selected: true,
        group_name: '',
        scope: 0,
        anim_time_update: '',
        blend_weight: '',
        start_delay: '',
        loop_delay: '',
        animators: {
            [groupUuid]: {
                name: 'group',
                type: 'bone',
                rotation_global: false,
                quaternion_interpolation: false,
                keyframes: [
                    {
                        channel: 'rotation',
                        data_points: [{ x: deg[0], y: deg[1], z: deg[2] }],
                        uuid: uuid(),
                        time: length,
                        color: -1,
                        interpolation: 'linear',
                    },
                    {
                        channel: 'rotation',
                        data_points: [{ x: '0', y: '0', z: '0' }],
                        uuid: uuid(),
                        time: 0,
                        color: -1,
                        interpolation: 'linear',
                    },
                ],
            },
        },
    };
}

/** Work clip: one in-out push along an axis (press head, piston arm). */
function workClip(groupUuid, channel, axis, amplitude, length) {
    const point = (v) => ({ x: '0', y: '0', z: '0', [axis]: String(v) });
    return {
        uuid: uuid(),
        name: 'work',
        loop: 'loop',
        override: false,
        length,
        snapping: 24,
        selected: false,
        group_name: '',
        scope: 0,
        anim_time_update: '',
        blend_weight: '',
        start_delay: '',
        loop_delay: '',
        animators: {
            [groupUuid]: {
                name: 'group',
                type: 'bone',
                rotation_global: false,
                quaternion_interpolation: false,
                keyframes: [
                    {
                        channel,
                        data_points: [point(0)],
                        uuid: uuid(),
                        time: 0,
                        color: -1,
                        interpolation: 'linear',
                    },
                    {
                        channel,
                        data_points: [point(amplitude)],
                        uuid: uuid(),
                        time: length / 2,
                        color: -1,
                        interpolation: 'linear',
                    },
                    {
                        channel,
                        data_points: [point(0)],
                        uuid: uuid(),
                        time: length,
                        color: -1,
                        interpolation: 'linear',
                    },
                ],
            },
        },
    };
}

const PROBLEMS = [];

function convertOne(entry) {
    const source = JSON.parse(readFileSync(join(here, entry.source), 'utf8'));
    const texNames = Object.keys(source.textures ?? {});
    if (!texNames.length) {
        PROBLEMS.push(`${entry.key}: source has no textures`);
    }
    const texRgb = { steel: [168, 170, 176], dark: [62, 57, 57] };
    const textures = texNames.map((name, index) => {
        const [width, height] = (source.textureSizes?.[`#${name}`]) ?? [16, 16];
        return {
            name: `${entry.key}_${name}.png`,
            id: String(index),
            group: '',
            scope: 0,
            width,
            height,
            uv_width: width,
            uv_height: height,
            particle: false,
            use_as_default: index === 0,
            layers_enabled: false,
            sync_to_project: '',
            file_format: 'png',
            render_mode: 'default',
            render_sides: 'auto',
            wrap_mode: 'clamp',
            pbr_channel: 'color',
            frame_time: 1,
            frame_order_type: 'loop',
            frame_order: '',
            frame_interpolate: false,
            visible: true,
            internal: true,
            saved: false,
            uuid: uuid(),
            relative_path: `./${entry.key}_${name}.png`,
            folder: '',
            namespace: '',
        };
    });

    const uvSize = 16;
    const elements = (source.elements ?? []).map((el) => {
        const texIndex = texNames.indexOf(el.texture);
        if (texIndex < 0) PROBLEMS.push(`${entry.key}: element references unknown texture ${el.texture}`);
        return bbElement(el, texIndex < 0 ? 0 : texIndex, uvSize);
    });

    const groupUuid = uuid();
    const group = {
        name: 'root',
        uuid: groupUuid,
        export: true,
        locked: false,
        scope: 0,
        selected: false,
        _static: { properties: {}, temp_data: {} },
        origin: [0, 8, 0],
        rotation: [0, 0, 0],
        color: 0,
        children: elements.map((e) => e.uuid),
        reset: false,
        shade: true,
        mirror_uv: false,
        visibility: true,
        autouv: 0,
        isOpen: true,
        primary_selected: false,
    };

    const animations = [];
    if (entry.clips?.includes('spin')) {
        animations.push(spinClip(groupUuid, entry.spin?.axis ?? 'y', entry.spin?.length ?? 8));
    }
    if (entry.clips?.includes('work')) {
        const w = entry.work ?? {};
        animations.push(workClip(groupUuid, w.channel ?? 'position', w.axis ?? 'y', w.amplitude ?? -8, w.length ?? 1));
    }

    const bbmodel = {
        meta: { format_version: '5.0', model_format: 'hytale_prop', box_uv: false },
        name: entry.key,
        visible_box: [1, 1, 0],
        variable_placeholders: '',
        variable_placeholder_buttons: [],
        timeline_setups: [],
        unhandled_root_fields: {},
        resolution: { width: uvSize, height: uvSize },
        elements,
        groups: [group],
        outliner: [group.uuid],
        textures,
        animations,
    };

    return { bbmodel, textures: textures.map((t) => ({ name: t.name, rgb: texRgb[t.name.split('_').pop().replace('.png', '')] ?? [150, 150, 150] })) };
}

const report = { generated: [], problems: PROBLEMS };

for (const entry of manifest.models) {
    const { bbmodel, textures } = convertOne(entry);
    const outPath = join(outDir, `${entry.key}.bbmodel`);
    report.generated.push({
        key: entry.key,
        bbmodel: outPath,
        elements: bbmodel.elements.length,
        clips: bbmodel.animations.map((a) => a.name),
    });
    if (checkOnly) {
        if (!existsSync(outPath)) PROBLEMS.push(`${entry.key}: missing ${outPath}`);
        for (const tex of textures) {
            if (!existsSync(join(outDir, tex.name))) PROBLEMS.push(`${entry.key}: missing texture ${tex.name}`);
        }
        continue;
    }
    mkdirSync(outDir, { recursive: true });
    writeFileSync(outPath, JSON.stringify(bbmodel, null, 1));
    for (const tex of textures) {
        writeFileSync(join(outDir, tex.name), png16(tex.rgb));
    }
    writeFileSync(join(outDir, `${entry.key}.meta.json`),
        JSON.stringify(manifest.texelBudgets, null, 1) + '\n');
}

const reportPath = join(here, 'report.json');
report.timestamp = new Date().toISOString();
report.checkOnly = checkOnly;
writeFileSync(reportPath, JSON.stringify(report, null, 1));

if (PROBLEMS.length) {
    console.error(`create-harvest: ${PROBLEMS.length} problem(s):`);
    for (const problem of PROBLEMS) console.error(' - ' + problem);
    process.exit(1);
}
console.log(`create-harvest: ${report.generated.length} model(s) ${checkOnly ? 'verified' : 'written'} to ${outDir} (report: ${reportPath})`);
