// Mineplus Asset Lab — live settings bridge.
//
// Resolves the settings document the *running server* actually uses (rather than
// dev-tool hardcoded defaults) and exposes it to the app as JSON.
//
// Source order (first existing wins, reported as `source`; the HTTP envelope uses
// the fixed contract fields `found/path/mtime/text/sections`):
//   0. --settings <path> override                      (cli-override)
//   1. <repo>/../server-spigot/plugins/Mineplus/settings.mp.yml
//   2. <repo>/../server-spigotss/plugins/Mineplus/settings.mp.yml
//   3. <repo>/plugins/Mineplus/settings.mp.yml         (repo-local)
//   4. the template the plugin generates itself — extracted from the Java text block
//      inside src/main/java/com/mineplus/config/ConfigManager.java  (generated-defaults)
//   5. SETTINGS_YAML_FALLBACK below                                    (hardcoded-fallback)
//
// 4/5 are NOT a deployed file: the snapshot reports `found:false` with an empty
// `sections` map and carries the template under `generatedDefaults` instead, so
// the app can show its own defaults labelled as such.
//
// Zero npm dependencies: a tiny indentation-based YAML *subset* reader lives here
// (2-space nesting, `KEY: value` scalars, `#` comments; no anchors, no flow syntax,
// no sequences) — exactly the shape of settings.mp.yml.
//
// Reading is always async + cached: `current()` never blocks the request handler.

import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';

// ---------------------------------------------------------------- section spec

/** Sections the renderer consumes, in display order, with the keys the UI expects. */
export const SETTINGS_SECTIONS = [
  {
    yamlKey: 'VIRTUAL_RENDERING',
    key: 'virtualRendering',
    label: 'Virtual rendering (bbmodel -> BlockDisplay)',
    expects: ['collisionMode', 'collisionEpsilon', 'collisionNonAirPolicy',
      'rotationSnap', 'perFaceRendering', 'originMode', 'textureMode'],
  },
  {
    yamlKey: 'TEXEL_BAKING',
    key: 'texelBaking',
    label: 'Texel surface baking',
    expects: ['enabled', 'mode', 'detail', 'maxPlatesPerFace', 'maxPlatesPerInstance', 'maxGridEdge'],
  },
  {
    yamlKey: 'ANIMATION',
    key: 'animation',
    label: 'Animation engine',
    expects: ['enabled', 'tickIntervalTicks', 'interpolationTicks', 'autoplay'],
  },
  {
    yamlKey: 'PACK',
    key: 'pack',
    label: 'Resource pack system',
    expects: ['enabled', 'lighting', 'delivery.mode'],
  },
  {
    yamlKey: 'CUSTOM_MOBS',
    key: 'customMobs',
    label: 'Custom mobs (rigs)',
    expects: ['enabled', 'rendering', 'renderIntervalTicks', 'renderInterpolationTicks',
      'renderDistance', 'renderNearDistance', 'shadows'],
  },
  {
    yamlKey: 'MOB_BEHAVIOR',
    key: 'mobBehavior',
    label: 'Mob behavior engine',
    expects: ['enabled', 'intervalTicks', 'staggerBuckets', 'scanRadiusChunks', 'budgetMillisPerRun'],
  },
  {
    yamlKey: 'BALLISTICS',
    key: 'ballistics',
    label: 'Ballistics simulation',
    expects: ['enabled', 'maxActiveRounds', 'tracers', 'intervalTicks'],
  },
  {
    yamlKey: 'PARTICLE_FX',
    key: 'particleFx',
    label: 'Particle budgets',
    expects: ['criticalPerTick', 'ambientPerTick', 'maxPerEmit', 'radius'],
  },
];

/**
 * Literal mirror of the ConfigManager template for the sections above, used only
 * when neither a settings file nor the Java text block can be read. Same reader
 * parses it, so `sections` and `sectionRaw` stay consistent with a real file.
 */
