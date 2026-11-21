// Bake tab: the texel surface baker, run through the real pipeline classes,
// plus the display list the emitter builds from its plans.
//
// Bake sequencing is generation-guarded and latest-wins (a bake requested while
// another is running is queued, and a superseded response never touches the
// viewer) — the failure mode this replaces was stale overwrites reading as
// strange geometry bugs.
//
// Settings follow the pipeline's own precedence: standard defaults, then the
// model's `.meta.json` as the model-specific default, then the user's explicit
// edits for this model, exactly once per field.

import { postJson } from './api.js';
import { esc, chip, section, kv, texThumb, cssRgb, bar } from './ui.js';

const STANDARD_DEFAULTS = {
    texelEnabled: true,
    texelMode: 'AUTO',
    texelDetail: 'FACE',
    maxPlatesPerFace: 96,
    maxPlatesPerInstance: 150,
    maxGridEdge: 64,
    texelBrightness: '',
    originMode: 'AUTO',
    perFaceRendering: true,
};

/** Panel setting -> daemon meta override key. */
const META_KEY_BY_SETTING = {
    texelMode: 'texelMode',
    texelDetail: 'texelDetail',
    maxPlatesPerFace: 'maxTexelPlatesPerFace',
    maxPlatesPerInstance: 'maxTexelPlatesPerInstance',
    texelBrightness: 'texelBrightness',
    originMode: 'originMode',
};

const CONTROL_ID_BY_SETTING = Object.fromEntries(
    Object.keys(META_KEY_BY_SETTING).map(k => [k, 'b-' + k]));

const HISTORY_LIMIT = 30;

const state = {
    modelFile: null,
    result: null,
    generation: 0,
    inFlight: false,
    rebakePending: false,
    settings: { ...STANDARD_DEFAULTS },
    userOverrides: {},
    fileMeta: {},
    loadedForKey: null,
    settingsRenderedFor: null,
    history: [],
};

export function initBake(ctx) {
    const inv = ctx.inventory();
    const el = ctx.el.listBake;
    el.innerHTML = `
      <h2>Texel bake</h2>
      <p class="muted small">Runs <code>TexelSurfaceBaker</code> and
      <code>DisplayEmitter</code> on the real model, then renders the display
      list the server would send the client.</p>
      <ul class="plain selectable">${(inv.blocks ?? []).map(b => `
        <li class="row" data-file="${esc(b.file)}">
            <span class="row-name">${esc(b.key)}</span>
            <span class="row-hint">${b.hasMeta ? 'meta' : 'bbmodel'}</span>
        </li>`).join('') || '<li class="muted">no block models in the watched roots</li>'}</ul>`;
    for (const row of el.querySelectorAll('li.row[data-file]')) {
        row.addEventListener('click', () => ctx.open('bake', row.dataset.file));
    }
}

export async function openBake(ctx, file) {
    if (state.modelFile !== file) {
        state.modelFile = file;
        state.userOverrides = {};
        state.fileMeta = {};
        state.settings = { ...STANDARD_DEFAULTS };
        state.loadedForKey = null;
        state.settingsRenderedFor = null;
    }
    ctx.el.listBake.querySelectorAll('li.row').forEach(li =>
        li.classList.toggle('selected', li.dataset.file === file));
    ctx.details(settingsHtml() + '<div id="bake-report"><p class="muted">baking…</p></div>');
    bindSettings(ctx);
    await requestBake(ctx);
}

// ---------------------------------------------------------------- baking

async function requestBake(ctx) {
    if (state.inFlight) {
        state.rebakePending = true; // queued behind the running bake, latest wins
        return;
    }
    await startBake(ctx);
}

