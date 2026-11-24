#!/usr/bin/env node
/* Mine Plus MCP server (devtools) — zero dependencies, Node >= 18.
 *
 * Exposes the Mine Plus model pipeline to any MCP client (Claude Desktop,
 * Cursor, VS Code, ...). Detects Mine Plus templates by reading the
 * top-level "mineplus" block the Blockbench plugin stamps, and audits model
 * files against the same budgets the server plugin enforces at pack compile.
 *
 * Tools:
 *   mineplus_detect_template   read a .mpmodel/.bbmodel/.meta.json and report
 *                              whether it is a Mine Plus template
 *   mineplus_audit_model       budgets, display ranges, 22.5° snap risk,
 *                              animations, textures
 *   mineplus_list_models       list model files under a directory, with
 *                              template id when stamped
 *   mineplus_format_limits     return the format limits (machine-readable)
 *
 * Register (any MCP client config):
 *   { "mcpServers": { "mineplus": {
 *       "command": "node",
 *       "args": ["/path/to/devtools/mcp/mineplus-mcp-server.js"] } } }
 *
 * Protocol: JSON-RPC 2.0 over stdio, MCP "stdio" transport. Implemented by
 * hand so the repo ships no npm dependency for a dev tool.
 */
'use strict';

const fs = require('fs');
const path = require('path');

const SERVER_INFO = { name: 'mineplus-devtools', version: '1.0.0' };
const BUDGETS = {
  cubes: 512, bones: 128, animations: 64,
  textureEdge: 2048, sourceBytes: 2 * 1024 * 1024
};
const LIMITS = { rotation: 180, translation: 80, scaleMax: 100, scaleMin: 0.01 };
const ANGLE_STEP = 22.5, ANGLE_MAX = 45;
const DISPLAY_CONTEXTS = [
  'thirdperson_righthand', 'thirdperson_lefthand',
  'firstperson_righthand', 'firstperson_lefthand',
  'head', 'gui', 'ground', 'fixed'
];
const MODEL_EXTS = ['.mpmodel', '.bbmodel', '.json'];

/* ------------------------------------------------------------- analysis */

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function extractTemplate(root) {
  if (!root || typeof root !== 'object') return { detected: false };
  const stamp = root.mineplus || (root.metadata && root.metadata.mineplus);
  if (!stamp || typeof stamp !== 'object') return { detected: false };
  return {
    detected: true,
    format: stamp.format ?? 1,
    plugin: stamp.plugin ?? null,
    template: stamp.template ?? null,
    kind: stamp.kind ?? null,
    title: stamp.title ?? null
  };
}

function countCubes(node, acc) {
  if (!node || typeof node !== 'object') return;
  if (Array.isArray(node.cubes)) acc.cubes += node.cubes.length;
  if (Array.isArray(node.elements)) {
    acc.cubes += node.elements.filter((e) => Array.isArray(e.from) && Array.isArray(e.to)).length;
  }
  for (const child of node.children || []) countCubes(child, acc);
}

function snapRisk(rotation) {
  if (!Array.isArray(rotation)) return null;
  const axes = rotation.filter((a) => a !== 0).length;
  if (axes > 1) return 'multi-axis rotation';
  if (rotation.some((a) => Math.abs(a) > ANGLE_MAX)) return 'beyond ±45°';
  if (rotation.some((a) => a !== 0 && (a / ANGLE_STEP) % 1 !== 0)) {
    return 'off the 22.5° grid';
  }
  return null;
}

