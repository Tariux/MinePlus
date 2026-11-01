// Asset Lab app bootstrap: tabs, status bar, the shared stage (lighting +
// layers), SSE wiring and the context every panel works through.

import { Viewer } from './viewer.js';
import { Lighting } from './shading.js';
import { getJson, postJson } from './api.js';
import { initMobs, showMob } from './mobs.js';
import { initItems, showItem } from './items.js';
import { initBlocks, showBlock } from './blocks.js';
import { initTextures } from './textures.js';
import { initAudit } from './audit.js';
import { initBake, openBake, refreshAfterReload } from './bake.js';
import { initPack, openPack } from './pack.js';
import { initStage } from './lighting.js';
import { section } from './ui.js';

const el = {
    listMobs: document.getElementById('list-mobs'),
    listItems: document.getElementById('list-items'),
    listBlocks: document.getElementById('list-blocks'),
    listBake: document.getElementById('list-bake'),
    listPack: document.getElementById('list-pack'),
    listTextures: document.getElementById('list-textures'),
    listAudit: document.getElementById('list-audit'),
    details: document.getElementById('details'),
    viewport: document.getElementById('viewport'),
    statusText: document.getElementById('status-text'),
    dotCode: document.getElementById('dot-code'),
    dotDaemon: document.getElementById('dot-daemon'),
    dotSettings: document.getElementById('dot-settings'),
};

let inventory = {
    blocks: [], mobDefs: [], mobModels: [], mobTextures: [], guns: [],
    packArtifacts: [], packScan: [],
};
let currentTab = 'mobs';
let liveSettings = null;

const viewer = new Viewer(el.viewport);
const lighting = new Lighting();
viewer.setLighting(lighting);

const ctx = {
    viewer,
    el,
    lighting,
    shading: null,
    inventory: () => inventory,
    settings: () => liveSettings,
    details(html) { el.details.innerHTML = html; },
    setLayer(name, visible) {
        viewer.setLayer(name, visible);
        const input = document.querySelector(`#stage-layers input[data-layer="${name}"]`);
        if (input) input.checked = !!visible;
    },
    open(category, file) {
        const opener = openers[category];
        if (opener) opener(file).catch(err => alert(err.message));
    },
};

const openers = {
    async mob(file) {
        const payload = file.endsWith('.yml') ? { yml: file } : { model: file };
        const response = await postJson('/api/mob', payload);
        switchTab('mobs');
        ctx.lastMobRequest = payload;
        showMob(ctx, response);
        return response;
    },
    async block(file) {
        const response = await postJson('/api/block', { model: file });
        switchTab(guessTab(file));
        showBlock(ctx, response);
        return response;
    },
    async item(file) {
        const response = await postJson('/api/item', { model: file });
        switchTab('items');
        showItem(ctx, response);
        return response;
    },
    async bake(file) {
        switchTab('bake');
        await openBake(ctx, file);
    },
    async pack(file) {
        switchTab('pack');
        return openPack(ctx, file);
    },
};

function guessTab(file) {
    // Gun bbmodel sources open in items; everything else in blocks.
    return /defaults[\/]guns[\\/]/.test(file) ? 'items' : 'blocks';
}

async function loadInventory() {
    try {
        inventory = await getJson('/api/inventory');
        initMobs(ctx);
        initItems(ctx);
        initBlocks(ctx);
        initBake(ctx);
        initPack(ctx);
        initTextures(ctx);
        initAudit(ctx);
        updateCounts();
    } catch (e) {
        setStatus(null, null, 'inventory failed: ' + e.message);
        console.error('inventory failed:', e);
    }
}

/**
 * Bridges the deployed `settings.mp.yml`: the pack lighting policy the server is
 * actually running becomes the stage default, so the viewport reproduces the
 * live server's light decisions instead of a guess.
 */
async function loadSettings() {
    try {
        liveSettings = await getJson('/api/settings');
    } catch (e) {
        liveSettings = { found: false, error: e.message };
    }
    el.dotSettings.className = 'dot ' + (liveSettings.found ? 'ok' : '');
    el.dotSettings.title = liveSettings.found
        ? 'deployed config: ' + liveSettings.path
        : 'no deployed settings.mp.yml found — using the tool’s defaults';
    const pack = liveSettings.sections?.PACK ?? {};
    const policy = pack.LIGHTING?.POLICY ?? pack.lighting?.policy;
    if (policy) lighting.set({ policy: String(policy).toUpperCase() });
    return liveSettings;
}

function updateCounts() {
    const set = (id, count) => {
        const badge = document.getElementById(id);
        if (badge) badge.textContent = count;
    };
    set('count-mobs', inventory.mobDefs.length + inventory.mobModels.length);
    set('count-items', inventory.guns.reduce((n, g) => n + g.frames.length, 0));
    set('count-blocks', inventory.blocks.length);
    set('count-bake', inventory.blocks.length);
    set('count-pack', (inventory.packArtifacts ?? []).length);
    set('count-textures',
        inventory.mobTextures.length
        + inventory.guns.reduce((n, g) => n + g.textures.length, 0));
}

