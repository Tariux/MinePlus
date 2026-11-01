// Blocks tab: virtual block models (.bbmodel) through the real importer,
// plus the shared block presentation used by other tabs.

import { postJson } from './api.js';
import { esc, chip, section, kv, list, texThumb, shadingCard, brightnessCard } from './ui.js';

export function initBlocks(ctx) {
    const inv = ctx.inventory();
    const el = ctx.el.listBlocks;
    el.innerHTML = `
      <h2>Block models</h2>
      <ul class="plain selectable">${inv.blocks.map(b => `
        <li class="row" data-file="${esc(b.file)}">
            <span class="row-name">${esc(b.key)}</span>
            <span class="row-hint">${b.hasMeta ? 'meta' : 'bbmodel'}</span>
        </li>`).join('') || '<li class="muted">none in the watched roots</li>'}</ul>`;
    for (const row of el.querySelectorAll('li.row[data-file]')) {
        row.addEventListener('click', () => ctx.open('block', row.dataset.file));
    }
}

export async function openBlock(ctx, file) {
    const response = await postJson('/api/block', { model: file });
    showBlock(ctx, response);
    return response;
}

export function showBlock(ctx, response) {
    if (response.status === 'BROKEN' && !response.cubes) {
        ctx.viewer.clear();
    } else {
        ctx.viewer.renderBlock(response);
    }
    ctx.shading = response.shading ?? null;
    const file = response.file ?? response.modelFile ?? null;
    const textures = response.textures ?? [];
    const unresolved = textures.filter(t => !t.resolved);
    const displays = response.displays ?? [];
    const byKind = countBy(displays, d => d.kind ?? 'face');
    const bySource = countBy(displays, d => d.rgbSource ?? 'fallback');

    ctx.details(`
      <h2>${esc(response.key ?? response.file ?? 'block')} ${
          file ? `<button class="btn tiny" data-act="bake" data-file="${esc(file)}">Bake →</button>` : ''}</h2>
      <div class="card verdict">
        ${chip(response.status ?? 'BROKEN')}
        <p>${esc(response.summary ?? '')}</p>
      </div>
      ${section('What the server emits', `
        ${kv([
            ['BlockDisplays', response.displayCount ?? displays.length],
            ['by kind', Object.entries(byKind).map(([k, n]) => `${k} ×${n}`).join(' · ') || '—'],
            ['colours from palette', bySource.palette ?? 0],
            ['sampled / fallback', `${bySource.sampled ?? 0} / ${bySource.fallback ?? 0}`],
        ])}
        <p class="muted small">Each entry is one <code>BlockDisplay</code> the real
        <code>DisplayEmitter</code> produced for this model, with its exact transform,
        material colour and light override. Toggle the <b>in-game displays</b> layer to
        see them instead of the model textures.</p>
        ${(bySource.sampled ?? 0) > 0 ? `<p class="muted small">${bySource.sampled} of them have no
        texel-palette material, so the tool colours them by sampling the model texture — vanilla block
        PNGs are not vendored here. In game those displays show the real block texture.</p>` : ''}`)
      }
      ${shadingCard(response.shading)}
      ${brightnessCard(response.brightness)}
      ${section('Mechanism', kv([
          ['model format', esc(response.modelFormat ?? '')],
          ['origin mode', esc(response.originMode ?? '')],
          ['collision mode', esc(response.meta?.collisionMode ?? 'global default')],
          ['autoplay clips', (response.meta?.autoplay ?? []).join(', ')],
          ['has .meta.json', response.hasMetaFile ? 'yes' : 'no'],
          ['bone count', response.boneCount],
      ]))}
      ${section('Geometry', kv([
          ['cubes', response.cubeCount],
          ['resolution', response.resolution ? `${response.resolution.width}×${response.resolution.height}` : ''],
          ['animated', response.animated ? 'yes' : 'no'],
      ]))}
      ${response.animations && response.animations.length > 0
        ? section('Clips', list(response.animations.map(c =>
            `${esc(c.name)} — ${c.lengthSeconds}s, ${esc(c.loopMode)}, ${Object.keys(c.tracks ?? {}).length} bone(s)`))) : ''}
      ${packSection(response)}
      ${section('Textures', textures.length ? `<div class="thumbs">${textures.map(t => `
          <figure>
              ${texThumb(t)}
              <figcaption>${esc(t.name)}${t.resolved ? `<br>${t.width}×${t.height}` : '<br><b class="broken-text">not on disk</b>'}</figcaption>
          </figure>`).join('')}</div>` : '<div class="muted">no textures referenced</div>')}
      ${unresolved.length > 0 ? section('Missing textures', list(unresolved.map(t =>
          `${esc(t.name)} — the client renders those faces with the missing texture`))) : ''}
    `);
    bindActions(ctx, response);
}

