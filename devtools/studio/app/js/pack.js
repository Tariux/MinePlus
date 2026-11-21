// Pack tab: the compiled resource-pack artifact itself.
//
// This is the highest-fidelity bridge in the tool — an `mp-<hash>.zip` is the
// byte-exact file the client downloads, so a model rendered from it is not a
// preview of the pipeline, it is the asset the game loads. The viewport draws
// the shipped element JSON with the shipped PNG, flat, because the emitted
// models carry `shade:false` + `ambientocclusion:false`.

import { postJson } from './api.js';
import { esc, chip, section, kv, texThumb } from './ui.js';

const TEXTURE_CACHE_LIMIT = 200;

const state = {
    artifacts: [],
    file: null,
    tree: null,
    entry: null,
    textures: new Map(), // "<file>|<entry>" -> THREE.Texture
    textureByPath: new Map(),
    filter: '',
};

export function initPack(ctx) {
    const inv = ctx.inventory();
    state.artifacts = inv.packArtifacts ?? [];
    const el = ctx.el.listPack;
    el.innerHTML = `
      <h2>Resource pack</h2>
      <p class="muted small">The compiled <code>mp-&lt;hash&gt;.zip</code> the client
      actually downloads — models, textures and generated blockstates.</p>
      <ul class="plain selectable">${state.artifacts.map(a => `
        <li class="row" data-file="${esc(a.file)}">
            <span class="row-name">${esc(a.name)}</span>
            <span class="row-hint">${(a.size / 1024).toFixed(0)} KB</span>
        </li>`).join('') || '<li class="muted">no artifact found — start the server or run <code>npm run pack</code></li>'}</ul>
      ${scannedDirsHtml(inv.packScan)}`;
    for (const row of el.querySelectorAll('li.row[data-file]')) {
        row.addEventListener('click', () => ctx.open('pack', row.dataset.file));
    }
}

function scannedDirsHtml(dirs) {
    if (!dirs || dirs.length === 0) return '';
    return `<p class="muted small">scanned: ${dirs.map(d =>
        `${esc(d.dir)}${d.exists ? '' : ' <span class="muted">(absent)</span>'}`).join('<br>')}</p>`;
}

export async function openPack(ctx, file) {
    state.file = file;
    state.tree = null;
    state.entry = null;
    ctx.el.listPack.querySelectorAll('li.row').forEach(li =>
        li.classList.toggle('selected', li.dataset.file === file));
    ctx.details(`<h2>${esc(shortName(file))}</h2><p class="muted">reading the artifact…</p>`);
    const response = await postJson('/api/pack', { action: 'tree', file });
    if (response.error) {
        ctx.details(`<h2>${esc(shortName(file))}</h2>
          <div class="card verdict"><span class="chip broken">ERROR</span>
          <p>${esc(response.error)}</p></div>`);
        return response;
    }
    state.tree = response;
    renderTree(ctx);
    return response;
}

function shortName(file) {
    return String(file).split(/[\\/]/).pop();
}

function renderTree(ctx) {
    const tree = state.tree;
    const entries = tree.entries ?? [];
    const byKind = groupBy(entries, e => e.kind);
    ctx.details(`
      <h2>${esc(shortName(state.file))}</h2>
      <div class="card verdict">
        ${chip('OK')}
        <p>${entries.length} entries — this is the exact byte stream the client receives.</p>
      </div>
      ${section('Artifact', kv([
          ['file', esc(shortName(state.file))],
          ['size', `${((tree.size ?? 0) / 1024).toFixed(1)} KB`],
          ['entries', entries.length],
          ['models', (byKind.model ?? []).length],
          ['textures', (byKind.texture ?? []).length],
          ['blockstates', (byKind.blockstate ?? []).length],
      ]))}
      ${section('pack.mcmeta', tree.mcmeta
        ? `<pre class="code">${esc(tree.mcmeta)}</pre>` : '<span class="muted">none</span>')}
      ${section('Browse', `
        <input type="search" id="pack-filter" placeholder="filter entries…" value="${esc(state.filter)}"
          class="small">
        <div id="pack-entries">${entryListHtml(entries)}</div>`)}
      ${section('Entry', `<div id="pack-detail"><p class="muted">Pick an entry to read it —
        a model renders in the viewport exactly as the client draws it.</p></div>`)}
    `);
    const filter = document.getElementById('pack-filter');
    filter.addEventListener('input', () => {
        state.filter = filter.value;
        document.getElementById('pack-entries').innerHTML = entryListHtml(entries);
        bindEntries(ctx);
    });
    bindEntries(ctx);
}

