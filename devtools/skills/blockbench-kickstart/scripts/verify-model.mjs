#!/usr/bin/env node
/**
 * One-command post-build verification: the checks that would otherwise be done
 * by hand with a PNG decoder and a `node -e` dump of the .bbmodel.
 *
 *   node verify-model.mjs <model.bbmodel|spec.json> [--spec <spec.json>]
 *                          [--target virtual|pack-item|pack-block|mob] [--max-colours N] [--json]
 *
 * Runs check-bbmodel.mjs --json for the rig/footprint rules, then adds:
 *   - UV windows vs face pixel sizes (1 texel per unit under `uv: "fit"`)
 *   - distinct colours in the sibling PNG (a vanilla sheet lands near palette size)
 *   - PNG size vs project resolution
 *   - spec/model drift: cube names, atlas size, palette size
 *
 * Exit: 0 = pass, 1 = warnings, 2 = errors.
 */
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const flag = (name) => argv.includes(name);
const value = (name, fallback = null) => {
  const index = argv.indexOf(name);
  return index >= 0 && index + 1 < argv.length ? argv[index + 1] : fallback;
};

const fileArg = argv.find((arg) => !arg.startsWith("--") && /\.json$/i.test(arg));
if (!fileArg) {
  console.error("Usage: node verify-model.mjs <model.bbmodel|spec.json> [--spec <spec.json>] [--target virtual|pack-item|pack-block|mob] [--max-colours N] [--json]");
  process.exit(2);
}
const asJson = flag("--json");
const targetArg = value("--target");

const errors = [];
const warnings = [];
const error = (message) => errors.push(message);
const warn = (message) => warnings.push(message);
const readJson = (file) => JSON.parse(fs.readFileSync(file, "utf8").replace(/^\uFEFF/, ""));

// A spec is also a valid argument: resolve out/name so the command is one path.
let inlineSpec = null;
if (!/\.bbmodel$/i.test(fileArg)) {
  try {
    const candidate = readJson(path.resolve(fileArg));
    if (candidate && typeof candidate === "object" && Array.isArray(candidate.cubes) && candidate.name) inlineSpec = candidate;
  } catch {
    // fall through: not a spec
  }
}
const specPath = value("--spec") ? path.resolve(value("--spec")) : inlineSpec ? path.resolve(fileArg) : null;
const modelPath = inlineSpec && inlineSpec.out && inlineSpec.name
  ? path.join(inlineSpec.out, `${inlineSpec.name}.bbmodel`)
  : path.resolve(fileArg);

let project;
try {
  project = readJson(modelPath);
} catch (parseError) {
  console.error(`Cannot parse ${modelPath}: ${parseError.message}`);
  process.exit(2);
}

const format = project.meta?.model_format ?? null;
const target = targetArg ?? (/bedrock|modded_entity/i.test(String(format ?? "")) ? "mob" : "virtual");
const resolution = {
  width: Number(project.resolution?.width ?? 16),
  height: Number(project.resolution?.height ?? 16),
};
const cubes = (project.elements ?? []).filter((element) => (element.type ?? "cube") === "cube");
const groups = Array.isArray(project.outliner) ? project.outliner.filter((node) => node && typeof node === "object").length : 0;
const animations = Array.isArray(project.animations) ? project.animations.length : 0;
const textures = Array.isArray(project.textures) ? project.textures : [];

// --- sentinel: run the shared pre-flight ------------------------------------
const checker = path.join(here, "..", "..", "mineplus-model-authoring", "scripts", "check-bbmodel.mjs");
let preflight = null;
if (fs.existsSync(checker)) {
  const args = [checker, modelPath, "--json"];
  if (targetArg) args.push("--target", targetArg);
  const run = spawnSync(process.execPath, args, { encoding: "utf8" });
  try {
    preflight = JSON.parse((run.stdout || "").trim());
  } catch {
    warn(`check-bbmodel.mjs produced no JSON (exit ${run.status}); its rules were skipped`);
  }
  if (preflight) {
    for (const message of preflight.errors ?? []) error(`check: ${message}`);
    for (const message of preflight.warnings ?? []) warn(`check: ${message}`);
  }
} else {
  warn(`check-bbmodel.mjs not found at ${checker}; structural rules were skipped`);
}

