// Main wiring: model list, per-model config loading, settings, layers,
// diagnostics, and the hot-reload loop.
//
// Bake sequencing: every bake carries a generation token; only the newest
// generation may touch the viewer/state. Older in-flight responses are dropped,
// and a bake requested while another is running is queued as latest-wins —
// no lost clicks, no stale overwrites (the "strange state errors" class).
//
// Config loading: selecting a model resets the settings panel to the standard
// defaults, then applies the model's .meta.json (reported by the daemon as
// `meta`) as the model-specific defaults. Fields the user edits afterwards are
// tracked in `userOverrides` and sent as explicit meta overrides, so user edits
// win over the file, exactly once per field, per model.

import { bake, fetchModels, assetUrl, requestRecompile, subscribeEvents } from './api.js';
import { cssRgb } from './geom.js';
import { Viewer } from './viewer.js';

const STANDARD_DEFAULTS = {
    texelMode: 'AUTO',
    texelDetail: 'FACE',
    maxPlatesPerFace: 96,
    maxPlatesPerInstance: 150,
    maxGridEdge: 64,
    voxelMode: 'AUTO',
    maxVoxelDisplays: 1024,
    originMode: 'AUTO',
};

// UI setting key -> daemon meta override key.
const META_KEY_BY_SETTING = {
    texelMode: 'texelMode',
    texelDetail: 'texelDetail',
    maxPlatesPerFace: 'maxTexelPlatesPerFace',
    maxPlatesPerInstance: 'maxTexelPlatesPerInstance',
    voxelMode: 'voxelMode',
    maxVoxelDisplays: 'maxVoxelDisplays',
    originMode: 'originMode',
};

const state = {
    modelFile: null,
    result: null,
    bakeGeneration: 0,
    rebakePending: false,
    settings: { ...STANDARD_DEFAULTS },
    userOverrides: {},   // setting keys the user changed since model load
    fileMeta: {},        // the model's .meta.json (daemon-reported)
};

const viewer = new Viewer(document.getElementById('viewport'));
const els = {
    status: document.getElementById('status-text'),
    dotCode: document.getElementById('dot-code'),
    dotDaemon: document.getElementById('dot-daemon'),
    modelList: document.getElementById('model-list'),
    textureList: document.getElementById('texture-list'),
    diagnostics: document.getElementById('diagnostics'),
    settingsBox: document.getElementById('settings'),
};

// ---------------------------------------------------------------- status

function setStatus(text) { els.status.textContent = text; }

function setDot(el, cls) {
    el.classList.remove('ok', 'warn', 'err');
    if (cls) el.classList.add(cls);
}

// ---------------------------------------------------------------- models

async function loadModels() {
    const data = await fetchModels();
    els.modelList.innerHTML = '';
    for (const root of data.roots) {
        for (const model of root.models) {
            const li = document.createElement('li');
            const label = document.createElement('span');
            label.textContent = model.key;
            li.appendChild(label);
            li.title = model.file + (model.hasMeta ? ' (has .meta.json)' : '');
            if (model.hasMeta) {
                const badge = document.createElement('span');
                badge.className = 'badge';
                badge.textContent = 'meta';
                li.appendChild(badge);
            }
            li.dataset.file = model.file;
            // Selection always triggers the bake immediately — no manual step.
            li.addEventListener('click', () => selectModel(model, li));
            els.modelList.appendChild(li);
        }
    }
    const first = els.modelList.querySelector('li');
    if (first) first.click();
}

function selectModel(model, li) {
    for (const other of els.modelList.children) other.classList.remove('selected');
    li.classList.add('selected');
    state.modelFile = model.file;
    // New model: standard defaults, then its .meta.json on first bake result;
    // any user overrides from the previous model are discarded.
    state.userOverrides = {};
    state.fileMeta = {};
    state.settings = { ...STANDARD_DEFAULTS };
    syncSettingsUi();
    requestBake();
}

// ---------------------------------------------------------------- baking

function requestBake() {
    if (state.inFlight) {
        state.rebakePending = true; // queued as latest-wins behind the running bake
        return;
    }
    startBake();
}

