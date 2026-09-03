// Mineplus Texel Devtool — development server.
//
// Responsibilities:
//   - compile the pipeline (src/main/java) + daemon (devtools/texel/java) with javac
//   - own the BakeDaemon JVM process (spawn, proxy JSON requests, restart on crash)
//   - hot reload: watch pipeline sources -> recompile -> restart daemon -> SSE event
//   - watch model/texture asset roots -> SSE event (the page re-bakes)
//   - serve the app (devtools/texel/app) + HTTP API (bake proxy, model tree, assets)
//
// Run:  node devtools/texel/server/server.mjs [--port 5166] [--models <dir>]...
// Zero npm dependencies (uses node:http, node:fs watchers, and SSE instead of WS).

import http from 'node:http';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';

const serverDir = import.meta.dirname;
const toolDir = path.dirname(serverDir);                    // devtools/texel
const root = path.resolve(toolDir, '..', '..');             // repo root
const appDir = path.join(toolDir, 'app');
const daemonJavaDir = path.join(toolDir, 'java');
const stubsDir = path.join(toolDir, 'java-stubs');
const buildDir = path.join(root, 'build', 'devtool');
const mainOut = path.join(root, 'build', 'classes', 'java', 'main');
const devtoolOut = path.join(root, 'build', 'classes', 'java', 'devtool');
const stubsOut = path.join(root, 'build', 'classes', 'java', 'devtool-stubs');
const vendorDir = path.join(serverDir, 'vendor');
const libsDir = path.join(root, 'libs');
const SEP = path.delimiter;

// ---------------------------------------------------------------- CLI args

const argv = process.argv.slice(2);
function argValue(name) {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : null;
}
const port = Number(argValue('--port') ?? 5166);
const extraModelRoots = [];
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--models' && i + 1 < argv.length) {
    extraModelRoots.push(path.resolve(argv[++i]));
  }
}

const modelRoots = [...extraModelRoots];
const defaultModelRoot = path.join(root, 'examples', 'mineplus-fun', 'src', 'main', 'resources', 'defaults', 'models');
if (fs.existsSync(defaultModelRoot)) modelRoots.push(defaultModelRoot);
const serverModelRoot = path.join(root, 'plugins', 'Mineplus', 'models');
if (fs.existsSync(serverModelRoot)) modelRoots.push(serverModelRoot);

// ---------------------------------------------------------------- logging + SSE

const log = (...a) => console.log('[devtool]', ...a);
const sseClients = new Set();

function sseSend(event) {
  const payload = `data: ${JSON.stringify(event)}\n\n`;
  for (const res of sseClients) res.write(payload);
}

// ---------------------------------------------------------------- guava discovery