function entryListHtml(entries) {
    const needle = state.filter.toLowerCase();
    const shown = entries.filter(e => !needle || e.path.toLowerCase().includes(needle));
    if (shown.length === 0) return '<p class="muted">no matching entry</p>';
    const grouped = groupBy(shown, e => e.kind);
    return Object.entries(grouped).map(([kind, items]) => `
      <h4 class="mono">${esc(kind)} <span class="muted">×${items.length}</span></h4>
      <ul class="plain selectable tight">${items.map(e => `
        <li class="row small" data-entry="${esc(e.path)}">
          <span class="row-name mono">${esc(shortName(e.path))}</span>
          <span class="row-hint">${e.size > 1024 ? (e.size / 1024).toFixed(1) + ' KB' : e.size + ' B'}</span>
        </li>`).join('')}</ul>`).join('');
}

function bindEntries(ctx) {
    for (const li of document.querySelectorAll('#pack-entries li[data-entry]')) {
        li.addEventListener('click', () => readEntry(ctx, li.dataset.entry));
    }
}

async function readEntry(ctx, entryPath) {
    const box = document.getElementById('pack-detail');
    box.innerHTML = '<p class="muted">reading…</p>';
    const response = await postJson('/api/pack', { action: 'entry', file: state.file, entry: entryPath });
    state.entry = response;
    if (response.error) {
        box.innerHTML = `<p class="broken-text">${esc(response.error)}</p>`;
        return;
    }
    if (response.kind === 'texture' && response.pngBase64) {
        box.innerHTML = `<p>${texThumb({ pngBase64: response.pngBase64 }, 'thumb big')}</p>
          <p class="muted small">${esc(response.path)} · ${response.width}×${response.height}</p>`;
        return;
    }
    const parsed = safeJson(response.text);
    if (parsed && Array.isArray(parsed.elements)) {
        const flat = parsed.elements.some(e => e.shade === false) || parsed.ambientocclusion === false;
        box.innerHTML = `<p><button class="btn small" id="pack-render">Render in viewport</button>
            <span class="muted small">${parsed.elements.length} element(s)${flat
                ? ' · shipped flat' : ''}</span></p>
          <pre class="code">${esc(JSON.stringify(parsed, null, 2))}</pre>`;
        document.getElementById('pack-render').addEventListener('click', async () => {
            const textures = await preloadModelTextures(ctx, parsed);
            ctx.viewer.clear();
            ctx.viewer.renderPackModel(parsed, key => textures.get(key) ?? null);
            ctx.setLayer('pack', true);
            ctx.shading = { kind: 'pack-model', faceShade: !flat,
                reason: 'the shipped model JSON is the client’s own asset' };
        });
        return;
    }
    box.innerHTML = `<pre class="code">${esc(response.text ?? '(binary entry)')}</pre>`;
}

/**
 * Resolves a model's `#texture` references against the PNGs shipped beside them
 * in the same artifact, so the viewport draws the file the client loads rather
 * than a repo-side copy that may differ.
 */
async function preloadModelTextures(ctx, model) {
    const index = textureIndex();
    const out = new Map();
    for (const [key, value] of Object.entries(model.textures ?? {})) {
        const name = key.startsWith('#') ? key.slice(1) : key;
        const entry = lookupTexture(index, value);
        if (!entry) continue;
        const cacheKey = state.file + '|' + entry;
        let texture = state.textures.get(cacheKey);
        if (texture) { out.set(name, texture); continue; }
        const response = await postJson('/api/pack', { action: 'entry', file: state.file, entry });
        if (!response.pngBase64) continue;
        texture = ctx.viewer.textureFromBase64(response.pngBase64);
        if (state.textures.size > TEXTURE_CACHE_LIMIT) state.textures.clear();
        state.textures.set(cacheKey, texture);
        out.set(name, texture);
    }
    return out;
}

/** `assets/<ns>/textures/<path>.png` indexed by `ns:path` and by bare `path`. */
function textureIndex() {
    const map = new Map();
    for (const entry of state.tree?.entries ?? []) {
        if (entry.kind !== 'texture') continue;
        const found = /^assets\/([^/]+)\/textures\/(.+)\.png$/.exec(entry.path);
        if (!found) continue;
        const [, namespace, rest] = found;
        map.set(`${namespace}:${rest}`, entry.path);
        if (!map.has(rest)) map.set(rest, entry.path);
    }
    return map;
}

function lookupTexture(index, value) {
    if (typeof value !== 'string') return null;
    const key = value.replace(/^#/, '');
    if (!key) return null;
    return index.get(key)
        ?? (key.includes(':') ? null : index.get('minecraft:' + key))
        ?? null;
}

function safeJson(text) {
    try { return JSON.parse(text); } catch { return null; }
}

function groupBy(items, keyOf) {
    const out = {};
    for (const item of items) {
        const key = keyOf(item) ?? 'other';
        (out[key] ??= []).push(item);
    }
    return out;
}
