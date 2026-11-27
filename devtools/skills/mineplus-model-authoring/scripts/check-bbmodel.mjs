#!/usr/bin/env node
/**
 * Static pre-flight for a .bbmodel destined for Mineplus.
 *
 *   node check-bbmodel.mjs <model.bbmodel> [--json]
 *
 * Checks the rules the Core importer, texel baker, pack writer and mob rig
 * actually enforce. It does not render; it catches the authoring mistakes that
 * fail silently in game.
 *
 * Exit: 0 = pass, 1 = warnings, 2 = errors.
 */

import fs from "node:fs";
import path from "node:path";

const file = process.argv[2];
const asJson = process.argv.includes("--json");
const targetIndex = process.argv.indexOf("--target");
const explicitTarget = targetIndex >= 0 ? process.argv[targetIndex + 1] : null;
const definitionIndex = process.argv.indexOf("--definition");
const definitionFile = definitionIndex >= 0 ? process.argv[definitionIndex + 1] : null;
if (!file || file.startsWith("--")) {
  console.error(
    "Usage: node check-bbmodel.mjs <model.bbmodel> [--json] [--target virtual|pack-item|pack-block|mob] [--definition blocks/<ns>/<id>.json]",
  );
  process.exit(2);
}

const absolute = path.resolve(file);
let project;
try {
  project = JSON.parse(fs.readFileSync(absolute, "utf8").replace(/^\uFEFF/, ""));
} catch (error) {
  console.error(`Cannot parse ${absolute}: ${error.message}`);
  process.exit(2);
}

const errors = [];
const warnings = [];
const notes = [];
const addUnique = (list, message) => {
  if (!list.includes(message)) list.push(message);
};
const error = (message) => addUnique(errors, message);
const warn = (message) => addUnique(warnings, message);
const note = (message) => addUnique(notes, message);

const format = project.meta?.model_format ?? null;
const target =
  explicitTarget ??
  (/bedrock|modded_entity/i.test(String(format ?? "")) ? "mob" : "virtual");
const resolution = {
  width: Number(project.resolution?.width ?? 16),
  height: Number(project.resolution?.height ?? 16),
};

const metaFile = absolute.replace(/\.bbmodel$/i, ".meta.json");
let modelMeta = null;
if (fs.existsSync(metaFile)) {
  try {
    modelMeta = JSON.parse(fs.readFileSync(metaFile, "utf8").replace(/^\uFEFF/, ""));
  } catch (parseError) {
    warn(`Sibling ${path.basename(metaFile)} is not valid JSON: ${parseError.message}`);
  }
}

const elements = Array.isArray(project.elements) ? project.elements : [];
const cubes = elements.filter((element) => (element.type ?? "cube") === "cube");
const nonCubes = elements.filter((element) => (element.type ?? "cube") !== "cube");
const textures = Array.isArray(project.textures) ? project.textures : [];

const textureNames = new Map();
const lastSegmentCounts = new Map();
for (let index = 0; index < textures.length; index++) {
  const texture = textures[index];
  const raw = texture.path ?? texture.relative_path ?? texture.name ?? null;
  let name = null;
  if (raw) {
    const normalized = String(raw).replaceAll("\\", "/").trim();
    const segment = normalized.slice(normalized.lastIndexOf("/") + 1);
    name = segment.replace(/\.png$/i, "").replace(/\.mcmeta$/i, "").toLowerCase() || null;
  }
  textureNames.set(texture.id ?? String(index), name);
  textureNames.set(String(index), name);
  if (name) lastSegmentCounts.set(name, (lastSegmentCounts.get(name) ?? 0) + 1);
  if (texture.source) {
    const sibling = path.join(path.dirname(absolute), `${name ?? ""}.png`);
    if (name && fs.existsSync(sibling)) {
      note(
        `Texture "${texture.name ?? index}" embeds a PNG, but the sibling ${path.basename(sibling)} is present. Mineplus ignores the embedded copy and reads the file.`,
      );
    } else {
      warn(
        `Texture "${texture.name ?? index}" only has an embedded Base64 PNG. Mineplus never reads embedded images: export ${name ?? "the texture"}.png next to the .bbmodel.`,
      );
    }
  }
  if (!name) warn(`Texture entry ${index} has no path/relative_path/name; faces referencing it cannot resolve.`);
}

