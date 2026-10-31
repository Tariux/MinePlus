#!/usr/bin/env node
/*
 * Authors the `longshot` marksman-rifle rig as a single Blockbench 5.0 project:
 * geometry, bone groups, display transforms and named animation clips, plus the
 * 64x64 texture those clips reference.
 *
 *   node examples/mineplus-gun/tools/gen_longshot_rig.js
 *
 * Output is deterministic (seeded ids, fixed key order) so the pack compiler's
 * content hashing never churns between runs.
 *
 * The file is written twice on purpose: group definitions appear both in the
 * top-level `groups[]` array (what Blockbench reads) and inlined into the
 * `outliner[]` tree (what BbModelImporter reads — it never consults `groups[]`).
 */

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const TEX = 64;
const OUT_DIR = path.resolve(__dirname, '../src/main/resources/defaults/guns/longshot');

// ---- deterministic ids -----------------------------------------------------
let seed = 0x2f6b1a3;
function rnd() {
  seed ^= seed << 13; seed >>>= 0;
  seed ^= seed >> 17;
  seed ^= seed << 5; seed >>>= 0;
  return seed / 0x100000000;
}
function uuid() {
  const h = (n) => Array.from({ length: n }, () =>
    Math.floor(rnd() * 16).toString(16)).join('');
  return `${h(8)}-${h(4)}-4${h(3)}-${(8 + Math.floor(rnd() * 4)).toString(16)}${h(3)}-${h(12)}`;
}

// ---- UV shelf packer -------------------------------------------------------
const faces = [];
let shelfX = 0, shelfY = 0, shelfH = 0;
function alloc(u, v) {
  const w = Math.max(1, Math.round(u)), h = Math.max(1, Math.round(v));
  if (shelfX + w > TEX) { shelfX = 0; shelfY += shelfH; shelfH = 0; }
  if (shelfY + h > TEX) { throw new Error('texture atlas overflow'); }
  const rect = [shelfX, shelfY, shelfX + w, shelfY + h];
  shelfX += w; shelfH = Math.max(shelfH, h);
  return rect;
}

// ---- geometry --------------------------------------------------------------
// Pixel space 0..16, muzzle toward -Z, buttstock toward +Z (matches the shipped
// pistol frames, so baked clip frames land in the same item-model space).
const PARTS = {
  gun:      { pivot: [8, 8, 8] },
  bolt:     { pivot: [8, 9.1, 12.5] },
  magazine: { pivot: [8, 7, 7.5] },
  flash:    { pivot: [8, 8, 0] },
};

// [part, name, from, to, colour, shadeTop]
const CUBES = [
  ['gun', 'receiver',      [6.5, 7.0, 5.0],  [9.5, 10.0, 12.0], [74, 78, 84]],
  ['gun', 'barrel',        [7.25, 8.0, 0.0],  [8.75, 9.5, 5.0],  [58, 62, 68]],
  ['gun', 'muzzle_brake',  [6.9, 7.6, -0.4],  [9.1, 9.9, 1.2],   [40, 43, 48]],
  ['gun', 'stock',         [7.0, 6.5, 12.0],  [9.0, 9.0, 16.0],  [104, 70, 40]],
  ['gun', 'comb',          [7.2, 9.0, 13.5],  [8.8, 9.8, 15.6],  [120, 82, 48]],
  ['gun', 'grip',          [7.0, 3.4, 10.4],  [8.8, 7.0, 12.4],  [34, 32, 34]],
  ['gun', 'trigger_guard', [7.3, 5.6, 9.0],   [8.7, 6.1, 10.4],  [34, 32, 34]],
  ['gun', 'scope_mount',   [7.1, 10.0, 6.6],  [8.9, 10.6, 10.4], [52, 55, 60]],
  ['gun', 'scope_tube',    [6.8, 10.6, 5.0],  [9.2, 12.4, 11.0], [30, 32, 36]],
  ['gun', 'scope_lens_f',  [7.0, 10.8, 4.6],  [9.0, 12.2, 5.0],  [64, 116, 148]],
  ['gun', 'front_sight',   [7.6, 9.5, 1.4],   [8.4, 10.5, 2.2],  [26, 28, 32]],
  ['bolt', 'bolt_body',    [7.4, 8.6, 11.4],  [8.6, 9.6, 13.4],  [96, 100, 108]],
  ['bolt', 'bolt_handle',  [8.6, 8.6, 11.8],  [10.6, 9.5, 12.9], [112, 116, 124]],
  ['magazine', 'mag_body', [7.05, 3.2, 6.4],  [8.95, 7.0, 8.6],  [86, 88, 94]],
  ['magazine', 'mag_base', [6.9, 2.8, 6.3],   [9.1, 3.4, 8.7],   [44, 46, 50]],
  ['flash', 'flash_v',     [7.6, 5.6, -1.2],  [8.4, 10.6, 0.4],  [252, 214, 96]],
  ['flash', 'flash_h',     [5.8, 7.6, -1.2],  [10.2, 8.4, 0.4],  [248, 176, 64]],
];