async function startBake(ctx) {
    if (!state.modelFile) return;
    state.inFlight = true;
    const generation = ++state.generation;
    setReport(ctx, `<p class="muted">baking ${esc(state.modelFile.split(/[\\/]/).pop())}…</p>`);
    const overrides = buildOverrides();
    try {
        const response = await postJson('/api/bake', {
            model: state.modelFile,
            overrides,
            // Top-level aliases for the same fields (the daemon accepts either
            // spelling; kept so the panel works during the merge transition).
            ...aliases(overrides),
        });
        if (generation !== state.generation) return; // superseded — drop it
        if (response.status === 'BROKEN' || response.error) {
            setReport(ctx, `<div class="card verdict"><span class="chip broken">${esc(response.status ?? 'ERROR')}</span>
                <p class="rationale">${esc(response.summary ?? response.error ?? 'bake failed')}</p></div>`);
            return;
        }
        state.result = response;
        applyModelDefaults(ctx, response);
        ctx.shading = response.shading ?? { kind: 'virtual-block', faceShade: true };
        ctx.viewer.renderBake(response);
        renderReport(ctx, response);
        pushHistory(ctx, response, overrides.meta ?? {});
    } catch (err) {
        if (generation === state.generation) {
            setReport(ctx, `<div class="card verdict"><span class="chip broken">ERROR</span>
                <p class="rationale">${esc(err.message)}</p></div>`);
        }
    } finally {
        state.inFlight = false;
        if (state.rebakePending) {
            state.rebakePending = false;
            await startBake(ctx);
        }
    }
}

function buildOverrides() {
    const overrides = {
        texelEnabled: state.settings.texelEnabled,
        texelMode: state.settings.texelMode,
        texelDetail: state.settings.texelDetail,
        maxPlatesPerFace: state.settings.maxPlatesPerFace,
        maxPlatesPerInstance: state.settings.maxPlatesPerInstance,
        maxGridEdge: state.settings.maxGridEdge,
        originMode: state.settings.originMode,
        perFaceRendering: state.settings.perFaceRendering,
    };
    // User-edited fields go through as explicit meta overrides so they win over
    // the model's .meta.json; everything else keeps the global default.
    // texelBrightness '' means "no explicit choice" — omit it so a meta value
    // keeps applying instead of being cleared.
    const meta = {};
    for (const [setting, metaKey] of Object.entries(META_KEY_BY_SETTING)) {
        if (state.userOverrides[setting] === undefined) continue;
        if (setting === 'texelBrightness' && state.settings[setting] === '') continue;
        meta[metaKey] = state.settings[setting];
    }
    if (Object.keys(meta).length > 0) overrides.meta = meta;
    return overrides;
}

function aliases(overrides) {
    const out = {};
    if (overrides.texelMode) out.mode = overrides.texelMode;
    if (overrides.texelDetail) out.detail = overrides.texelDetail;
    if (overrides.maxPlatesPerFace !== undefined) out.maxPlatesPerFace = overrides.maxPlatesPerFace;
    if (overrides.maxPlatesPerInstance !== undefined) out.maxPlatesPerInstance = overrides.maxPlatesPerInstance;
    return out;
}

/** Applies the model's own defaults on load: meta first, then user edits win. */
function applyModelDefaults(ctx, response) {
    const meta = response.meta ?? {};
    const changed = !shallowEqual(state.fileMeta, meta);
    state.fileMeta = meta;
    if (!changed && state.loadedForKey === response.key) return;
    state.loadedForKey = response.key;

    state.settings = { ...STANDARD_DEFAULTS };
    for (const [setting, metaKey] of Object.entries(META_KEY_BY_SETTING)) {
        if (meta[metaKey] !== undefined) state.settings[setting] = meta[metaKey];
        if (state.userOverrides[setting] !== undefined) {
            state.settings[setting] = state.userOverrides[setting];
        }
    }
    state.settings.texelBrightness = meta.texelBrightness ?? state.settings.texelBrightness;
    renderSettings(ctx);
}

function shallowEqual(a, b) {
    const ka = Object.keys(a), kb = Object.keys(b);
    return ka.length === kb.length && ka.every(k => a[k] === b[k]);
}

