// Audit tab: run every asset in the watched roots through its op in one pass
// and summarize — the "is anything broken before I go in game" button.

import { postJson } from './api.js';
import { esc, chip } from './ui.js';

export function initAudit(ctx) {
    const el = ctx.el.listAudit;
    el.innerHTML = `
      <h2>Pack audit</h2>
      <p class="muted">Runs every block model, mob definition, mob model and item
      frame in the watched roots through the daemon — the same ops the other tabs
      use — and reports each one's verdict.</p>
      <button id="btn-audit" class="btn primary">Run audit</button>
      <div id="audit-progress" class="muted"></div>`;

    el.querySelector('#btn-audit').addEventListener('click', async () => {
        const button = el.querySelector('#btn-audit');
        const progress = el.querySelector('#audit-progress');
        button.disabled = true;
        progress.textContent = 'running… (all assets through the pipeline)';
        try {
            const result = await postJson('/api/audit', {});
            progress.textContent = '';
            showAudit(ctx, result);
        } catch (e) {
            progress.textContent = 'failed: ' + e.message;
        } finally {
            button.disabled = false;
        }
    });
}

export function showAudit(ctx, result) {
    const rows = result.rows ?? [];
    ctx.details(`
      <h2>Audit — ${result.checked ?? rows.length} asset(s)</h2>
      <div class="card tally">
        <span class="chip ok">${result.okCount ?? 0} OK</span>
        <span class="chip repaired">${result.repairedCount ?? 0} repaired</span>
        <span class="chip broken">${result.brokenCount ?? 0} broken</span>
      </div>
      <table class="grid audit-table">
        <tr><th></th><th>category</th><th>asset</th><th>counts</th><th>summary</th></tr>
        ${rows.map((row, i) => `
          <tr class="row" data-i="${i}" data-category="${esc(row.category)}" data-file="${esc(row.file)}">
              <td>${chip(row.status)}</td>
              <td>${esc(row.category)}</td>
              <td class="row-name">${esc(row.name)}</td>
              <td class="muted">${counts(row)}</td>
              <td class="muted">${esc(row.summary ?? '')}</td>
          </tr>`).join('')}
      </table>
      <p class="muted">Click a row to open that asset in its tab with the full viewer.</p>`);

    for (const tr of ctx.el.details.querySelectorAll('tr.row[data-file]')) {
        tr.addEventListener('click', () => {
            const category = tr.dataset.category === 'item' ? 'item'
                : tr.dataset.category === 'mob' ? 'mob' : 'block';
            ctx.open(category, tr.dataset.file);
        });
    }
}

function counts(row) {
    const parts = [];
    if (row.bones) parts.push(row.bones + ' bones');
    if (row.cubes) parts.push(row.cubes + ' cubes');
    if (row.elements) parts.push(row.elements + ' elements');
    if (row.textures) parts.push(row.textures + ' textures');
    return parts.join(', ');
}
