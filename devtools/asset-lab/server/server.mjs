// Mineplus Asset Lab Devtool — development server.
//
// A separate tool from the texel devtool (own dir, own build outputs, own port):
//   - compile the pipeline (src/main/java) + daemon (devtools/asset-lab/java) with javac
//   - own the AssetDaemon JVM process (spawn, proxy JSON requests, restart on crash)
//   - hot reload: watch pipeline sources -> recompile -> restart daemon -> SSE event
//   - watch asset roots (block models, mob definitions, gun item models) -> SSE event
//   - serve the app (devtools/asset-lab/app) + HTTP API (inventory, op proxies, assets)
//
// Run:  node devtools/asset-lab/server/server.mjs [--port 5167]
// Zero npm dependencies (uses node:http, node:fs watchers, and SSE instead of WS).

import http from 'node:http';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';

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
const texelVendorDir = path.join(root, 'devtools', 'texel', 'server', 'vendor');
const texelStagedGuava = path.join(root, 'build', 'devtool', 'libs', 'guava.jar');
const libsDir = path.join(root, 'libs');
const SEP = path.delimiter;

// ---------------------------------------------------------------- CLI args

const argv = process.argv.slice(2);
function argValue(name) {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : null;
}
const port = Number(argValue('--port') ?? 5167);

// Asset roots, per category.
const blockRoots = [];
const mobRoots = [];
const itemRoots = [];

const blockDefault = path.join(root, 'examples', 'mineplus-fun', 'src', 'main', 'resources', 'defaults', 'models');
if (fs.existsSync(blockDefault)) blockRoots.push(blockDefault);
const blockServer = path.join(root, 'plugins', 'Mineplus', 'models');
if (fs.existsSync(blockServer)) blockRoots.push(blockServer);

const mobMobfun = path.join(root, 'examples', 'mineplus-mobfun', 'src', 'main', 'resources', 'mobs');
if (fs.existsSync(mobMobfun)) mobRoots.push(mobMobfun);
const mobServer = path.join(root, 'plugins', 'Mineplus', 'mobs');
if (fs.existsSync(mobServer)) mobRoots.push(mobServer);
const mobCoreExamples = path.join(root, 'src', 'main', 'resources', 'mobs');
if (fs.existsSync(mobCoreExamples)) mobRoots.push(mobCoreExamples);

const itemDefault = path.join(root, 'examples', 'mineplus-gun', 'src', 'main', 'resources', 'defaults', 'guns');
if (fs.existsSync(itemDefault)) itemRoots.push(itemDefault);

const allRoots = [...blockRoots, ...mobRoots, ...itemRoots];

// ---------------------------------------------------------------- logging + SSE

const log = (...a) => console.log('[asset-lab]', ...a);
const sseClients = new Set();

function sseSend(event) {
  const payload = `data: ${JSON.stringify(event)}\n\n`;
  for (const res of sseClients) res.write(payload);
}

// ---------------------------------------------------------------- JDK resolution

// Prefer the JDK from JAVA_HOME over whatever `javac`/`java` the PATH resolves
// to (vendor shims can front a newer JDK that crashes on this source set).
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

// ---------------------------------------------------------------- guava discovery

