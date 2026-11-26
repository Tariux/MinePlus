#!/usr/bin/env node
/**
 * Export the open Blockbench project as a .bbmodel plus its PNGs into a
 * Mineplus runtime folder, then run the static pre-flight.
 *
 *   node export-to-mineplus.mjs --out <dir> --name <model-key> [--target virtual|pack-item|pack-block|mob]
 *                               [--meta '<json>' | --meta <file.json>] [--overwrite]
 *                               [--codec project|java_block] [--textures <dir>] [--skip-check]
 *
 * The PNGs are written next to the model because Mineplus never reads the
 * embedded Base64 copy: TextureImageStore looks for <name>.png beside the
 * .bbmodel, then in the models root. Use --textures for raw frame models whose
 * PNG lives in a different folder.
 *
 * Env: BB_MCP_URL (default http://localhost:6960/bb-mcp), BB_MCP_SCRIPT_DIR
 */
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const URL = process.env.BB_MCP_URL || "http://localhost:6960/bb-mcp";
const here = path.dirname(fileURLToPath(import.meta.url));

const argv = process.argv.slice(2);
const flag = (name) => argv.includes(name);
const value = (name, fallback = null) => {
  const index = argv.indexOf(name);
  return index >= 0 && index + 1 < argv.length ? argv[index + 1] : fallback;
};

const outDir = value("--out");
const key = value("--name");
const target = value("--target", "virtual");
const overwrite = flag("--overwrite");
const metaArg = value("--meta");
const codec = value("--codec", "project");
const textureDir = value("--textures", outDir);
const skipCheck = flag("--skip-check");

if (!outDir || !key) {
  console.error("Usage: node export-to-mineplus.mjs --out <dir> --name <model-key> [--target virtual|pack-item|pack-block|mob] [--meta '<json>'|file] [--overwrite] [--codec project|java_block] [--textures <dir>] [--skip-check]");
  process.exit(2);
}
if (!["virtual", "pack-item", "pack-block", "mob"].includes(target)) {
  console.error(`Unknown --target '${target}'. Use virtual, pack-item, pack-block or mob.`);
  process.exit(2);
}

const headers = { "Content-Type": "application/json", Accept: "application/json, text/event-stream" };
async function post(body, sessionId) {
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
    const events = text.split(/\r?\n/).filter((line) => line.startsWith("data:"))
      .map((line) => line.slice(5).trim()).filter(Boolean);
    if (events.length) return JSON.parse(events[events.length - 1]);
  }
  return JSON.parse(text);
}

const init = await post({
  jsonrpc: "2.0", id: 1, method: "initialize",
  params: { protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "mineplus-model-authoring", version: "1.0.0" } },
});
const sessionId = init.response.headers.get("mcp-session-id");
if (!sessionId) {
  console.error(`Blockbench did not issue a session id (HTTP ${init.response.status}). Is the MCP plugin running at ${URL}?`);
  process.exit(1);
}
await post({ jsonrpc: "2.0", method: "notifications/initialized" }, sessionId);

async function call(name, args = {}) {
  const result = await post({ jsonrpc: "2.0", id: 2, method: "tools/call", params: { name, arguments: args } }, sessionId);
  const payload = decode(result.text, result.response.headers.get("content-type") || "");
  if (payload.error) throw new Error(`${name}: ${JSON.stringify(payload.error)}`);
  if (payload.result?.isError) {
    throw new Error((payload.result.content || []).map((item) => item.text).join("\n") || `${name} failed`);
  }
  const text = (payload.result?.content || []).filter((item) => item.type === "text").map((item) => item.text).join("\n");
  if (payload.result?.structuredContent) return payload.result.structuredContent;
  if (text) {
    try {
      return JSON.parse(text);
    } catch {
      return text;
    }
  }
  return null;
}

const normalize = (raw) => {
  if (!raw) return null;
  const normalized = String(raw).replaceAll("\\", "/").trim();
  const segment = normalized.slice(normalized.lastIndexOf("/") + 1);
  return segment.replace(/\.png$/i, "").replace(/\.mcmeta$/i, "").toLowerCase() || null;
};

const capabilities = await call("get_capabilities", { include_tools: false });
console.log(`Blockbench ${capabilities.blockbench?.version ?? "?"} / MCP ${capabilities.plugin?.version ?? "?"}, project format ${capabilities.project?.format_id ?? "?"}`);

fs.mkdirSync(outDir, { recursive: true });
fs.mkdirSync(textureDir, { recursive: true });
const extension = codec === "project" ? "bbmodel" : "json";
const modelPath = path.join(outDir, `${key}.${extension}`);
if (fs.existsSync(modelPath) && !overwrite) {
  console.error(`${modelPath} exists. Re-run with --overwrite to replace it.`);
  process.exit(1);
}

const exported = await call("export_model", {
  codec_id: codec,
  path: modelPath,
  overwrite: true,
  max_content_length: 0,
});
if (typeof exported === "string" && /error|fail/i.test(exported) && !fs.existsSync(modelPath)) {
  console.error(exported);
  process.exit(1);
}
console.log(`Wrote ${modelPath}`);

const textures = await call("risky_eval", {
  code: "Texture.all.map(function(t){return {name:t.name,path:t.path,relative_path:t.relative_path,source:t.source,width:t.width,height:t.height}})",
});
const written = [];
for (const texture of Array.isArray(textures) ? textures : []) {
  const name = normalize(texture.path) ?? normalize(texture.relative_path) ?? normalize(texture.name);
  if (!name) {
    console.warn(`  ! texture '${texture.name ?? "?"}' has no path/name; Mineplus cannot resolve it.`);
    continue;
  }
  if (!texture.source || !texture.source.startsWith("data:image/")) {
    console.warn(`  ! texture '${texture.name ?? name}' has no embedded bitmap; export its PNG manually as ${name}.png`);
    continue;
  }
  const base64 = texture.source.slice(texture.source.indexOf(",") + 1);
  const pngPath = path.join(textureDir, `${name}.png`);
  fs.writeFileSync(pngPath, Buffer.from(base64, "base64"));
  written.push(`${name}.png`);
  console.log(`  + ${pngPath}`);
}
if (!written.length) console.log("  (no embedded textures to write)");

if (metaArg) {
  let metaText = metaArg;
  if (fs.existsSync(metaArg)) metaText = fs.readFileSync(metaArg, "utf8");
  try {
    JSON.parse(metaText);
  } catch (error) {
    console.error(`--meta is not valid JSON: ${error.message}`);
    process.exit(1);
  }
  const metaPath = path.join(outDir, `${key}.meta.json`);
  fs.writeFileSync(metaPath, metaText.endsWith("\n") ? metaText : `${metaText}\n`);
  console.log(`  + ${metaPath}`);
}

if (skipCheck || codec !== "project") {
  console.log(codec === "project" ? "Pre-flight skipped." : `Raw ${codec} export: pre-flight applies to .bbmodel projects only.`);
  process.exit(0);
}

const checker = path.join(here, "check-bbmodel.mjs");
const check = spawnSync(process.execPath, [checker, modelPath, "--target", target], { stdio: "inherit" });
console.log(`Pre-flight exit ${check.status ?? "?"} (0 pass, 1 warnings, 2 errors)`);
process.exit(check.status ?? 0);
