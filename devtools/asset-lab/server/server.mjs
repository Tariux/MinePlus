// Mineplus Asset Lab Devtool — the single development server.
//
// One port, one compile loop, one daemon, every capability the old texel and
// asset-lab dev servers had (this file supersedes both):
//   - compile the pipeline (src/main/java) + daemon (devtools/asset-lab/java) + stubs with javac
//   - own the AssetDaemon JVM process (spawn, proxy line-delimited JSON requests, restart on crash)
//   - hot reload: watch pipeline sources -> recompile -> restart daemon -> SSE event
//   - watch asset roots (block models, mob defs/models, gun item models, multiblock/recipe
//     JSON, pack artifacts) -> debounced SSE event
//   - live settings bridge: report the settings.mp.yml the *running server* uses
//   - serve the app (devtools/asset-lab/app) + the HTTP API
//
// Run:  node devtools/asset-lab/server/server.mjs [--port 5167] [--models <dir>]
//           [--settings <path>] [--no-compile]
//       --models is repeatable and appends extra block-model roots (texel-tool flag).
//       --settings points the live settings bridge at one specific settings.mp.yml
//       (otherwise the deployed-server candidates are probed in order).
//       --no-compile (or ASSET_LAB_SKIP_JAVAC=1) starts the HTTP/UI side only: the
//       daemon stays down and ops answer clean DAEMON_UNAVAILABLE JSON (HTTP 200) —
//       used to verify the server survives a daemon that cannot start.
//       --reuse-classes skips the startup javac and boots the daemon from the last
//       good build/asset-lab output (source watchers still recompile).
//       --daemon-main <class> points at a stand-in daemon (integration testing).
//
// Zero npm dependencies: node:http, node:fs, node:path, node:child_process only.

import http from 'node:http';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createSettingsBridge, SETTINGS_SECTIONS } from './settings-bridge.mjs';

const serverDir = import.meta.dirname;
const toolDir = path.dirname(serverDir);                    // devtools/asset-lab
const root = path.resolve(toolDir, '..', '..');             // repo root
const appDir = path.join(toolDir, 'app');
const daemonJavaDir = path.join(toolDir, 'java');
const stubsDir = path.join(toolDir, 'java-stubs');
const buildDir = path.join(root, 'build', 'asset-lab');
const mainOut = path.join(buildDir, 'classes', 'main');
const daemonOut = path.join(buildDir, 'classes', 'daemon');
const stubsOut = path.join(buildDir, 'classes', 'stubs');
const vendorDir = path.join(serverDir, 'vendor');
const libsDir = path.join(root, 'libs');
const SEP = path.delimiter;

// The live server this repo deploys to (see package.json `build` scripts).
const liveServerDir = path.join(root, '..', 'server-spigotss');
const livePluginDir = path.join(liveServerDir, 'plugins', 'Mineplus');

// ---------------------------------------------------------------- CLI args

const argv = process.argv.slice(2);
function argValue(name) {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : null;
}
const port = Number(argValue('--port') ?? process.env.ASSET_LAB_PORT ?? 5167);
const skipCompile = argv.includes('--no-compile') || process.env.ASSET_LAB_SKIP_JAVAC === '1';
// --settings <path>: point the live settings bridge at one specific settings.mp.yml.
const settingsOverride = argValue('--settings') ? path.resolve(argValue('--settings')) : null;
// Daemon main class — override only to test the server against a stand-in daemon.
const daemonMain = argValue('--daemon-main') ?? 'com.mineplus.devtools.assetlab.AssetDaemon';
// --no-compile: never run javac (UI + settings only; ops answer DAEMON_UNAVAILABLE).
// --reuse-classes: skip the startup javac and boot the daemon from the last good
// build/asset-lab output; source watchers still recompile afterwards.
const reuseClasses = argv.includes('--reuse-classes') || process.env.ASSET_LAB_REUSE_CLASSES === '1';

/** Repeatable `--models <dir>`: extra block-model roots, first ones win in listings. */
const extraModelRoots = [];
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--models' && i + 1 < argv.length) extraModelRoots.push(path.resolve(argv[++i]));
}

// ---------------------------------------------------------------- asset roots

function existsDir(dir) {
  try { return fs.statSync(dir).isDirectory(); } catch { return false; }
}
function dedupePaths(list) {
  const seen = new Set();
  const out = [];
  for (const item of list) {
    const key = process.platform === 'win32' ? item.toLowerCase() : item;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(item);
  }
  return out;
}

// Block/model roots: extra CLI roots always count (even before the dir is created),
// the shipped + repo + live-server roots only when present.
const blockRoots = dedupePaths([
  ...extraModelRoots,
  path.join(root, 'examples', 'mineplus-fun', 'src', 'main', 'resources', 'defaults', 'models'),
  path.join(root, 'plugins', 'Mineplus', 'models'),
  path.join(livePluginDir, 'models'),
].filter((p, i) => i < extraModelRoots.length || existsDir(p)));

// Mob roots (definitions, .bbmodel rigs, textures — recursive, so `mobs/examples` counts).
const mobRoots = dedupePaths([
  path.join(root, 'examples', 'mineplus-mobfun', 'src', 'main', 'resources', 'mobs'),
  path.join(root, 'plugins', 'Mineplus', 'mobs'),
  path.join(livePluginDir, 'mobs'),
  path.join(root, 'src', 'main', 'resources', 'mobs'),
].filter(existsDir));

// Gun item-model roots.
const itemRoots = dedupePaths([
  path.join(root, 'examples', 'mineplus-gun', 'src', 'main', 'resources', 'defaults', 'guns'),
  path.join(root, 'plugins', 'Mineplus', 'guns'),
  path.join(livePluginDir, 'guns'),
].filter(existsDir));

// Data-driven JSON roots (multiblock + machine recipes) — watched and inventoried.
const dataRoots = dedupePaths([
  path.join(root, 'examples', 'mineplus-fun', 'src', 'main', 'resources', 'defaults', 'multiblocks'),
  path.join(root, 'examples', 'mineplus-fun', 'src', 'main', 'resources', 'defaults', 'recipes'),
  path.join(root, 'src', 'main', 'resources', 'defaults', 'multiblocks'),
  path.join(root, 'src', 'main', 'resources', 'defaults', 'recipes'),
  path.join(root, 'plugins', 'Mineplus', 'multiblocks'),
  path.join(root, 'plugins', 'Mineplus', 'recipes'),
  path.join(livePluginDir, 'multiblocks'),
  path.join(livePluginDir, 'recipes'),
].filter(existsDir));