// ---------------------------------------------------------------- settings UI

function settingsHtml() {
    const opt = (values, current) => values.map(v =>
        `<option value="${esc(v[0])}"${v[0] === current ? ' selected' : ''}>${esc(v[1])}</option>`).join('');
    return `
      <h2>Bake settings</h2>
      <div class="card settings" id="bake-settings">
        <label>texel mode
          <select id="${CONTROL_ID_BY_SETTING.texelMode}">${opt([
            ['AUTO', 'AUTO — bake when it pays off'],
            ['ON', 'ON — always bake'],
            ['OFF', 'OFF — plain UV windows']], state.settings.texelMode)}</select></label>
        <label>detail
          <select id="${CONTROL_ID_BY_SETTING.texelDetail}">${opt([
            ['FACE', 'FACE — one grid per face'],
            ['PIXEL', 'PIXEL — finest grid'],
            ['COARSE', 'COARSE — fewest plates']], state.settings.texelDetail)}</select></label>
        <label>plates / face
          <input type="number" min="1" max="4096" step="1" id="${CONTROL_ID_BY_SETTING.maxPlatesPerFace}"
            value="${state.settings.maxPlatesPerFace}"></label>
        <label>plates / instance
          <input type="number" min="1" max="20000" step="1" id="${CONTROL_ID_BY_SETTING.maxPlatesPerInstance}"
            value="${state.settings.maxPlatesPerInstance}"></label>
        <label>grid edge cap
          <input type="number" min="1" max="256" step="1" id="b-maxGridEdge"
            value="${state.settings.maxGridEdge}"></label>
        <label>texel brightness
          <select id="${CONTROL_ID_BY_SETTING.texelBrightness}">${opt([
            ['', 'none — natural world light'],
            ...Array.from({ length: 16 }, (_, i) => [String(i), String(i)])],
            String(state.settings.texelBrightness ?? ''))}</select></label>
        <label>origin mode
          <select id="${CONTROL_ID_BY_SETTING.originMode}">${opt([
            ['AUTO', 'AUTO — resolved from the model'],
            ['CENTER', 'CENTER — block centre'],
            ['GRID', 'GRID — corner anchor']], state.settings.originMode)}</select></label>
        <label class="inline"><input type="checkbox" id="b-perFaceRendering"
          ${state.settings.perFaceRendering ? 'checked' : ''}> per-face rendering</label>
        <label class="inline"><input type="checkbox" id="b-texelEnabled"
          ${state.settings.texelEnabled ? 'checked' : ''}> texel baking enabled</label>
      </div>
      <p class="muted small">Fields marked <span class="badge">meta</span> come from this
      model’s <code>.meta.json</code>; editing one overrides it for this model only.</p>`;
}

function renderSettings(ctx) {
    const box = document.getElementById('bake-settings');
    if (!box) return;
    const set = (id, value) => {
        const control = document.getElementById(id);
        if (!control) return;
        if (control.type === 'checkbox') control.checked = !!value;
        else control.value = value;
    };
    set(CONTROL_ID_BY_SETTING.texelMode, state.settings.texelMode);
    set(CONTROL_ID_BY_SETTING.texelDetail, state.settings.texelDetail);
    set(CONTROL_ID_BY_SETTING.maxPlatesPerFace, state.settings.maxPlatesPerFace);
    set(CONTROL_ID_BY_SETTING.maxPlatesPerInstance, state.settings.maxPlatesPerInstance);
    set('b-maxGridEdge', state.settings.maxGridEdge);
    set(CONTROL_ID_BY_SETTING.texelBrightness, String(state.settings.texelBrightness ?? ''));
    set(CONTROL_ID_BY_SETTING.originMode, state.settings.originMode);
    set('b-perFaceRendering', state.settings.perFaceRendering);
    set('b-texelEnabled', state.settings.texelEnabled);

    for (const label of box.querySelectorAll('label')) {
        const control = label.querySelector('select, input');
        if (!control || !control.id) continue;
        const setting = SETTING_BY_CONTROL_ID[control.id];
        if (!setting) continue;
        const badge = label.querySelector('.badge.meta-source');
        const fromMeta = state.fileMeta[META_KEY_BY_SETTING[setting]] !== undefined
            && state.userOverrides[setting] === undefined;
        if (fromMeta && !badge) {
            const el = document.createElement('span');
            el.className = 'badge meta-source';
            el.textContent = 'meta';
            label.appendChild(el);
        } else if (!fromMeta && badge) {
            badge.remove();
        }
    }
}