function auditModel(root, file) {
  const problems = [];
  const notes = [];
  const cubes = { cubes: 0 };
  if (Array.isArray(root.elements)) {
    countCubes({ elements: root.elements, children: root.outliner }, cubes);
  } else if (root.bones) {
    for (const bone of Object.values(root.bones)) countCubes(bone, cubes);
  }
  const display = root.display || {};
  const contexts = DISPLAY_CONTEXTS.filter((c) => display[c]);
  for (const [context, entry] of Object.entries(display)) {
    for (const field of ['rotation', 'translation', 'scale']) {
      const v = entry && entry[field];
      if (!v) continue;
      v.forEach((c, i) => {
        if (typeof c !== 'number' || Number.isNaN(c)) {
          problems.push(`display.${context}.${field}[${i}] is NaN`);
        } else if (field === 'rotation' && Math.abs(c) > LIMITS.rotation) {
          problems.push(`display.${context}.rotation[${i}] = ${c} exceeds ±180`);
        } else if (field === 'translation' && Math.abs(c) > LIMITS.translation) {
          problems.push(`display.${context}.translation[${i}] = ${c} exceeds ±80`);
        } else if (field === 'scale'
            && (Math.abs(c) > LIMITS.scaleMax || Math.abs(c) < LIMITS.scaleMin)) {
          problems.push(`display.${context}.scale[${i}] = ${c} outside 0.01–100`);
        }
      });
    }
  }
  let snapCases = 0;
  for (const el of root.elements || []) {
    if (el.type === 'cube' && snapRisk(el.rotation)) snapCases++;
  }
  if (snapCases) {
    notes.push(`${snapCases} cube(s) with rotations that get snapped to 22.5° `
        + `for clients below 1.21.6 (ModelGeometryGuard)`);
  }
  const textures = root.textures || [];
  for (const tex of textures) {
    if ((tex.width || 0) > BUDGETS.textureEdge || (tex.height || 0) > BUDGETS.textureEdge) {
      problems.push(`texture "${tex.name || tex.id}" exceeds ${BUDGETS.textureEdge}px edge`);
    }
  }
  const animations = root.animations ? root.animations.length
      : (root.animations_by_name ? Object.keys(root.animations_by_name).length : 0);
  if (animations > BUDGETS.animations) {
    problems.push(`${animations} animations exceed the soft budget of ${BUDGETS.animations}`);
  }
  let size = 0;
  try { size = fs.statSync(file).size; } catch { /* remote path */ }
  if (size > BUDGETS.sourceBytes) {
    problems.push(`file is ${size} bytes; the soft source budget is ${BUDGETS.sourceBytes}`);
  }
  const template = extractTemplate(root);
  if (!template.detected) {
    notes.push('not stamped as a Mine Plus template (no top-level "mineplus" block)');
  }
  return {
    file: path.basename(file),
    template,
    counts: { cubes: cubes.cubes, displayContexts: contexts.length, animations, textures: textures.length },
    problems,
    notes
  };
}

function looksLikeModel(file) {
  return MODEL_EXTS.includes(path.extname(file).toLowerCase());
}

/* ------------------------------------------------------------- protocol */

function jsonRpcResult(id, result) {
  return JSON.stringify({ jsonrpc: '2.0', id, result });
}

function jsonRpcError(id, code, message) {
  return JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } });
}

function fileArg(args) {
  const file = args && (args.file || args.path);
  if (!file || typeof file !== 'string') {
    throw new Error('missing required argument: file (absolute or repo-relative path)');
  }
  return file;
}