// Pack artifact search dirs — listed even when missing, so the UI can show where it looked.
// Contract rule 4: `dist/`, the repo `plugins/Mineplus/{packs,resource-pack}`, and the
// same two dirs under every deployed server dir from the settings-candidate list.
const deployedServerPluginDirs = dedupePaths([
  path.join(root, 'plugins', 'Mineplus'),
  path.join(root, '..', 'server-spigot', 'plugins', 'Mineplus'),
  path.join(livePluginDir),
]);
const packSearchRoots = dedupePaths([
  path.join(root, 'dist'),
  ...deployedServerPluginDirs.flatMap(dir => [path.join(dir, 'packs'), path.join(dir, 'resource-pack')]),
]);

/** Anything with a file the browser may load / path the daemon may read. */
const assetRoots = dedupePaths([...blockRoots, ...mobRoots, ...itemRoots, ...dataRoots, ...packSearchRoots]);
/** Model files an op may be pointed at (block/mob/gun geometry). */
const modelRoots = dedupePaths([...blockRoots, ...mobRoots, ...itemRoots]);
const allRoots = assetRoots;

/** Resolved live-server plugin data dir (null until the deployed server has run once). */
const liveServerPluginDir = existsDir(livePluginDir) ? livePluginDir : null;

// ---------------------------------------------------------------- logging + SSE

const log = (...a) => console.log('[asset-lab]', ...a);
const sseClients = new Set();

function sseSend(event) {
  const payload = `data: ${JSON.stringify(event)}\n\n`;
  for (const res of sseClients) {
    try { res.write(payload); } catch { sseClients.delete(res); }
  }
}
// Keep-alive comment: dev proxies and some browsers drop an idle event stream.
setInterval(() => {
  for (const res of sseClients) {
    try { res.write(': ping\n\n'); } catch { sseClients.delete(res); }
  }
}, 15000).unref();

// ---------------------------------------------------------------- JDK resolution

// Prefer the JDK from JAVA_HOME over whatever `javac`/`java` the PATH resolves
// to: vendor shims (e.g. Oracle's Common Files\Java\javapath) can front a newer
// JDK whose javac crashes outright on this source set (exit 4, "1 error",
// "printing javac parameters to: ..." and no diagnostic). The project targets
// JDK 21, which JAVA_HOME normally points at on dev machines.
const javaExe = process.platform === 'win32' ? '.exe' : '';
const javaHomeBin = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin') : null;
function resolveTool(name) {
  if (javaHomeBin) {
    const candidate = path.join(javaHomeBin, name + javaExe);
    if (fs.existsSync(candidate)) return candidate;
  }
  return name;
}
const javacCmd = resolveTool('javac');
const javaCmd = resolveTool('java');

// ------------------------------------------------------- runtime jar discovery

// The vendored paper-api jar needs guava at *runtime* for Material's static
// initializer, and Bukkit's YamlConfiguration needs snakeyaml — a headless
// daemon has neither, so both are located from (in order) the staged copy, this
// server's own vendor dir, or the local Gradle cache. A jar that cannot be found
// is reported at startup and again in /api/health; the ops that need it fail with
// a readable error instead of taking the daemon down. This server is fully
// self-contained: nothing under devtools/texel/** is read.
const RUNTIME_JARS = [
  {
    key: 'guava', staged: 'guava.jar', vendor: ['guava.jar'],
    gradle: ['com.google.guava', 'guava'], pattern: /^guava-\d[\d.]*(-jre)?\.jar$/,
    neededBy: 'any op that touches Material (the texture resolver, the texel palette)',
  },
  {
    key: 'snakeyaml', staged: 'snakeyaml.jar', vendor: ['snakeyaml.jar', 'snakeyaml-2.2.jar'],
    gradle: ['org.yaml', 'snakeyaml'], pattern: /^snakeyaml-\d[\d.]*\.jar$/,
    neededBy: 'mob ops that read a .yml definition (Bukkit YamlConfiguration)',
  },
];
const runtimeJars = new Map();

async function findRuntimeJar(spec) {
  const staged = path.join(buildDir, 'libs', spec.staged);
  if (fs.existsSync(staged)) return staged;
  for (const name of spec.vendor) {
    const own = path.join(vendorDir, name);
    if (fs.existsSync(own)) return own;
  }
  const home = process.env.HOME ?? process.env.USERPROFILE ?? '~';
  const gradleCache = path.join(home, '.gradle', 'caches', 'modules-2', 'files-2.1', ...spec.gradle);
  if (fs.existsSync(gradleCache)) {
    const candidates = [];
    async function walk(dir, depth) {
      if (depth > 4) return;
      let entries;
      try { entries = await fsp.readdir(dir, { withFileTypes: true }); } catch { return; }
      for (const entry of entries) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) await walk(full, depth + 1);
        else if (spec.pattern.test(entry.name)) candidates.push(full);
      }
    }
    await walk(gradleCache, 0);
    if (candidates.length > 0) {
      await fsp.mkdir(path.join(buildDir, 'libs'), { recursive: true });
      await fsp.copyFile(candidates[0], staged);
      log(`staged ${spec.key} from Gradle cache:`, candidates[0]);
      return staged;
    }
  }
  return null;
}

async function resolveRuntimeJars() {
  for (const spec of RUNTIME_JARS) {
    runtimeJars.set(spec.key, await findRuntimeJar(spec));
    log(`${spec.key}:`, runtimeJars.get(spec.key)
      ?? `NOT FOUND — ${spec.neededBy} will fail`);
  }
  return runtimeJars;
}

// ---------------------------------------------------------------- compilation

const libJars = () => fs.existsSync(libsDir)
  ? fs.readdirSync(libsDir).filter(f => f.endsWith('.jar')).map(f => path.join(libsDir, f))
  : [];

async function listJavaFiles(dir) {
  const out = [];
  async function walk(d) {
    let entries;
    try { entries = await fsp.readdir(d, { withFileTypes: true }); } catch { return; }
    for (const e of entries) {
      const full = path.join(d, e.name);
      if (e.isDirectory()) await walk(full);
      else if (e.name.endsWith('.java')) out.push(full);
    }
  }
  await walk(dir);
  return out;
}

