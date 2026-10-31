// Items tab: gun item models — per-frame validation of the raw JSON the pack
// ships, exactly as the client parses it.

import { postJson } from './api.js';
import { esc, chip, section, kv, list, findings, texThumbForKey } from './ui.js';

export function initItems(ctx) {
    const inv = ctx.inventory();
    const el = ctx.el.listItems;
    const FRAME_ORDER = ['idle', 'primed', 'equip', 'cycle', 'scope_in', 'scope_hold', 'scope_out', 'loading', 'fire', 'other'];
    el.innerHTML = inv.guns.map(g => {
        const groups = new Map();
        for (const frame of g.frames) {
            const family = frame.frame.startsWith('loading_') ? 'loading'
                : frame.frame.startsWith('fire') ? 'fire' : frame.frame;
            if (!groups.has(family)) groups.set(family, []);
            groups.get(family).push(frame);
        }
        const families = [...groups.keys()].sort((a, b) =>
            FRAME_ORDER.indexOf(a) - FRAME_ORDER.indexOf(b));
        return `
        <h2>${esc(g.gun)}</h2>
        <ul class="plain selectable">${families.map(family => `
            <li class="group">${esc(family)}
                <ul class="plain">${groups.get(family).map(f => `
                    <li class="row" data-file="${esc(f.file)}">
                        <span class="row-name">${esc(f.name)}</span><span class="row-hint">json</span>
                    </li>`).join('')}</ul>
            </li>`).join('')}
            ${g.sources.map(s => `
            <li class="row" data-file="${esc(s.file)}" data-source="1">
                <span class="row-name">${esc(s.name)}</span><span class="row-hint">bbmodel</span>
            </li>`).join('')}
        </ul>`;
    }).join('') || '<div class="muted">no gun roots found</div>';

    for (const row of el.querySelectorAll('li.row[data-file]')) {
        row.addEventListener('click', () => ctx.open(row.dataset.source ? 'block' : 'item', row.dataset.file));
    }
}

export async function openItem(ctx, file) {
    const response = await postJson('/api/item', { model: file });
    showItem(ctx, response);
    return response;
}

export function showItem(ctx, response) {
    ctx.viewer.renderItem(response);
    const textures = response.textures ?? {};
    const textureKeys = Object.keys(textures);
    ctx.details(`
      <h2>${esc(response.name ?? 'item')}</h2>
      <div class="card verdict">
        ${chip(response.status ?? 'BROKEN')}
        <p>${esc(response.summary ?? '')}</p>
      </div>
      ${section('Model', kv([
          ['file', esc(shortPath(response.file))],
          ['parent', esc(response.parent ?? '—')],
          ['gui light', esc(response.guiLight ?? 'front-side (default)')],
          ['texture size', response.textureSize ? `${response.textureSize.width}×${response.textureSize.height}` : ''],
          ['elements', response.elementCount],
          ['out of window', response.outOfWindow ?? 0],
      ]))}
      ${section('Findings', findings(response.findings))}
      ${textureKeys.length > 0 ? section('Textures', `<table class="grid">
          <tr><th></th><th>key</th><th>resolved target</th><th>png</th></tr>
          ${textureKeys.map(k => {
              const t = textures[k];
              return `<tr>
                  <td>${texThumbForKey(t, 'thumb small')}</td>
                  <td class="code">${esc(k)}</td>
                  <td>${esc(t.resolved ?? '')}${t.error ? `<br><span class="broken-text">${esc(t.error)}</span>` : ''}</td>
                  <td>${t.png?.found ? `${t.png.width}×${t.png.height}` : '<span class="broken-text">not found</span>'}</td>
              </tr>`;
          }).join('')}
      </table>`) : ''}
      ${section('Elements', list((response.elements ?? []).map(e => {
          const faces = Object.entries(e.faces ?? {});
          const bad = faces.filter(([, f]) => f.resolvedOk === false).length;
          return `${esc(e.name)} — from [${e.from?.map(v => Math.round(v * 10) / 10).join(', ')}]`
              + ` to [${e.to?.map(v => Math.round(v * 10) / 10).join(', ')}]`
              + (e.rotation ? `, ${esc(e.rotation.axis)}@${e.rotation.angle}°` : '')
              + `, ${faces.length} face(s)`
              + (e.inWindow === false ? ' <b class="broken-text">out of window</b>' : '')
              + (bad > 0 ? ` <b class="broken-text">${bad} untextured</b>` : '');
      })))}
    `);
}

function shortPath(path) {
    return String(path ?? '').replace(/^.*\/examples\//, 'examples/').replace(/^.*\/resources\//, '…/');
}
