// Textures tab: every texture PNG under the watched roots, with a large
// preview and the file's identity.

import { esc } from './ui.js';

export function initTextures(ctx) {
    const inv = ctx.inventory();
    const el = ctx.el.listTextures;
    const entries = [];
    for (const t of inv.mobTextures) entries.push({ file: t.file, group: 'mob textures' });
    for (const gun of inv.guns) {
        for (const file of gun.textures) entries.push({ file, group: `gun ${gun.gun}` });
    }
    const groups = new Map();
    for (const e of entries) {
        if (!groups.has(e.group)) groups.set(e.group, []);
        groups.get(e.group).push(e.file);
    }
    el.innerHTML = [...groups.entries()].map(([group, files]) => `
      <h2>${esc(group)}</h2>
      <ul class="plain selectable">${files.map(f => `
        <li class="row" data-file="${esc(f)}">
            <img class="thumb small" src="/api/asset?p=${encodeURIComponent(f)}" alt="">
            <span class="row-name">${esc(f.split('/').pop())}</span>
        </li>`).join('')}</ul>`).join('')
      || '<div class="muted">no textures under the watched roots</div>';

    for (const row of el.querySelectorAll('li.row[data-file]')) {
        row.addEventListener('click', () => showTexture(ctx, row.dataset.file));
    }
}

export function showTexture(ctx, file) {
    ctx.viewer.clear();
    ctx.details(`
      <h2>${esc(file.split('/').pop())}</h2>
      <div class="card">
        <img class="preview" src="/api/asset?p=${encodeURIComponent(file)}" alt="">
        <p class="muted" id="texture-dims"></p>
        <p class="code" style="word-break:break-all">${esc(file)}</p>
      </div>
      <p class="muted">Previewed exactly as shipped — pixel-for-pixel what the client samples.
      Watch for: wrong canvas size for the model's UV layout, stray opaque pixels
      where cutouts are expected, and palette drift from the vanilla style.</p>`);
    const img = ctx.el.details.querySelector('img.preview');
    img.addEventListener('load', () => {
        ctx.el.details.querySelector('#texture-dims').textContent =
            `${img.naturalWidth} × ${img.naturalHeight} px`;
    });
}