async function runJavac(args, cwd) {
  const argFile = path.join(cwd, `javac_args_${Date.now()}_${Math.random().toString(36).slice(2)}.txt`);
  // Forward slashes for Java @file parsing (backslashes are escape chars in arg files),
  // and quote every argument containing a space (this repo lives under "Backups Main").
  const normalized = args.map(a => a.replace(/\\/g, '/'));
  const quoted = normalized.map(a => a.includes(' ') ? '"' + a.replace(/"/g, '\\"') + '"' : a);
  await fsp.writeFile(argFile, quoted.join('\n'), 'utf8');

  return new Promise((resolve) => {
    let child;
    try {
      child = spawn(javacCmd, ['@' + argFile], { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (e) {
      resolve({ code: -1, output: String(e) });
      return;
    }
    let stdout = '', stderr = '';
    child.stdout?.on('data', d => { stdout += d; });
    child.stderr?.on('data', d => { stderr += d; });
    child.on('error', err => resolve({ code: -1, output: String(err) }));
    child.on('close', async (code) => {
      try { await fsp.unlink(argFile); } catch {}
      resolve({ code, output: stderr + stdout });
    });
  });
}

let compileSeq = 0;
let compiled = false;
let compileError = null;
let lastCompileMs = null;
const startedAt = Date.now();

async function compileAll(reason) {
  const seq = ++compileSeq;
  sseSend({ type: 'code', status: 'compiling', reason });
  log('compiling pipeline...', reason);
  const t0 = Date.now();

  await fsp.mkdir(mainOut, { recursive: true });
  await fsp.mkdir(daemonOut, { recursive: true });
  await fsp.mkdir(stubsOut, { recursive: true });

  const cp = [...libJars()].join(SEP);
  const mainSources = await listJavaFiles(path.join(root, 'src', 'main', 'java'));
  const daemonSources = await listJavaFiles(daemonJavaDir);
  const stubSources = await listJavaFiles(stubsDir);

  const main = await runJavac(['--class-path', cp, '-d', mainOut, '-encoding', 'UTF-8', ...mainSources], root);
  if (seq !== compileSeq) return false; // superseded by a newer edit — don't restart on stale classes
  if (main.code !== 0) return fail('main compile', main.output);
  const stubs = await runJavac(['-d', stubsOut, ...stubSources], root);
  if (seq !== compileSeq) return false;
  if (stubs.code !== 0) return fail('stub compile', stubs.output);
  const daemon = await runJavac(['--class-path', [mainOut, cp].join(SEP), '-d', daemonOut, '-encoding', 'UTF-8', ...daemonSources], root);
  if (seq !== compileSeq) return false;
  if (daemon.code !== 0) return fail('daemon compile', daemon.output);

  compiled = true;
  compileError = null;
  lastCompileMs = Date.now() - t0;
  log('compiled in', (lastCompileMs / 1000).toFixed(1) + 's');
  sseSend({ type: 'code', status: 'ok', ms: Date.now() - t0 });
  await restartDaemon();
  return true;

  function fail(stage, output) {
    compiled = false;
    lastCompileMs = Date.now() - t0;
    compileError = { stage, output: output.slice(0, 20000) };
    log('COMPILE FAILED (' + stage + '):\n' + output);
    sseSend({ type: 'code', status: 'error', stage, output: compileError.output });
    return false;
  }
}

// One javac at a time; a change during a compile queues exactly one more pass.
let compileInFlight = null;
let compileQueued = false;
let compileQueueReason = 'source change';

function requestCompile(reason) {
  compileQueueReason = reason;
  if (skipCompile) return Promise.resolve(false);
  if (compileInFlight) { compileQueued = true; return compileInFlight; }
  compileInFlight = compileAll(reason)
    .catch(e => log('compile error:', e))
    .finally(() => {
      compileInFlight = null;
      if (compileQueued) {
        compileQueued = false;
        requestCompile(compileQueueReason + ' (queued)');
      }
    });
  return compileInFlight;
}

// ---------------------------------------------------------------- daemon lifecycle

let daemon = null;
let daemonStarting = false;
let daemonReqSeq = 0;
const pendingRequests = new Map();
let daemonStdoutBuf = '';
let daemonRestarts = 0;
const DAEMON_RESTART_CAP = 10;
const daemonState = { up: false, pid: null, lastExitCode: null, lastError: null, restarts: 0, capped: false };
// Rolling tail of the daemon's stderr: attached to `dead` events so the app can
// show *why* the child died without a second round-trip.
const daemonStderrLines = [];
function pushDaemonStderr(chunk) {
  for (const line of String(chunk).split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    daemonStderrLines.push(trimmed.slice(0, 500));
    while (daemonStderrLines.length > 12) daemonStderrLines.shift();
  }
}

async function daemonClasspath() {
  if (runtimeJars.size === 0) await resolveRuntimeJars();
  const parts = [mainOut, daemonOut, stubsOut, ...libJars()];
  for (const jar of runtimeJars.values()) if (jar) parts.push(jar);
  return parts.join(SEP);
}

/** Human-readable absence warning for the jars a headless daemon cannot supply. */
function warnMissingRuntimeJars() {
  for (const spec of RUNTIME_JARS) {
    if (runtimeJars.get(spec.key)) continue;
    log(`WARNING: ${spec.key} not found — ${spec.neededBy}.`,
      'Place a jar at', path.join(vendorDir, spec.vendor[0]), 'if those ops error.');
  }
}

function markDaemonDown({ code = null, error = null, crash = true } = {}) {
  daemonState.up = false;
  daemonState.pid = null;
  if (code !== null) daemonState.lastExitCode = code;
  if (error) daemonState.lastError = error;
  sseSend({
    type: 'daemon', status: 'dead', code, error,
    stderr: daemonStderrLines.slice(-8), capped: daemonState.capped,
  });
  if (!crash) return;
  if (daemonRestarts < DAEMON_RESTART_CAP) {
    daemonRestarts++;
    daemonState.restarts = daemonRestarts;
    sseSend({ type: 'daemon', status: 'restarting', attempt: daemonRestarts, cap: DAEMON_RESTART_CAP });
    log('daemon exited (code ' + code + '), restarting #' + daemonRestarts);
    setTimeout(() => startDaemon().catch(e => log('daemon restart failed:', e.message)), 500);
  } else {
    daemonState.capped = true;
    log('daemon exited; giving up after ' + DAEMON_RESTART_CAP + ' restarts — ops stay disabled',
      '(fix the daemon compile, then POST /api/recompile)');
  }
}

/** Last-good build output present (so --reuse-classes can boot without javac). */
function hasDaemonClasses() {
  try {
    return fs.readdirSync(daemonOut).length > 0 && fs.readdirSync(mainOut).length > 0;
  } catch {
    return false;
  }
}

async function startDaemon() {
  if (daemon || daemonStarting || skipCompile) return;
  if (!compiled && !hasDaemonClasses()) {
    log('daemon not started: nothing compiled yet (see the last compile error).');
    return;
  }
  daemonStarting = true;
  const cp = await daemonClasspath();
  warnMissingRuntimeJars();
  let child;
  try {
    child = spawn(javaCmd, ['-cp', cp, daemonMain], { cwd: root });
  } catch (e) {
    daemonStarting = false;
    markDaemonDown({ error: String(e?.message ?? e) });
    return;
  }
  daemon = child;
  daemonStarting = false;
  daemonState.up = true;
  daemonState.pid = child.pid ?? null;
  daemonState.capped = false;
  log('daemon started (pid ' + daemonState.pid + ')');
  daemonStderrLines.length = 0;
  sseSend({ type: 'daemon', status: 'started', pid: daemonState.pid });

  child.stdout?.setEncoding('utf8');
  child.stdout?.on('data', chunk => {
    daemonStdoutBuf += chunk;
    let nl;
    while ((nl = daemonStdoutBuf.indexOf('\n')) >= 0) {
      const line = daemonStdoutBuf.slice(0, nl).trim();
      daemonStdoutBuf = daemonStdoutBuf.slice(nl + 1);
      if (!line) continue;
      try {
        const msg = JSON.parse(line);
        if (msg.id !== undefined && pendingRequests.has(msg.id)) {
          const { resolve } = pendingRequests.get(msg.id);
          pendingRequests.delete(msg.id);
          resolve(msg);
        }
      } catch { log('daemon: unparseable line:', line.slice(0, 200)); }
    }
  });
  child.stderr?.setEncoding('utf8');
  child.stderr?.on('data', chunk => {
    pushDaemonStderr(chunk);
    sseSend({ type: 'daemon-log', text: String(chunk).slice(0, 8000) });
  });

  // A spawn that fails outright (java missing, bad classpath) emits 'error' and may
  // never emit 'exit' — handle both so the server never wedges on a phantom daemon.
  child.on('error', err => {
    log('daemon error:', err.message);
    if (daemon === child) {
      daemon = null;
      failAllPending('daemon error: ' + err.message);
      markDaemonDown({ error: err.message });
    }
  });
  child.on('exit', (code) => {
    if (daemon !== child) return; // superseded by a restart/reload
    daemon = null;
    failAllPending('daemon exited (code ' + code + ')');
    markDaemonDown({ code });
  });
}

function failAllPending(message) {
  for (const { reject } of pendingRequests.values()) reject(new Error(message));
  pendingRequests.clear();
}

async function stopDaemon() {
  if (!daemon) return;
  const d = daemon;
  daemon = null;
  daemonState.up = false;
  await new Promise(resolve => {
    let settled = false;
    const done = () => { if (!settled) { settled = true; resolve(); } };
    d.on('exit', done);
    d.on('error', done);
    try { d.stdin.end(); } catch { done(); }
    setTimeout(() => { try { d.kill(); } catch {} done(); }, 2000);
  });
}

async function restartDaemon() {
  daemonRestarts = 0;
  daemonState.restarts = 0;
  daemonState.capped = false;
  await stopDaemon();
  await startDaemon();
}

// Per-request daemon timeout: generous enough for a big bake, bounded so every
// op route answers cleanly instead of hanging.
function daemonRequest(payload, timeoutMs = 45000) {
  return new Promise((resolve, reject) => {
    if (!daemon || daemon.exitCode !== null) {
      const why = compileError
        ? `javac failed (${compileError.stage}) — see the status bar`
        : skipCompile
          ? 'daemon disabled by --no-compile'
          : 'daemon not running (still compiling, or it failed to start — see the status bar)';
      reject(new Error(why));
      return;
    }
    const id = ++daemonReqSeq;
    const timer = setTimeout(() => {
      pendingRequests.delete(id);
      reject(new Error('daemon request timed out'));
    }, timeoutMs);
    pendingRequests.set(id, {
      resolve: v => { clearTimeout(timer); resolve(v); },
      reject: e => { clearTimeout(timer); reject(e); },
    });
    try {
      daemon.stdin.write(JSON.stringify({ id, ...payload }) + '\n');
    } catch (e) {
      pendingRequests.delete(id);
      clearTimeout(timer);
      reject(new Error('daemon stdin write failed: ' + e.message));
    }
  });
}

// ---------------------------------------------------------------- inventory

/** Pack artifact filename contract: mp-<graph hash hex>.zip (case-insensitive on Windows). */
const MP_ARTIFACT_RE = /^mp-[a-f0-9]{1,12}\.zip$/i;
/** Last completed inventory scan; /api/health reads it instead of rescanning. */
let cachedInventory = null;

async function walkFiles(dir, filter) {
  const out = [];
  async function walk(d) {
    let entries;
    try { entries = await fsp.readdir(d, { withFileTypes: true }); } catch { return; }
    for (const e of entries) {
      const full = path.join(d, e.name);
      if (e.isDirectory()) await walk(full);
      else if (e.isFile() && filter(e.name, full)) out.push(full);
    }
  }
  await walk(dir);
  return out;
}

function classifyFrame(fileName, gunName) {
  let base = fileName.slice(0, -'.json'.length);
  if (base.startsWith(gunName + '_')) base = base.slice(gunName.length + 1);
  if (base === 'idle') return 'idle';
  if (base === 'primed') return 'primed';
  if (base === 'equip') return 'equip';
  if (base === 'cycle') return 'cycle';
  if (base === 'scope_in' || base === 'scope_hold' || base === 'scope_out') return base;
  let m = /^loading_(\d+)$/.exec(base);
  if (m) return 'loading_' + m[1];
  m = /^fire(_\d+)?$/.exec(base);
  if (m) return m[0];
  return 'other';
}

async function scanInventory() {
  const blocks = [];
  for (const dir of blockRoots) {
    for (const file of await walkFiles(dir, n => n.endsWith('.bbmodel'))) {
      blocks.push({
        key: path.basename(file).slice(0, -'.bbmodel'.length),
        file,
        root: dir,
        hasMeta: fs.existsSync(file.slice(0, -'.bbmodel'.length) + '.meta.json'),
      });
    }
  }
  blocks.sort((a, b) => a.key.localeCompare(b.key));

  const mobDefs = [];
  const mobModels = [];
  const mobTextures = [];
  for (const dir of mobRoots) {
    for (const file of await walkFiles(dir, n => n.endsWith('.yml'))) {
      mobDefs.push({ file, root: dir, name: path.basename(file, '.yml') });
    }
    for (const file of await walkFiles(dir, n => n.endsWith('.bbmodel'))) {
      mobModels.push({ file, root: dir, name: path.basename(file, '.bbmodel') });
    }
    for (const file of await walkFiles(dir, n => n.endsWith('.png'))) {
      mobTextures.push({ file, root: dir, name: path.basename(file) });
    }
  }
  const byName = (a, b) => a.name.localeCompare(b.name);
  mobDefs.sort(byName);
  mobModels.sort(byName);
  mobTextures.sort(byName);

  const guns = [];
  for (const dir of itemRoots) {
    let entries;
    try { entries = await fsp.readdir(dir, { withFileTypes: true }); } catch { continue; }
    for (const e of entries) {
      if (!e.isDirectory()) continue;
      const gunDir = path.join(dir, e.name);
      const modelsDir = path.join(gunDir, 'models');
      const frames = [];
      const sources = [];
      if (fs.existsSync(modelsDir)) {
        for (const file of await walkFiles(modelsDir, n => n.endsWith('.json'))) {
          frames.push({
            file,
            name: path.basename(file, '.json'),
            frame: classifyFrame(path.basename(file), e.name),
          });
        }
        for (const file of await walkFiles(modelsDir, n => n.endsWith('.bbmodel'))) {
          sources.push({ file, name: path.basename(file, '.bbmodel') });
        }
      }
      frames.sort((a, b) => a.name.localeCompare(b.name));
      sources.sort((a, b) => a.name.localeCompare(b.name));
      const textures = await walkFiles(gunDir, n => n.endsWith('.png'));
      guns.push({ gun: e.name, root: dir, dir: gunDir, frames, sources, textures });
    }
  }
  guns.sort((a, b) => a.gun.localeCompare(b.gun));

  // Data-driven JSON (multiblock levels + machine recipes).
  const multiblocks = [];
  const recipes = [];
  for (const dir of dataRoots) {
    for (const file of await walkFiles(dir, n => n.endsWith('.json'))) {
      const name = path.basename(file, '.json');
      if (dir.toLowerCase().endsWith('multiblocks')) multiblocks.push({ file, root: dir, name });
      else recipes.push({ file, root: dir, name });
    }
  }
  multiblocks.sort(byName);
  recipes.sort(byName);

  // Compiled resource pack artifacts: paths only — the daemon reads the zip.
  // The Core names its artifacts `mp-<hex>.zip` (graph hash in the name); `npm run
  // pack` also ships a fixed-name bundle for manual distribution, so any zip in a
  // pack root is listed and only the contract-named ones are marked as such.
  const packArtifacts = [];
  for (const dir of packSearchRoots) {
    for (const file of await walkFiles(dir, n => n.toLowerCase().endsWith('.zip'))) {
      let size = null;
      let mtimeMs = null;
      try { const st = await fsp.stat(file); size = st.size; mtimeMs = st.mtimeMs; } catch { /* racing delete */ }
      packArtifacts.push({
        file, root: dir, name: path.basename(file), size,
        mtime: Math.round(mtimeMs ?? 0), mtimeMs,
        contract: MP_ARTIFACT_RE.test(path.basename(file)),
      });
    }
  }
  packArtifacts.sort((a, b) => (b.mtimeMs ?? 0) - (a.mtimeMs ?? 0));

  return {
    blocks,
    mobDefs,
    mobModels,
    mobTextures,
    guns,
    multiblocks,
    recipes,
    packArtifacts,
    serverDir: liveServerPluginDir,
    packRoots: packSearchRoots,
    roots: { blockRoots, mobRoots, itemRoots, dataRoots, packRoots: packSearchRoots, extraModelRoots },
  };
}

// The texel tool's grouped model tree, kept so its workflow survives the merge.
async function scanModels() {
  const roots = [];
  for (const dir of blockRoots) {
    const models = [];
    for (const file of await walkFiles(dir, n => n.endsWith('.bbmodel'))) {
      models.push({
        key: path.basename(file, '.bbmodel'),
        file,
        hasMeta: fs.existsSync(file.slice(0, -'.bbmodel'.length) + '.meta.json'),
      });
    }
    models.sort((a, b) => a.key.localeCompare(b.key));
    roots.push({ dir, models });
  }
  return roots;
}

// ---------------------------------------------------------------- watchers

function debounce(fn, ms) {
  let t = null;
  return (...args) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...args), ms);
  };
}

const debouncedRecompile = debounce(() => requestCompile('source change'), 400);
const debouncedAssets = debounce(() => sseSend({ type: 'assets' }), 200);

function watchRecursive(dir, label, onChange) {
  try {
    fs.watch(dir, { recursive: true }, (event, filename) => {
      if (label === 'sources') {
        if (!filename || filename.endsWith('.java')) onChange();
      } else {
        onChange();
      }
    });
    log('watching', label, ':', dir);
  } catch (e) {
    log('watch failed for', dir, e.message);
  }
}

function watchAll() {
  if (!skipCompile) {
    watchRecursive(path.join(root, 'src', 'main', 'java'), 'sources', debouncedRecompile);
    watchRecursive(daemonJavaDir, 'sources', debouncedRecompile);
    watchRecursive(stubsDir, 'sources', debouncedRecompile);
  }
  for (const dir of assetRoots) {
    if (existsDir(dir)) watchRecursive(dir, 'assets', debouncedAssets);
  }
}

// ---------------------------------------------------------------- settings bridge

const settingsBridge = createSettingsBridge({
  repoRoot: root,
  overridePath: settingsOverride,
  log,
  onChange: snap => sseSend({
    type: 'settings',
    source: snap.source,
    sourceLabel: snap.sourceLabel,
    sourcePath: snap.sourcePath,
    sourcePath: snap.sourcePath,
    mtimeMs: snap.mtimeMs,
    refreshedAt: snap.refreshedAt,
  }),
});
settingsBridge.startWatching();

// ---------------------------------------------------------------- HTTP helpers

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.bbmodel': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.zip': 'application/zip',
  '.yml': 'text/plain; charset=utf-8',
  '.yaml': 'text/plain; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
};

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function sendError(res, code, error, extra) {
  sendJson(res, code, { ok: false, error, ...extra });
}

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return Buffer.concat(chunks);
}

