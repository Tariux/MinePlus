// Blocks tab: virtual block models (.bbmodel) through the real importer,
// plus the shared block presentation used by other tabs.

import { postJson } from './api.js';
import { esc, chip, section, kv, list, texThumb } from './ui.js';

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
    const textures = response.textures ?? [];
    const unresolved = textures.filter(t => !t.resolved);
    ctx.details(`
      <h2>${esc(response.key ?? response.file ?? 'block')}</h2>
      <div class="card verdict">
        ${chip(response.status ?? 'BROKEN')}
        <p>${esc(response.summary ?? '')}</p>
      </div>
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
      ${section('Textures', textures.length ? `<div class="thumbs">${textures.map(t => `
          <figure>
              ${texThumb(t)}
              <figcaption>${esc(t.name)}${t.resolved ? `<br>${t.width}×${t.height}` : '<br><b class="broken-text">not on disk</b>'}</figcaption>
          </figure>`).join('')}</div>` : '<div class="muted">no textures referenced</div>')}
      ${unresolved.length > 0 ? section('Missing textures', list(unresolved.map(t =>
          `${esc(t.name)} — the client renders those faces with the missing texture`))) : ''}
    `);
}
