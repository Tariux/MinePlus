// The stage bar: one place to control what the viewport reproduces — which
// layers are visible and which light the displays render under.
//
// This is the "am I seeing what the game will show" control surface. Light state
// is the shared `Lighting` object from shading.js; every change re-derives the
// materials, it never re-requests from the daemon.

import { LIGHT_PRESETS, SKY_TOP, BLOCK_TOP, lightScale, displayLight } from './shading.js';

const LAYERS = [
    { key: 'reference', label: 'model textures', hint: 'the .bbmodel’s own UV windows — what the author drew' },
    { key: 'game', label: 'in-game displays', hint: 'the exact BlockDisplay list the server emits (plates, palette, brightness)' },
    { key: 'pack', label: 'pack model', hint: 'the JSON model the resource pack ships, drawn flat like the client' },
    { key: 'guides', label: 'guides', hint: 'anchor cell, hitbox, ground shadow' },
    { key: 'wire', label: 'wireframe', hint: 'display outlines — shows plate overlap and hollow faces' },
    { key: 'markers', label: 'bone markers', hint: 'pivot points and hand/item attachments' },
];

export function initStage(ctx) {
    const stage = document.getElementById('stage');
    const viewer = ctx.viewer;
    const lighting = ctx.lighting;

    stage.innerHTML = `
      <button id="stage-toggle" class="btn tiny" title="Collapse the stage bar">stage</button>
      <div id="stage-body">
        <div class="stage-row" id="stage-layers"></div>
        <div class="stage-row">
          <label class="inline small">light <select id="light-preset"></select></label>
          <label class="inline small">sky <input type="range" id="light-sky" min="0" max="${SKY_TOP}" step="1"
            value="${lighting.sky}" title="Sky light level at the display position"></label>
          <label class="inline small">block <input type="range" id="light-block" min="0" max="15" step="1"
            value="${lighting.block}" title="Block light level at the display position"></label>
          <label class="inline small">policy <select id="light-policy" title="PACK.LIGHTING policy: how the pack backend overrides world light">
            ${['NATURAL', 'AUTO', 'EMISSIVE', 'FULLBRIGHT'].map(p =>
                `<option value="${p}">${p}</option>`).join('')}
          </select></label>
        </div>
        <div class="stage-row">
          <label class="inline small"><input type="checkbox" id="opt-faceshade" checked
            title="Vanilla per-face multipliers: up 1.0, north/south 0.8, east/west 0.6, down 0.5"> face shading</label>
          <label class="inline small"><input type="checkbox" id="opt-tint"
            title="Approximate sky/block colour cast — NOT vendored from the client shader"> lightmap tint</label>
          <label class="inline small"><input type="checkbox" id="opt-cull"
            title="Off = the client's face culling; on = see through into hollow cubes"> double-sided</label>
          <span id="stage-readout" class="muted small"></span>
        </div>
      </div>`;

    const layers = document.getElementById('stage-layers');
    layers.innerHTML = LAYERS.map(l => `
        <label class="inline small" title="${l.hint}">
            <input type="checkbox" data-layer="${l.key}" ${viewer.getLayer(l.key) ? 'checked' : ''}> ${l.label}
        </label>`).join('');
    for (const input of layers.querySelectorAll('input[data-layer]')) {
        input.addEventListener('change', () => viewer.setLayer(input.dataset.layer, input.checked));
    }

    const preset = document.getElementById('light-preset');
    preset.innerHTML = `<option value="custom">custom</option>`
        + Object.entries(LIGHT_PRESETS).map(([k, p]) => `<option value="${k}">${p.label}</option>`).join('');
    const sky = document.getElementById('light-sky');
    const block = document.getElementById('light-block');
    const policy = document.getElementById('light-policy');
    policy.value = lighting.policy;

    preset.addEventListener('change', () => {
        const p = LIGHT_PRESETS[preset.value];
        if (!p) return;
        lighting.set({ sky: p.sky, block: p.block ?? 0 });
    });
    sky.addEventListener('input', () => { preset.value = 'custom'; lighting.set({ sky: Number(sky.value) }); });
    block.addEventListener('input', () => { preset.value = 'custom'; lighting.set({ block: Number(block.value) }); });
    policy.addEventListener('change', () => lighting.set({ policy: policy.value }));

    document.getElementById('opt-faceshade').addEventListener('change', (e) =>
        lighting.set({ faceShade: e.target.checked }));
    document.getElementById('opt-tint').addEventListener('change', (e) =>
        lighting.set({ tint: e.target.checked }));
    document.getElementById('opt-cull').addEventListener('change', (e) =>
        viewer.setDoubleSided(e.target.checked));

    const toggle = document.getElementById('stage-toggle');
    const body = document.getElementById('stage-body');
    toggle.addEventListener('click', () => {
        body.classList.toggle('hidden');
        toggle.classList.toggle('open', !body.classList.contains('hidden'));
    });

    function readout() {
        sky.value = lighting.sky;
        block.value = lighting.block;
        policy.value = lighting.policy;
        const light = displayLight({ lightEmission: 0, brightness: null }, lighting.world());
        const percent = Math.round(lightScale(light) * 100);
        const shaded = lighting.faceShade ? 'face-shaded' : 'unshaded';
        const kind = ctx.shading ? ` · ${ctx.shading.kind} ${ctx.shading.faceShade ? 'shaded' : 'FLAT'}` : '';
        document.getElementById('stage-readout').textContent =
            `natural light ${percent}% · ${shaded} · policy ${lighting.policy}${kind}`;
    }
    lighting.onChange(readout);
    readout();
    return { readout };
}