const TOOLS = {
  mineplus_detect_template(args) {
    const file = fileArg(args);
    const root = readJson(file);
    const template = extractTemplate(root);
    return {
      content: [{
        type: 'text',
        text: JSON.stringify(template.detected
            ? { ...template, file, summary: `Mine Plus template "${template.template}"`
                + ` (${template.kind}), format v${template.format}, by ${template.plugin}` }
            : { detected: false, file,
                summary: 'not a Mine Plus template (no "mineplus" block)' }),
      }]
    };
  },

  mineplus_audit_model(args) {
    const file = fileArg(args);
    const root = readJson(file);
    const audit = auditModel(root, file);
    return {
      content: [{
        type: 'text',
        text: JSON.stringify({
          ...audit,
          verdict: audit.problems.length
              ? `${audit.problems.length} problem(s)`
              : 'fully matches the Mine Plus format'
        }),
      }]
    };
  },

  mineplus_list_models(args) {
    const dir = fileArg(args);
    const out = [];
    const walk = (d) => {
      for (const entry of fs.readdirSync(d, { withFileTypes: true })) {
        const full = path.join(d, entry.name);
        if (entry.isDirectory()) { walk(full); continue; }
        if (!looksLikeModel(entry.name)) continue;
        try {
          const root = readJson(full);
          const template = extractTemplate(root);
          out.push({ file: path.relative(dir, full), template: template.template ?? null, detected: template.detected });
        } catch (e) {
          out.push({ file: path.relative(dir, full), error: String(e.message || e) });
        }
      }
    };
    walk(dir);
    return { content: [{ type: 'text', text: JSON.stringify(out) }] };
  },

  mineplus_format_limits() {
    return {
      content: [{
        type: 'text',
        text: JSON.stringify({
          display: LIMITS, displayContexts: DISPLAY_CONTEXTS,
          budgets: BUDGETS, angleStep: ANGLE_STEP, angleMax: ANGLE_MAX,
          angleRule: 'clients below 1.21.6 snap element rotations to 22.5° steps within ±45°',
          templateSchema: {
            mineplus: { format: 1, plugin: 'mineplus_generic_display',
              template: 'mineplus:<category>/<name>', kind: 'item|block|mob|generic',
              title: 'optional human title' }
          }
        }),
      }]
    };
  }
};

const TOOL_DEFS = [
  { name: 'mineplus_detect_template',
    description: 'Read a .mpmodel/.bbmodel file and report whether it is detected as a Mine Plus template (top-level "mineplus" block).',
    inputSchema: { type: 'object', required: ['file'],
      properties: { file: { type: 'string', description: 'Path to the model file' } } } },
  { name: 'mineplus_audit_model',
    description: 'Audit a model against Mine Plus format limits: display ranges, cube/bone/animation budgets, 22.5° snap risk, texture sizes, file size.',
    inputSchema: { type: 'object', required: ['file'],
      properties: { file: { type: 'string' } } } },
  { name: 'mineplus_list_models',
    description: 'List model files under a directory with their Mine Plus template ids.',
    inputSchema: { type: 'object', required: ['dir'],
      properties: { dir: { type: 'string' } } } },
  { name: 'mineplus_format_limits',
    description: 'Return the Mine Plus model format limits and template schema as JSON.',
    inputSchema: { type: 'object', properties: {} } }
];

function dispatch(request) {
  const { id, method, params } = request;
  if (method === 'initialize') {
    return jsonRpcResult(id, {
      protocolVersion: (params && params.protocolVersion) || '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: SERVER_INFO
    });
  }
  if (method === 'notifications/initialized') return null;
  if (method === 'ping') return jsonRpcResult(id, {});
  if (method === 'tools/list') {
    return jsonRpcResult(id, { tools: TOOL_DEFS });
  }
  if (method === 'tools/call') {
    const name = params && params.name;
    const tool = TOOLS[name];
    if (!tool) return jsonRpcError(id, -32602, `unknown tool: ${name}`);
    try {
      return jsonRpcResult(id, tool(params.arguments));
    } catch (e) {
      return jsonRpcResult(id, {
        content: [{ type: 'text', text: JSON.stringify({ error: String(e.message || e) }) }],
        isError: true
      });
    }
  }
  return jsonRpcError(id, -32601, `method not found: ${method}`);
}

function main() {
  let buffer = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (chunk) => {
    buffer += chunk;
    let index;
    while ((index = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, index).trim();
      buffer = buffer.slice(index + 1);
      if (!line) continue;
      let response = null;
      try {
        response = dispatch(JSON.parse(line));
      } catch (e) {
        response = jsonRpcError(null, -32700, `parse error: ${e.message}`);
      }
      if (response) process.stdout.write(response + '\n');
    }
  });
  process.stdin.resume();
}

if (require.main === module) main();
module.exports = { dispatch, extractTemplate, auditModel, TOOLS, TOOL_DEFS };