async function startBake() {
    if (!state.modelFile) return;
    state.inFlight = true;
    const generation = ++state.bakeGeneration;
    setStatus('baking…');
    setDot(els.dotDaemon, 'warn');
    try {
        const overrides = { ...state.settings };
        // User-edited fields are sent as explicit meta overrides so they win
        // over the model's .meta.json; everything else stays a global default.
        const meta = {};
        for (const [setting, metaKey] of Object.entries(META_KEY_BY_SETTING)) {
            if (state.userOverrides[setting] !== undefined) {
                meta[metaKey] = state.settings[setting];
            }
        }
        if (Object.keys(meta).length > 0) overrides.meta = meta;

        const result = await bake(state.modelFile, overrides);
        if (generation !== state.bakeGeneration) {
            return; // a newer bake superseded this response — drop it
        }
        if (!result.ok) {
            setStatus('bake failed: ' + (result.error ?? 'unknown'));
            setDot(els.dotDaemon, 'err');
            els.diagnostics.innerHTML =
                `<p class="rationale">${escapeHtml(result.errorType ?? 'Error')}: ${escapeHtml(result.error ?? '')}</p>`;
        } else {
            applyModelDefaults(result);
            state.result = result;
            viewer.render(result);
            renderTextures(result);
            renderDiagnostics(result);
            setStatus(`${result.key} — ${result.texel.totalPlates} plates · strategy ${result.voxel.strategy} · `
                + `texel ${result.texel.bakeTimeMs.toFixed(1)}ms`);
            setDot(els.dotDaemon, 'ok');
        }
    } catch (err) {
        if (generation === state.bakeGeneration) {
            setStatus('bake error: ' + err.message);
            setDot(els.dotDaemon, 'err');
        }
    } finally {
        state.inFlight = false;
        if (state.rebakePending) {
            state.rebakePending = false;
            startBake();
        }
    }
}

// ---------------------------------------------------------------- per-model config

/**
 * Applies the model's own default settings on load: fields present in its
 * .meta.json (daemon `meta`) become the panel values unless the user already
 * edited that field for this model; absent fields keep the standard defaults.
 */
function applyModelDefaults(result) {
    const meta = result.meta ?? {};
    const changed = !shallowEqual(state.fileMeta, meta);
    state.fileMeta = meta;
    if (!changed && state.loadedForKey === result.key) return;
    state.loadedForKey = result.key;

    state.settings = { ...STANDARD_DEFAULTS };
    for (const [setting, metaKey] of Object.entries(META_KEY_BY_SETTING)) {
        if (meta[metaKey] !== undefined) {
            state.settings[setting] = meta[metaKey];
        }
        // Re-apply user edits made before the meta arrived (e.g. a re-bake
        // after code hot reload): the user's explicit choice always wins.
        if (state.userOverrides[setting] !== undefined) {
            state.settings[setting] = state.userOverrides[setting];
        }
    }
    syncSettingsUi();
}

function shallowEqual(a, b) {
    const ka = Object.keys(a), kb = Object.keys(b);
    return ka.length === kb.length && ka.every(k => a[k] === b[k]);
}

/** Pushes state.settings into the inputs and refreshes "meta" badges. */
function syncSettingsUi() {
    document.getElementById('s-texelMode').value = state.settings.texelMode;
    document.getElementById('s-texelDetail').value = state.settings.texelDetail;
    document.getElementById('s-maxPlatesPerFace').value = state.settings.maxPlatesPerFace;
    document.getElementById('s-maxPlatesPerInstance').value = state.settings.maxPlatesPerInstance;
    document.getElementById('s-maxGridEdge').value = state.settings.maxGridEdge;
    document.getElementById('s-voxelMode').value = state.settings.voxelMode;
    document.getElementById('s-maxVoxelDisplays').value = state.settings.maxVoxelDisplays;
    document.getElementById('s-originMode').value = state.settings.originMode;

    for (const label of els.settingsBox.querySelectorAll('label')) {
        const control = label.querySelector('select, input');
        if (!control || !control.id) continue;
        const setting = SETTING_BY_CONTROL_ID[control.id];
        if (!setting) continue;
        let badge = label.querySelector('.badge.meta-source');
        const fromMeta = state.fileMeta[META_KEY_BY_SETTING[setting]] !== undefined
            && state.userOverrides[setting] === undefined;
        if (fromMeta && !badge) {
            badge = document.createElement('span');
            badge.className = 'badge meta-source';
            badge.textContent = 'meta';
            label.appendChild(badge);
        } else if (!fromMeta && badge) {
            badge.remove();
        }
    }
}

