#!/usr/bin/env node
/**
 * Re-render just the preview from a spec — no project rebuild, one MCP session.
 * Use this to iterate on camera framing without paying for a full build.
 *
 *   node preview-shot.mjs <spec.json> [--out <dir>] [--view <id>]
 *                          [--position x,y,z] [--target x,y,z] [--fov n]
 *
 * Camera defaults come from preview.mjs: the authored bounds, shifted into
 * viewport space (java_block draws its cell centred on the origin). Explicit
 * spec.preview values win, and the CLI flags win over both.
 *
 * Env: BB_MCP_URL (default http://localhost:6960/bb-mcp)
 */
import fs from "node:fs";
import path from "node:path";
import { capturePreview, previewCamera, PREVIEW_VIEW } from "./preview.mjs";

const URL = process.env.BB_MCP_URL || "http://localhost:6960/bb-mcp";
const argv = process.argv.slice(2);
const flag = (name) => argv.includes(name);
const value = (name, fallback = null) => {
  const index = argv.indexOf(name);
  return index >= 0 && index + 1 < argv.length ? argv[index + 1] : fallback;
};
const nums = (raw) => {
  if (!raw) return null;
  const parts = raw.split(",").map(Number);
  return parts.length === 3 && parts.every(Number.isFinite) ? parts : null;
};

const specPath = argv.find((arg) => !arg.startsWith("--") && arg.endsWith(".json"));
if (!specPath) {
  console.error("Usage: node preview-shot.mjs <spec.json> [--out <dir>] [--view <id>] [--position x,y,z] [--target x,y,z] [--fov n]");
  process.exit(2);
}
const spec = JSON.parse(fs.readFileSync(specPath, "utf8").replace(/^\uFEFF/, ""));
const format = spec.format || "free";
const cubes = spec.cubes || [];
const outDir = value("--out", spec.out);
if (!outDir) {
  console.error("No --out and no spec.out; a PNG needs a destination directory");
  process.exit(2);
}

const overrides = { ...(spec.preview ?? {}) };
const position = nums(value("--position"));
const target = nums(value("--target"));
const fov = value("--fov");
if (position) overrides.position = position;
if (target) overrides.target = target;
if (fov) overrides.fov = Number(fov);
const shotSpec = { ...spec, preview: overrides };
const camera = previewCamera(shotSpec, format, cubes);

const headers = { "Content-Type": "application/json", Accept: "application/json, text/event-stream" };
let sessionId = null;
async function post(body) {
  const response = await fetch(URL, {
    method: "POST",
    headers: sessionId ? { ...headers, "Mcp-Session-Id": sessionId } : headers,
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(120000),
  });
  return { response, text: await response.text() };
}
function decode(text, contentType = "") {
  if (contentType.includes("text/event-stream")) {
    const events = text.split(/\r?\n/).filter((line) => line.startsWith("data:")).map((line) => line.slice(5).trim()).filter(Boolean);
    if (events.length) return JSON.parse(events[events.length - 1]);
  }
  return JSON.parse(text);
}
async function call(tool, args = {}) {
  const { response, text } = await post({ jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: tool, arguments: args } });
  const payload = decode(text, response.headers.get("content-type") || "");
  if (payload.error) throw new Error(`${tool}: ${JSON.stringify(payload.error)}`);
  if (payload.result?.isError) throw new Error(`${tool}: ${(payload.result.content || []).map((item) => item.text).join("\n")}`);
  return {
    text: (payload.result?.content || []).filter((item) => item.type === "text").map((item) => item.text).join("\n"),
    content: payload.result?.content || [],
  };
}
async function callQuiet(tool, args) {
  try {
    return await call(tool, args);
  } catch (error) {
    console.log(`  ! ${error.message.slice(0, 200)}`);
    return null;
  }
}

const init = await post({
  jsonrpc: "2.0",
  id: 1,
  method: "initialize",
  params: { protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "blockbench-kickstart-preview", version: "1.0.0" } },
});
sessionId = init.response.headers.get("mcp-session-id");
if (!sessionId) {
  console.error(`Blockbench MCP did not issue a session (HTTP ${init.response.status}). Is Blockbench open with the MCP plugin?`);
  process.exit(1);
}
await post({ jsonrpc: "2.0", method: "notifications/initialized" });

// A preview is only meaningful for the project the spec describes. The app may
// have restarted onto a different model; fail fast instead of shooting it.
try {
  const info = await call("get_project_info", {});
  const openName = JSON.parse(info.text)?.project?.name ?? null;
  if (openName && spec.name && openName !== spec.name) {
    console.error(`Blockbench has "${openName}" open, not "${spec.name}".`);
    console.error(`Run: node model-build.mjs ${specPath} --preview   (or open ${spec.name}.bbmodel)`);
    process.exit(2);
  }
} catch {
  console.log("  ! could not read the open project name; shooting anyway");
}

console.log(`camera     position ${camera.position.join(",")}  target ${camera.target.join(",")}  fov ${camera.fov}${flag("--view") ? `  view ${value("--view")}` : ""}`);
const file = await capturePreview(
  { call, callQuiet, step: (label, detail) => console.log(`${label.padEnd(11)}${detail}`) },
  { name: spec.name, outDir, spec: shotSpec, format, cubes, view: value("--view", PREVIEW_VIEW) },
);
process.exit(file ? 0 : 1);