/** Malformed JSON is a 400 with a clean body, never a stack trace. */
async function parseJsonBody(req) {
  const raw = (await readBody(req)).toString('utf8');
  if (!raw.trim()) return {};
  return JSON.parse(raw); // throws SyntaxError — caller answers 400
}

const CI = process.platform === 'win32';
function samePath(a, b) {
  return CI ? a.toLowerCase() === b.toLowerCase() : a === b;
}

function isInsideRoots(absPath, roots = allRoots) {
  if (typeof absPath !== 'string' || absPath.length === 0 || absPath.includes('\0')) return false;
  let resolved;
  try { resolved = path.resolve(absPath); } catch { return false; }
  const probe = CI ? resolved.toLowerCase() : resolved;
  return roots.some(rootDir => {
    const base = CI ? path.resolve(rootDir).toLowerCase() : path.resolve(rootDir);
    return probe.startsWith(base + path.sep) || probe === base;
  });
}

/**
 * Daemon down / timed out: every op route answers this instead of hanging or
 * 500-ing — HTTP 200 with a parsable `{status:"DAEMON_UNAVAILABLE", error, hint}`
 * body, so the app's normal JSON path handles the failure.
 */
function daemonUnavailable(res, e) {
  const hint = compileError
    ? 'javac failed (' + compileError.stage + ') — fix the daemon sources, then POST /api/recompile.'
    : skipCompile
      ? 'this server runs with --no-compile; restart it without the flag to enable daemon ops.'
      : daemonState.capped
        ? 'the daemon kept crashing and hit the restart cap — see the stderr lines, fix, then POST /api/recompile.'
        : 'the daemon is starting or just died — watch /api/events for daemon status and retry shortly.';
  sendJson(res, 200, {
    status: 'DAEMON_UNAVAILABLE',
    ok: false,
    error: e.message,
    hint,
    daemonUp: false,
    compiled,
    compileError: compileError?.stage ?? null,
    stderr: daemonStderrLines.slice(-6),
  });
}