const SETTINGS_YAML_FALLBACK = `# Asset Lab fallback defaults (hardcoded mirror of the ConfigManager template).
VIRTUAL_RENDERING:
  # Collision proxy occupancy grid: GEOMETRY | SURFACE | AABB
  COLLISION_MODE: GEOMETRY
  # Cell shrink epsilon for geometry contact tests.
  COLLISION_EPSILON: 0.001
  # Behavior when a collision cell is not air: SKIP | STRICT
  COLLISION_NON_AIR_POLICY: SKIP
  # Snap placement rotations to the 24 grid orientations.
  ROTATION_SNAP: true
  # Max deviation from the nearest grid orientation before a warning is logged (degrees).
  ROTATION_SNAP_THRESHOLD_DEGREES: 5
  # Emit per-face material plates for mixed-material cubes.
  PER_FACE_RENDERING: true
  # Anchor convention: AUTO (detect from model format and extent) | CENTER | GRID
  ORIGIN_MODE: AUTO

TEXEL_BAKING:
  # Global enable (false = rendering pipeline identical to before).
  ENABLED: true
  # AUTO: only FULL-strategy faces with a resolvable PNG. ON: every resolvable face. OFF: never.
  MODE: AUTO
  # Sampling per texel: FACE | SUPERSAMPLE_2X2 | SUPERSAMPLE_4X4.
  DETAIL: FACE
  # Merged-plate ceiling per face; above it the face falls back.
  MAX_PLATES_PER_FACE: 96
  # Whole-instance plate budget; faces overflow in emission order.
  MAX_PLATES_PER_INSTANCE: 150
  # Hard grid edge cap per face (max texels per axis pre-merge).
  MAX_GRID_EDGE: 64

ANIMATION:
  # Master switch for the animation runtime.
  ENABLED: true
  # Server ticks between transform pushes (1 = every tick).
  TICK_INTERVAL_TICKS: 1
  # Client interpolation window in ticks; 0 = match TICK_INTERVAL_TICKS.
  INTERPOLATION_TICKS: 1
  # Auto-start animations declared by levels ("animations") or model meta ("autoplay").
  AUTOPLAY: true

PACK:
  # Master switch (false = nothing compiled, served, or pushed).
  ENABLED: true
  DELIVERY:
    # DISABLED | LOCAL (default) | STATIC_URL.
    MODE: LOCAL
    LOCAL_HOST: 0.0.0.0
    LOCAL_PORT: 8163
    PUBLIC_URL: ''
    STATIC_URL: ''
    PROMPT_MESSAGE: ''
    PROMPT_DELAY_TICKS: 60
  CACHE:
    MAX_ARTIFACTS: 8
  PACK_FORMAT_OVERRIDE: 0
  APPEARANCE_MODE: AUTO
  VALIDATE_ASSETS: true
  # AUTO | NATURAL | EMISSIVE | FULLBRIGHT.
  LIGHTING: AUTO

CUSTOM_MOBS:
  # Master switch; false makes registration and spawning no-ops.
  ENABLED: true
  # Build per-part visual rigs (needs the pack subsystem).
  RENDERING: true
  # Server ticks between rig pose pushes.
  RENDER_INTERVAL_TICKS: 1
  # Ticks a display eases its transformation over.
  RENDER_INTERPOLATION_TICKS: 1
  # Blocks beyond which a rig is released entirely.
  RENDER_DISTANCE: 48
  # Blocks inside which a rig is posed at full rate.
  RENDER_NEAR_DISTANCE: 20
  MAX_RIGGED_PER_WORLD: 48
  # Draw a soft ground shadow plate under a rigged mob.
  SHADOWS: true
  REMOVE_UNKNOWN_ON_LOAD: true

MOB_BEHAVIOR:
  # Master switch (false = registered profiles stay frozen).
  ENABLED: true
  # Server ticks between engine runs.
  INTERVAL_TICKS: 3
  # Entity buckets: each entity is evaluated every N-th run.
  STAGGER_BUCKETS: 4
  # Chunk radius around each online player considered active.
  SCAN_RADIUS_CHUNKS: 2
  # Soft processing budget per run in milliseconds.
  BUDGET_MILLIS_PER_RUN: 5
  # Hard ceiling of world block changes per run.
  MAX_BLOCK_EDITS_PER_RUN: 96
  # Hard ceiling of entities summoned per run.
  MAX_ENTITY_SPAWNS_PER_RUN: 8
  # Cap on scenario-claimed entities advanced per run even with no player near.
  MAX_SUSTAINED_PER_RUN: 64
  # Cap on multi-stage pipeline scenarios advanced per run.
  MAX_PIPELINES_PER_RUN: 64

BALLISTICS:
  # Master switch; false makes every shot a no-op.
  ENABLED: true
  # Rounds simulated concurrently.
  MAX_ACTIVE_ROUNDS: 128
  # Draw the dashed tracer along each round's path.
  TRACERS: true
  # Server ticks between simulation steps (1 = every tick).
  INTERVAL_TICKS: 1

PARTICLE_FX:
  # Confirmation effects (muzzle flash, impact) budget per tick.
  CRITICAL_PER_TICK: 96
  # Decorative effects (tracers, smoke, rings) budget per tick.
  AMBIENT_PER_TICK: 128
  # Ceiling a single emission request may spend.
  MAX_PER_EMIT: 32
  # How far a particle packet reaches, in blocks.
  RADIUS: 48
`;