const SETTING_BY_CONTROL_ID = {
    's-texelMode': 'texelMode',
    's-texelDetail': 'texelDetail',
    's-maxPlatesPerFace': 'maxPlatesPerFace',
    's-maxPlatesPerInstance': 'maxPlatesPerInstance',
    's-maxGridEdge': 'maxGridEdge',
    's-voxelMode': 'voxelMode',
    's-maxVoxelDisplays': 'maxVoxelDisplays',
    's-originMode': 'originMode',
};

// ---------------------------------------------------------------- panels

function renderTextures(result) {
    els.textureList.innerHTML = '';
    for (const texture of result.textures) {
        const li = document.createElement('li');
        if (texture.resolved) {
            const img = document.createElement('img');
            img.src = assetUrl(texture.path);
            li.appendChild(img);
            const name = document.createElement('span');
            name.textContent = texture.name;
            name.style.alignSelf = 'center';
            li.appendChild(name);
            const badge = document.createElement('span');
            badge.className = 'badge ok';
            badge.textContent = texture.width + '×' + texture.height;
            li.appendChild(badge);
        } else {
            console.warn('[texel devtool] Texture not found:', texture.name);
            const name = document.createElement('span');
            name.textContent = texture.name;
            name.style.alignSelf = 'center';
            li.appendChild(name);
            const badge = document.createElement('span');
            badge.className = 'badge err';
            badge.textContent = 'not found';
            li.appendChild(badge);
        }
        els.textureList.appendChild(li);
    }
}