// The vendored paper-api jar needs guava at *runtime* for Material's static
// initializer; staged into build/asset-lab/libs from (in order) this tool's
// staged copy, its vendor dir, the texel tool's copies (read-only reuse), or
// the local Gradle cache.
let guavaJar = null;
async function findGuava() {
  const staged = path.join(buildDir, 'libs', 'guava.jar');
  if (fs.existsSync(staged)) return staged;
  const own = path.join(vendorDir, 'guava.jar');
  if (fs.existsSync(own)) return own;
  if (fs.existsSync(texelStagedGuava)) return texelStagedGuava;
  const texelVendored = path.join(texelVendorDir, 'guava.jar');
  if (fs.existsSync(texelVendored)) return texelVendored;
  const home = process.env.HOME ?? process.env.USERPROFILE ?? '~';
  const gradleCache = path.join(home, '.gradle', 'caches', 'modules-2', 'files-2.1', 'com.google.guava', 'guava');
  if (fs.existsSync(gradleCache)) {
    const candidates = [];
    async function walk(dir, depth) {
      if (depth > 4) return;
      for (const entry of await fsp.readdir(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) await walk(full, depth + 1);
        else if (/^guava-\d[\d.]*-jre\.jar$/.test(entry.name)) candidates.push(full);
      }
    }
    await walk(gradleCache, 0);
    if (candidates.length > 0) {
      await fsp.mkdir(path.join(buildDir, 'libs'), { recursive: true });
      await fsp.copyFile(candidates[0], staged);
      log('staged guava from Gradle cache:', candidates[0]);
      return staged;
    }
  }
  return null;
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

function runJavac(args, cwd) {
  return new Promise((resolve) => {
    const child = spawn(javacCmd, args, { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '', stderr = '';
    child.stdout.on('data', d => { stdout += d; });
    child.stderr.on('data', d => { stderr += d; });
    child.on('error', err => resolve({ code: -1, output: String(err) }));
    child.on('close', code => resolve({ code, output: stderr + stdout }));
  });
}

let compileSeq = 0;
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
  if (main.code !== 0) return fail('main compile', main.output);
  const stubs = await runJavac(['-d', stubsOut, ...stubSources], root);
  if (stubs.code !== 0) return fail('stub compile', stubs.output);
  const daemon = await runJavac(['--class-path', [mainOut, cp].join(SEP), '-d', daemonOut, '-encoding', 'UTF-8', ...daemonSources], root);
  if (daemon.code !== 0) return fail('daemon compile', daemon.output);

  log('compiled in', ((Date.now() - t0) / 1000).toFixed(1) + 's');
  sseSend({ type: 'code', status: 'ok', ms: Date.now() - t0 });
  await restartDaemon();
  return true;

  function fail(stage, output) {
    log('COMPILE FAILED (' + stage + '):\n' + output);
    sseSend({ type: 'code', status: 'error', stage, output: output.slice(0, 20000) });
    return false;
  }
}

// ---------------------------------------------------------------- daemon lifecycle

let daemon = null;
let daemonStarting = false;
let daemonReqSeq = 0;
const pendingRequests = new Map();
let daemonStdoutBuf = '';
let daemonRestarts = 0;

async function daemonClasspath() {
  guavaJar = guavaJar ?? await findGuava();
  const parts = [mainOut, daemonOut, stubsOut, ...libJars()];
  if (guavaJar) parts.push(guavaJar);
  return parts.join(SEP);
}

async function startDaemon() {
  if (daemon || daemonStarting) return;
  daemonStarting = true;
  const cp = await daemonClasspath();
  if (!guavaJar) {
    log('WARNING: guava not found — daemon may fail to load Bukkit classes.',
      'Place a guava jar at', path.join(vendorDir, 'guava.jar'), 'if ops error.');
  }
  daemon = spawn(javaCmd, ['-cp', cp, 'com.mineplus.devtools.assetlab.AssetDaemon'], { cwd: root });
  daemonStarting = false;
  log('daemon started (pid ' + daemon.pid + ')');
  sseSend({ type: 'daemon', status: 'up' });

  daemon.stdout.setEncoding('utf8');
  daemon.stdout.on('data', chunk => {
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
  daemon.stderr.setEncoding('utf8');
  daemon.stderr.on('data', chunk => sseSend({ type: 'daemon-log', text: chunk }));

  daemon.on('exit', (code) => {
    daemon = null;
    for (const { reject } of pendingRequests.values()) {
      reject(new Error('daemon exited (code ' + code + ')'));
    }
    pendingRequests.clear();
    sseSend({ type: 'daemon', status: 'down', code });
    if (daemonRestarts < 10) {
      daemonRestarts++;
      log('daemon exited (code ' + code + '), restarting #' + daemonRestarts);
      setTimeout(() => startDaemon().catch(e => log('daemon restart failed:', e.message)), 500);
    } else {
      log('daemon exited; giving up after 10 restarts');
    }
  });
}

async function stopDaemon() {
  if (!daemon) return;
  const d = daemon;
  daemon = null;
  await new Promise(resolve => {
    d.on('exit', resolve);
    d.stdin.end();
    setTimeout(() => { try { d.kill(); } catch {} resolve(); }, 2000);
  });
}

async function restartDaemon() {
  daemonRestarts = 0;
  await stopDaemon();
  await startDaemon();
}

function daemonRequest(payload, timeoutMs = 60000) {
  return new Promise((resolve, reject) => {
    if (!daemon || daemon.exitCode !== null) {
      reject(new Error('daemon not running (still compiling, or javac failed — see the status bar)'));
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
    daemon.stdin.write(JSON.stringify({ id, ...payload }) + '\n');
  });
}

// ---------------------------------------------------------------- inventory

async function walkFiles(dir, filter) {
  const out = [];
  async function walk(d) {
    let entries;
    try { entries = await fsp.readdir(d, { withFileTypes: true }); } catch { return; }
    for (const e of entries) {
      const full = path.join(d, e.name);
      if (e.isDirectory()) await walk(full);
      else if (filter(e.name, full)) out.push(full);
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
      guns.push({
        gun: e.name,
        root: dir,
        dir: gunDir,
        frames,
        sources,
        textures,
      });
    }
  }
  guns.sort((a, b) => a.gun.localeCompare(b.gun));

  return { blocks, mobDefs, mobModels, mobTextures, guns, roots: { blockRoots, mobRoots, itemRoots } };
}

// ---------------------------------------------------------------- watchers

function debounce(fn, ms) {
  let t = null;
  return (...args) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...args), ms);
  };
}

const debouncedRecompile = debounce(() => { compileAll('source change').catch(e => log('compile error:', e)); }, 400);
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

watchRecursive(path.join(root, 'src', 'main', 'java'), 'sources', debouncedRecompile);
watchRecursive(daemonJavaDir, 'sources', debouncedRecompile);
watchRecursive(stubsDir, 'sources', debouncedRecompile);
for (const dir of allRoots) {
  watchRecursive(dir, 'assets', debouncedAssets);
}

// ---------------------------------------------------------------- HTTP

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(body);
}

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return Buffer.concat(chunks);
}