// The vendored paper-api jar needs guava + kyori examination at *runtime* for
// Material's static initializer; the server stages guava from the local Gradle
// cache (or devtools/texel/server/vendor/guava.jar) into build/devtool/libs.
let guavaJar = null;
async function findGuava() {
  const staged = path.join(buildDir, 'libs', 'guava.jar');
  if (fs.existsSync(staged)) return staged;
  const vendored = path.join(vendorDir, 'guava.jar');
  if (fs.existsSync(vendored)) return vendored;
  const gradleCache = path.join(process.env.USERPROFILE ?? '~', '.gradle', 'caches', 'modules-2', 'files-2.1', 'com.google.guava', 'guava');
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

const libJars = () => fs.readdirSync(libsDir).filter(f => f.endsWith('.jar')).map(f => path.join(libsDir, f));

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
    const child = spawn('javac', args, { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
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
  log('compiling pipeline (' + reason + ')...');
  const t0 = Date.now();

  await fsp.mkdir(mainOut, { recursive: true });
  await fsp.mkdir(devtoolOut, { recursive: true });
  await fsp.mkdir(stubsOut, { recursive: true });

  const cp = [...libJars()].join(SEP);
  const mainSources = await listJavaFiles(path.join(root, 'src', 'main', 'java'));
  const daemonSources = await listJavaFiles(daemonJavaDir);
  const stubSources = await listJavaFiles(stubsDir);

  const main = await runJavac(['--class-path', cp, '-d', mainOut, '-encoding', 'UTF-8', ...mainSources], root);
  if (main.code !== 0) return fail('main compile', main.output, seq);
  const stubs = await runJavac(['-d', stubsOut, ...stubSources], root);
  if (stubs.code !== 0) return fail('stub compile', stubs.output, seq);
  const daemon = await runJavac(['--class-path', [mainOut, cp].join(SEP), '-d', devtoolOut, '-encoding', 'UTF-8', ...daemonSources], root);
  if (daemon.code !== 0) return fail('daemon compile', daemon.output, seq);

  log('compiled in', ((Date.now() - t0) / 1000).toFixed(1) + 's');
  sseSend({ type: 'code', status: 'ok', ms: Date.now() - t0 });
  await restartDaemon();
  return true;

  function fail(stage, output, seq2) {
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
  const parts = [mainOut, devtoolOut, stubsOut, ...libJars()];
  if (guavaJar) parts.push(guavaJar);
  return parts.join(SEP);
}

async function startDaemon() {
  if (daemon || daemonStarting) return;
  daemonStarting = true;
  const cp = await daemonClasspath();
  if (!guavaJar) {
    log('WARNING: guava not found — daemon may fail to load Material.',
      'Place a guava jar at', path.join(vendorDir, 'guava.jar'), 'if baking errors.');
  }
  daemon = spawn('java', ['-cp', cp, 'com.mineplus.devtools.texel.BakeDaemon'], { cwd: root });
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
      reject(new Error('daemon not running'));
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

// ---------------------------------------------------------------- model tree

async function scanModels() {
  const roots = [];
  for (const dir of modelRoots) {
    const models = [];
    async function walk(d) {
      let entries;
      try { entries = await fsp.readdir(d, { withFileTypes: true }); } catch { return; }
      for (const e of entries) {
        const full = path.join(d, e.name);
        if (e.isDirectory()) await walk(full);
        else if (e.name.endsWith('.bbmodel')) {
          const key = e.name.slice(0, -'.bbmodel'.length);
          models.push({
            key,
            file: full,
            hasMeta: fs.existsSync(full.slice(0, -9) + '.meta.json'),
          });
        }
      }
    }
    await walk(dir);
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

const debouncedRecompile = debounce(() => { compileAll('source change').catch(e => log('compile error:', e)); }, 400);

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
for (const dir of modelRoots) {
  watchRecursive(dir, 'assets', debounce(() => sseSend({ type: 'assets' }), 200));
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

function isInsideRoots(absPath) {
  const resolved = path.resolve(absPath);
  return modelRoots.some(rootDir => resolved.startsWith(path.resolve(rootDir) + path.sep));
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

    // ---- bake proxy
    if (pathname === '/api/bake' && req.method === 'POST') {
      const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
      const modelPath = path.resolve(body.model ?? '');
      if (!isInsideRoots(modelPath)) {
        sendJson(res, 400, { ok: false, error: 'model path outside watched roots' });
        return;
      }
      const reply = await daemonRequest({
        op: 'bake',
        model: modelPath,
        textureRoot: body.textureRoot ? path.resolve(body.textureRoot) : undefined,
        overrides: body.overrides ?? {},
      });
      sendJson(res, reply.ok ? 200 : 400, reply);
      return;
    }

    // ---- model tree
    if (pathname === '/api/models') {
      sendJson(res, 200, { roots: await scanModels() });
      return;
    }

    // ---- raw asset (texture PNGs) restricted to watched roots
    if (pathname === '/api/asset') {
      const p = url.searchParams.get('p');
      if (!p || !isInsideRoots(p)) { res.writeHead(403); res.end(); return; }
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

await compileAll('startup');
if (!fs.existsSync(appDir)) {
  log('WARNING: app dir missing:', appDir);
}
server.listen(port, () => {
  log('texel devtool ready: http://localhost:' + port);
  log('model roots:', modelRoots.join(', '));
});
