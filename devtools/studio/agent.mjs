#!/usr/bin/env node
// Mineplus Studio — agent CLI.
//
// The terminal twin of the web app: every check the browser can run is
// scriptable here, with machine-readable output and CI-friendly exit codes.
// AI agents (and CI) verify sections while coding without opening a browser;
// the checks go through the SAME daemon ops the UI uses, so an agent verdict
// is the web verdict.
//
// Usage:
//   node devtools/studio/agent.mjs <command> [args] [--base http://localhost:5167] [--json]
//
// Commands:
//   status                       server + daemon + settings health
//   list [kind]                  inventory: block|mob|mobdef|item|texture|pack
//   audit                        full audit pass (all asset kinds)
//   block <key>                  validate one block model (+ texel bake + displays)
//   mob <name>                   validate one mob model/definition (+ rig compile)
//   poses <model> [--duration s] [--speed b/t] [--attack|--hurt|--death]
//   item <gun>                   validate one gun's frame JSONs
//   pack                         inspect the compiled pack artifacts
//   verify <key>                 block-or-item verdict for one asset (exit 1 on BROKEN)
//   wait [--timeout <sec>]       block until the daemon is up (compile ready)
//
// Exit codes: 0 = ok / repaired, 1 = broken or failed check, 2 = tool
// unavailable (server down, daemon not compiled), 3 = usage error.
// --json prints the raw op response (every field the UI renders).

import { getJson, postJson } from './app/js/api-cli.js';

const args = process.argv.slice(2);
let base = process.env.STUDIO_URL ?? 'http://localhost:5167';
let jsonOut = false;
const rest = [];
for (let i = 0; i < args.length; i++) {
    if (args[i] === '--base') base = args[++i];
    else if (args[i] === '--json') jsonOut = true;
    else rest.push(args[i]);
}
const command = rest.shift();

function out(value) {
    console.log(jsonOut ? JSON.stringify(value, null, 2) : value);
}

const EXIT = { OK: 0, BROKEN: 1, UNAVAILABLE: 2, USAGE: 3 };
function exit(code) {
    process.exit(code);
}

/**
 * Ops share the audit vocabulary: `status: OK|REPAIRED|BROKEN` plus
 * `findings: [{code, message}]`. The audit pass tallies rows instead.
 */
function verdictOf(report) {
    if (typeof report?.status === 'string') {
        return report.status.toUpperCase().includes('BROKEN') ? 'BROKEN'
            : report.status.toUpperCase().includes('REPAIRED') ? 'REPAIRED'
            : 'OK';
    }
    if (typeof report?.brokenCount === 'number') {
        return report.brokenCount > 0 ? 'BROKEN'
            : report.repairedCount > 0 ? 'REPAIRED'
            : 'OK';
    }
    return report?.ok === false ? 'BROKEN' : 'OK';
}

function renderFindings(report) {
    const findings = report?.findings ?? [];
    if (!findings.length) return '  (no findings)';
    return findings.map(f => `  [${f.code ?? 'finding'}] ${f.message ?? f.detail ?? ''}`).join('\n');
}