function isInsideRoots(absPath, roots) {
  const resolved = path.resolve(absPath);
  return roots.some(rootDir => resolved.startsWith(path.resolve(rootDir) + path.sep));
}

async function proxyOp(res, op, body, roots, extra) {
  const filePath = path.resolve(body.model ?? body.yml ?? '');
  if (!isInsideRoots(filePath, roots)) {
    sendJson(res, 400, { ok: false, error: 'path outside this tool\'s watched roots' });
    return;
  }
  try {
    const reply = await daemonRequest({ op, ...body, ...extra });
    sendJson(res, reply.ok ? 200 : 400, reply);
  } catch (e) {
    sendJson(res, 500, { ok: false, error: e.message });
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const pathname = decodeURIComponent(url.pathname);

  try {
    // ---- SSE event stream
    if (pathname === '/api/events') {
      res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive',
      });
      res.write('\n');
      sseClients.add(res);
      req.on('close', () => sseClients.delete(res));
      return;
    }

    // ---- inventory
    if (pathname === '/api/inventory') {
      sendJson(res, 200, await scanInventory());
      return;
    }

    // ---- op proxies
    if (pathname === '/api/block' && req.method === 'POST') {
      const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
      await proxyOp(res, 'block', body, [...blockRoots, ...itemRoots]);
      return;
    }
    if (pathname === '/api/mob' && req.method === 'POST') {
      const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
      await proxyOp(res, 'mob', body, mobRoots);
      return;
    }
    if (pathname === '/api/mob-poses' && req.method === 'POST') {
      const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
      await proxyOp(res, 'poses', body, mobRoots);
      return;
    }
    if (pathname === '/api/item' && req.method === 'POST') {
      const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
      // Default texture root: the gun folder (parent of models/), where <gun>.png lives.
      const extra = body.textureRoot ? {} : { textureRoot: path.dirname(path.dirname(path.resolve(body.model))) };
      await proxyOp(res, 'item', body, itemRoots, extra);
      return;
    }
    if (pathname === '/api/audit' && req.method === 'POST') {
      const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
      const inventory = await scanInventory();
      const request = {
        blocks: body.blocks ?? inventory.blocks.map(b => b.file),
        mobs: body.mobs ?? inventory.mobDefs.map(m => m.file),
        mobModels: body.mobModels ?? inventory.mobModels.map(m => m.file),
        items: body.items ?? inventory.guns.flatMap(g => g.frames.map(f => f.file)),
      };
      try {
        const reply = await daemonRequest({ op: 'audit', ...request }, 120000);
        sendJson(res, reply.ok ? 200 : 400, reply);
      } catch (e) {
        sendJson(res, 500, { ok: false, error: e.message });
      }
      return;
    }

    // ---- raw asset (texture PNGs) restricted to watched roots
    if (pathname === '/api/asset') {
      const p = url.searchParams.get('p');
      if (!p || !isInsideRoots(p, allRoots)) { res.writeHead(403); res.end(); return; }
      const data = await fsp.readFile(p);
      res.writeHead(200, { 'Content-Type': MIME[path.extname(p).toLowerCase()] ?? 'application/octet-stream' });
      res.end(data);
      return;
    }

    // ---- force recompile
    if (pathname === '/api/recompile' && req.method === 'POST') {
      compileAll('manual').catch(e => log('compile error:', e));
      sendJson(res, 200, { ok: true, started: true });
      return;
    }

    // ---- static app files
    let rel = pathname === '/' ? '/index.html' : pathname;
    const file = path.normalize(path.join(appDir, rel));
    if (!file.startsWith(appDir)) { res.writeHead(403); res.end(); return; }
    try {
      const data = await fsp.readFile(file);
      res.writeHead(200, { 'Content-Type': MIME[path.extname(file).toLowerCase()] ?? 'application/octet-stream' });
      res.end(data);
    } catch {
      res.writeHead(404); res.end('not found');
    }
  } catch (e) {
    sendJson(res, 500, { ok: false, error: e.message });
  }
});

// ---------------------------------------------------------------- startup

log('javac:', javacCmd);
await compileAll('startup');
if (!fs.existsSync(appDir)) {
  log('WARNING: app dir missing:', appDir);
}
server.listen(port, () => {
  log('asset lab ready: http://localhost:' + port);
  log('block roots:', blockRoots.join(', ') || '(none)');
  log('mob roots:', mobRoots.join(', ') || '(none)');
  log('item roots:', itemRoots.join(', ') || '(none)');
});
