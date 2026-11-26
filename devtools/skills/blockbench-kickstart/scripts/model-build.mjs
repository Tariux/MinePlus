#!/usr/bin/env node
/**
 * One-command Blockbench model build over the live MCP.
 *
 *   node model-build.mjs <spec.json> [--preview] [--no-export] [--out <dir>]
 *
 * One MCP session, no per-call handshakes, no shell quoting. Reads a spec,
 * builds groups + cubes + UVs + textures + animations, exports the .bbmodel
 * with its PNGs, and runs the Mineplus pre-flight.
 *
 * Env: BB_MCP_URL (default http://localhost:6960/bb-mcp)
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { encodePng, painted, renderAtlas } from "./png.mjs";
import { capturePreview } from "./preview.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const URL = process.env.BB_MCP_URL || "http://localhost:6960/bb-mcp";

const argv = process.argv.slice(2);
const flag = (n) => argv.includes(n);
const value = (n, d = null) => {
  const i = argv.indexOf(n);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : d;
};

const specPath = argv.find((a) => !a.startsWith("--") && a.endsWith(".json"));
if (!specPath) {
  console.error("Usage: node model-build.mjs <spec.json> [--preview] [--no-export] [--out <dir>]");
  process.exit(2);
}

const spec = JSON.parse(fs.readFileSync(specPath, "utf8").replace(/^\uFEFF/, ""));
const name = spec.name;
const format = spec.format || "free";
const target = spec.target || "virtual";
const outDir = value("--out", spec.out);
const wantPreview = (flag("--preview") || !!spec.preview) && !flag("--no-preview");
const doExport = !flag("--no-export") && !!outDir;

if (!name) {
  console.error("spec.name is required");
  process.exit(2);
}

const headers = { "Content-Type": "application/json", Accept: "application/json, text/event-stream" };
let sessionId = null;

async function post(body) {
  const res = await fetch(URL, {
    method: "POST",
    headers: sessionId ? { ...headers, "Mcp-Session-Id": sessionId } : headers,
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(120000),
  });
  return { res, text: await res.text() };
}

function decode(text, contentType = "") {
  if (contentType.includes("text/event-stream")) {
    const events = text
      .split(/\r?\n/)
      .filter((l) => l.startsWith("data:"))
      .map((l) => l.slice(5).trim())
      .filter(Boolean);
    if (events.length === 0) return JSON.parse(text);
    return JSON.parse(events[events.length - 1]);
  }
  return JSON.parse(text);
}

async function call(tool, args = {}) {
  const r = await post({ jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: tool, arguments: args } });
  const payload = decode(r.text, r.res.headers.get("content-type") || "");
  if (payload.error) throw new Error(`${tool}: ${JSON.stringify(payload.error)}`);
  if (payload.result?.isError) {
    throw new Error(`${tool}: ${(payload.result.content || []).map((i) => i.text).join("\n")}`);
  }
  const text = (payload.result?.content || []).filter((i) => i.type === "text").map((i) => i.text).join("\n");
  return { text, content: payload.result?.content || [] };
}

async function callQuiet(tool, args) {
  try {
    return await call(tool, args);
  } catch (error) {
    console.log(`  ! ${error.message.slice(0, 200)}`);
    return null;
  }
}

const step = (label, detail = "") => console.log(`${label.padEnd(12)} ${detail}`);

const FORMATS = ["free", "java_block", "bedrock"];
const TARGETS = ["virtual", "pack-item", "pack-block", "mob"];
const UV_MODES = ["full", "half", "auto", "none", "fit"];
const FACES = ["north", "south", "east", "west", "up", "down"];
// Vanilla is one texel per Blockbench unit. `fit` maps each face to a window of
// exactly its own pixel size, which is what makes a texture read at vanilla scale.
const isVec3 = (value) => Array.isArray(value) && value.length === 3 && value.every((n) => Number.isFinite(n));
const isRect = (value) => Array.isArray(value) && value.length === 4 && value.every(Number.isFinite);
const isAnchor = (value) => Array.isArray(value) && value.length === 2 && value.every(Number.isFinite);
// Vanilla block and item models are static; only entity formats carry clips.
const ANIMATED_FORMATS = ["free", "bedrock"];

// Size in model pixels of one face of a cube. This is the texel count vanilla
// would use for that face, so it is also the UV window size under `fit`.
function faceSize(cube, face) {
  const [x0, y0, z0] = cube.from;
  const [x1, y1, z1] = cube.to;
  const w = Math.abs(x1 - x0);
  const h = Math.abs(y1 - y0);
  const d = Math.abs(z1 - z0);
  if (face === "north" || face === "south") return [w, h];
  if (face === "east" || face === "west") return [d, h];
  return [w, d];
}

// The atlas is the one texture a vanilla-style model ships with. Vanilla art is a
// small palette, flat fills and hard 1 px detail, so the spec declares rectangles
// instead of asking for a painting tool.
const atlas = spec.atlas || null;
const atlasSize = atlas ? { width: atlas.width || 16, height: atlas.height || 16 } : null;

// Runs before the MCP session, because create_project replaces the open project:
// a typo caught here costs nothing, a typo caught after costs the user's work.
function validateSpec(spec) {
  const problems = [];
  const bad = (message) => problems.push(message);

  if (!FORMATS.includes(format)) bad(`format "${format}" is not one of ${FORMATS.join(", ")}`);
  if (!TARGETS.includes(target)) bad(`target "${target}" is not one of ${TARGETS.join(", ")}`);

  if (atlas) {
    if (!atlas.name) bad(`atlas has no name; it becomes the .png stem`);
    if (!(atlasSize.width > 0 && atlasSize.height > 0)) bad(`atlas width/height must be positive`);
    if (atlasSize.width > 64 || atlasSize.height > 64) bad(`atlas ${atlasSize.width}x${atlasSize.height} is larger than vanilla ever uses; 16-64 px per sheet`);
    if (target === "virtual") {
      bad(`target "virtual" cannot sample an atlas: the packless engine renders one material per cube with full-face UVs. Use pack-item or pack-block.`);
    }
    if (target === "mob") bad(`an atlas needs a per-face UV window, which the mob rig serialisation does not keep; use pack-item or pack-block`);
    const paletteSize = Object.keys(atlas.palette || {}).length;
    if (paletteSize > 16) bad(`atlas palette has ${paletteSize} colours; vanilla materials stay under about 12`);
    const inside = (x, y) => x >= 0 && y >= 0 && x < atlasSize.width && y < atlasSize.height;
    for (const [i, op] of (atlas.art || []).entries()) {
      const rect = op.rect;
      if (!rect && !op.px && !op.pattern) bad(`atlas.art[${i}] needs rect, px or pattern`);
      if (op.pattern) {
        if (!Array.isArray(op.pattern) || !op.pattern.length) bad(`atlas.art[${i}].pattern must be an array of rows`);
        else {
          const width = [...op.pattern[0]].length;
          const [ox, oy] = op.at ?? [0, 0];
          for (const [row, line] of op.pattern.entries()) {
            if ([...line].length !== width) bad(`atlas.art[${i}] row ${row} is ${[...line].length} chars, expected ${width}`);
            if (!inside(ox, oy + row)) bad(`atlas.art[${i}] row ${row} starts outside the sheet`);
            if (!inside(ox + width - 1, oy + row)) bad(`atlas.art[${i}] row ${row} runs off the sheet`);
          }
          const map = op.map || {};
          for (const char of new Set([...op.pattern.join("")])) {
            if (char === "." || char === " ") continue;
            const value = map[char] ?? char;
            const known = typeof value === "string" && (value.startsWith("#") || atlas.palette?.[value]);
            if (!known) bad(`atlas.art[${i}] char "${char}" maps to "${value}", which is not a palette key`);
          }
        }
      }
      if (rect) {
        if (!isRect(rect)) {
          bad(`atlas.art[${i}].rect must be [x, y, w, h]`);
        } else if (!inside(rect[0], rect[1]) || !inside(rect[0] + rect[2] - 1, rect[1] + rect[3] - 1)) {
          bad(`atlas.art[${i}] rect ${rect.join(",")} leaves the ${atlasSize.width}x${atlasSize.height} sheet`);
        }
      }
      if (op.px) {
        for (const point of op.px) {
          if (!Array.isArray(point) || !inside(point[0], point[1])) bad(`atlas.art[${i}] px ${JSON.stringify(point)} leaves the sheet`);
        }
      }
      for (const key of ["fill", "outline"]) {
        if (op[key] === undefined) continue;
        const value = op[key];
        const known = typeof value === "string" && (value.startsWith("#") || atlas.palette?.[value]);
        if (!known) bad(`atlas.art[${i}].${key} "${value}" is not a palette key or #rrggbb`);
      }
      if (op.dither) {
        for (const value of op.dither) {
          const known = typeof value === "string" && (value.startsWith("#") || atlas.palette?.[value]);
          if (!known) bad(`atlas.art[${i}].dither "${value}" is not a palette key or #rrggbb`);
        }
      }
    }
  }

  const textureNames = new Set(atlas?.name ? [atlas.name] : []);
  if (atlas && (spec.textures || []).length) {
    bad(`atlas and textures are mutually exclusive; the atlas is the single texture`);
  }
  for (const [i, tex] of (spec.textures || []).entries()) {
    if (!tex.name) {
      bad(`textures[${i}] has no name`);
      continue;
    }
    if (textureNames.has(tex.name)) bad(`texture "${tex.name}" is declared twice`);
    textureNames.add(tex.name);
    const sources = ["png", "fill", "top"].filter((key) => tex[key] !== undefined);
    if (sources.length > 1) bad(`texture "${tex.name}" sets ${sources.join(" and ")}; pick one source`);
    if (tex.top !== undefined && tex.bottom === undefined) bad(`texture "${tex.name}" sets "top" without "bottom"`);
  }

  const groupNames = new Set();
  for (const [i, group] of (spec.groups || []).entries()) {
    if (!group.name) {
      bad(`groups[${i}] has no name`);
      continue;
    }
    if (groupNames.has(group.name)) bad(`group "${group.name}" is declared twice`);
    if (group.origin && !isVec3(group.origin)) bad(`group "${group.name}" origin must be [x, y, z]`);
    if (group.parent && group.parent !== "root" && !groupNames.has(group.parent)) {
      bad(`group "${group.name}" has parent "${group.parent}", which is not declared before it`);
    }
    groupNames.add(group.name);
  }

  const cubeNames = new Set();
  for (const [i, cube] of (spec.cubes || []).entries()) {
    const label = cube.name ? `cube "${cube.name}"` : `cubes[${i}]`;
    if (!cube.name) bad(`${label} has no name; set_cube_uv and animations address cubes by name`);
    else if (cubeNames.has(cube.name)) bad(`cube "${cube.name}" is declared twice`);
    else cubeNames.add(cube.name);
    if (!isVec3(cube.from) || !isVec3(cube.to)) {
      bad(`${label} needs from and to as [x, y, z]`);
      continue;
    }
    if (cube.to.some((value, axis) => value <= cube.from[axis])) bad(`${label} has to <= from on an axis; it cannot render`);
    if (cube.group && cube.group !== "root" && !groupNames.has(cube.group)) bad(`${label} belongs to undeclared group "${cube.group}"`);
    if (cube.texture && !textureNames.has(cube.texture)) bad(`${label} uses undeclared texture "${cube.texture}"`);
    if (cube.origin && !isVec3(cube.origin)) bad(`${label} origin must be [x, y, z]`);
    if (cube.rotation && !isVec3(cube.rotation)) bad(`${label} rotation must be [x, y, z]`);
    const mode = cube.uv ?? spec.uv ?? "full";
    const isWindow = isRect(mode);
    const isFaces = mode && typeof mode === "object" && !Array.isArray(mode);
    if (atlas) {
      if (!cube.uv && !cube.faces) bad(`${label} needs uv "fit", [u1, v1, u2, v2] or a faces map; an atlas has no default window`);
      if (cube.texture) bad(`${label} sets texture "${cube.texture}"; an atlas model samples the atlas only`);
      for (const [face, faceUv] of Object.entries(cube.faces || {})) {
        if (!FACES.includes(face)) bad(`${label} faces.${face} is not one of ${FACES.join(", ")}`);
        else if (!isRect(faceUv) && !isAnchor(faceUv)) bad(`${label} faces.${face} must be [u1, v1, u2, v2] or a fit anchor [x, y]`);
        else if (isAnchor(faceUv) && mode !== "fit") {
          bad(`${label} faces.${face} is an anchor but uv is not "fit"; anchors only size faces under fit`);
        }
      }
    }
    if (!isWindow && !isFaces && !UV_MODES.includes(mode)) {
      bad(`${label} uv must be ${UV_MODES.join(", ")} or [u1, v1, u2, v2]`);
    }
    if (mode === "fit" && !atlas) bad(`${label} uses uv "fit", which needs an atlas to sample`);
    for (const rect of [isWindow ? mode : null, ...Object.values(isFaces ? mode : {})].filter(isRect)) {
      if (atlas && (rect[0] < 0 || rect[1] < 0 || rect[2] > atlasSize.width || rect[3] > atlasSize.height)) {
        bad(`${label} uv window ${rect.join(",")} leaves the ${atlasSize.width}x${atlasSize.height} atlas`);
      }
    }
    if (atlas && mode === "fit") {
      const anchor = cube.uvFrom || [0, 0];
      if (!isAnchor(anchor)) bad(`${label} uvFrom must be [x, y]`);
      else {
        for (const face of FACES) {
          const [w, h] = faceSize(cube, face);
          if (anchor[0] + w > atlasSize.width || anchor[1] + h > atlasSize.height) {
            bad(`${label} ${face} is ${w}x${h} px at 1:1 and overruns the atlas from uvFrom ${anchor.join(",")}`);
            break;
          }
        }
      }
    }
  }

  for (const [i, anim] of (spec.animations || []).entries()) {
    if (!anim.name) bad(`animations[${i}] has no name`);
    if (!(Number(anim.length) > 0)) bad(`animation "${anim.name ?? i}" needs a positive length in seconds`);
    if (!ANIMATED_FORMATS.includes(format)) {
      bad(`format "${format}" has no animation support, so animation "${anim.name ?? i}" cannot be created. Vanilla blocks and items are static: drop animations, or use format "bedrock" with target "mob".`);
    }
    for (const bone of Object.keys(anim.bones || {})) {
      if (bone !== "root" && !groupNames.has(bone)) bad(`animation "${anim.name ?? i}" keys bone "${bone}", which is not a declared group`);
    }
  }

  const lowest = Math.min(...(spec.cubes || []).filter((cube) => isVec3(cube.from)).map((cube) => cube.from[1]));
  if (Number.isFinite(lowest) && lowest < 0 && target !== "mob") {
    bad(`geometry starts at y=${lowest}; virtual and pack models sit on y=0 (mob rigs are the exception)`);
  }

  return problems;
}

const problems = validateSpec(spec);
if (problems.length) {
  console.error(`${path.basename(specPath)} is invalid, project left untouched:`);
  for (const problem of problems) console.error(`  - ${problem}`);
  process.exit(2);
}
step("spec", `${(spec.cubes || []).length} cubes validated`);

const init = await post({
  jsonrpc: "2.0",
  id: 1,
  method: "initialize",
  params: { protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "blockbench-kickstart", version: "1.0.0" } },
});
sessionId = init.res.headers.get("mcp-session-id");
if (!sessionId) {
  console.error(`Blockbench MCP did not issue a session (HTTP ${init.res.status}). Is Blockbench open with the MCP plugin?`);
  console.error(init.text.slice(0, 300));
  process.exit(1);
}
await post({ jsonrpc: "2.0", method: "notifications/initialized" });
step("connect", `${URL}`);

await callQuiet("save_checkpoint", { name: `before ${name}` });
await call("create_project", { name, format });
step("project", `${name} (${format})`);

const texSizes = new Map();
const generatedTexDir = path.join(os.tmpdir(), "blockbench-kickstart-textures");

// A painted ramp beats a flat fill for anything that should read as hand-painted
// at 16 px. The PNG lands in temp: it only feeds create_texture, and the exporter
// writes the real <name>.png next to the model from the embedded bitmap.
function writePaintedTexture(tex, width, height) {
  fs.mkdirSync(generatedTexDir, { recursive: true });
  const file = path.join(generatedTexDir, `${tex.name}.png`);
  const options = { rim: tex.rim, band: tex.band, seed: tex.seed };
  fs.writeFileSync(file, encodePng(width, height, painted(tex.top, tex.bottom, options)));
  return file;
}

function writeAtlasTexture(entry) {
  fs.mkdirSync(generatedTexDir, { recursive: true });
  const file = path.join(generatedTexDir, `${entry.name}.png`);
  const sheet = renderAtlas(atlasSize.width, atlasSize.height, entry.art || [], entry.palette || {});
  fs.writeFileSync(file, encodePng(sheet.width, sheet.height, sheet.pixel));
  return file;
}

if (atlas) {
  texSizes.set(atlas.name, atlasSize);
  await call("create_texture", { name: atlas.name, width: atlasSize.width, height: atlasSize.height, data: writeAtlasTexture(atlas) });
} else {
  for (const tex of spec.textures || []) {
    const width = tex.width || 16;
    const height = tex.height || 16;
    texSizes.set(tex.name, { width, height });
    const args = { name: tex.name, width, height };
    if (tex.png) args.data = tex.png;
    else if (tex.top !== undefined) args.data = writePaintedTexture(tex, width, height);
    else if (tex.fill) args.fill_color = tex.fill;
    else if (!tex.blank) args.fill_color = "#8a8a8a";
    await call("create_texture", args);
  }
}
const textureCount = atlas ? 1 : (spec.textures || []).length;
if (textureCount) {
  // Two fixes the MCP cannot do on its own:
  // 1. create_texture leaves the project resolution at 16 even for a 32/64 px
  //    atlas, and the pack writer scales UVs by 16/resolution.
  // 2. A texture made from a file keeps that absolute path; the model must
  //    reference the bare <name>.png the exporter writes beside it.
  const wantedName = atlas ? atlas.name : null;
  const fixed = await call("risky_eval", {
    code: `(function(){
      var list = Texture.all.slice();
      Undo.initEdit({textures: list});
      list.forEach(function(t){
        if (!t.path || t.path.indexOf("\\\\") >= 0 || t.path.indexOf("/") >= 0) t.path = t.name + ".png";
        t.uv_width = t.width;
        t.uv_height = t.height;
      });
      Undo.finishEdit("atlas paths");
      var target = ${wantedName ? `Texture.all.find(function(t){ return t.name === ${JSON.stringify(wantedName)}; })` : "Texture.all.slice().sort(function(a,b){ return b.width - a.width; })[0]"};
      if (target) {
        Project.texture_width = target.width;
        Project.texture_height = target.height;
      }
      return { resolution: [Project.texture_width, Project.texture_height], paths: list.map(function(t){ return t.name + ":" + t.path; }) };
    })()`,
  });
  step("resolution", fixed.text.replace(/\s+/g, " ").slice(0, 160));
}
step("textures", atlas
  ? `${atlas.name} ${atlasSize.width}x${atlasSize.height} atlas (${(atlas.art || []).length} ops)`
  : (spec.textures || []).map((t) => `${t.name} ${t.width || 16}x${t.height || 16}`).join(", ") || "none");

for (const group of spec.groups || []) {
  await call("add_group", { name: group.name, origin: group.origin || [0, 0, 0], parent: group.parent || "root" });
}
step("groups", (spec.groups || []).map((g) => g.name).join(", ") || "none");

const cubes = spec.cubes || [];
const batches = new Map();
for (const cube of cubes) {
  const texture = atlas ? atlas.name : cube.texture;
  const key = `${cube.group || ""}|${texture || ""}`;
  if (!batches.has(key)) batches.set(key, []);
  batches.get(key).push(cube);
}
let placed = 0;
for (const [key, list] of batches) {
  const [group, texture] = key.split("|");
  const elements = list.map((c) => {
    const el = { name: c.name, from: c.from, to: c.to };
    if (c.origin) el.origin = c.origin;
    if (c.rotation) el.rotation = c.rotation;
    if (c.inflate) el.inflate = c.inflate;
    return el;
  });
  const args = { elements, faces: true };
  if (group) args.group = group;
  if (texture) args.texture = texture;
  await call("place_cube", args);
  placed += elements.length;
}
step("cubes", `${placed} placed in ${batches.size} batch(es)`);

const defaultTexture = atlas ? atlas.name : (spec.textures || [])[0]?.name;
let uvFaces = 0;
let autoUvOnVirtual = 0;
let fitFaces = 0;
for (const cube of cubes) {
  const mode = cube.uv || spec.uv || "full";
  if (mode === "auto" || mode === "none") {
    if (mode === "auto" && target === "virtual") autoUvOnVirtual++;
    continue;
  }
  const size = texSizes.get(cube.texture || defaultTexture) || { width: 16, height: 16 };
  const baseAnchor = isAnchor(cube.uvFrom) ? cube.uvFrom : [0, 0];
  const window = (value, face) => {
    if (isRect(value)) return value;
    if (isAnchor(value)) {
      // Fit anchor: 1 texel per model pixel, starting at the anchor.
      const [w, h] = faceSize(cube, face);
      return [value[0], value[1], value[0] + w, value[1] + h];
    }
    if (value === "fit") {
      const [w, h] = faceSize(cube, face);
      return [baseAnchor[0], baseAnchor[1], baseAnchor[0] + w, baseAnchor[1] + h];
    }
    if (value === "half") return [0, 0, size.width / 2, size.height / 2];
    return [0, 0, size.width, size.height];
  };
  const perFace = cube.faces || (mode && typeof mode === "object" && !Array.isArray(mode) ? mode : null);
  const texture = atlas ? atlas.name : cube.texture;
  const faces = {};
  for (const f of FACES) {
    const source = perFace?.[f] ?? mode;
    const rect = window(source, f);
    faces[f] = { uv: rect };
    if (texture) faces[f].texture = texture;
    if (source === "fit" || isAnchor(source)) fitFaces++;
  }
  // Mob rigs are authored with per-face UV windows (box_uv false); the bedrock
  // entity format defaults to box UV, which refuses per-face patches.
  const uvArgs = { id: cube.name, faces };
  if (target === "mob") uvArgs.box_uv = false;
  await call("set_cube_uv", uvArgs);
  uvFaces += 6;
}
if (autoUvOnVirtual > 0) {
  console.log(`  ! ${autoUvOnVirtual} cube(s) left on auto UV for a virtual target; the engine stretches them`);
}
step("uv", uvFaces ? `${uvFaces} faces normalised${fitFaces ? ` (${fitFaces} at 1:1)` : ""}` : "left as authored");

const animations = ANIMATED_FORMATS.includes(format) ? spec.animations || [] : [];
if ((spec.animations || []).length && !animations.length) {
  console.log(`  ! format "${format}" cannot animate; ${spec.animations.length} clip(s) skipped`);
}
for (const anim of animations) {
  await call("create_animation", {
    name: anim.name,
    loop: anim.loop !== false,
    animation_length: anim.length || 2,
    bones: anim.bones,
  });
}
step("animation", animations.map((a) => `${a.name} ${a.length || 2}s`).join(", ") || "none");

const outline = await call("list_outline", { include_cubes: true });
step("outline", outline.text.replace(/\s+/g, " ").slice(0, 300));

if (doExport) {
  const exporter = path.join(here, "..", "..", "mineplus-model-authoring", "scripts", "export-to-mineplus.mjs");
  if (!fs.existsSync(exporter)) {
    console.error(`  ! exporter not found at ${exporter}`);
  } else {
    const args = [exporter, "--out", outDir, "--name", name, "--target", target, "--overwrite"];
    if (spec.meta) args.push("--meta", typeof spec.meta === "string" ? spec.meta : JSON.stringify(spec.meta));
    const result = spawnSync(process.execPath, args, { encoding: "utf8" });
    step("export", `exit ${result.status}`);
    process.stdout.write(result.stdout || "");
    if (result.status !== 0) process.stdout.write(result.stderr || "");
    if (result.status !== 0 && result.status !== 1) process.exitCode = 2;
  }
}

if (wantPreview && outDir) {
  // preview.mjs reuses the shared offscreen view (create_offscreen_view refuses
  // a duplicate id) and targets the viewport-space centre, which is the origin
  // for java_block models. It restores the user's camera on the fallback path.
  await capturePreview({ call, callQuiet, step }, { name, outDir, spec, format, cubes });
}

step("done", name);