const SETTING_BY_CONTROL_ID = Object.fromEntries(
    Object.entries(CONTROL_ID_BY_SETTING).map(([setting, id]) => [id, setting]));
SETTING_BY_CONTROL_ID['b-maxGridEdge'] = 'maxGridEdge';
SETTING_BY_CONTROL_ID['b-perFaceRendering'] = 'perFaceRendering';
SETTING_BY_CONTROL_ID['b-texelEnabled'] = 'texelEnabled';

let settingsBound = false;
function bindSettings(ctx) {
    if (settingsBound) return;
    settingsBound = true;
    const onSetting = (setting, value) => {
        state.settings[setting] = value;
        state.userOverrides[setting] = value; // user edit wins over the meta file
        renderSettings(ctx);
        requestBake(ctx);
    };
    for (const [id, setting] of Object.entries(SETTING_BY_CONTROL_ID)) {
        const control = document.getElementById(id);
        if (!control) continue;
        const handler = () => {
            if (setting === 'texelBrightness') {
                onSetting(setting, control.value === '' ? '' : Number(control.value));
            } else if (control.type === 'checkbox') {
                onSetting(setting, control.checked);
            } else if (control.tagName === 'SELECT') {
                onSetting(setting, control.value);
            } else {
                const value = Number(control.value);
                if (!Number.isFinite(value) || value < 1) return;
                onSetting(setting, value);
            }
        };
        if (control.type === 'checkbox') control.addEventListener('change', handler);
        else if (control.tagName === 'SELECT') control.addEventListener('change', handler);
        else control.addEventListener('input', debounce(handler, 300));
    }
}

function debounce(fn, ms) {
    let t = null;
    return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}

function setReport(ctx, html) {
    const box = document.getElementById('bake-report');
    if (box) box.innerHTML = html;
}

// ---------------------------------------------------------------- report