function resolveTexture(reference) {
  if (reference === null || reference === undefined) return null;
  const key = String(reference);
  if (textureNames.has(key)) return textureNames.get(key);
  if (key.startsWith("#") && textureNames.has(key.slice(1))) return textureNames.get(key.slice(1));
  return key.replace(/^#/, "").toLowerCase();
}

// --- outliner: groups, element ownership, bone names -----------------------
const elementByUuid = new Map();
for (const element of elements) if (element.uuid) elementByUuid.set(element.uuid, element);

const groups = [];
const groupsByUuid = new Map();
const rootElements = [];
const claimedElements = new Map(); // element uuid -> innermost group uuid

// Bedrock projects keep group metadata in a separate `groups` array and the
// outliner only carries uuids; free projects inline it. Merge both shapes.
const groupMeta = new Map();
for (const group of Array.isArray(project.groups) ? project.groups : []) {
  if (group?.uuid) groupMeta.set(group.uuid, group);
}

function walkOutliner(nodes, parentUuid) {
  for (const node of nodes ?? []) {
    if (typeof node === "string") {
      if (parentUuid) claimedElements.set(node, parentUuid);
      else rootElements.push(node);
      continue;
    }
    if (!node || typeof node !== "object") continue;
    const meta = node.uuid ? groupMeta.get(node.uuid) : null;
    const group = {
      uuid: node.uuid ?? null,
      name: node.name ?? meta?.name ?? null,
      origin: node.origin ?? meta?.origin ?? [0, 0, 0],
      rotation: node.rotation ?? meta?.rotation ?? [0, 0, 0],
      visibility: node.visibility ?? meta?.visibility ?? true,
      parent: parentUuid,
    };
    groups.push(group);
    if (group.uuid) groupsByUuid.set(group.uuid, group);
    walkOutliner(node.children, group.uuid);
  }
}
walkOutliner(project.outliner, null);

const boneNames = new Set(groups.map((group) => String(group.name ?? "").replace(/[^a-z0-9_]/gi, "_").toLowerCase()));
const boneUuids = new Set(groups.map((group) => group.uuid).filter(Boolean));

for (const element of cubes) {
  if (!element.uuid) continue;
  if (!claimedElements.has(element.uuid) && !rootElements.includes(element.uuid)) {
    note(`Cube "${element.name ?? element.uuid}" is not in the outliner; it renders at the origin and cannot animate.`);
  }
  if (rootElements.includes(element.uuid)) {
    note(`Cube "${element.name ?? element.uuid}" is at outliner root; it renders but cannot animate.`);
  }
}

if (groups.length === 0) {
  note("No outliner groups. Static models are fine; nothing can animate and mob rigs have no bones.");
}

// --- matrices (mirrors BbModelImporter: T(p)·R·T(-p), Euler Z·Y·X) ----------
const I = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
function multiply(a, b) {
  const out = new Array(16).fill(0);
  for (let col = 0; col < 4; col++) {
    for (let row = 0; row < 4; row++) {
      let sum = 0;
      for (let k = 0; k < 4; k++) sum += a[k * 4 + row] * b[col * 4 + k];
      out[col * 4 + row] = sum;
    }
  }
  return out;
}
function translation(x, y, z) {
  return [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1];
}
function scaling(x, y, z) {
  return [x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1];
}
function pivot(origin, rotation) {
  const [ox = 0, oy = 0, oz = 0] = origin ?? [];
  const [rx = 0, ry = 0, rz = 0] = rotation ?? [];
  if (!rx && !ry && !rz) return I;
  const cx = Math.cos((rx * Math.PI) / 180), sx = Math.sin((rx * Math.PI) / 180);
  const cy = Math.cos((ry * Math.PI) / 180), sy = Math.sin((ry * Math.PI) / 180);
  const cz = Math.cos((rz * Math.PI) / 180), sz = Math.sin((rz * Math.PI) / 180);
  // Rz * Ry * Rx
  const r = [
    cz * cy, sz * cy, -sy, 0,
    cz * sy * sx - sz * cx, sz * sy * sx + cz * cx, cy * sx, 0,
    cz * sy * cx + sz * sx, sz * sy * cx - cz * sx, cy * cx, 0,
    0, 0, 0, 1,
  ];
  const t = translation(ox / 16, oy / 16, oz / 16);
  const tinv = translation(-ox / 16, -oy / 16, -oz / 16);
  return multiply(multiply(t, r), tinv);
}
function transform(matrix, point) {
  return [
    matrix[0] * point[0] + matrix[4] * point[1] + matrix[8] * point[2] + matrix[12],
    matrix[1] * point[0] + matrix[5] * point[1] + matrix[9] * point[2] + matrix[13],
    matrix[2] * point[0] + matrix[6] * point[1] + matrix[10] * point[2] + matrix[14],
  ];
}
function groupMatrix(uuid, seen = new Set()) {
  if (!uuid || seen.has(uuid)) return I;
  const group = groupsByUuid.get(uuid);
  if (!group) return I;
  seen.add(uuid);
  const parent = groupMatrix(group.parent, seen);
  return multiply(parent, pivot(group.origin, group.rotation));
}

let minX = Infinity, minY = Infinity, minZ = Infinity;
let maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;
let rotatedCubeCount = 0;
let multiAxisRotationCount = 0;
let nonFortyFiveCount = 0;
let cubesOutsideClientWindow = 0;

for (const cube of cubes) {
  if (!Array.isArray(cube.from) || !Array.isArray(cube.to)) {
    error(`Cube "${cube.name ?? cube.uuid ?? "?"}" has no from/to; the importer drops it.`);
    continue;
  }
  const inflate = Number(cube.inflate ?? 0);
  const from = cube.from.map((value) => value - inflate);
  const to = cube.to.map((value) => value + inflate);
  if (to.some((value, axis) => value <= from[axis])) {
    const zeroThickness = to.filter((value, axis) => value <= from[axis]).length;
    if (zeroThickness === 3) {
      error(`Cube "${cube.name ?? cube.uuid}" has no volume; it cannot render.`);
    } else if (target === "mob") {
      warn(`Mob element "${cube.name ?? cube.uuid}" has a zero/negative extent; BedrockMobModel skips it.`);
    } else if (target.startsWith("pack")) {
      warn(`Cube "${cube.name ?? cube.uuid}" is a zero-thickness plane; vanilla element models need a positive size on every axis.`);
    } else {
      note(`Cube "${cube.name ?? cube.uuid}" is a zero-thickness plane; per-face plates render it, collision ignores it.`);
    }
  }
  const rotation = cube.rotation ?? [0, 0, 0];
  const nonZeroAxes = rotation.filter((angle) => Math.abs(Number(angle)) > 1e-6).length;
  if (nonZeroAxes > 0) {
    rotatedCubeCount++;
    if (nonZeroAxes > 1) multiAxisRotationCount++;
    if (rotation.some((angle) => Number(angle) % 45 !== 0)) nonFortyFiveCount++;
  }
  const local = multiply(
    pivot(cube.origin ?? from, rotation),
    multiply(translation(from[0] / 16, from[1] / 16, from[2] / 16), scaling((to[0] - from[0]) / 16, (to[1] - from[1]) / 16, (to[2] - from[2]) / 16)),
  );
  const matrix = multiply(groupMatrix(claimedElements.get(cube.uuid) ?? null), local);
  for (let corner = 0; corner < 8; corner++) {
    const point = transform(matrix, [corner & 1, (corner >> 1) & 1, (corner >> 2) & 1]);
    const x = point[0] * 16, y = point[1] * 16, z = point[2] * 16;
    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
    minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
    if (x < -16 || x > 32 || y < -16 || y > 32 || z < -16 || z > 32) cubesOutsideClientWindow++;
  }
}

// --- faces / UVs / textures -------------------------------------------------
let missingFaceCount = 0;
let untexturedFaceCount = 0;
const partialUvFaces = [];
for (const cube of cubes) {
  const faces = cube.faces ?? {};
  const present = Object.keys(faces);
  if (present.length < 6) missingFaceCount += 6 - present.length;
  for (const [faceName, face] of Object.entries(faces)) {
    if (face.texture === null) continue;
    const uv = Array.isArray(face.uv) ? face.uv : [0, 0, resolution.width, resolution.height];
    const name = resolveTexture(face.texture);
    if (!name) {
      untexturedFaceCount++;
      continue;
    }
    const width = Math.abs(uv[2] - uv[0]);
    const height = Math.abs(uv[3] - uv[1]);
    const full = Math.abs(width - resolution.width) < 1e-6 && Math.abs(height - resolution.height) < 1e-6;
    const halfW = Math.abs(width - resolution.width / 2) < 1e-6 && Math.abs(height - resolution.height) < 1e-6;
    const halfH = Math.abs(width - resolution.width) < 1e-6 && Math.abs(height - resolution.height / 2) < 1e-6;
    if (!full && !halfW && !halfH) {
      partialUvFaces.push(`${cube.name ?? cube.uuid}:${faceName}`);
    }
    if (width > resolution.width + 1e-6 || height > resolution.height + 1e-6) {
      warn(`Face ${cube.name ?? cube.uuid}:${faceName} uses a wrapping UV (${uv.join(",")}); the pack writer clamps it to 0..${resolution.width}/0..${resolution.height}, and the virtual engine tiles it.`);
    }
  }
}
if (missingFaceCount > 0) {
  warn(`${missingFaceCount} cube faces are omitted. Mineplus emits nothing for an omitted face: the cube will be open, not a full box.`);
}
if (untexturedFaceCount > 0) {
  warn(`${untexturedFaceCount} present faces have no resolvable texture; they render as white concrete (virtual) or a missing sprite (pack).`);
}
if (partialUvFaces.length > 0 && target === "virtual") {
  warn(
    `${partialUvFaces.length} face(s) use an arbitrary sub-pixel UV window. The packless virtual engine can only render full or exact half windows; those faces stretch the full material. Use full-face UVs for packless models, or choose a pack target.`,
  );
} else if (partialUvFaces.length > 0) {
  note(`${partialUvFaces.length} face(s) use custom UV windows; the pack writer copies them at 16/resolution scale.`);
}

// --- adjacent PNGs for texel / pack ----------------------------------------
const modelDir = path.dirname(absolute);
const pngNames = new Set();
for (const name of textureNames.values()) {
  if (name) pngNames.add(name);
}
let missingPngs = 0;
if (project.textures?.length) {
  for (const name of pngNames) {
    const candidates = [
      path.join(modelDir, `${name}.png`),
      path.join(modelDir, `${name}.PNG`),
    ];
    if (!candidates.some((candidate) => fs.existsSync(candidate))) {
      missingPngs++;
      if (!textureNamesMatchVanilla(name)) {
        warn(`No ${name}.png beside the model. Texel baking and pack delivery need the PNG next to the .bbmodel (or in the models root).`);
      }
    }
  }
}

function textureNamesMatchVanilla(name) {
  return /^(?:[a-z0-9_]+)$/.test(name) && !/^(texture|image|untitled|material|default)$/.test(name);
}

// --- anchor / footprint -----------------------------------------------------
let originMode = modelMeta?.originMode ?? "AUTO";
if (originMode === "AUTO") {
  const fmt = String(format ?? "").toLowerCase();
  const byFormat = fmt.includes("java_block") || fmt.includes("java_item") || fmt === "modded_block" ? "GRID" : "CENTER";
  if (byFormat === "GRID" && Number.isFinite(minX)) {
    const centered = Math.abs((minX + maxX) / 2) <= 2 && Math.abs((minZ + maxZ) / 2) <= 2;
    originMode = centered ? "CENTER" : "GRID";
  } else {
    originMode = byFormat;
  }
}

let cellsX = null;
let cellsY = null;
let cellsZ = null;
let rawCellsX = null;
let rawCellsY = null;
let rawCellsZ = null;
const SUBPIXEL_PX = 1;
if (Number.isFinite(minX)) {
  const anchorX = originMode === "CENTER" ? -8 : 0;
  const anchorZ = originMode === "CENTER" ? -8 : 0;
  const spanCells = (lo, hi, anchor, shrink) => {
    const from = lo + shrink;
    const to = hi - shrink;
    return Math.floor((to - anchor - 1e-6) / 16) - Math.floor((from - anchor) / 16) + 1;
  };
  const spanCellsY = (lo, hi, shrink) => {
    const from = Math.min(lo + shrink, 0);
    const to = hi - shrink;
    return Math.floor((to - 1e-6) / 16) - Math.floor(from / 16) + 1;
  };
  rawCellsX = spanCells(minX, maxX, anchorX, 0);
  rawCellsY = spanCellsY(minY, maxY, 0);
  rawCellsZ = spanCells(minZ, maxZ, anchorZ, 0);
  // Sub-pixel art overhang (< 1 px per side) is rounding noise, not a filled
  // block: the Core filters those sliver cells out through the declared box,
  // so the footprint compared against a definition uses the shrunken bounds.
  const shrink = (lo, hi) => Math.min(SUBPIXEL_PX, Math.max(0, (hi - lo) / 4));
  cellsX = spanCells(minX, maxX, anchorX, shrink(minX, maxX));
  cellsY = spanCellsY(minY, maxY, shrink(minY, maxY));
  cellsZ = spanCells(minZ, maxZ, anchorZ, shrink(minZ, maxZ));
  if (cellsX > 1 || cellsY > 1 || cellsZ > 1) {
    note(`Footprint spans ${cellsX}×${cellsY}×${cellsZ} block cells (${Math.round(minX)}..${Math.round(maxX)} x, ${Math.round(minY)}..${Math.round(maxY)} y, ${Math.round(minZ)}..${Math.round(maxZ)} z). Every occupied cell becomes a collision barrier.`);
  }
  if (rawCellsX !== cellsX || rawCellsY !== cellsY || rawCellsZ !== cellsZ) {
    note(`Sub-pixel overhang adds sliver cells (raw ${rawCellsX}×${rawCellsY}×${rawCellsZ}); the declared occupancy box filters them, so they never become barriers.`);
  }
  if (minY < -0.001) warn(`Geometry reaches y=${Math.round(minY)}. Virtual models should sit on y=0 (CENTER) or y=0..16 (GRID).`);
}

// --- pack constraints -------------------------------------------------------
if (target.startsWith("pack")) {
  if (multiAxisRotationCount > 0) {
    note(`${multiAxisRotationCount} cube(s) rotate on more than one axis; pack serialization degrades them to their AABB.`);
  }
  if (nonFortyFiveCount > 0) {
    note(`${nonFortyFiveCount} cube(s) use a rotation that is not a multiple of 45°; pack serialization cannot represent it.`);
  }
  if (cubesOutsideClientWindow > 0) {
    note("Some geometry lies outside the client-safe -16..32 px window; the pack writer re-bases or squashes it.");
  }
  for (const [name, count] of lastSegmentCounts) {
    if (count > 1) warn(`Texture file name "${name}" appears ${count} times; pack assets are keyed by the last path segment, so they collide.`);
  }
}

if (target === "pack-item") {
  // Pack items are serialized with no origin shift (PackCompiler forces
  // originMode null for the item axis), so the authored pixels land as written.
  if (Number.isFinite(minX) && (minX < -0.5 || maxX > 16.5 || minY < -0.5 || maxY > 16.5 || minZ < -0.5 || maxZ > 16.5)) {
    warn(
      `pack-item geometry spans ${Math.round(minX)}..${Math.round(maxX)} x, ${Math.round(minY)}..${Math.round(maxY)} y, ${Math.round(minZ)}..${Math.round(maxZ)} z. Item models are emitted unshifted: author 0..16 pixels or the item renders off-centre.`,
    );
  }
  if (!project.display && !modelMeta?.display && !modelMeta?.itemParent) {
    note("No authored display contexts and no itemParent: the pack writer falls back to vanilla generated-item poses. Set display in the .bbmodel or itemParent in .meta.json for held/tool poses.");
  }
}

// --- animations -------------------------------------------------------------
const animations = Array.isArray(project.animations) ? project.animations : [];
const animationReport = [];
for (const animation of animations) {
  const animators = animation.animators ?? {};
  let boneAnimators = 0;
  let effectAnimators = 0;
  let molangValues = 0;
  const channels = new Set();
  for (const [uuid, animator] of Object.entries(animators)) {
    if (animator.type && animator.type !== "bone") {
      effectAnimators++;
      continue;
    }
    boneAnimators++;
    const boneName = String(animator.name ?? groupsByUuid.get(uuid)?.name ?? "").replace(/[^a-z0-9_]/gi, "_").toLowerCase();
    if (!boneNames.has(boneName) && !boneUuids.has(uuid) && !animator.name) {
      warn(`Animation "${animation.name}" animates "${boneName || uuid}", which is not an outliner group; that animator is dropped.`);
    }
    for (const keyframe of animator.keyframes ?? []) {
      if (keyframe.channel) channels.add(keyframe.channel);
      for (const point of keyframe.data_points ?? []) {
        if ([point.x, point.y, point.z].some((value) => typeof value === "string" && !Number.isFinite(Number(value)))) {
          molangValues++;
        }
      }
    }
  }
  animationReport.push({
    name: animation.name,
    loop: animation.loop ?? null,
    length: animation.length ?? null,
    bones: boneAnimators,
    effects: effectAnimators,
    channels: [...channels],
  });
  if (effectAnimators > 0) note(`Animation "${animation.name}" has ${effectAnimators} effect animator(s); Mineplus ignores them.`);
  if (molangValues > 0) warn(`Animation "${animation.name}" contains ${molangValues} Molang expression value(s); the importer substitutes 0.`);
  if (["catmullrom", "bezier", "ease"].includes(animation.loop)) {
    // loop is not interpolation; kept for clarity below
  }
}

// --- block definition gate --------------------------------------------------
const DEFINITION_COLLISION_MODES = ["geometry", "surface", "single", "box", "none"];
let definition = null;
if (definitionFile) {
  const definitionPath = path.resolve(definitionFile);
  try {
    definition = JSON.parse(fs.readFileSync(definitionPath, "utf8").replace(/^\uFEFF/, ""));
  } catch (definitionError) {
    error(`Cannot read definition ${definitionPath}: ${definitionError.message}`);
  }
  if (definition) {
    const modelStem = path.basename(absolute).replace(/\.bbmodel$/i, "").toLowerCase();
    const normalizeModel = (value) =>
      String(value ?? "")
        .replace(/^[a-z0-9_]+:/i, "")
        .replace(/\.bbmodel$/i, "")
        .split("/")
        .filter(Boolean)
        .pop()
        ?.toLowerCase() ?? "";
    const visuals = definition.visuals ?? {};
    const declaredModel = visuals.model;
    if (!declaredModel) {
      error("Definition has no visuals.model; the loader rejects it.");
    } else if (normalizeModel(declaredModel) !== modelStem) {
      error(
        `Definition visuals.model "${declaredModel}" does not match the model file "${path.basename(absolute)}". The loader looks the name up under models/, so a mismatch is a load error.`,
      );
    }
    const itemModel = definition.item?.model;
    if (itemModel && normalizeModel(itemModel) !== modelStem) {
      note(`Definition item.model "${itemModel}" is a different model from the one being checked; only the visual model is gated here.`);
    }

    const occupancy = definition.occupancy ?? {};
    const declaredCells = {
      width: Number(occupancy.width ?? 1),
      height: Number(occupancy.height ?? 1),
      depth: Number(occupancy.depth ?? 1),
    };
    if (cellsX !== null) {
      const measured = { width: cellsX, height: cellsY, depth: cellsZ };
      for (const axis of ["width", "height", "depth"]) {
        if (!Number.isFinite(declaredCells[axis]) || declaredCells[axis] < 1 || declaredCells[axis] > 32) {
          error(`Definition occupancy.${axis} must be 1..32; got ${occupancy[axis]}.`);
        } else if (declaredCells[axis] !== measured[axis]) {
          error(
            `Definition occupancy.${axis}=${declaredCells[axis]} but the geometry spans ${measured[axis]} cell(s) on that axis (${cellsX}x${cellsY}x${cellsZ}). Every occupied cell becomes a barrier, so the declared box must match or placement leaves blocking cells out.`,
          );
        }
      }
    }

    const metaFile = `${absolute}.meta.json`;
    if (fs.existsSync(metaFile)) {
      try {
        const meta = JSON.parse(fs.readFileSync(metaFile, 'utf8').replace(/^\uFEFF/, ''));
        const box = Array.isArray(meta.fillBox) ? meta.fillBox : null;
        if (box) {
          if (box.length !== 6 || box.some((n) => !Number.isInteger(n))) {
            error(`meta.json fillBox must be six integers [x0,y0,z0,x1,y1,z1]; got ${JSON.stringify(box)}.`);
          } else {
            const boxCells = { width: box[3] - box[0] + 1, height: box[4] - box[1] + 1, depth: box[5] - box[2] + 1 };
            for (const axis of ["width", "height", "depth"]) {
              if (boxCells[axis] < 1) {
                error(`meta.json fillBox has an inverted ${axis} range; the Engine rejects the box.`);
              } else if (boxCells[axis] !== declaredCells[axis]) {
                error(
                  `meta.json fillBox spans ${boxCells.width}x${boxCells.height}x${boxCells.depth} cells but occupancy declares ${declaredCells.width}x${declaredCells.height}x${declaredCells.depth}. The fillBox is what the Engine turns into barriers, so the two must agree.`,
                );
              }
            }
            note(`meta.json fillBox pins the barrier lattice to ${boxCells.width}x${boxCells.height}x${boxCells.depth} cells.`);
          }
        }
      } catch (metaError) {
        error(`Cannot read ${path.basename(metaFile)}: ${metaError.message}`);
      }
    } else if (rawCellsX !== null && (rawCellsX !== cellsX || rawCellsY !== cellsY || rawCellsZ !== cellsZ)) {
      note(`No ${path.basename(metaFile)} to pin the lattice: sub-pixel slivers become barriers (raw ${rawCellsX}×${rawCellsY}×${rawCellsZ}).`);
    }

    const collisionMode = definition.collision?.mode ?? "geometry";
    if (!DEFINITION_COLLISION_MODES.includes(String(collisionMode).toLowerCase())) {
      error(`Definition collision.mode "${collisionMode}" is not one of ${DEFINITION_COLLISION_MODES.join(", ")}; the loader rejects it.`);
    }

    const hardness = Number(definition.breaking?.hardness ?? 1.0);
    if (!Number.isFinite(hardness) || hardness < 0 || hardness > 3600) {
      error(`Definition breaking.hardness must be a finite number 0..3600 seconds; got ${definition.breaking?.hardness}.`);
    }

    const autoplay = Array.isArray(visuals.animations?.autoplay) ? visuals.animations.autoplay : [];
    const clipNames = new Set(animationReport.map((clip) => clip.name));
    for (const clip of autoplay) {
      if (!clipNames.has(clip)) {
        error(
          `Definition autoplay clip "${clip}" is not in the model (clips: ${[...clipNames].join(", ") || "none"}). The clip never plays.`,
        );
      }
    }
    if (autoplay.length === 0 && animationReport.length > 0) {
      note(`Model has ${animationReport.length} clip(s) but the definition autoplays none; add visuals.animations.autoplay to see them move.`);
    }
  }
}

// --- target-specific checks -------------------------------------------------
const isMobTarget = target === "mob";
if (isMobTarget) {
  for (const cube of cubes) {
    if (!cube.name) warn(`Cube ${cube.uuid ?? "?"} has no name; BedrockMobModel skips unnamed elements.`);
    if (!Array.isArray(cube.from) || !Array.isArray(cube.to)) continue;
    if (cube.to.some((value, axis) => value - cube.from[axis] <= 0)) {
      warn(`Mob element "${cube.name ?? cube.uuid}" has a non-positive extent and will be skipped.`);
    }
    const faces = Object.values(cube.faces ?? {});
    if (faces.length === 0 || faces.every((face) => !Array.isArray(face.uv))) {
      warn(`Mob element "${cube.name ?? cube.uuid}" has no UV-bearing face and will be skipped.`);
    }
    const rotation = cube.rotation ?? [0, 0, 0];
    if (rotation.filter((angle) => Math.abs(Number(angle)) > 1e-6).length > 1) {
      warn(`Mob element "${cube.name ?? cube.uuid}" rotates on more than one axis; the mob rig keeps one axis only.`);
    }
  }
  if (groups.length === 0) {
    warn("No outliner groups: partsFromOutliner() has no bones, so the mob rig cannot animate and unclaimed elements become one static part.");
  }
  for (const group of groups) {
    if (!group.name) warn("An outliner group has no name; mob bone names become synthetic and animation clips cannot target them.");
    if (Number(group.origin?.[1] ?? 0) < 0) warn(`Group "${group.name}" pivot is below y=0; mob pivots are feet-at-zero.`);
  }
  if (animationReport.length > 0) {
    const names = animationReport.map((clip) => clip.name);
    if (!names.some((name) => /(^|\.)idle$/.test(name))) note("No animation.idle clip; the rig will hold its rest pose when standing.");
    if (!names.some((name) => /(^|\.)walk$/.test(name))) note("No animation.walk clip; the rig will not swing while moving.");
  } else {
    note("No clips: the rig falls back to role posing. partsFromOutliner() marks every group STATIC, so head tracking and leg swing need a role override (code .role(name, ROLE) or mobs/<id>.model.json legRoleMap).");
  }
}

for (const animation of animationReport) {
  if (animation.channels.some((channel) => !["rotation", "position", "scale"].includes(channel))) {
    warn(`Animation "${animation.name}" uses an unsupported channel; only rotation, position and scale import.`);
  }
}

// --- report -----------------------------------------------------------------
const report = {
  file: absolute,
  target,
  format,
  resolution,
  counts: {
    cubes: cubes.length,
    nonCubes: nonCubes.length,
    groups: groups.length,
    textures: textures.length,
    animations: animations.length,
  },
  anchor: {
    mode: originMode,
    formatGuess: format,
    bounds: Number.isFinite(minX)
      ? {
          min: [Math.round(minX * 100) / 100, Math.round(minY * 100) / 100, Math.round(minZ * 100) / 100],
          max: [Math.round(maxX * 100) / 100, Math.round(maxY * 100) / 100, Math.round(maxZ * 100) / 100],
        }
      : null,
    rotatedCubes: rotatedCubeCount,
  },
  textures: [...pngNames].map((name) => ({
    name,
    pngBesideModel: fs.existsSync(path.join(modelDir, `${name}.png`)),
  })),
  animations: animationReport,
  definition: definition
    ? {
        file: path.resolve(definitionFile),
        id: definition.id ?? null,
        model: definition.visuals?.model ?? null,
        occupancy: definition.occupancy ?? null,
        measuredCells: cellsX === null ? null : [cellsX, cellsY, cellsZ],
      }
    : null,
  errors,
  warnings,
  notes,
};

if (asJson) {
  console.log(JSON.stringify(report, null, 2));
} else {
  console.log(`Mineplus bbmodel check: ${path.basename(absolute)}  [target: ${target}]`);
  console.log(`  format      ${format ?? "(none)"}   resolution ${resolution.width}x${resolution.height}`);
  console.log(`  geometry    ${cubes.length} cubes, ${nonCubes.length} non-cube elements`);
  console.log(`  rig         ${groups.length} groups, ${animationReport.length} animations`);
  console.log(`  anchor      ${originMode}${modelMeta?.originMode ? " (meta)" : " (resolved)"}`);
  if (report.anchor.bounds) {
    console.log(`  bounds      ${report.anchor.bounds.min.join(", ")} .. ${report.anchor.bounds.max.join(", ")} px`);
  }
  console.log(`  textures    ${textures.length}${missingPngs ? `, ${missingPngs} without a local PNG` : ""}`);
  if (definitionFile) {
    console.log(`  definition  ${path.basename(path.resolve(definitionFile))}${definition?.id ? `  (${definition.id})` : ""}${cellsX === null ? "" : `  cells ${cellsX}x${cellsY}x${cellsZ}`}`);
  }
  if (animationReport.length) {
    for (const clip of animationReport) {
      console.log(`    - ${clip.name}: ${clip.length ?? "?"}s ${clip.loop ?? "once"}, ${clip.bones} bones${clip.effects ? `, ${clip.effects} effects ignored` : ""}`);
    }
  }
  for (const message of errors) console.log(`  ERROR  ${message}`);
  for (const message of warnings) console.log(`  WARN   ${message}`);
  for (const message of notes) console.log(`  NOTE   ${message}`);
  console.log(
    errors.length ? "  RESULT  FAIL" : warnings.length ? "  RESULT  PASS WITH WARNINGS" : "  RESULT  PASS",
  );
}

process.exit(errors.length ? 2 : warnings.length ? 1 : 0);