const AXIS = { north: 'z-', south: 'z+', west: 'x-', east: 'x+', up: 'y+', down: 'y-' };
const elements = [];
const byPart = { gun: [], bolt: [], magazine: [], flash: [] };

for (const [part, name, from, to, rgb] of CUBES) {
  const dims = [to[0] - from[0], to[1] - from[1], to[2] - from[2]];
  const uvOf = (a, b) => alloc(dims[a], dims[b]);
  const faceSpec = [
    ['north', 2, 1], ['south', 2, 1], ['east', 2, 1], ['west', 2, 1],
    ['up', 0, 2], ['down', 0, 2],
  ];
  const faceMap = {};
  for (const [key, ua, va] of faceSpec) {
    const rect = uvOf(ua, va);
    faces.push({ rect, rgb, key, part });
    faceMap[key] = { uv: rect, texture: 0 };
  }
  const uuidStr = uuid();
  elements.push({
    name, uuid: uuidStr, box_uv: false, render_order: 'default', locked: false,
    export: true, scope: 0, allow_mirror_modeling: true,
    from, to, autouv: 0, color: -1, origin: [...PARTS[part].pivot],
    faces: faceMap,
  });
  byPart[part].push(uuidStr);
}

// ---- clips -----------------------------------------------------------------
// Blockbench 5.0 keyframe shape, exactly what BbModelImporter.parseKeyframes
// reads: {channel, time, interpolation, data_points:[{x,y,z}]}. Times are
// seconds. `scale` data is a MULTIPLIER (1 = authored size), not a percentage:
// AnimationEvaluator composes S(delta) directly, so 0 collapses a bone.
const K = (channel, time, v, interpolation = 'linear') => ({
  channel, time, interpolation,
  data_points: [{ x: v[0], y: v[1], z: v[2] }],
  uuid: uuid(),
});