// ---------------------------------------------------------------- key naming

/** `MAX_PLATES_PER_FACE` -> `maxPlatesPerFace`; an already-camel key is preserved. */
export function toCamelKey(key) {
  const segments = String(key).trim().split('_').filter(Boolean);
  return segments
    .map((segment, i) => {
      const shouty = segment === segment.toUpperCase();
      const word = shouty ? segment.toLowerCase() : segment;
      return i === 0
        ? word.charAt(0).toLowerCase() + word.slice(1)
        : word.charAt(0).toUpperCase() + word.slice(1);
    })
    .join('');
}

// ---------------------------------------------------------------- YAML subset

function parseScalar(raw) {
  const value = raw.trim();
  if (value === '') return null;
  // Inline list: [a, b, 'c'] -> array of parsed scalars (quotes tolerated, nested lists not used).
  if (value.startsWith('[') && value.endsWith(']')) {
    const inner = value.slice(1, -1).trim();
    if (!inner) return [];
    return inner.split(',').map(item => parseScalar(item.trim()));
  }
  const quoted = /^(['"`])/.exec(value);
  if (quoted && value.length > 1 && value.endsWith(quoted[1])) {
    return value.slice(1, -1);
  }
  if (value === 'true') return true;
  if (value === 'false') return false;
  if (value === 'null' || value === '~') return null;
  if (/^-?\d+$/.test(value)) return Number(value);
  if (/^-?\d*\.\d+(?:[eE][-+]?\d+)?$/.test(value)) return Number(value);
  if (/^-?\d+(?:\.\d+)?[eE][-+]?\d+$/.test(value)) return Number(value);
  return value;
}

/** Drop a trailing ` # comment`, but never inside a quoted scalar. */
function stripInlineComment(rest) {
  const first = rest.trimStart().charAt(0);
  if (first === '"' || first === "'" || first === '`') {
    const close = rest.indexOf(first, rest.indexOf(first) + 1);
    if (close > 0) {
      const tail = rest.slice(close + 1);
      return /^\s*#/.test(tail) ? rest.slice(0, close + 1) : rest;
    }
    return rest;
  }
  const hash = rest.indexOf(' #');
  return hash >= 0 ? rest.slice(0, hash) : rest;
}

function leadingIndent(line) {
  return /^[ \t]*/.exec(line)[0].length;
}

/**
 * Indentation-based YAML subset reader.
 * Returns `{ tree, sectionRaw }` where `tree` is the whole document camelCased and
 * `sectionRaw` maps each top-level section name to its original text block.
 */
export function parseYamlSubset(text) {
  const tree = {};
  const stack = [{ indent: -1, node: tree }];
  const sectionRaw = {};
  let currentTop = null;      // raw section name currently open
  let pendingComments = [];   // top-level comments seen before the next section

  for (const line of String(text).replace(/\r\n?/g, '\n').split('\n')) {
    const trimmed = line.trim();

    if (trimmed === '') {
      if (currentTop) sectionRaw[currentTop].push(line);
      continue;
    }

    const indent = leadingIndent(line);
    const isComment = trimmed.startsWith('#');

    // A top-level comment closes the current section and belongs to the next one.
    if (indent === 0 && isComment) {
      currentTop = null;
      pendingComments.push(line);
      continue;
    }
    if (isComment) {
      if (currentTop) sectionRaw[currentTop].push(line);
      continue;
    }
    // Sequences are not part of this subset: keep them out of the tree, show the line.
    if (trimmed.startsWith('- ') || trimmed === '-') {
      if (currentTop) sectionRaw[currentTop].push(line);
      continue;
    }

    const match = /^([^:]+):\s*(.*)$/.exec(trimmed);
    if (!match) {
      if (currentTop) sectionRaw[currentTop].push(line);
      continue;
    }
    const rawKey = match[1].trim().replace(/^['"]|['"]$/g, '');
    const rest = stripInlineComment(match[2]);
    const key = toCamelKey(rawKey);

    if (indent === 0) {
      // Open a new top-level entry; keep its raw block for display.
      while (stack.length > 1) stack.pop();
      currentTop = rawKey;
      sectionRaw[currentTop] = [...pendingComments, line];
      pendingComments = [];
      if (rest === '') {
        const child = {};
        stack[stack.length - 1].node[key] = child;
        stack.push({ indent, node: child });
      } else {
        stack[stack.length - 1].node[key] = parseScalar(rest);
        currentTop = null; // a top-level scalar has no body
      }
      continue;
    }

    while (stack.length > 1 && indent <= stack[stack.length - 1].indent) stack.pop();
    const parent = stack[stack.length - 1].node;
    if (rest === '') {
      const child = {};
      parent[key] = child;
      stack.push({ indent, node: child });
    } else {
      parent[key] = parseScalar(rest);
    }
    if (currentTop && indent > 0) sectionRaw[currentTop].push(line);
  }

  return { tree, sectionRaw };
}

/** Raw text block of one top-level section, given the parser output. */
function rawBlock(sectionRaw, yamlKey) {
  const lines = sectionRaw[yamlKey];
  return Array.isArray(lines) ? lines.join('\n').replace(/\n+$/, '') : '';
}

function pick(tree, dottedKey) {
  let node = tree;
  for (const part of dottedKey.split('.')) {
    if (!node || typeof node !== 'object') return undefined;
    node = node[part];
  }
  return node;
}

/** Deep-copy plain-object sections so the JSON response is stable and isolated. */
function snapshotSections(tree) {
  const sections = {};
  const missing = {};
  for (const spec of SETTINGS_SECTIONS) {
    const value = tree[spec.key];
    sections[spec.key] = value && typeof value === 'object' && !Array.isArray(value) ? value : null;
    missing[spec.key] = spec.expects.filter(name => {
      const found = pick(sections[spec.key] ?? {}, name);
      return found === undefined;
    });
  }
  return { sections, missing };
}

/**
 * The contract `sections` map: the same parsed content keyed by the exact
 * UPPER_SNAKE YAML section names (`VIRTUAL_RENDERING`, `TEXEL_BAKING`, ...).
 * Inner keys stay camelCase — that is the daemon/op convention throughout.
 */
function contractSections(tree) {
  const out = {};
  for (const spec of SETTINGS_SECTIONS) {
    const value = tree[spec.key];
    out[spec.yamlKey] = value && typeof value === 'object' && !Array.isArray(value) ? value : null;
  }
  return out;
}

// ---------------------------------------------------------------- ConfigManager

/** Java text-block escapes that can realistically appear in the template. */
function unescapeJavaTextBlock(body) {
  return body.replace(/\\([nrtbf"\\'])/g, (all, ch) => {
    switch (ch) {
      case 'n': return '\n';
      case 't': return '\t';
      case 'r': return '\r';
      case 'b': return '\b';
      case 'f': return '\f';
      default: return ch;
    }
  });
}

/**
 * Pull the generated settings.mp.yml template out of ConfigManager's text block.
 * Returns null when the source no longer matches the expected shape — the caller
 * then falls back to the literal defaults.
 */
export function extractDefaultTemplate(javaSource) {
  const text = String(javaSource);
  const anchor = /String\s+defaultConfigContent\s*=\s*"""/.exec(text);
  if (!anchor) return null;
  const start = anchor.index + anchor[0].length;
  const end = text.indexOf('"""', start);
  if (end < 0) return null;
  const body = unescapeJavaTextBlock(text.slice(start, end));
  const lines = body.replace(/\r\n?/g, '\n').split('\n');
  let margin = Infinity;
  for (const line of lines) {
    if (line.trim() === '') continue;
    margin = Math.min(margin, leadingIndent(line));
  }
  if (!Number.isFinite(margin)) return null;
  return lines.map(l => (l.trim() === '' ? '' : l.slice(margin))).join('\n');
}

// ---------------------------------------------------------------- bridge

const RAW_LIMIT = 240000;

/**
 * Create the settings bridge for one repo.
 * `onChange(snapshot)` fires after each successful refresh (debounced by the watcher).
 */
export function createSettingsBridge({ repoRoot, overridePath = null, log = () => {}, onChange = () => {} }) {
  const candidates = [];
  if (overridePath) {
    candidates.push({ id: 'cli-override', label: '--settings override', path: path.resolve(overridePath) });
  }
  candidates.push(
    {
      id: 'live-server-spigot',
      label: 'deployed server (server-spigot)',
      path: path.resolve(repoRoot, '..', 'server-spigot', 'plugins', 'Mineplus', 'settings.mp.yml'),
    },
    {
      id: 'live-server',
      label: 'live deployed server (server-spigotss)',
      path: path.resolve(repoRoot, '..', 'server-spigotss', 'plugins', 'Mineplus', 'settings.mp.yml'),
    },
    {
      id: 'repo-local',
      label: 'repo-local plugin data folder',
      path: path.join(repoRoot, 'plugins', 'Mineplus', 'settings.mp.yml'),
    },
  );
  const defaultsSourcePath = path.join(repoRoot, 'src', 'main', 'java', 'com', 'mineplus', 'config', 'ConfigManager.java');

  let cached = null;
  let inFlight = null;
  let watchers = [];

  /** Shared envelope fields for a snapshot built from a YAML text + parsed tree. */
  function envelope({ tree, sectionRaw, text, mtimeMs }) {
    const { sections, missing } = snapshotSections(tree);
    return {
      sectionsCamel: sections,
      missing,
      document: tree,
      raw: text.length > RAW_LIMIT ? text.slice(0, RAW_LIMIT) : text,
      rawTruncated: text.length > RAW_LIMIT,
      mtimeMs,
      refreshedAt: new Date().toISOString(),
    };
  }

  function fallbackSnapshot(reason) {
    const { tree, sectionRaw } = parseYamlSubset(SETTINGS_YAML_FALLBACK);
    return {
      ok: true,
      // Contract envelope — no deployed file exists; the app shows its own defaults.
      found: false,
      path: null,
      mtime: null,
      text: null,
      sections: {},
      checkedAt: new Date().toISOString(),
      source: 'hardcoded-fallback',
      sourceLabel: 'hardcoded mirror of the ConfigManager template',
      sourcePath: null,
      detail: reason,
      candidates: candidates.map(c => ({ ...c, exists: fs.existsSync(c.path), used: false })),
      defaultsSourcePath,
      generatedDefaults: {
        available: true,
        source: 'hardcoded-fallback',
        path: null,
        text: SETTINGS_YAML_FALLBACK.trimEnd(),
        sections: contractSections(tree),
      },
      sectionRaw: mapSectionRaw(sectionRaw),
      pending: reason ? true : undefined,
      ...envelope({ tree, sectionRaw, text: SETTINGS_YAML_FALLBACK, mtimeMs: null }),
    };
  }

  function mapSectionRaw(sectionRaw) {
    const out = {};
    for (const spec of SETTINGS_SECTIONS) out[spec.key] = rawBlock(sectionRaw, spec.yamlKey);
    return out;
  }

  async function readSnapshot() {
    // 0-3: a real settings file (CLI override, then deployed servers, then repo-local).
    for (const candidate of candidates) {
      let text = null;
      let mtimeMs = null;
      try {
        const stat = await fsp.stat(candidate.path);
        if (!stat.isFile() || stat.size === 0) continue;
        mtimeMs = stat.mtimeMs;
        text = await fsp.readFile(candidate.path, 'utf8');
      } catch {
        continue; // absent or unreadable — next source
      }
      if (!text || !text.trim()) continue;
      const { tree, sectionRaw } = parseYamlSubset(text);
      if (!SETTINGS_SECTIONS.some(spec => tree[spec.key] && typeof tree[spec.key] === 'object')) {
        return fallbackSnapshot('settings file parsed but contained none of the renderer sections');
      }
      return {
        ok: true,
        // Contract envelope.
        found: true,
        path: candidate.path,
        mtime: Math.round(mtimeMs),
        text,
        sections: contractSections(tree),
        checkedAt: new Date().toISOString(),
        source: candidate.id,
        sourceLabel: candidate.label,
        sourcePath: candidate.path,
        detail: null,
        candidates: candidates.map(c => ({
          ...c, exists: fs.existsSync(c.path), used: c.id === candidate.id,
        })),
        defaultsSourcePath,
        generatedDefaults: null,
        sectionRaw: mapSectionRaw(sectionRaw),
        ...envelope({ tree, sectionRaw, text, mtimeMs }),
      };
    }

    // 4: no deployed file — expose the template the plugin itself generates,
    //    clearly labelled `generated-defaults` (found stays false).
    try {
      const javaSource = await fsp.readFile(defaultsSourcePath, 'utf8');
      const template = extractDefaultTemplate(javaSource);
      if (!template) throw new Error('no defaultConfigContent text block found');
      const { tree, sectionRaw } = parseYamlSubset(template);
      return {
        ok: true,
        found: false,
        path: null,
        mtime: null,
        text: null,
        sections: {},
        checkedAt: new Date().toISOString(),
        source: 'generated-defaults',
        sourceLabel: 'template generated by ConfigManager (no settings file on disk)',
        sourcePath: defaultsSourcePath,
        detail: null,
        candidates: candidates.map(c => ({ ...c, exists: fs.existsSync(c.path), used: false })),
        defaultsSourcePath,
        generatedDefaults: {
          available: true,
          source: 'generated-defaults',
          path: defaultsSourcePath,
          text: template,
          sections: contractSections(tree),
        },
        sectionRaw: mapSectionRaw(sectionRaw),
        ...envelope({ tree, sectionRaw, text: template, mtimeMs: null }),
      };
    } catch (e) {
      // 5: last resort.
      return fallbackSnapshot('ConfigManager template extraction failed: ' + e.message);
    }
  }

  function refresh() {
    if (inFlight) return inFlight;
    inFlight = readSnapshot()
      .then(snapshot => {
        cached = snapshot;
        inFlight = null;
        onChange(snapshot);
        return snapshot;
      })
      .catch(e => {
        inFlight = null;
        log('settings refresh failed:', e.message);
        return cached ?? fallbackSnapshot('settings refresh failed: ' + e.message);
      });
    return inFlight;
  }

  /** Never blocks: returns the cached snapshot and schedules a refresh if none yet. */
  function current() {
    if (cached) return cached;
    refresh();
    return fallbackSnapshot('first read pending');
  }

  function startWatching() {
    if (watchers.length > 0) return;
    let timer = null;
    const debounced = () => {
      clearTimeout(timer);
      timer = setTimeout(() => {
        refresh().then(snap => {
          log('settings refreshed from', snap.source, snap.sourcePath ?? '(built-in defaults)');
        });
      }, 250);
    };
    // watchFile polls, so it also notices a file that does not exist yet
    // (a server started for the first time creates its settings.mp.yml).
    // Node fires the listener once at registration: remember the signature then
    // and only react to a real change afterwards.
    const seen = new Map();
    const onChange = (file) => (curr, prev) => {
      const sig = curr.mtimeMs + ':' + curr.size;
      const before = seen.get(file);
      seen.set(file, sig);
      if (before === undefined) return;   // registration snapshot, not a change
      if (before === sig && prev.mtimeMs === curr.mtimeMs) return;
      debounced();
    };
    for (const candidate of candidates) {
      try {
        const listener = onChange(candidate.path);
        fs.watchFile(candidate.path, { persistent: true, interval: 2000 }, listener);
        watchers.push(() => fs.unwatchFile(candidate.path, listener));
      } catch { /* ignore */ }
    }
    try {
      const listener = onChange(defaultsSourcePath);
      fs.watchFile(defaultsSourcePath, { persistent: true, interval: 4000 }, listener);
      watchers.push(() => fs.unwatchFile(defaultsSourcePath, listener));
    } catch { /* ignore */ }
  }

  function stopWatching() {
    for (const dispose of watchers.splice(0)) {
      try { dispose(); } catch { /* ignore */ }
    }
  }

  return { candidates, defaultsSourcePath, refresh, current, startWatching, stopWatching };
}
