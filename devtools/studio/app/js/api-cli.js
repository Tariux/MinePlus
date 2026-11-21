// Node-side fetch helpers for the Studio agent CLI (the browser's api.js
// equivalents, parameterized by base URL so both hit the same endpoints).

export async function getJson(base, url) {
    const res = await fetch(base + url);
    const body = await res.json();
    if (!res.ok) throw new Error(body.error ?? res.statusText);
    return body;
}

export async function postJson(base, url, payload) {
    const res = await fetch(base + url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload ?? {}),
    });
    const body = await res.json();
    if (!res.ok) throw new Error(body.error ?? res.statusText);
    return body;
}