/** Every body key that carries a filesystem path the daemon will read. */
const PATH_KEYS = ['model', 'yml', 'texture', 'textureRoot', 'file'];

/**
 * Guarded op proxy: each path key must be a string resolving inside the allowed
 * roots, and at least one `anyOf` selector must be present, then the body passes
 * through to the daemon untouched.
 */
async function proxyOp(res, op, body, roots, { anyOf = ['model'], extra } = {}) {
  for (const key of PATH_KEYS) {
    const value = body[key];
    if (value === undefined || value === null) continue;
    if (typeof value !== 'string' || !value || !isInsideRoots(path.resolve(value), roots)) {
      sendError(res, 400, `${key} must be a path inside this tool's watched roots`, { key, roots });
      return;
    }
  }
  if (!anyOf.some(key => typeof body[key] === 'string' && body[key])) {
    sendError(res, 400, `op '${op}' needs one of: ${anyOf.join(', ')}`);
    return;
  }
  try {
    // `op` is owned by the route: a body must never retarget the daemon.
    const reply = await daemonRequest({ ...body, op, ...extra });
    sendJson(res, reply.ok ? 200 : 400, reply);
  } catch (e) {
    daemonUnavailable(res, e);
  }
}

// ---------------------------------------------------------------- routes

async function handleEvents(req, res) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache, no-store',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no',
  });
  res.write('retry: 2000\n\n');
  sseClients.add(res);
  // Snapshot for the status bar so a fresh tab is accurate before the next event.
  res.write(`data: ${JSON.stringify({
    type: 'hello',
    daemon: daemonState.up ? 'started' : 'dead',
    daemonState,
    compiled,
    compileError: compileError?.stage ?? null,
    settings: settingsBridge.current().source,
    port,
  })}\n\n`);
  req.on('close', () => sseClients.delete(res));
}