async function main() {
    if (!command || command === '--help' || command === '-h' || command === 'help'
        || rest.includes('--help') || rest.includes('-h')) {
        out(__doc__);
        exit(command ? EXIT.OK : EXIT.USAGE);
    }

    try {
        switch (command) {
            case 'status': {
                const health = await getJson(base, '/api/health');
                const roots = health.details?.roots ?? {};
                const rootCount = Object.values(roots)
                    .reduce((n, list) => n + (Array.isArray(list) ? list.length : 0), 0);
                out(jsonOut ? health : [
                    `daemon:   ${health.daemon?.alive ? 'up' : 'down'}`
                        + (health.daemon?.lastError ? ` (${health.daemon.lastError})` : ''),
                    `code:     ${health.compile?.status ?? 'unknown'}`,
                    `settings: ${health.settings?.path ?? 'none deployed'}`,
                    `roots:    ${rootCount} watched root(s)`,
                ].join('\n'));
                exit(health.daemon?.alive ? EXIT.OK : EXIT.UNAVAILABLE);
                break;
            }
            case 'wait': {
                const timeoutIdx = rest.indexOf('--timeout');
                const deadline = Date.now() + (timeoutIdx >= 0 ? Number(rest[timeoutIdx + 1]) : 60) * 1000;
                while (Date.now() < deadline) {
                    try {
                        const health = await getJson(base, '/api/health');
                        if (health.daemon?.alive) {
                            out('daemon up');
                            exit(EXIT.OK);
                        }
                    } catch { /* not up yet */ }
                    await new Promise(r => setTimeout(r, 500));
                }
                out('daemon did not come up in time');
                exit(EXIT.UNAVAILABLE);
                break;
            }
            case 'list': {
                const kind = rest[0];
                const inventory = await getJson(base, '/api/inventory');
                const groups = {
                    block: inventory.blocks.map(b => b.key),
                    mob: inventory.mobModels.map(m => m.name),
                    mobdef: inventory.mobDefs.map(d => d.name),
                    item: inventory.guns.map(g => g.gun),
                    texture: inventory.mobTextures.map(t => t.name),
                    pack: (inventory.packArtifacts ?? []).map(a => a.name ?? String(a)),
                };
                if (kind) {
                    if (!groups[kind]) {
                        out(`unknown kind '${kind}' — block|mob|mobdef|item|texture|pack`);
                        exit(EXIT.USAGE);
                    }
                    out(jsonOut ? groups[kind] : groups[kind].join('\n'));
                } else {
                    out(jsonOut ? groups : Object.entries(groups)
                        .map(([k, v]) => `${k} (${v.length}): ${v.join(', ') || '—'}`).join('\n'));
                }
                exit(EXIT.OK);
                break;
            }
            case 'audit': {
                const report = await postJson(base, '/api/audit', {});
                if (jsonOut) {
                    out(report);
                } else {
                    out(`audit: ${report.okCount ?? 0} ok, ${report.repairedCount ?? 0} repaired,`
                        + ` ${report.brokenCount ?? 0} broken (of ${report.checked ?? 0})`
                        + `, ${report.bakesAttempted ?? 0} bake(s) attempted`);
                    for (const row of report.rows ?? []) {
                        const verdict = verdictOf(row);
                        if (verdict !== 'OK') {
                            out(`${row.op ?? 'row'} ${row.file ?? row.name ?? ''}: ${verdict}`);
                            out(renderFindings(row));
                        }
                    }
                }
                exit((report.brokenCount ?? 0) > 0 ? EXIT.BROKEN : EXIT.OK);
                break;
            }
            case 'block':
            case 'bake': {
                const key = rest[0];
                if (!key) { out('missing model key'); exit(EXIT.USAGE); }
                const inventory = await getJson(base, '/api/inventory');
                const block = inventory.blocks.find(b => b.key === key)
                    ?? inventory.blocks.find(b => b.key.toLowerCase().includes(key.toLowerCase()));
                if (!block) { out(`no block model matches '${key}' — try: list block`); exit(EXIT.USAGE); }
                const report = await postJson(base, '/api/bake', { model: block.file });
                if (jsonOut) {
                    out(report);
                } else {
                    out(`${command} ${block.key}: ${verdictOf(report)}`
                        + (report.cubeCount != null ? ` — ${report.cubeCount} cube(s)` : '')
                        + (report.displayCount != null ? `, ${report.displayCount} display(s)` : '')
                        + (report.summary ? `\n  ${report.summary}` : ''));
                    out(renderFindings(report));
                }
                exit(verdictOf(report) === 'BROKEN' ? EXIT.BROKEN : EXIT.OK);
                break;
            }
            case 'mob': {
                const name = rest[0];
                if (!name) { out('missing mob model/definition name'); exit(EXIT.USAGE); }
                const inventory = await getJson(base, '/api/inventory');
                const def = inventory.mobDefs.find(d => d.name === name)
                    ?? inventory.mobDefs.find(d => d.name.toLowerCase().includes(name.toLowerCase()));
                const model = inventory.mobModels.find(m => m.name === name + '.bbmodel')
                    ?? inventory.mobModels.find(m => m.name.toLowerCase().includes(name.toLowerCase()));
                if (!def && !model) { out(`no mob definition/model matches '${name}' — try: list mob`); exit(EXIT.USAGE); }
                const payload = def ? { yml: def.file } : { model: model.file };
                const report = await postJson(base, '/api/mob', payload);
                if (jsonOut) {
                    out(report);
                } else {
                    out(`mob ${name}: ${verdictOf(report)}`
                        + (report.summary ? `\n  ${report.summary}` : ''));
                    out(renderFindings(report));
                }
                exit(verdictOf(report) === 'BROKEN' ? EXIT.BROKEN : EXIT.OK);
                break;
            }
            case 'poses': {
                const model = rest[0];
                if (!model) { out('usage: poses <model> [--duration s] [--speed blocks/tick] [--attack|--hurt|--death]'); exit(EXIT.USAGE); }
                const inventory = await getJson(base, '/api/inventory');
                const mob = inventory.mobModels.find(m => m.name === model + '.bbmodel')
                    ?? inventory.mobModels.find(m => m.name.toLowerCase().includes(model.toLowerCase()));
                if (!mob) { out(`no mob model matches '${model}' — try: list mob`); exit(EXIT.USAGE); }
                const durationIdx = rest.indexOf('--duration');
                const speedIdx = rest.indexOf('--speed');
                const events = ['attack', 'hurt', 'death'].filter(e => rest.includes('--' + e));
                const report = await postJson(base, '/api/mob-poses', {
                    model: mob.file,
                    timeline: [{
                        duration: durationIdx >= 0 ? Number(rest[durationIdx + 1]) : 2.0,
                        speed: speedIdx >= 0 ? Number(rest[speedIdx + 1]) : 0.1,
                        ...(events.length ? { events } : {}),
                    }],
                });
                if (jsonOut) {
                    out(report);
                } else {
                    out(`poses ${mob.name}: ${verdictOf(report)}`
                        + (report.summary ? `\n  ${report.summary}` : ''));
                    out(renderFindings(report));
                }
                exit(verdictOf(report) === 'BROKEN' ? EXIT.BROKEN : EXIT.OK);
                break;
            }
            case 'item': {
                const gun = rest[0];
                if (!gun) { out('missing gun id'); exit(EXIT.USAGE); }
                const inventory = await getJson(base, '/api/inventory');
                const entry = inventory.guns.find(g => g.gun === gun)
                    ?? inventory.guns.find(g => g.gun.toLowerCase().includes(gun.toLowerCase()));
                if (!entry) { out(`no gun matches '${gun}' — try: list item`); exit(EXIT.USAGE); }
                const rows = [];
                for (const frame of entry.frames) {
                    rows.push(await postJson(base, '/api/item', {
                        model: frame.file,
                        textureRoot: entry.dir,
                    }));
                }
                const verdict = rows.some(r => verdictOf(r) === 'BROKEN') ? 'BROKEN'
                    : rows.some(r => verdictOf(r) === 'REPAIRED') ? 'REPAIRED' : 'OK';
                if (jsonOut) {
                    out({ gun: entry.gun, verdict, frames: rows });
                } else {
                    out(`item ${entry.gun}: ${verdict} — ${rows.length} frame(s) checked`);
                    for (const row of rows) {
                        if (verdictOf(row) !== 'OK') {
                            out(`  ${row.name ?? row.file}: ${verdictOf(row)}`);
                            out(renderFindings(row));
                        }
                    }
                }
                exit(verdict === 'BROKEN' ? EXIT.BROKEN : EXIT.OK);
                break;
            }
            case 'pack': {
                const report = await postJson(base, '/api/pack', {});
                if (jsonOut) {
                    out(report);
                } else {
                    out(`pack: ${verdictOf(report)}`
                        + (report.summary ? `\n  ${report.summary}` : ''));
                    out(renderFindings(report));
                }
                exit(verdictOf(report) === 'BROKEN' ? EXIT.BROKEN : EXIT.OK);
                break;
            }
            case 'verify': {
                // One asset, right op picked from the inventory. The single
                // command an agent runs after editing an asset or pipeline code.
                const key = rest[0];
                if (!key) { out('usage: verify <asset-key-or-gun-id>'); exit(EXIT.USAGE); }
                const inventory = await getJson(base, '/api/inventory');
                const gun = inventory.guns.find(g => g.gun === key)
                    ?? inventory.guns.find(g => g.gun.toLowerCase().includes(key.toLowerCase()));
                if (gun) {
                    const rows = [];
                    for (const frame of gun.frames) {
                        rows.push(await postJson(base, '/api/item', {
                            model: frame.file,
                            textureRoot: gun.dir,
                        }));
                    }
                    const verdict = rows.some(r => verdictOf(r) === 'BROKEN') ? 'BROKEN'
                        : rows.some(r => verdictOf(r) === 'REPAIRED') ? 'REPAIRED' : 'OK';
                    if (jsonOut) {
                        out({ gun: gun.gun, verdict, frames: rows });
                    } else {
                        out(`item ${gun.gun}: ${verdict} — ${rows.length} frame(s) checked`);
                        for (const row of rows) {
                            if (verdictOf(row) !== 'OK') {
                                out(`  ${row.name ?? row.file}: ${verdictOf(row)}`);
                                out(renderFindings(row));
                            }
                        }
                    }
                    exit(verdict === 'BROKEN' ? EXIT.BROKEN : EXIT.OK);
                }
                const block = inventory.blocks.find(b => b.key === key)
                    ?? inventory.blocks.find(b => b.key.toLowerCase().includes(key.toLowerCase()));
                if (!block) { out(`no asset matches '${key}' — try: list`); exit(EXIT.USAGE); }
                const report = await postJson(base, '/api/bake', { model: block.file });
                if (jsonOut) {
                    out(report);
                } else {
                    out(`block ${block.key}: ${verdictOf(report)}`
                        + (report.summary ? `\n  ${report.summary}` : ''));
                    out(renderFindings(report));
                }
                exit(verdictOf(report) === 'BROKEN' ? EXIT.BROKEN : EXIT.OK);
                break;
            }
            default:
                out(`unknown command '${command}' — status|list|audit|block|mob|poses|item|bake|pack|verify|wait`);
                exit(EXIT.USAGE);
        }
    } catch (error) {
        out(jsonOut
            ? JSON.stringify({ ok: false, error: error.message })
            : `studio unavailable: ${error.message} (start it: node devtools/studio/server/server.mjs)`);
        exit(EXIT.UNAVAILABLE);
    }
}

const __doc__ = `
Mineplus Studio agent CLI — verify plugin assets without a browser.

Every command goes through the same daemon ops the web UI uses, so an agent
verdict equals the web verdict. Output is human text by default; pass --json
for the raw op response (every field the UI renders).

Commands:
  status              server + daemon + settings health
  wait [--timeout s]  block until the daemon is up
  list [kind]         inventory (block|mob|mobdef|item|texture|pack)
  audit               full audit pass over every asset kind
  block <key>         validate one block model (bake + displays)
  bake <key>          same as block (alias)
  mob <name>          validate one mob model/definition
  poses <model> [--duration s] [--speed b/t] [--attack|--hurt|--death]
                      drive the real MobAnimator through a scripted timeline
  item <gun>          validate one gun's frame JSONs
  pack                inspect compiled pack artifacts
  verify <key>        one asset (gun or block), right op chosen automatically
                      — the command to run after editing an asset or code
                      to run after editing an asset or pipeline code

Flags: --json, --base http://localhost:5167 (or STUDIO_URL env).
Exit codes: 0 ok/repaired, 1 broken, 2 tool unavailable, 3 usage error.
`.trim();

await main();
