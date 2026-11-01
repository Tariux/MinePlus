// Shared HTML rendering helpers for the Asset Lab panels.

export function esc(text) {
    return String(text ?? '')
        .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;');
}

export function chip(status) {
    const cls = status === 'OK' ? 'ok' : status === 'REPAIRED' ? 'repaired' : 'broken';
    return `<span class="chip ${cls}">${esc(status)}</span>`;
}

export function section(title, inner) {
    return `<h3>${esc(title)}</h3><div class="card">${inner}</div>`;
}

export function kv(pairs) {
    const rows = pairs.filter(([, v]) => v !== undefined && v !== null && v !== '')
        .map(([k, v]) => `<div class="kv"><span>${esc(k)}</span><span>${v}</span></div>`)
        .join('');
    return rows ? `<div class="kv-grid">${rows}</div>` : '<div class="muted">—</div>';
}

export function list(items, emptyText) {
    if (!items || items.length === 0) return `<div class="muted">${esc(emptyText ?? 'none')}</div>`;
    return `<ul class="plain">${items.map(i => `<li>${i}</li>`).join('')}</ul>`;
}

export function findings(items) {
    if (!items || items.length === 0) return '<div class="muted">none</div>';
    return `<ul class="findings">${items.map(f => {
        const code = typeof f === 'string' ? '' : f.code;
        const message = typeof f === 'string' ? f : f.message;
        return `<li>${code ? `<span class="code">${esc(code)}</span> ` : ''}${esc(message)}</li>`;
    }).join('')}</ul>`;
}

/** Texture preview: base64 PNG inline, or a path through /api/asset. */
export function texThumb(texture, cls = 'thumb') {
    if (texture?.pngBase64) {
        return `<img class="${cls}" src="data:image/png;base64,${texture.pngBase64}" alt="">`;
    }
    if (texture?.path) {
        return `<img class="${cls}" src="/api/asset?p=${encodeURIComponent(texture.path)}" alt="">`;
    }
    return `<span class="${cls} missing"></span>`;
}

export function texThumbForKey(entry, cls) {
    if (!entry) return `<span class="${cls ?? 'thumb'} missing"></span>`;
    if (entry.png?.found) return texThumb(entry.png, cls);
    return `<span class="${cls ?? 'thumb'} missing"></span>`;
}

/** CSS #rrggbb for a packed 0xRRGGBB colour. */
export function cssRgb(packed) {
    if (!Number.isFinite(packed)) return '#808080';
    return '#' + ((packed >>> 0) & 0xffffff).toString(16).padStart(6, '0');
}

/** A proportion bar, used by the bake histograms. */
export function bar(percent, color = '#5b8dc9') {
    const width = Math.max(0, Math.min(100, percent)).toFixed(0);
    return `<div class="bar-wrap"><div class="bar" style="width:${width}%;background:${color}"></div></div>`;
}

export function badge(text, cls = '') {
    return `<span class="badge ${cls}">${esc(text)}</span>`;
}

/**
 * How this content is lit in game, in the client's own terms — the answer comes
 * from the daemon's `shading` field, so the panel never claims a shade model the
 * pipeline does not implement.
 */
export function shadingCard(shading) {
    if (!shading) return '';
    const flat = !shading.faceShade;
    return section('Client shading', `
      <p>${flat
        ? `Rendered <b>flat</b> — ${esc(shading.reason ?? 'the shipped model carries shade:false')}.`
        : `Rendered with the <b>vanilla face multipliers</b> (up 1.0, north/south 0.8, east/west 0.6, down 0.5).`}
      </p>
      ${kv([['kind', esc(shading.kind ?? '')], ['face shading', flat ? 'off' : 'on']])}`);
}

/**
 * How the server answers the question "what light does this display carry" — the
 * daemon's `brightness` field, so the panel states the engine's rule verbatim
 * instead of the tool guessing one.
 */
export function brightnessCard(brightness) {
    if (!brightness) return '';
    if (brightness.overrides === false) {
        return section('Light override', `
          <p>No <code>setBrightness</code> call: the visual inherits the world's
          sky/block light at its position.</p>
          <p class="muted small">${esc(brightness.reason ?? '')}</p>
          ${kv([['kind', esc(brightness.kind ?? '')]])}`);
    }
    return section('Light override', `
      <p>${esc(brightness.rule ?? '')}</p>
      ${kv([
          ['floor (texelBrightness)', brightness.floor],
          ['block light pinned to', brightness.block ?? 15],
          ['source', `<span class="code">${esc(brightness.source ?? '')}</span>`],
      ])}
      <p class="muted small">A display with no emission and no floor still inherits world light —
      drag the stage's sky/block sliders to see that case.</p>`);
}
