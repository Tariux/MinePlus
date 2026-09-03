// API layer: SSE subscription + HTTP helpers.

export function bake(model, overrides) {
    return fetch('/api/bake', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ model, overrides }),
    }).then(r => r.json());
}

export function fetchModels() {
    return fetch('/api/models').then(r => r.json());
}

export function assetUrl(absPath) {
    return '/api/asset?p=' + encodeURIComponent(absPath);
}

export function requestRecompile() {
    return fetch('/api/recompile', { method: 'POST' }).then(r => r.json());
}

export function subscribeEvents(onEvent) {
    const es = new EventSource('/api/events');
    es.onmessage = e => {
        try {
            onEvent(JSON.parse(e.data));
        } catch (err) {
            console.error('bad SSE payload', err);
        }
    };
    return es;
}