// --- UV windows vs face pixel sizes ----------------------------------------
const FACE_AXES = {
  north: [0, 1],
  south: [0, 1],
  east: [2, 1],
  west: [2, 1],
  up: [0, 2],
  down: [0, 2],
};
let uvFaces = 0;
let uvMismatches = 0;
const uvMismatchNames = [];
if (target !== "virtual" && target !== "mob") {
  for (const cube of cubes) {
    if (!Array.isArray(cube.from) || !Array.isArray(cube.to)) continue;
    const size = [cube.to[0] - cube.from[0], cube.to[1] - cube.from[1], cube.to[2] - cube.from[2]];
    for (const [faceName, face] of Object.entries(cube.faces ?? {})) {
      const axes = FACE_AXES[faceName];
      if (!axes || !Array.isArray(face.uv)) continue;
      uvFaces++;
      const expected = [Math.abs(size[axes[0]]), Math.abs(size[axes[1]])];
      const actual = [Math.abs(face.uv[2] - face.uv[0]), Math.abs(face.uv[3] - face.uv[1])];
      if (Math.abs(expected[0] - actual[0]) > 0.01 || Math.abs(expected[1] - actual[1]) > 0.01) {
        uvMismatches++;
        if (uvMismatchNames.length < 4) uvMismatchNames.push(`${cube.name ?? "?"}:${faceName} ${actual.join("x")} on a ${expected.join("x")} face`);
      }
    }
  }
  if (uvMismatches > 0) {
    warn(`${uvMismatches} of ${uvFaces} UV windows do not match their face size at 1:1 (${uvMismatchNames.join("; ")}). Use uv "fit" or intentional windows.`);
  }
}

// --- PNG decode (palette + size) -------------------------------------------
function decodePng(buffer) {
  if (!buffer.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) throw new Error("not a PNG");
  let offset = 8;
  let width = 0, height = 0, depth = 0, colorType = 0, interlace = 0;
  let palette = null;
  const idat = [];
  while (offset < buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.toString("ascii", offset + 4, offset + 8);
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    if (type === "IHDR") {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      depth = data[8];
      colorType = data[9];
      interlace = data[12];
    } else if (type === "PLTE") palette = data;
    else if (type === "IDAT") idat.push(data);
    else if (type === "IEND") break;
    offset += 12 + length;
  }
  if (depth !== 8 || interlace !== 0) throw new Error(`unsupported PNG (depth ${depth}, interlace ${interlace})`);
  const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[colorType];
  if (!channels) throw new Error(`unsupported colour type ${colorType}`);
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const pixels = Buffer.alloc(height * stride);
  const paeth = (a, b, c) => {
    const p = a + b - c;
    const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
    return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
  };
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    for (let x = 0; x < stride; x++) {
      const left = x >= channels ? pixels[y * stride + x - channels] : 0;
      const up = y > 0 ? pixels[(y - 1) * stride + x] : 0;
      const upLeft = y > 0 && x >= channels ? pixels[(y - 1) * stride + x - channels] : 0;
      const value = line[x];
      pixels[y * stride + x] =
        filter === 0 ? value
        : filter === 1 ? (value + left) & 0xff
        : filter === 2 ? (value + up) & 0xff
        : filter === 3 ? (value + ((left + up) >> 1)) & 0xff
        : (value + paeth(left, up, upLeft)) & 0xff;
    }
  }
  const colourAt = (x, y) => {
    const index = y * stride + x * channels;
    if (colorType === 3) {
      const p = pixels[index] * 3;
      return [palette[p], palette[p + 1], palette[p + 2], 255];
    }
    if (colorType === 0 || colorType === 4) return [pixels[index], pixels[index], pixels[index], colorType === 4 ? pixels[index + 1] : 255];
    return [pixels[index], pixels[index + 1], pixels[index + 2], colorType === 6 ? pixels[index + 3] : 255];
  };
  return { width, height, colourAt };
}

const colours = new Set();
let painted = 0;
let empty = 0;
let png = null;
const pngPath = path.join(path.dirname(modelPath), `${path.basename(modelPath).replace(/\.bbmodel$/i, "")}.png`);
if (!fs.existsSync(pngPath)) {
  warn(`No ${path.basename(pngPath)} beside the model; palette and resolution were not checked`);
} else {
  try {
    png = decodePng(fs.readFileSync(pngPath));
    for (let y = 0; y < png.height; y++) {
      for (let x = 0; x < png.width; x++) {
        const [r, g, b, a] = png.colourAt(x, y);
        if (!a) { empty++; continue; }
        painted++;
        colours.add(`${r},${g},${b}`);
      }
    }
  } catch (pngError) {
    warn(`${path.basename(pngPath)} could not be decoded (${pngError.message}); palette check skipped`);
  }
  if (png && (png.width !== resolution.width || png.height !== resolution.height)) {
    error(`PNG is ${png.width}x${png.height} but the project resolution is ${resolution.width}x${resolution.height}`);
  }
  if (painted === 0) error(`${path.basename(pngPath)} has no opaque pixels`);
}