function renderReport(ctx, response) {
    const t = response.texel ?? {};
    const displays = response.displays ?? [];
    const byKind = countBy(displays, d => d.kind ?? 'cube');
    const byRgb = countBy(displays, d => d.rgbSource ?? 'palette');
    const light = response.lighting ?? {};
    const strategies = countBy(Object.values(response.cubes ?? {})
        .flatMap(c => Object.values(c.faces ?? {})).filter(f => f.strategy), f => f.strategy);
    const solidCount = (byKind.cube ?? 0) + (byKind.face ?? 0);

    const rows = [
        ['model', `${esc(response.name)} · ${response.cubeCount} cubes · `
            + `${response.resolution?.width ?? '?'}×${response.resolution?.height ?? '?'}`
            + (response.animated ? ' · animated' : '')
            + (response.hasMetaFile ? ' · .meta.json' : '')],
        ['origin mode', esc(response.originMode ?? '')],
        ['texel', `${esc(t.mode ?? '-')} / ${esc(t.detail ?? '-')}`
            + (t.enabled === false ? ' (off)' : '')],
        ['faces baked', `${t.facesBaked ?? 0} / ${t.facesTotal ?? 0}`],
        ['plates', `${t.totalPlates ?? 0} (max on face ${t.maxPlatesOnFace ?? 0}, avg `
            + `${(t.facesBaked ? (t.totalPlates ?? 0) / t.facesBaked : 0).toFixed(1)})`],
        ['budgets', `face ${t.effectiveMaxPlatesPerFace ?? '-'} · instance `
            + `${t.effectiveMaxPlatesPerInstance ?? '-'}`
            + ((t.faceBudgetFallbacks ?? 0) + (t.instanceBudgetFallbacks ?? 0) > 0
                ? ` — <b class="broken-text">${(t.faceBudgetFallbacks ?? 0)
                    + (t.instanceBudgetFallbacks ?? 0)} fallback(s)</b>` : '')],
        ['bake time', `${(t.bakeTimeMs ?? 0).toFixed(1)} ms`],
        ['occluded cells', t.occludedCells ?? 0],
        ['strategies', Object.entries(strategies)
            .sort((a, b) => b[1] - a[1]).map(([k, v]) => `${esc(k)} ×${v}`).join(' · ') || '—'],
    ];

    const honesty = byRgb.sampled
        ? `<p class="muted small"><b>${byRgb.sampled}</b> display colour(s) are sampled from the `
          + `model texture, not the client’s block texture — the in-game layer is approximate for `
          + `non-palette materials. Palette entries are exact.</p>` : '';

    setReport(ctx, `
      <div class="card verdict">
        ${chip(response.status ?? 'OK')}
        <p>${esc(response.summary ?? '')}</p>
      </div>
      ${section('Display list', `
        ${kv([
            ['displays', `${response.displayCount ?? displays.length}`
                + ` (solid ${response.solidDisplays ?? solidCount})`],
            ['whole-cube / face / texel', `${byKind.cube ?? 0} · ${byKind.face ?? 0} · ${byKind.texel ?? 0}`],
            ['brightness override', `${emissive} of ${displays.length} displays`],
            ['colour source', Object.entries(byRgb).map(([k, v]) => `${k} ×${v}`).join(' · ')],
        ])}
        ${honesty}`)}
      ${section('Pipeline diagnostics', `<div class="kv-grid">${rows.map(([k, v]) =>
        `<div class="kv"><span>${k}</span><span>${v}</span></div>`).join('')}</div>`)}
      ${gridsHtml(t.gridHistogram)}
      ${paletteHtml(response.palette ?? [], t.paletteUsage ?? {})}
      ${facesHtml(response)}
      ${texturesHtml(response.textures ?? [])}
      ${historyHtml()}`);
    bindHistory(ctx);
}

function gridsHtml(histogram) {
    const entries = Object.entries(histogram ?? {});
    if (entries.length === 0) return '';
    const max = Math.max(...entries.map(([, n]) => n));
    return section('Texel grids', entries.map(([grid, n]) =>
        `<div class="mono">${esc(grid)} <span class="muted">×${n}</span></div>${bar(100 * n / max, '#5b8dc9')}`).join(''));
}

function paletteHtml(palette, usage) {
    const entries = Object.entries(usage ?? {}).sort((a, b) => b[1] - a[1]);
    if (entries.length === 0) return '';
    const total = entries.reduce((sum, [, n]) => sum + n, 0);
    return section('Palette usage', entries.map(([index, n]) => {
        const entry = palette[Number(index)];
        if (!entry) return '';
        return `<div class="palette-row">
            <span class="swatch" style="background:${cssRgb(entry.rgb)}"></span>
            <span style="flex:1">${esc(String(entry.name).toLowerCase().replace(/_/g, ' '))}</span>
            <span class="muted">${(100 * n / total).toFixed(1)}%</span></div>`;
    }).join(''));
}

