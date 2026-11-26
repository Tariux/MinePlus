#!/usr/bin/env node
/**
 * Shared preview camera + offscreen-view handling for the kickstart scripts.
 *
 * The one thing that is easy to get wrong: Blockbench's Java Block/Item format
 * draws the authored 0..16 cell centred on the origin, so a preview target of
 * [8, y, 8] frames the model off-centre and clips it. `previewCamera` therefore
 * subtracts the format's viewport offset from the authored bounds. Authored
 * `spec.preview.target` / `.position` still win when present.
 */
import fs from "node:fs";
import path from "node:path";

export const PREVIEW_VIEW = "kickstart_preview";
export const PREVIEW_SIZE = 640;

const isVec = (value) => Array.isArray(value) && value.length === 3 && value.every(Number.isFinite);

/** Authored-space AABB of a spec's cubes (rotation-agnostic; good enough to aim a camera). */
export function modelBounds(cubes = []) {
  let min = [Infinity, Infinity, Infinity];
  let max = [-Infinity, -Infinity, -Infinity];
  for (const cube of cubes) {
    if (!isVec(cube.from) || !isVec(cube.to)) continue;
    for (let axis = 0; axis < 3; axis++) {
      min[axis] = Math.min(min[axis], cube.from[axis], cube.to[axis]);
      max[axis] = Math.max(max[axis], cube.from[axis], cube.to[axis]);
    }
  }
  return Number.isFinite(min[0]) ? { min, max } : null;
}

/** Viewport shift from authored space to what Blockbench draws. */
export function viewportOffset(format) {
  return format === "java_block" ? [-8, 0, -8] : [0, 0, 0];
}

/**
 * @returns {{ position: number[], target: number[], fov: number }}
 */
export function previewCamera(spec = {}, format = "free", cubes = []) {
  const bounds = modelBounds(cubes);
  const offset = viewportOffset(format);
  const center = bounds
    ? [(bounds.min[0] + bounds.max[0]) / 2 + offset[0], (bounds.min[1] + bounds.max[1]) / 2 + offset[1], (bounds.min[2] + bounds.max[2]) / 2 + offset[2]]
    : [0, 6, 0];
  const preview = spec.preview ?? {};
  const target = isVec(preview.target) ? preview.target : center;
  const position = isVec(preview.position)
    ? preview.position
    : [target[0] + 26, target[1] + 15, target[2] - 24];
  return { position, target, fov: Number(preview.fov) || 40 };
}

async function listViews(call) {
  try {
    const listed = await call("list_views", {});
    return JSON.parse(listed.text)?.views ?? [];
  } catch {
    return null;
  }
}

/**
 * Render `<outDir>/<name>-preview.png` from an offscreen view, reusing the
 * shared view id when it already exists (create_offscreen_view refuses a
 * duplicate id). Falls back to the user's viewport only when no view can be
 * had, and restores that viewport's camera afterwards.
 *
 * @param {{call: Function, callQuiet: Function, step?: Function}} env
 */
export async function capturePreview(env, { name, outDir, spec, format = "free", cubes = [], view = PREVIEW_VIEW }) {
  const { call, callQuiet, step } = env;
  if (!view) view = PREVIEW_VIEW;
  const camera = previewCamera(spec, format, cubes);
  const views = await listViews(call);
  const existing = views?.find((entry) => entry.id === view);
  let targetView = view;
  let activeCamera = null;

  if (existing) {
    if (existing.width !== PREVIEW_SIZE || existing.height !== PREVIEW_SIZE) {
      await callQuiet("resize_offscreen_view", { id: view, width: PREVIEW_SIZE, height: PREVIEW_SIZE });
    }
  } else {
    const created = await callQuiet("create_offscreen_view", {
      id: view,
      width: PREVIEW_SIZE,
      height: PREVIEW_SIZE,
      copy_view: "none",
      antialias: true,
    });
    if (!created) {
      const active = views?.find((entry) => entry.active);
      if (active?.camera) {
        activeCamera = {
          position: active.camera.position,
          target: active.camera.target,
          fov: active.camera.fov,
        };
      }
      targetView = "active";
      console.log("  ! offscreen view unavailable; rendered the active viewport and will restore its camera");
    }
  }

  const shot = await callQuiet("set_camera_angle", {
    view: targetView,
    position: camera.position,
    target: camera.target,
    projection: "perspective",
    fov: camera.fov,
  });
  const image = (shot?.content || []).find((item) => item.type === "image" && item.data);
  let file = null;
  if (image) {
    file = path.join(outDir, `${name}-preview.png`);
    fs.writeFileSync(file, Buffer.from(image.data, "base64"));
    step?.("preview", file);
  } else {
    console.log("  ! preview render returned no image");
  }

  if (activeCamera) {
    await callQuiet("set_camera_angle", {
      view: "active",
      position: activeCamera.position,
      target: activeCamera.target,
      projection: "perspective",
      fov: activeCamera.fov,
    });
  }
  return file;
}