function renderDiagnostics(result) {
    const t = result.texel;
    const v = result.voxel;
    const rows = [
        ['model', `${escapeHtml(result.name)} · ${result.cubeCount} cubes · ${result.resolution.width}×${result.resolution.height}`
            + (result.animated ? ' · animated' : '')
            + (result.hasMetaFile ? ' · .meta.json' : '')],
        ['origin mode', result.originMode],
        ['strategy', `<b>${v.strategy}</b>`],
        ['texel mode', `${t.mode} / ${t.detail}`],
        ['faces baked', `${t.facesBaked} / ${t.facesTotal}`],
        ['plates', `${t.totalPlates} (max on face ${t.maxPlatesOnFace}, avg ${(t.facesBaked ? t.totalPlates / t.facesBaked : 0).toFixed(1)})`],
        ['budgets', `face ${t.effectiveMaxPlatesPerFace} · instance ${t.effectiveMaxPlatesPerInstance}`
            + (t.faceBudgetFallbacks + t.instanceBudgetFallbacks > 0
                ? ` — <span style="color:#e58a8a">${t.faceBudgetFallbacks + t.instanceBudgetFallbacks} fallback(s)!</span>` : '')],
        ['occluded cells', t.occludedCells],
        ['texel bake', `${t.bakeTimeMs.toFixed(1)} ms`],
        ['voxel runs', v.runs.length],
        ['voxels', `${v.surfaceVoxels} surface / ${v.occupiedVoxels} occupied / ${v.culledInteriorVoxels} culled`],
        ['voxel bake', `${v.bakeTimeMs.toFixed(1)} ms`],
    ];
    let html = `<p class="rationale">${escapeHtml(v.rationale)}</p>`;
    html += '<table>' + rows.map(([k, val]) => `<tr><td>${k}</td><td>${val}</td></tr>`).join('') + '</table>';

    // Grid histogram.
    const grids = Object.entries(t.gridHistogram ?? {});
    if (grids.length > 0) {
        html += '<h2>Grids</h2>';
        const max = Math.max(...grids.map(([, n]) => n));
        html += grids.map(([grid, n]) =>
            `<div>${grid} <span style="color:#8b93a3">×${n}</span></div>`
            + `<div class="bar-wrap"><div class="bar" style="width:${(100 * n / max).toFixed(0)}%;background:#5b8dc9"></div></div>`).join('');
    }

    // Palette usage (combined texel + voxel).
    const usage = {};
    for (const [k, n] of Object.entries(t.paletteUsage ?? {})) usage[k] = (usage[k] ?? 0) + n;
    for (const [k, n] of Object.entries(v.paletteUsage ?? {})) usage[k] = (usage[k] ?? 0) + n;
    const entries = Object.entries(usage).sort((a, b) => b[1] - a[1]);
    if (entries.length > 0) {
        const total = entries.reduce((sum, [, n]) => sum + n, 0);
        html += '<h2>Palette</h2>';
        for (const [index, n] of entries) {
            const entry = result.palette[Number(index)];
            if (!entry) continue;
            html += `<div class="palette-row"><span class="swatch" style="background:${cssRgb(entry.rgb)}"></span>`
                + `<span style="flex:1">${escapeHtml(entry.name.toLowerCase().replace(/_/g, ' '))}</span>`
                + `<span style="color:#8b93a3">${(100 * n / total).toFixed(1)}%</span></div>`;
        }
    }
    els.diagnostics.innerHTML = html;
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// ---------------------------------------------------------------- settings + layers

for (const id of Object.keys(SETTING_BY_CONTROL_ID)) {
    const setting = SETTING_BY_CONTROL_ID[id];
    const control = document.getElementById(id);
    const handler = () => {
        const value = control.tagName === 'SELECT'
            ? control.value
            : Number(control.value);
        if (control.tagName !== 'SELECT' && (!Number.isFinite(value) || value < 1)) return;
        state.settings[setting] = value;
        state.userOverrides[setting] = value; // user edit wins over the meta file
        syncSettingsUi();
        requestBake();
    };
    if (control.tagName === 'SELECT') {
        control.addEventListener('change', handler);
    } else {
        control.addEventListener('input', debounce(handler, 300));
    }
}

// View modes are mutually exclusive radios: the textured reference and the
// palette reconstruction are alternative views of the same model — showing
// both at once reads as two blended, misaligned textures.
for (const radio of document.querySelectorAll('input[name="viewmode"]')) {
    radio.addEventListener('change', e => {
        if (e.target.checked) viewer.showView(e.target.value);
    });
}
document.getElementById('layer-wireframe').addEventListener('change', e => {
    viewer.setWireframeVisible(e.target.checked);
});

document.getElementById('btn-bake').addEventListener('click', requestBake);
document.getElementById('btn-recompile').addEventListener('click', async () => {
    setStatus('recompiling…');
    setDot(els.dotCode, 'warn');
    await requestRecompile();
});

// ---------------------------------------------------------------- hot reload events

subscribeEvents(event => {
    switch (event.type) {
        case 'code':
            if (event.status === 'compiling') {
                setStatus('compiling pipeline…');
                setDot(els.dotCode, 'warn');
            } else if (event.status === 'ok') {
                setDot(els.dotCode, 'ok');
                setStatus(`pipeline reloaded in ${(event.ms / 1000).toFixed(1)}s`);
                requestBake(); // fresh code — re-bake for immediate feedback
            } else if (event.status === 'error') {
                setDot(els.dotCode, 'err');
                setStatus('compile failed (' + event.stage + ') — fix and save');
                els.diagnostics.innerHTML =
                    `<p class="rationale">COMPILE FAILED (${escapeHtml(event.stage ?? '')})</p>`
                    + `<pre style="white-space:pre-wrap;color:#e58a8a;font-size:11px">${escapeHtml(event.output ?? '')}</pre>`;
            }
            break;
        case 'daemon':
            if (event.status === 'down') setDot(els.dotDaemon, 'err');
            if (event.status === 'up') setDot(els.dotDaemon, 'ok');
            break;
        case 'assets':
            setStatus('assets changed — re-baking');
            requestBake();
            break;
        case 'daemon-log':
            if (/WARNING|SEVERE/.test(event.text)) {
                console.warn('[daemon]', event.text.trim());
            }
            break;
    }
});

// ---------------------------------------------------------------- utils

function debounce(fn, ms) {
    let t = null;
    return (...args) => {
        clearTimeout(t);
        t = setTimeout(() => fn(...args), ms);
    };
}

// ---------------------------------------------------------------- start

loadModels().catch(err => setStatus('failed to list models: ' + err.message));