function handleHealth(res) {
  const settings = settingsBridge.current();
  const snap = cachedInventory;
  // Contract shape (health strip fields first), plus a read-only `details` blob
  // the status panel uses to avoid a second request. Cheap: no disk scan here —
  // packArtifacts comes from the last inventory scan (refreshed by /api/inventory,
  // by asset watchers, and once at startup).
  sendJson(res, 200, {
    daemon: {
      alive: daemonState.up,
      restarts: daemonState.restarts,
      lastError: daemonState.lastError ?? (daemonState.up ? null : 'daemon not running'),
      pid: daemonState.pid,
      capped: daemonState.capped,
      stderrTail: daemonState.up ? [] : daemonStderrLines.slice(-6),
    },
    compile: {
      status: skipCompile ? 'skipped' : compileError ? 'error' : compiled ? 'ok' : 'pending',
      ms: lastCompileMs,
      lastError: compileError ? { stage: compileError.stage, output: compileError.output.slice(0, 4000) } : null,
    },
    packArtifacts: snap ? snap.packArtifacts.map(a => a.name) : [],
    settings: { source: settings.source, path: settings.sourcePath },
    port,
    uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
    details: {
      ok: true,
      daemonUp: daemonState.up,
      compiled,
      roots: { blockRoots, mobRoots, itemRoots, dataRoots, packRoots: packSearchRoots, extraModelRoots },
      javaCmd,
      javacCmd,
      runtimeJars: Object.fromEntries(runtimeJars),
      settingsSource: settings.source,
      settingsPath: settings.sourcePath ?? settings.path,
      skipCompile,
      serverDir: liveServerPluginDir,
      sections: SETTINGS_SECTIONS.map(s => s.yamlKey),
      artifactPaths: snap ? snap.packArtifacts.map(a => a.file) : [],
    },
  });
}

