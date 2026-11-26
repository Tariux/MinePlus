#!/usr/bin/env node
/**
 * Zero-dependency PNG writer + the painted shading model used by spec textures.
 *
 *   import { encodePng, painted } from "./png.mjs";
 *   fs.writeFileSync(file, encodePng(16, 16, painted("#9ccf8a", "#3f7444")));
 *
 * `painted` is the cheap way out of the flat-fill look: a vertical colour ramp
 * with a darkened rim and a deterministic wobble band, which reads as hand-
 * painted at 16 px without any external art tool.
 */

import zlib from "node:zlib";

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buffer) {
  let c = -1;
  for (let i = 0; i < buffer.length; i++) c = CRC_TABLE[(c ^ buffer[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}

/**
 * @param {number} width
 * @param {number} height
 * @param {(x: number, y: number) => number[]} pixel  [r, g, b] or [r, g, b, a]
 */
export function encodePng(width, height, pixel) {
  const raw = Buffer.alloc(height * (width * 4 + 1));
  let offset = 0;
  for (let y = 0; y < height; y++) {
    raw[offset++] = 0;
    for (let x = 0; x < width; x++) {
      const [r, g, b, a = 255] = pixel(x, y);
      raw[offset++] = r & 0xff;
      raw[offset++] = g & 0xff;
      raw[offset++] = b & 0xff;
      raw[offset++] = a & 0xff;
    }
  }
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8;
  header[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", header),
    chunk("IDAT", zlib.deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

const hex = (value) => {
  const clean = String(value).replace("#", "");
  const full = clean.length === 3 ? clean.split("").map((c) => c + c).join("") : clean;
  return [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16));
};
const lerp = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
const clamp01 = (v) => Math.min(1, Math.max(0, v));

/**
 * @param {string} top    hex colour at the top edge
 * @param {string} bottom hex colour at the bottom edge
 * @param {{ rim?: number, band?: number, seed?: number }} [options]
 *   rim  — how much the left/right edges darken (0..0.3)
 *   band — amplitude of the diagonal wobble that breaks up the ramp (0..0.06)
 *   seed — phase shift, so sibling materials do not share one wobble
 */
export function painted(top, bottom, options = {}) {
  const { rim = 0.1, band = 0.04, seed = 0 } = options;
  const start = hex(top);
  const end = hex(bottom ?? top);
  return (x, y) => {
    const v = clamp01(y / 15);
    const base = lerp(start, end, v * v * 0.55 + v * 0.45);
    const edge = Math.min(x, 15 - x) / 4;
    const shade = 1 - rim * (1 - clamp01(edge)) ** 2;
    const wobble = Math.sin((((x * 1.7 + y * 0.9) / 16) + seed) * Math.PI * 2) * band;
    return base.map((c) => Math.round(Math.min(255, Math.max(0, c * (shade + wobble)))));
  };
}

/**
 * A mutable RGBA canvas for hand-placed pixel art. Every colour is opaque; a
 * transparent texel is written with `null`, which vanilla reads as a cut-out.
 */
export function canvas(width, height) {
  const data = new Uint8ClampedArray(width * height * 4);
  const inside = (x, y) => x >= 0 && y >= 0 && x < width && y < height;

  const put = (x, y, rgb) => {
    if (!inside(x, y)) return;
    const offset = (y * width + x) * 4;
    if (rgb === null) {
      data[offset] = data[offset + 1] = data[offset + 2] = data[offset + 3] = 0;
      return;
    }
    data[offset] = rgb[0];
    data[offset + 1] = rgb[1];
    data[offset + 2] = rgb[2];
    data[offset + 3] = 255;
  };

  const get = (x, y) => {
    const offset = (y * width + x) * 4;
    return [data[offset], data[offset + 1], data[offset + 2]];
  };

  return {
    width,
    height,
    data,
    put,
    get,
    pixel: (x, y) => {
      const offset = (y * width + x) * 4;
      return [data[offset], data[offset + 1], data[offset + 2], data[offset + 3]];
    },
  };
}

/**
 * Renders the `atlas.art` op list onto a canvas. Ops are intentionally tiny and
 * rectangular: vanilla textures are flat fills, hard edges and 1 px detail, not
 * gradients. Coordinates are atlas pixels, origin top-left.
 *
 *   { rect: [x, y, w, h], fill: "leaf" }              solid
 *   { rect: [...], dither: ["leaf", "leafDark"] }     checkerboard, vanilla noise
 *   { px: [[x, y], ...], fill: "leafDark" }           single pixels
 *   { rect: [...], noise: 0.05, seed: 3 }             tone jitter on what is there
 *   { rect: [...], outline: "leafDark" }              1 px inner border
 *   { at: [x, y], pattern: ["AB.", "BA."], map: {...} } literal pixel art
 *
 * `fill`/`dither`/`outline` accept a palette key or a #rrggbb string.
 */
export function renderAtlas(width, height, ops = [], palette = {}) {
  const sheet = canvas(width, height);
  const resolve = (value) => {
    if (value === null) return null;
    const key = typeof value === "string" ? value.trim() : value;
    if (typeof key === "string" && key.startsWith("#")) return hex(key);
    if (typeof key === "string" && palette[key]) return hex(palette[key]);
    throw new Error(`atlas colour "${key}" is neither a #rrggbb value nor a palette key`);
  };
  const jitter = (rgb, amount, seed) => {
    const n = Math.sin(seed * 12.9898 + rgb[0] * 0.37 + rgb[1] * 0.11) * 43758.5453;
    const step = Math.round((n - Math.floor(n)) * 2 - 1) * Math.round(255 * amount);
    return rgb.map((c) => Math.min(255, Math.max(0, c + step)));
  };

  for (const op of ops) {
    const rect = op.rect ?? null;
    if (op.pattern) {
      // Literal pixel art: one string per row, one char per texel. "." is empty.
      const map = op.map || {};
      const [ox, oy] = op.at ?? [0, 0];
      for (const [dy, line] of op.pattern.entries()) {
        for (const [dx, char] of [...line].entries()) {
          if (char === "." || char === " ") continue;
          const key = map[char] ?? char;
          sheet.put(ox + dx, oy + dy, resolve(key));
        }
      }
      continue;
    }
    if (op.px) {
      const fill = resolve(op.fill);
      for (const [x, y] of op.px) sheet.put(x, y, fill);
      continue;
    }
    if (!rect) throw new Error(`atlas op ${JSON.stringify(op).slice(0, 60)} needs rect, px or pattern`);
    const [x0, y0, w, h] = rect;
    if (op.fill !== undefined) {
      const fill = resolve(op.fill);
      for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) sheet.put(x, y, fill);
    }
    if (op.dither) {
      const [a, b] = op.dither.map(resolve);
      const size = op.size ?? 1;
      for (let y = y0; y < y0 + h; y++) {
        for (let x = x0; x < x0 + w; x++) {
          const odd = (Math.floor((x - x0) / size) + Math.floor((y - y0) / size)) % 2 === 1;
          sheet.put(x, y, odd ? b : a);
        }
      }
    }
    if (op.noise) {
      const seed = op.seed ?? 1;
      for (let y = y0; y < y0 + h; y++) {
        for (let x = x0; x < x0 + w; x++) {
          if (!sheet.pixel(x, y)[3]) continue;
          sheet.put(x, y, jitter(sheet.get(x, y), op.noise, seed + x * 7 + y * 13));
        }
      }
    }
    if (op.outline) {
      const colour = resolve(op.outline);
      for (let y = y0; y < y0 + h; y++) {
        for (let x = x0; x < x0 + w; x++) {
          const border = x === x0 || y === y0 || x === x0 + w - 1 || y === y0 + h - 1;
          if (border) sheet.put(x, y, colour);
        }
      }
    }
  }
  return sheet;
}

