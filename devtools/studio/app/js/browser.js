// Unified asset browser: every asset across all watched roots — the shipped
// defaults, the live server folder, and every extra `--models` root — in one
// navigable list. Clicking (or walking with j/k) opens the asset in the tool
// that owns it; `?open=<kind>:<id>` deep-links straight to one asset so
// agents and bookmarks can land on a model in a single step.

import { section } from './ui.js';

const KIND_ICON = {
    block: '🧱',
    mob: '🐺',
    mobdef: '📋',
    item: '🔫',
    texture: '🖼',
    pack: '📦',
};

export function initBrowser(ctx) {
    const list = document.getElementById('list-browser');
    if (!list) return;

    const state = { entries: [], selected: -1, byKey: new Map() };

    function collect() {
        const inv = ctx.inventory();
        const entries = [];
        const push = (kind, id, label, sub, file) => {
            if (!id) return;
            const entry = { kind, id, label: label ?? id, sub: sub ?? '', file };
            entries.push(entry);
            state.byKey.set(kind + ':' + id, entry);
        };
        for (const b of inv.blocks) {
            push('block', b.key, b.key, rootTag(b.root), b.file);
        }
        for (const m of inv.mobModels) {
            push('mob', m.name, m.name, rootTag(m.root), m.file);
        }
        for (const d of inv.mobDefs) {
            push('mobdef', d.name, d.name + '.yml', rootTag(d.root), d.file);
        }
        for (const g of inv.guns) {
            push('item', g.gun + '#source', g.gun + ' (bbmodel)', rootTag(g.root), g.dir);
            for (const f of g.frames) {
                push('item', g.gun + '#' + f.name, f.name, g.gun, f.file);
            }
        }
        for (const t of inv.mobTextures) {
            push('texture', t.name, t.name, rootTag(t.root), t.file);
        }
        for (const p of inv.packArtifacts ?? []) {
            push('pack', String(p), fileName(String(p)), 'artifact', String(p));
        }
        entries.sort((a, b) => a.kind.localeCompare(b.kind) || a.label.localeCompare(b.label));
        state.entries = entries;
        state.byKey.clear();
        for (const entry of entries) {
            state.byKey.set(entry.kind + ':' + entry.id, entry);
        }
    }

    function fileName(file) {
        return String(file).split('/').pop();
    }

    function rootTag(root) {
        // The watched-root label: the last path segment ("models", "defaults",
        // an extra root's folder name) — enough to tell roots apart at a glance.
        const parts = String(root).split('/').filter(Boolean);
        return parts.length ? parts[parts.length - 1] : '';
    }

    function render(filter) {
        const q = (filter ?? '').trim().toLowerCase();
        const visible = q
            ? state.entries.filter(e =>
                (e.label + ' ' + e.kind + ' ' + e.sub).toLowerCase().includes(q))
            : state.entries;
        if (!visible.length) {
            list.innerHTML = `<div class="empty">${q ? 'no assets match' : 'no assets found'}</div>`;
            return;
        }
        list.innerHTML = visible.map(e => `
            <button class="entry" data-key="${e.kind}:${e.id}" title="${e.kind}: ${e.id}">
                <span class="ico">${KIND_ICON[e.kind] ?? '•'}</span>
                <span class="name">${e.label}</span>
                <span class="sub">${e.sub}</span>
            </button>`).join('');
        for (const button of list.querySelectorAll('.entry')) {
            button.addEventListener('click', () => select(button.dataset.key));
        }
        highlight();
    }

    function highlight() {
        for (const button of list.querySelectorAll('.entry')) {
            button.classList.toggle('selected', button.dataset.key === state.selected);
        }
        const active = list.querySelector('.entry.selected');
        if (active) active.scrollIntoView({ block: 'nearest' });
    }

    async function select(key) {
        const entry = state.byKey.get(key);
        if (!entry) return;
        state.selected = key;
        highlight();
        history.replaceState(null, '', `?open=${encodeURIComponent(key)}`);
        await ctx.open(entry.kind, entry.file);
    }

    list.parentElement?.addEventListener('keydown', (e) => {
        if (e.key !== 'j' && e.key !== 'k') return;
        e.preventDefault();
        const visible = [...list.querySelectorAll('.entry')].map(b => b.dataset.key);
        const at = visible.indexOf(state.selected);
        const next = visible[at === -1 ? 0 : Math.max(0, Math.min(visible.length - 1, at + (e.key === 'j' ? 1 : -1)))];
        if (next) select(next);
    });

    const search = document.getElementById('browser-search');
    if (search) {
        search.addEventListener('input', () => render(search.value));
    }

    ctx.browser = {
        refresh() {
            collect();
            render(search ? search.value : '');
        },
        entries: () => state.entries,
        find(kind, id) {
            // Exact key first, then a substring match (agents pass short ids).
            return state.byKey.get(kind + ':' + id)
                ?? state.entries.find(e => e.kind === kind
                    && e.id.toLowerCase().includes(id.toLowerCase()));
        },
    };

    // Deep link (?open=block:alchemy-table) — resolved by main.js after the
    // inventory lands.
    const requested = new URLSearchParams(location.search).get('open');
    if (requested) {
        ctx.pendingDeepLink = requested;
    }
}
