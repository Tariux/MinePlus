// Thin fetch helpers for the Asset Lab app.

export async function getJson(url) {
    const res = await fetch(url);
    const body = await res.json();
    if (!res.ok) throw new Error(body.error ?? res.statusText);
    return body;
}

export async function postJson(url, payload) {
    const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload ?? {}),
    });
    const body = await res.json();
    if (!res.ok) throw new Error(body.error ?? res.statusText);
    return body;
}