/**
 * The pack axis of the same model: `ModelJsonWriter` output with the shading
 * flags it actually ships, so the flat look is explained rather than assumed.
 */
function packSection(response) {
    if (response.packModel === null || response.packModel === undefined) {
        return section('Pack model',
            `<p class="muted">${esc(response.packModelError ?? 'not emitted for this asset')}</p>`);
    }
    return section('Pack model', `
      ${kv([
          ['ambientocclusion', response.packModelAmbientOcclusion === undefined ? '—' : String(response.packModelAmbientOcclusion)],
          ['shade', response.packModelShade === undefined ? '—' : String(response.packModelShade)],
      ])}
      ${(response.packModelWarnings ?? []).length > 0
        ? list(response.packModelWarnings.map(w => esc(w))) : ''}
      <p class="muted small">Pack models ship <code>shade:false</code>, so the client draws
      them with no directional multipliers — that is what the <b>pack model</b> layer reproduces.</p>
      <button class="btn tiny" data-act="pack-render">Render pack model</button>
      <details><summary class="muted small">model JSON</summary>
        <pre class="code small">${esc(prettyJson(response.packModel))}</pre></details>`);
}

function prettyJson(model) {
    try {
        return JSON.stringify(typeof model === 'string' ? JSON.parse(model) : model, null, 2);
    } catch {
        return String(model);
    }
}

function bindActions(ctx, response) {
    for (const button of ctx.el.details.querySelectorAll('button[data-act]')) {
        button.addEventListener('click', () => runAction(ctx, response, button.dataset));
    }
}

function runAction(ctx, response, dataset) {
    if (dataset.act === 'bake') {
        ctx.open('bake', dataset.file);
        return;
    }
    if (dataset.act === 'pack-render') {
        let model;
        try {
            model = typeof response.packModel === 'string'
                ? JSON.parse(response.packModel) : response.packModel;
        } catch (e) {
            alert('pack model JSON did not parse: ' + e.message);
            return;
        }
        ctx.viewer.renderPackModel(model, packTextureResolver(ctx, response));
        ctx.setLayer('pack', true);
        ctx.setLayer('reference', false);
        ctx.setLayer('game', false);
    }
}

/** Resolves a pack face key (`ns:path` or bare) to a texture the daemon already found. */
function packTextureResolver(ctx, response) {
    const byKey = new Map();
    const remember = (key, path) => {
        if (key && path) byKey.set(String(key), path);
    };
    for (const t of response.textures ?? []) {
        if (!t.resolved || !t.path) continue;
        const bare = String(t.name).split('/').pop();
        remember(t.name, t.path);
        remember(String(t.name).replace(/^[^:]*:/, ''), t.path);
        remember(bare, t.path);
        remember(bare.replace(/\.png$/i, ''), t.path);
    }
    return (key) => {
        const bare = String(key ?? '').split('/').pop();
        const path = byKey.get(String(key)) ?? byKey.get(bare) ?? byKey.get(bare.replace(/\.png$/i, ''));
        return path ? ctx.viewer.textureFromPath(path) : null;
    };
}

function countBy(items, key) {
    const out = {};
    for (const item of items) {
        const k = key(item);
        out[k] = (out[k] ?? 0) + 1;
    }
    return out;
}