const CLIP_SPECS = [
  {
    name: 'idle', loop: 'loop', length: 2.8, animators: {
      gun: { rotation: [K('rotation', 0, [0, 0, 0]), K('rotation', 1.4, [0.8, 0, 0]), K('rotation', 2.8, [0, 0, 0])],
             position: [K('position', 0, [0, 0, 0]), K('position', 1.4, [0, 0.08, 0]), K('position', 2.8, [0, 0, 0])] },
    },
  },
  {
    name: 'fire', loop: 'once', length: 0.45, animators: {
      gun: {
        rotation: [K('rotation', 0, [0, 0, 0]), K('rotation', 0.05, [-16, 2, 0], 'ease'),
                   K('rotation', 0.12, [-9, -1, 0]), K('rotation', 0.45, [0, 0, 0], 'ease')],
        position: [K('position', 0, [0, 0, 0]), K('position', 0.05, [0, 0.5, 2.2], 'ease'),
                   K('position', 0.2, [0, 0.1, 0.6]), K('position', 0.45, [0, 0, 0], 'ease')],
      },
      bolt: { position: [K('position', 0, [0, 0, 0]), K('position', 0.06, [0, 0, 2.4], 'ease'),
                         K('position', 0.2, [0, 0, 0.2]), K('position', 0.32, [0, 0, 0], 'ease')] },
      flash: {
        scale: [K('scale', 0, [0, 0, 0], 'step'), K('scale', 0.01, [1.6, 1.6, 1.2]),
                K('scale', 0.09, [0, 0, 0], 'step')],
        rotation: [K('rotation', 0, [0, 0, 0]), K('rotation', 0.09, [0, 0, 30])],
      },
    },
  },
  {
    name: 'reload', loop: 'once', length: 2.2, animators: {
      gun: { rotation: [K('rotation', 0, [0, 0, 0]), K('rotation', 0.5, [-8, 6, 0], 'ease'),
                        K('rotation', 1.3, [-6, 8, 0]), K('rotation', 1.9, [-3, 2, 0]),
                        K('rotation', 2.2, [0, 0, 0], 'ease')] },
      magazine: {
        position: [K('position', 0, [0, 0, 0]), K('position', 0.35, [0, -3.5, 0], 'ease'),
                   K('position', 0.6, [0, -13, 1]), K('position', 0.95, [0, -13, 1], 'step'),
                   K('position', 1.25, [0, 0, 0], 'ease')],
        rotation: [K('rotation', 0, [0, 0, 0]), K('rotation', 0.45, [-14, 0, 0]),
                   K('rotation', 0.6, [-18, 0, 0]), K('rotation', 1.0, [-18, 0, 0]),
                   K('rotation', 1.25, [0, 0, 0], 'ease')],
        scale: [K('scale', 0, [1, 1, 1]), K('scale', 0.62, [1, 1, 1], 'step'),
                K('scale', 0.66, [0, 0, 0], 'step'), K('scale', 0.92, [0, 0, 0], 'step'),
                K('scale', 0.96, [1, 1, 1], 'step')],
      },
      bolt: { position: [K('position', 0, [0, 0, 0]), K('position', 1.45, [0, 0, 2.6], 'ease'),
                        K('position', 1.75, [0, 0, 2.6], 'step'), K('position', 1.9, [0, 0, 0], 'step')] },
    },
  },
  {
    name: 'cycle', loop: 'once', length: 0.6, animators: {
      gun: { rotation: [K('rotation', 0, [0, 0, 0]), K('rotation', 0.2, [-3, 0, 0]), K('rotation', 0.6, [0, 0, 0], 'ease')] },
      bolt: {
        rotation: [K('rotation', 0, [0, 0, 0]), K('rotation', 0.15, [0, 0, -55], 'ease'),
                   K('rotation', 0.4, [0, 0, -55]), K('rotation', 0.6, [0, 0, 0], 'ease')],
        position: [K('position', 0, [0, 0, 0]), K('position', 0.2, [0, 0, 2.2]),
                   K('position', 0.42, [0, 0, 0]), K('position', 0.6, [0, 0, 0])],
      },
    },
  },
  {
    name: 'equip', loop: 'once', length: 0.45, animators: {
      gun: {
        rotation: [K('rotation', 0, [26, -8, 4]), K('rotation', 0.3, [-3, 0, 0], 'ease'), K('rotation', 0.45, [0, 0, 0])],
        position: [K('position', 0, [0, -4.5, -2]), K('position', 0.3, [0, 0.2, 0.3], 'ease'), K('position', 0.45, [0, 0, 0])],
      },
    },
  },
  {
    name: 'scope_in', loop: 'once', length: 0.3, animators: {
      gun: { rotation: [K('rotation', 0, [0, 0, 0]), K('rotation', 0.3, [-6, 0, 2], 'ease')],
             position: [K('position', 0, [0, 0, 0]), K('position', 0.3, [0, -1.1, -0.6], 'ease')] },
    },
  },
  {
    name: 'scope_hold', loop: 'loop', length: 1.6, animators: {
      gun: { rotation: [K('rotation', 0, [-6, 0, 2]), K('rotation', 0.8, [-5.4, 0.6, 1.4]), K('rotation', 1.6, [-6, 0, 2])],
             position: [K('position', 0, [0, -1.1, -0.6]), K('position', 0.8, [0, -1.0, -0.7]), K('position', 1.6, [0, -1.1, -0.6])] },
    },
  },
  {
    name: 'scope_out', loop: 'once', length: 0.3, animators: {
      gun: { rotation: [K('rotation', 0, [-6, 0, 2]), K('rotation', 0.3, [0, 0, 0], 'ease')],
             position: [K('position', 0, [0, -1.1, -0.6]), K('position', 0.3, [0, 0, 0], 'ease')] },
    },
  },
];