/** Per-cube plan table: what each face baked into, and how many plates. */
function facesHtml(response) {
    const plans = response.texel?.cubePlans ?? [];
    if (plans.length === 0) return '';
    const rows = plans.map(p => `<tr>
        <td class="mono">${esc(p.cube)}</td><td>${esc(p.face)}</td>
        <td>${p.gridWidth}×${p.gridHeight}</td>
        <td>${p.plates?.length ?? 0}</td>
        <td>${p.cutoutCells ?? 0}/${p.occludedCells ?? 0}</td>
        <td><span class="swatch" style="background:${
            cssRgb((response.palette?.[p.dominantPaletteIndex] ?? {}).rgb ?? 0x808080)}"></span></td>
      </tr>`).join('');
    return section('Face plans', `<table class="compact">
        <thead><tr><td>cube</td><td>face</td><td>grid</td><td>plates</td><td>cutout/occluded</td><td>dominant</td></tr></thead>
        <tbody>${rows}</tbody></table>`);
}

function texturesHtml(textures) {
    if (textures.length === 0) return '';
    const missing = textures.filter(t => !t.resolved);
    return section('Textures', `<div class="thumbs">${textures.map(t => `
        <figure>${texThumb(t)}
          <figcaption>${esc(t.name)}${t.resolved
            ? `<br>${t.width}×${t.height}` : '<br><b class="broken-text">not on disk</b>'}</figcaption>
        </figure>`).join('')}</div>`
        + (missing.length ? `<p class="muted small">${missing.length} texture(s) unresolved — the client `
            + 'renders those faces with <code>missingno</code>.</p>' : ''));
}

// ---------------------------------------------------------------- history

function pushHistory(ctx, response, metaSnapshot) {
    state.history.unshift({
        key: response.key,
        modelFile: state.modelFile,
        plates: response.texel?.totalPlates ?? 0,
        displays: response.displayCount ?? (response.displays ?? []).length,
        bakeTimeMs: response.texel?.bakeTimeMs ?? 0,
        meta: { ...metaSnapshot },
    });
    if (state.history.length > HISTORY_LIMIT) state.history.pop();
    const box = document.getElementById('bake-history');
    if (box) box.innerHTML = historyRows();
    bindHistory(ctx);
}

function historyHtml() {
    return `<h3>Bake history</h3><div class="card"><ul class="plain" id="bake-history">${historyRows()}</ul></div>`;
}

function historyRows() {
    return state.history.map((entry, i) => `<li class="row" data-history="${i}">
        <span class="row-name">${esc(entry.key)}</span>
        <span class="row-hint">${entry.displays}d · ${entry.plates}p · ${entry.bakeTimeMs.toFixed(0)}ms</span>
      </li>`).join('') || '<li class="muted">none yet</li>';
}

function bindHistory(ctx) {
    for (const li of document.querySelectorAll('#bake-history li[data-history]')) {
        li.addEventListener('click', () => restoreHistory(ctx, Number(li.dataset.history)));
    }
}

/** Clicking a history entry restores the overrides that produced it and re-bakes. */
async function restoreHistory(ctx, index) {
    const entry = state.history[index];
    if (!entry) return;
    if (entry.modelFile !== state.modelFile) {
        state.modelFile = entry.modelFile;
        state.settings = { ...STANDARD_DEFAULTS };
        state.fileMeta = {};
        state.loadedForKey = null;
    }
    state.userOverrides = {};
    for (const [metaKey, value] of Object.entries(entry.meta)) {
        for (const [setting, key] of Object.entries(META_KEY_BY_SETTING)) {
            if (key === metaKey) state.userOverrides[setting] = value;
        }
    }
    await openBake(ctx, state.modelFile);
}

// ---------------------------------------------------------------- helpers

function countBy(items, keyOf) {
    const out = {};
    for (const item of items ?? []) {
        const key = keyOf(item);
        if (key === undefined || key === null) continue;
        out[key] = (out[key] ?? 0) + 1;
    }
    return out;
}

export async function rebakeCurrent(ctx) {
    if (state.modelFile) await startBake(ctx);
}

/** After the daemon restarts on fresh code: refresh the list and re-bake. */
export async function refreshAfterReload(ctx) {
    initBake(ctx);
    if (state.modelFile) await startBake(ctx);
}

export function currentBake() {
    return state.result;
}
