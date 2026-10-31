// Asset Lab app bootstrap: tabs, status bar, SSE wiring and the shared
// context every panel works through.

import { Viewer } from './viewer.js';
import { getJson, postJson } from './api.js';
import { initMobs, showMob } from './mobs.js';
import { initItems, showItem } from './items.js';
import { initBlocks, showBlock } from './blocks.js';
import { initTextures } from './textures.js';
import { initAudit } from './audit.js';

const el = {
    listMobs: document.getElementById('list-mobs'),
    listItems: document.getElementById('list-items'),
    listBlocks: document.getElementById('list-blocks'),
    listTextures: document.getElementById('list-textures'),
    listAudit: document.getElementById('list-audit'),
    details: document.getElementById('details'),
    viewport: document.getElementById('viewport'),
    statusText: document.getElementById('status-text'),
    dotCode: document.getElementById('dot-code'),
    dotDaemon: document.getElementById('dot-daemon'),
};

let inventory = { blocks: [], mobDefs: [], mobModels: [], mobTextures: [], guns: [] };
let currentTab = 'mobs';

const viewer = new Viewer(el.viewport);

const ctx = {
    viewer,
    el,
    inventory: () => inventory,
    details(html) { el.details.innerHTML = html; },
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
        initTextures(ctx);
        initAudit(ctx);
        updateCounts();
    } catch (e) {
        el.statusText.textContent = 'inventory failed: ' + e.message;
        console.error('inventory failed:', e);
    }
}

function updateCounts() {
    const set = (id, count) => {
        const badge = document.getElementById(id);
        if (badge) badge.textContent = count;
    };
    set('count-mobs', inventory.mobDefs.length + inventory.mobModels.length);
    set('count-items', inventory.guns.reduce((n, g) => n + g.frames.length, 0));
    set('count-blocks', inventory.blocks.length);
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
    el.dotCode.className = 'dot ' + (code ?? '');
    el.dotDaemon.className = 'dot ' + (daemon ?? '');
    if (text) el.statusText.textContent = text;
}

setStatus('busy', 'down', 'starting…');

const events = new EventSource('/api/events');
events.onmessage = (msg) => {
    let event;
    try { event = JSON.parse(msg.data); } catch { return; }
    if (event.type === 'code') {
        if (event.status === 'compiling') setStatus('busy', el.dotDaemon.className.split(' ')[1] ?? 'down', 'compiling pipeline…');
        if (event.status === 'ok') setStatus('ok', el.dotDaemon.className.split(' ')[1] ?? 'down', 'pipeline compiled');
        if (event.status === 'error') {
            setStatus('error', el.dotDaemon.className.split(' ')[1] ?? 'down', 'compile failed — see the server console');
            console.error('compile failed:', event.stage, event.output);
        }
    }
    if (event.type === 'daemon') {
        const code = el.dotCode.className.split(' ')[1] ?? 'busy';
        if (event.status === 'up') setStatus(code, 'ok', 'daemon up');
        if (event.status === 'down') setStatus(code, 'down', 'daemon down (restarting…)');
    }
    if (event.type === 'assets') {
        loadInventory();
        el.statusText.textContent = 'assets changed — inventory refreshed';
    }
};

document.getElementById('btn-recompile').addEventListener('click', async () => {
    try { await postJson('/api/recompile', {}); } catch (e) { alert(e.message); }
});
document.getElementById('btn-markers').addEventListener('click', () => {
    const checkbox = document.getElementById('btn-markers');
    viewer.setMarkersVisible(checkbox.checked);
});
document.getElementById('btn-frame').addEventListener('click', () => viewer.focus());

for (const button of document.querySelectorAll('#tabs button')) {
    button.addEventListener('click', () => switchTab(button.dataset.tab));
}

switchTab('mobs');
loadInventory();