// --- spec / model drift -----------------------------------------------------
let spec = null;
let paletteLimit = Number(value("--max-colours", 32));
if (specPath) {
  try {
    spec = readJson(specPath);
  } catch (specError) {
    error(`Cannot parse spec ${specPath}: ${specError.message}`);
  }
}
if (spec) {
  const specNames = (spec.cubes ?? []).map((cube) => cube.name).filter(Boolean).sort();
  const modelNames = cubes.map((cube) => cube.name).filter(Boolean).sort();
  const missing = specNames.filter((name) => !modelNames.includes(name));
  const extra = modelNames.filter((name) => !specNames.includes(name));
  if (missing.length || extra.length) {
    error(`Model does not match the spec: missing [${missing.join(", ") || "-"}], extra [${extra.join(", ") || "-"}]. Rebuild before verifying.`);
  }
  if (spec.name && path.basename(modelPath).replace(/\.bbmodel$/i, "") !== spec.name) {
    warn(`Spec name "${spec.name}" does not match ${path.basename(modelPath)}`);
  }
  const atlas = spec.atlas ?? null;
  if (atlas) {
    paletteLimit = Object.keys(atlas.palette ?? {}).length;
    if (atlas.width !== resolution.width || atlas.height !== resolution.height) {
      error(`Atlas is ${atlas.width}x${atlas.height} but the project resolution is ${resolution.width}x${resolution.height}`);
    }
    if (textures.length !== 1) warn(`Atlas spec but ${textures.length} textures are in the model`);
  } else if ((spec.textures ?? []).length && textures.length !== (spec.textures ?? []).length) {
    warn(`Spec declares ${(spec.textures ?? []).length} textures but the model has ${textures.length}`);
  }
}
if (png && painted > 0) {
  if (colours.size > paletteLimit) {
    warn(`${colours.size} distinct colours with a limit of ${paletteLimit}: a vanilla sheet should land near its palette size (no gradients, no noise)`);
  }
  if (colours.size === 1) warn("Texture is a single flat colour; add 1 px detail, a border or a shade step");
}

// --- bounds vs target space -------------------------------------------------
const bounds = preflight?.anchor?.bounds ?? null;
if (bounds) {
  const { min, max } = bounds;
  if (min[1] < -0.01) error(`Geometry reaches y=${min[1]}; models sit on y=0`);
  if (target === "pack-block" && (min[0] < -0.01 || min[2] < -0.01 || max[0] > 16.01 || max[2] > 16.01)) {
    warn(`Footprint ${min[0]}..${max[0]} x, ${min[2]}..${max[2]} z leaves the 0..16 cell; every extra cell becomes collision`);
  }
  if (target === "virtual" && (min[0] < -8.01 || max[0] > 8.01 || min[2] < -8.01 || max[2] > 8.01)) {
    warn(`Footprint ${min[0]}..${max[0]} x, ${min[2]}..${max[2]} z leaves -8..8; the prop will overlap neighbouring blocks`);
  }
  if (target === "pack-item" && (min[0] < -0.01 || min[1] < -0.01 || min[2] < -0.01 || max[0] > 16.01 || max[1] > 16.01 || max[2] > 16.01)) {
    warn(`Item geometry ${min.join(",")} .. ${max.join(",")} leaves 0..16; item models are emitted unshifted`);
  }
}

// --- report -----------------------------------------------------------------
const passedColourCount = png && painted > 0 ? `${colours.size} colour${colours.size === 1 ? "" : "s"}${empty ? `, ${empty} empty px` : ""}` : "not checked";
const report = {
  file: modelPath,
  format,
  target,
  resolution,
  counts: { cubes: cubes.length, groups, animations, textures: textures.length },
  anchor: preflight?.anchor ?? null,
  texture: png ? { file: path.basename(pngPath), width: png.width, height: png.height, colours: colours.size, painted, empty } : null,
  uv: { faces: uvFaces, mismatches: target === "virtual" || target === "mob" ? null : uvMismatches },
  spec: specPath ? { file: specPath, paletteLimit } : null,
  errors,
  warnings,
};

if (asJson) {
  console.log(JSON.stringify(report, null, 2));
} else {
  console.log(`${path.basename(modelPath)}  [${target}]  ${errors.length ? "FAIL" : warnings.length ? "PASS WITH WARNINGS" : "PASS"}`);
  console.log(`  geometry   ${cubes.length} cubes, ${groups} groups, ${animations} animations`);
  if (bounds) console.log(`  bounds     ${bounds.min.join(", ")} .. ${bounds.max.join(", ")} px   ${preflight?.anchor?.mode ?? ""}`);
  console.log(`  texture    ${pngPath ? `${path.basename(pngPath)} ${png ? `${png.width}x${png.height}` : ""}, ${passedColourCount}` : "missing"}`);
  console.log(`  uv         ${target === "virtual" || target === "mob" ? "engine-mapped (not 1:1 checked)" : `${uvFaces - uvMismatches}/${uvFaces} faces at 1:1`}`);
  if (spec) console.log(`  spec       ${path.basename(specPath)} (${(spec.cubes ?? []).length} cubes, palette <= ${paletteLimit})`);
  for (const message of errors) console.log(`  ERROR      ${message}`);
  for (const message of warnings) console.log(`  WARN       ${message}`);
}

process.exit(errors.length ? 2 : warnings.length ? 1 : 0);