// Authored display transforms, in vanilla item-model pixels. The Core reads the
// root `display` key (ModelDisplay.fromJson) and re-emits it on every frame, so
// the first-person pose the author set survives densification.
const DISPLAY = {
  thirdperson_righthand: { rotation: [-4, 0, 0], translation: [0, 3.25, -1.5], scale: [0.6, 0.6, 0.6] },
  thirdperson_lefthand: { rotation: [-4, 0, 0], translation: [0, 3.25, -1.5], scale: [0.6, 0.6, 0.6] },
  firstperson_righthand: { rotation: [-12, 8, 0], translation: [1.5, 2.25, -3.5], scale: [0.62, 0.62, 0.62] },
  firstperson_lefthand: { rotation: [-12, -8, 0], translation: [-1.5, 2.25, -3.5], scale: [0.62, 0.62, 0.62] },
  ground: { rotation: [0, 90, 0], translation: [0, 2.5, 0], scale: [0.5, 0.5, 0.5] },
  gui: { rotation: [88, -42, 92], translation: [-1.5, 2.5, 0], scale: [0.42, 0.42, 0.42] },
  fixed: { rotation: [90, 45, -90], translation: [2.5, 3.5, -0.5], scale: [0.6, 0.6, 0.6] },
  on_shelf: { rotation: [90, 45, -90], translation: [1.5, 1.25, -1], scale: [0.55, 0.55, 0.55] },
};

// ---- assemble --------------------------------------------------------------
const groupUuid = {};
for (const name of Object.keys(PARTS)) groupUuid[name] = uuid();

// Outliner tree: `gun` is the root bone, the three movers are its children.
// Every group object repeats its definition because BbModelImporter reads the
// tree only.
function outlinerGroup(name, childPartNames) {
  return {
    uuid: groupUuid[name],
    name,
    origin: [...PARTS[name].pivot],
    rotation: [0, 0, 0],
    visibility: true,
    isOpen: true,
    children: [
      ...byPart[name],
      ...childPartNames.map((c) => outlinerGroup(c, [])),
    ],
  };
}
const outliner = [outlinerGroup('gun', ['bolt', 'magazine', 'flash'])];

// Flat definitions for Blockbench itself.
const groupsFlat = Object.keys(PARTS).map((name) => ({
  name, uuid: groupUuid[name], export: true, locked: false, scope: 0, selected: false,
  _static: false, origin: [...PARTS[name].pivot], rotation: [0, 0, 0], color: -1,
  children: [], reset: false, shade: true, mirror_uv: false, visibility: true,
  autouv: 0, isOpen: true, primary_selected: false,
}));

const animations = CLIP_SPECS.map((clip) => {
  const animators = {};
  for (const [partName, channels] of Object.entries(clip.animators)) {
    const keyframes = Object.values(channels).flat();
    animators[groupUuid[partName]] = {
      name: partName, type: 'bone', rotation_global: false, quaternion_interpolation: false,
      keyframes,
    };
  }
  return {
    uuid: uuid(), name: clip.name, loop: clip.loop, override: false,
    length: clip.length, snapping: 60, selected: false, group_name: '', scope: 0,
    anim_time_update: '', blend_weight: '', start_delay: '', loop_delay: '',
    animators,
  };
});