function switchTab(tab) {
    currentTab = tab;
    for (const button of document.querySelectorAll('#tabs button')) {
        button.classList.toggle('active', button.dataset.tab === tab);
    }
    for (const list of document.querySelectorAll('.tab-list')) {
        list.classList.toggle('active', list.dataset.tab === tab);
    }
}

// ---------------------------------------------------------------- status + SSE

function setStatus(code, daemon, text) {
    if (code !== null) el.dotCode.className = 'dot ' + (code ?? '');
    if (daemon !== null) el.dotDaemon.className = 'dot ' + (daemon ?? '');
    if (text) el.statusText.textContent = text;
}

setStatus('busy', 'down', 'starting…');

const events = new EventSource('/api/events');
events.onmessage = (msg) => {
    let event;
    try { event = JSON.parse(msg.data); } catch { return; }
    const code = el.dotCode.className.split(' ')[1] ?? 'busy';
    const daemon = el.dotDaemon.className.split(' ')[1] ?? 'down';
    if (event.type === 'code') {
        if (event.status === 'compiling') setStatus('busy', daemon, 'compiling pipeline…');
        if (event.status === 'ok') {
            setStatus('ok', daemon, `pipeline reloaded in ${(event.ms / 1000).toFixed(1)}s`);
            // Fresh code — re-run the current bake for immediate feedback.
            refreshAfterReload(ctx);
        }
        if (event.status === 'error') {
            setStatus('error', daemon, 'compile failed — see the server console');
            console.error('compile failed:', event.stage, event.output);
        }
    }
    if (event.type === 'daemon') {
        if (event.status === 'up') setStatus(code, 'ok', 'daemon up');
        if (event.status === 'down') setStatus(code, 'down', 'daemon down (restarting…)');
        if (event.status === 'dead') setStatus(code, 'error',
            'daemon dead — ' + (event.error ?? 'see the server console'));
    }
    if (event.type === 'assets') {
        loadInventory();
        el.statusText.textContent = 'assets changed — inventory refreshed';
    }
    if (event.type === 'settings') {
        loadSettings();
        el.statusText.textContent = 'deployed config changed — lighting policy refreshed';
    }
    if (event.type === 'daemon-log' && /WARNING|SEVERE/.test(event.text ?? '')) {
        console.warn('[daemon]', (event.text ?? '').trim());
    }
};

document.getElementById('btn-recompile').addEventListener('click', async () => {
    try { await postJson('/api/recompile', {}); } catch (e) { alert(e.message); }
});
document.getElementById('btn-markers').addEventListener('change', (e) =>
    ctx.setLayer('markers', e.target.checked));
document.getElementById('btn-frame').addEventListener('click', () => viewer.focus());
document.getElementById('btn-help').addEventListener('click', () => showHelp());

for (const button of document.querySelectorAll('#tabs button')) {
    button.addEventListener('click', () => switchTab(button.dataset.tab));
}

function showHelp() {
    ctx.details(`
      <h2>Asset Lab</h2>
      <div class="card">
        <p>One bridge over the real pipeline: the same classes the server runs
        (<code>BbModelImporter</code>, <code>FaceUvAnalyzer</code>,
        <code>TextureMaterialResolver</code>, <code>TexelSurfaceBaker</code>,
        <code>DisplayEmitter</code>, <code>MobAnimator</code>, the pack compiler)
        execute in the daemon and this viewport draws <b>their</b> numbers — not a
        second JavaScript implementation, so the preview cannot drift from the game.</p>
      </div>
      ${section('How to read it', `
        <ul class="plain">
          <li><b>model textures</b> — what the author drew: the bbmodel’s own UV windows.</li>
          <li><b>in-game displays</b> — the exact <code>BlockDisplay</code> list the server
              emits, with its transforms, palette colours and brightness override.</li>
          <li><b>pack model</b> — the JSON inside the shipped <code>mp-&lt;hash&gt;.zip</code>,
              drawn flat because it carries <code>shade:false</code>.</li>
          <li><b>light</b> — sky/block levels drive the client’s 80%-per-level response;
              face shading applies the vanilla multipliers (up 1.0, N/S 0.8, E/W 0.6, down 0.5).</li>
        </ul>`)}
      ${section('Known approximations', `
        <ul class="plain">
          <li>An in-game display whose material is not in the texel palette is coloured by
              sampling the model texture — vanilla block textures are not vendored here.</li>
          <li>The lightmap <b>tint</b> is off unless you enable it: the client generates it in
              its shader from dimension and weather, which this tool cannot read.</li>
          <li>Animation is interpolated locally; the server pushes poses at its tick rate and
              the client smooths between them.</li>
        </ul>`)}
    `);
}

switchTab('mobs');
initStage(ctx);
loadSettings();
loadInventory();
showHelp();