/** Contract allow-list for /api/asset — nothing else ever leaves the disk. */
const ASSET_EXT_RE = /\.(png|json|bbmodel|yml|md|txt|zip)$/i;
/** Files that sit next to settings.mp.yml and must never be streamed to a browser. */
const SECRET_NAME_RE = /(credential|password|secret|token|\.db(-wal|-shm|-journal)?|\.sqlite|\.env|users\.yml|spigot\.yml|bukkit\.yml)$/i;

async function handleAsset(res, url) {
  const p = url.searchParams.get('p');
  if (!p) { sendError(res, 400, 'missing ?p='); return; }
  const abs = path.resolve(p);
  if (!isInsideRoots(abs)) { sendError(res, 403, 'path outside watched roots', { path: abs }); return; }
  if (!ASSET_EXT_RE.test(abs)) { sendError(res, 403, 'extension not allowed for assets', { path: abs }); return; }
  if (SECRET_NAME_RE.test(path.basename(abs))) { sendError(res, 403, 'protected file', { path: abs }); return; }
  let real;
  try {
    real = await fsp.realpath(abs); // resolves symlinks — an escape out of the roots fails below
  } catch {
    sendError(res, 404, 'asset not found', { path: abs });
    return;
  }
  if (!isInsideRoots(real)) { sendError(res, 403, 'path escapes the watched roots via a link', { path: real }); return; }
  if (SECRET_NAME_RE.test(path.basename(real))) { sendError(res, 403, 'protected file', { path: real }); return; }
  fsp.readFile(real).then(data => {
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(real).toLowerCase()] ?? 'application/octet-stream',
      'Cache-Control': 'no-store',
      'Content-Length': data.length,
    });
    res.end(data);
  }).catch(e => sendError(res, 404, 'asset unreadable: ' + e.message));
}

async function handleBake(res, body) {
  if (typeof body.model !== 'string' || !body.model) {
    sendError(res, 400, 'bake needs a "model" path inside the watched roots');
    return;
  }
  const modelPath = path.resolve(body.model);
  if (!isInsideRoots(modelPath, modelRoots)) {
    sendError(res, 400, 'model path outside this tool\'s watched roots', { model: modelPath, roots: modelRoots });
    return;
  }
  if (typeof body.textureRoot === 'string' && body.textureRoot
    && !isInsideRoots(path.resolve(body.textureRoot), assetRoots)) {
    sendError(res, 400, 'textureRoot outside this tool\'s watched roots', { textureRoot: body.textureRoot });
    return;
  }
  // Body passes through untouched (model / textureRoot / detail / mode /
  // maxPlatesPerFace / maxPlatesPerInstance / perFaceRendering, plus anything the
  // daemon adds later); only `op` and the guarded paths are pinned here.
  const request = { ...body, op: 'bake', model: modelPath };
  if (typeof body.textureRoot === 'string' && body.textureRoot) request.textureRoot = path.resolve(body.textureRoot);
  try {
    const reply = await daemonRequest(request);
    sendJson(res, reply.ok ? 200 : 400, reply);
  } catch (e) {
    daemonUnavailable(res, e);
  }
}

async function handlePack(res, body) {
  if (typeof body.file === 'string' && body.file && !isInsideRoots(path.resolve(body.file), packSearchRoots)) {
    sendError(res, 400, 'pack file outside the discovered pack dirs', { file: body.file, roots: packSearchRoots });
    return;
  }
  // The daemon's pack op scans `dirs` and answers `scannedDirs:[{dir,exists}]`,
  // so every search dir goes out (missing ones included) — `roots` is the same
  // list under the spec name for any client that prefers it.
  const request = { ...body, op: 'pack', dirs: packSearchRoots, roots: packSearchRoots };
  if (!request.action && !request.sub && !request.mode) request.action = 'list';
  try {
    const reply = await daemonRequest(request);
    sendJson(res, reply.ok ? 200 : 400, reply);
  } catch (e) {
    daemonUnavailable(res, e);
  }
}

/**
 * Default texture root for a gun item model: the gun folder (parent of `models/`),
 * where `<gun>.png` lives. Null when the model is not an allowed gun path.
 */
function gunTextureRoot(model) {
  if (typeof model !== 'string' || !model) return null;
  const abs = path.resolve(model);
  if (!isInsideRoots(abs, itemRoots)) return null;
  return path.dirname(path.dirname(abs));
}

async function handleAudit(res, body) {
  const inventory = await scanInventory();
  const request = {
    blocks: body.blocks ?? inventory.blocks.map(b => b.file),
    mobs: body.mobs ?? inventory.mobDefs.map(m => m.file),
    mobModels: body.mobModels ?? inventory.mobModels.map(m => m.file),
    items: body.items ?? inventory.guns.flatMap(g => g.frames.map(f => f.file)),
  };
  try {
    const reply = await daemonRequest({ op: 'audit', ...request });
    sendJson(res, reply.ok ? 200 : 400, reply);
  } catch (e) {
    daemonUnavailable(res, e);
  }
}

async function serveStatic(res, pathname) {
  const rel = pathname === '/' ? '/index.html' : pathname;
  if (rel.includes('\0')) { sendError(res, 400, 'bad path'); return; }
  const file = path.normalize(path.join(appDir, rel));
  if (!(file.startsWith(appDir + path.sep) || samePath(file, appDir))) {
    sendError(res, 403, 'path outside the app directory', { path: file });
    return;
  }
  try {
    const data = await fsp.readFile(file);
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file).toLowerCase()] ?? 'application/octet-stream',
      'Cache-Control': 'no-store',
      'Content-Length': data.length,
    });
    res.end(data);
  } catch {
    res.writeHead(404, { 'Cache-Control': 'no-store' });
    res.end('not found');
  }
}

const POST_ONLY = new Set(['/api/block', '/api/mob', '/api/mob-poses', '/api/item',
  '/api/audit', '/api/bake', '/api/pack', '/api/recompile']);