const project = {
  meta: { format_version: '5.0', model_format: 'java_block', box_uv: false },
  name: 'longshot',
  model_identifier: 'longshot',
  visible_box: [-8, -8, -8, 24, 24, 24],
  variable_placeholders: [],
  multi_file_ruleset: [],
  variable_placeholder_buttons: [],
  timeline_setups: [],
  unhandled_root_fields: {},
  resolution: { width: TEX, height: TEX },
  elements,
  groups: groupsFlat,
  outliner,
  textures: [{
    name: 'longshot.png',
    path: 'external/longshot.png',
    relative_path: path.join(OUT_DIR, 'longshot.png').replace(/\\/g, '/'),
    folder: '', namespace: '', id: '0', group: '', scope: 0,
    width: TEX, height: TEX, uv_width: TEX, uv_height: TEX,
    particle: false, use_as_default: false, layers_enabled: false,
  }],
  animations,
  display: DISPLAY,
};

// ---- texture ---------------------------------------------------------------
function shade(rgb, factor, edge) {
  const v = rgb.map((c) => Math.max(0, Math.min(255, Math.round(c * factor))));
  return edge ? v.map((c) => Math.max(0, c - 26)) : v;
}

function buildRaster() {
  const px = Buffer.alloc(TEX * TEX * 4);
  for (let i = 0; i < TEX * TEX; i++) {
    px[i * 4] = 26; px[i * 4 + 1] = 26; px[i * 4 + 2] = 30; px[i * 4 + 3] = 255;
  }
  const factors = { up: 1.22, down: 0.66, north: 1.0, south: 0.9, east: 0.86, west: 0.94 };
  for (const f of faces) {
    const [x0, y0, x1, y1] = f.rect;
    const factor = factors[f.key] || 1.0;
    for (let y = y0; y < y1; y++) {
      for (let x = x0; x < x1; x++) {
        const edge = x === x0 || y === y0 || x === x1 - 1 || y === y1 - 1;
        const noise = ((x * 7 + y * 13) % 5) * 0.012 - 0.024;
        const [r, g, b] = shade(f.rgb, factor + noise, edge);
        const i = (y * TEX + x) * 4;
        px[i] = r; px[i + 1] = g; px[i + 2] = b; px[i + 3] = 255;
      }
    }
  }
  return px;
}

function encodePng(px) {
  const raw = Buffer.alloc((TEX * 4 + 1) * TEX);
  for (let y = 0; y < TEX; y++) {
    raw[y * (TEX * 4 + 1)] = 0;
    px.copy(raw, y * (TEX * 4 + 1) + 1, y * TEX * 4, (y + 1) * TEX * 4);
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body) >>> 0);
    return Buffer.concat([len, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(TEX, 0); ihdr.writeUInt32BE(TEX, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

let CRC_TABLE = null;
function crc32(buf) {
  if (!CRC_TABLE) {
    CRC_TABLE = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      CRC_TABLE[n] = c;
    }
  }
  let crc = -1;
  for (const byte of buf) crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ byte) & 0xff];
  return crc ^ -1;
}

// ---- write -----------------------------------------------------------------
fs.mkdirSync(path.join(OUT_DIR, 'models'), { recursive: true });
fs.writeFileSync(path.join(OUT_DIR, 'longshot.png'), encodePng(buildRaster()));
fs.writeFileSync(
  path.join(OUT_DIR, 'models', 'longshot.bbmodel'),
  JSON.stringify(project, null, '\t'),
);
console.log(`[ok] ${elements.length} cubes, ${groupsFlat.length} bones, `
  + `${animations.length} clips, ${faces.length} UV faces packed into ${TEX}x${TEX}`);
console.log(`[ok] wrote ${path.relative(process.cwd(), OUT_DIR)}/longshot.png`);
console.log(`[ok] wrote ${path.relative(process.cwd(), OUT_DIR)}/models/longshot.bbmodel`);
