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