const server = http.createServer(async (req, res) => {
  let url;
  try {
    url = new URL(req.url, 'http://localhost');
  } catch {
    sendError(res, 400, 'malformed request url');
    return;
  }
  let pathname;
  try {
    pathname = decodeURIComponent(url.pathname);
  } catch {
    sendError(res, 400, 'malformed request path');
    return;
  }

  try {
    // ---- SSE event stream
    if (pathname === '/api/events') { handleEvents(req, res); return; }

    // ---- status + live config (never block on disk)
    if (pathname === '/api/health') { handleHealth(res); return; }
    if (pathname === '/api/settings') { sendJson(res, 200, settingsBridge.current()); return; }

    // ---- inventory
    if (pathname === '/api/inventory') {
      const inventory = await scanInventory();
      cachedInventory = inventory;
      sendJson(res, 200, inventory);
      return;
    }
    // ---- model tree grouped by root (texel tool capability)
    if (pathname === '/api/models') {
      sendJson(res, 200, { roots: await scanModels(), modelRoots });
      return;
    }

    // ---- raw asset (texture PNGs, pack zips) restricted to watched roots
    if (pathname === '/api/asset') { await handleAsset(res, url); return; }

    // ---- POST-only API surface (JSON bodies guarded before they reach the daemon)
    if (POST_ONLY.has(pathname)) {
      if (req.method !== 'POST') { sendError(res, 405, `${pathname} requires POST`); return; }

      if (pathname === '/api/recompile') {
        if (skipCompile) {
          sendError(res, 409, 'this server was started with --no-compile; restart it without the flag');
          return;
        }
        requestCompile('manual');
        sendJson(res, 200, { ok: true, started: true });
        return;
      }

      let body = {};
      try {
        body = await parseJsonBody(req);
      } catch {
        sendError(res, 400, 'request body is not valid JSON');
        return;
      }
      if (!body || typeof body !== 'object' || Array.isArray(body)) {
        sendError(res, 400, 'request body must be a JSON object');
        return;
      }

      if (pathname === '/api/block') {
        await proxyOp(res, 'block', body, modelRoots);
        return;
      }
      if (pathname === '/api/mob') {
        await proxyOp(res, 'mob', body, mobRoots, { anyOf: ['yml', 'model'] });
        return;
      }
      if (pathname === '/api/mob-poses') {
        await proxyOp(res, 'poses', body, mobRoots, { anyOf: ['yml', 'model'] });
        return;
      }
      if (pathname === '/api/item') {
        // Default texture root: the gun folder (parent of models/), where <gun>.png lives.
        const extra = body.textureRoot ? {} : { textureRoot: gunTextureRoot(body.model) };
        if (!extra.textureRoot) {
          sendError(res, 400, 'item needs a "model" path inside the gun roots', { roots: itemRoots });
          return;
        }
        await proxyOp(res, 'item', body, itemRoots, { extra });
        return;
      }
      if (pathname === '/api/audit') { await handleAudit(res, body); return; }
      if (pathname === '/api/bake') { await handleBake(res, body); return; }
      if (pathname === '/api/pack') { await handlePack(res, body); return; }
      sendError(res, 404, 'unknown api route', { path: pathname });
      return;
    }

    if (req.method !== 'GET' && req.method !== 'HEAD') {
      sendError(res, 405, `${req.method} is not allowed here`, { path: pathname });
      return;
    }
    if (pathname.startsWith('/api/')) { sendError(res, 404, 'unknown api route', { path: pathname }); return; }

    // ---- static app files
    await serveStatic(res, pathname);
  } catch (e) {
    // A handler never crashes the server: a clean JSON/404 is the contract.
    const message = String(e?.message ?? e);
    log('request failed:', pathname, message);
    if (!res.headersSent) {
      sendError(res, pathname.startsWith('/api/') ? 500 : 404, message);
    } else {
      try { res.end(); } catch { /* already gone */ }
    }
  }
});

// ---------------------------------------------------------------- startup

log('javac:', javacCmd, '| java:', javaCmd, javaHomeBin ? '(JAVA_HOME preferred over PATH)' : '(PATH only)');

await resolveRuntimeJars();

await settingsBridge.refresh();
const bootSettings = settingsBridge.current();
log('settings:', bootSettings.source === 'live'
  ? 'live config ' + bootSettings.sourcePath
  : bootSettings.source + ' -> ' + (bootSettings.sourcePath ?? '(no file)')
    + ' | searched: ' + (bootSettings.candidates ?? []).map(c => c.path).join(', '));
log('watch roots: blocks=' + blockRoots.length + ' mobs=' + mobRoots.length + ' items=' + itemRoots.length
  + ' data=' + dataRoots.length + ' packs=' + packSearchRoots.length);
log('pack search dirs:', packSearchRoots.join(', '));

cachedInventory = await scanInventory();
log('pack artifacts found:', cachedInventory.packArtifacts.length,
  cachedInventory.packArtifacts.map(a => a.name).join(', ') || '(none yet)');

watchAll();

if (skipCompile) {
  log('--no-compile: skipping javac; the daemon stays down and ops answer DAEMON_UNAVAILABLE (HTTP 200).');
} else if (reuseClasses) {
  log('--reuse-classes: skipping the startup javac, booting the daemon from the last good build.');
  await restartDaemon();
} else {
  await requestCompile('startup');
}

if (!fs.existsSync(appDir)) log('WARNING: app dir missing:', appDir);

server.on('error', e => {
  if (e.code === 'EADDRINUSE') {
    log(`FATAL: port ${port} is in use — another dev server is running. Pass --port <other>.`);
    process.exit(1);
  }
  log('server error:', e.message);
});

server.listen(port, () => {
  // One-line banner: port, roots, live settings file, pack artifact count, daemon.
  const s = settingsBridge.current();
  log(`READY http://localhost:${port} | roots: blocks=${blockRoots.length} mobs=${mobRoots.length} `
    + `items=${itemRoots.length} data=${dataRoots.length} packs=${packSearchRoots.length} `
    + `| settings: ${s.source === 'live' ? 'live ' + s.sourcePath : s.sourceLabel ?? s.source} `
    + `| packArtifacts: ${(cachedInventory?.packArtifacts ?? []).length} `
    + `| daemon: ${daemonState.up ? 'started (pid ' + daemonState.pid + ')' : 'down'}`
    + ` | compiled: ${compiled}`);
});

function shutdown(signal) {
  log(signal + ': closing server and stopping the daemon');
  settingsBridge.stopWatching();
  try { server.close(); } catch { /* ignore */ }
  const d = daemon;
  daemon = null;
  if (d) { try { d.kill(); } catch { /* ignore */ } }
  process.exit(0);
}
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
