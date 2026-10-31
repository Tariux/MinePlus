// Mobs tab: custom-mob definitions (.yml) and standalone mob models
// (.bbmodel), opened through the daemon's real mob pipeline.

import { postJson } from './api.js';
import { esc, chip, section, kv, list, findings, texThumb } from './ui.js';

export function initMobs(ctx) {
    renderList(ctx);
}

function renderList(ctx) {
    const inv = ctx.inventory();
    const el = ctx.el.listMobs;
    const defs = inv.mobDefs.map(m => `
        <li class="row" data-kind="yml" data-file="${esc(m.file)}">
            <span class="row-name">${esc(m.name)}</span><span class="row-hint">yml</span>
        </li>`).join('');
    const models = inv.mobModels.map(m => `
        <li class="row" data-kind="model" data-file="${esc(m.file)}">
            <span class="row-name">${esc(m.name)}</span><span class="row-hint">bbmodel</span>
        </li>`).join('');
    el.innerHTML = `
      <h2>Mob definitions</h2>
      <ul class="plain selectable">${defs || '<li class="muted">no mob .yml in the watched roots (code-registered mobs are documented in the module sources)</li>'}</ul>
      <h2>Mob models</h2>
      <ul class="plain selectable">${models || '<li class="muted">none</li>'}</ul>`;
    for (const row of el.querySelectorAll('li.row[data-file]')) {
        row.addEventListener('click', () => ctx.open('mob', row.dataset.file));
    }
}

/** Full mob presentation: rig (or retexture verdict) + details. */
export async function showMob(ctx, response) {
    const retexture = response.renderType === 'RETEXTURE_VANILLA';
    const none = response.renderType === 'NONE';
    if (retexture || none) {
        ctx.viewer.clear();
    } else {
        ctx.viewer.renderRig(response, {});
        bindRigControls(ctx, response);
    }
    ctx.details(mobDetails(response));
}

function mobDetails(r) {
    const stats = r.stats ?? {};
    const spec = r.spec ?? {};
    let html = `
      <h2>${esc(r.source ?? 'mob')}</h2>
      <div class="card verdict">
        ${chip(r.status ?? 'BROKEN')}
        <p>${esc(r.summary ?? '')}</p>
        ${r.statusLine ? `<p class="muted">${esc(r.statusLine)}</p>` : ''}
      </div>`;

    if (spec.id) {
        html += section('Definition', kv([
            ['id', esc(spec.id)],
            ['base entity', esc(spec.baseEntity ?? '—')],
            ['display name', esc(spec.displayName ?? '—')],
            ['render type', esc(r.renderType ?? spec.type ?? '')],
            ['scale', spec.scale],
            ['fallback', esc(spec.fallback ?? '')],
            ['hitbox', spec.hitbox ? `${spec.hitbox[0]} × ${spec.hitbox[1]}` : 'vanilla'],
            ['hand items', Object.keys(spec.handItems ?? {}).length
                ? Object.entries(spec.handItems).map(([b, i]) => `${esc(b)} → ${esc(i)}`).join(', ') : ''],
            ['animation aliases', Object.keys(spec.animationMap ?? {}).length
                ? Object.entries(spec.animationMap).map(([s, c]) => `${esc(s)} → ${esc(c)}`).join(', ') : ''],
        ]));
    }

    if (r.textures && r.textures.length > 0) {
        html += section('Textures', `<div class="thumbs">${r.textures.map(t => `
            <figure>
                ${texThumb(t)}
                <figcaption>${esc(t.name)}<br>${t.width}×${t.height}${t.resolved ? '' : '<br><b class="broken-text">unresolved</b>'}</figcaption>
            </figure>`).join('')}</div>`);
    }

    if (stats.boneCount !== undefined) {
        html += section('Rig', kv([
            ['bones', `${stats.boneCount} (${stats.geometryBones} with geometry, max ${stats.maxBones})`],
            ['cubes', stats.cubeCount],
            ['pack pieces', stats.pieceCount],
            ['displays per mob', stats.displayEstimate],
            ['pack assets written', r.packAssets ? `${r.packAssets.generated} generated, ${r.packAssets.displayModels} display models` : ''],
        ]));
        html += section('Bones', `<table class="grid"><tr><th>bone</th><th>cubes</th><th>attachment</th></tr>${
            (r.bones ?? []).map(b => `<tr><td>${esc(b.name)}</td><td>${b.cubeCount}</td><td>${esc(b.attachment)}${b.locator ? ' (locator)' : ''}</td></tr>`).join('')
        }</table>`);
        html += section('Pack pieces', list((r.pieces ?? []).map(p =>
            `${esc(p.bone)} → <span class="code">${esc(p.componentPath)}</span>`
            + (p.offsetPx && (p.offsetPx[0] || p.offsetPx[1] || p.offsetPx[2])
                ? ` offset [${p.offsetPx.map(v => Math.round(v)).join(', ')}] px` : ''),
        ), 'single piece per bone'));
    }

    if (r.animations && r.animations.length > 0 && !r.renderType?.startsWith('RETEXTURE')) {
        html += section('Clips', `<ul class="plain">${r.animations.map(c => `
            <li>${esc(c.rawName || c.name)} — ${c.lengthSeconds}s, ${esc(c.loopMode)}
                ${Object.keys(c.tracks ?? {}).length} bone(s)</li>`).join('')}</ul>`);
    }
    if (r.animationAliases && r.animationAliases.length > 0) {
        html += section('Unbound animation aliases', findings(r.animationAliases.map(a =>
            ({ code: a.state, message: `clip '${a.clip}' not found in the model` }))));
    }

    html += section('Parser issues', findings(r.issues));
    html += section('Compile report reasons', findings(r.reasons));
    return html;
}

function bindRigControls(ctx, response) {
    const clips = response.animations ?? [];
    const hasClips = clips.length > 0;
    ctx.details(mobDetails(response) + `
      <h3>Pose</h3>
      <div class="card controls">
        <label>Clip
          <select id="rig-clip">
            <option value="">(rest pose)</option>
            ${clips.map(c => `<option value="${esc(c.name)}">${esc(c.rawName || c.name)}</option>`).join('')}
          </select></label>
        <label class="inline"><input type="checkbox" id="rig-play" ${hasClips ? 'checked' : 'disabled'}> play</label>
        <label>Scrub <input type="range" id="rig-scrub" min="0" max="1000" value="0"></label>
        <label>Body yaw <input type="range" id="rig-yaw" min="-180" max="180" value="0"></label>
        <label>Head pitch <input type="range" id="rig-pitch" min="-75" max="75" value="0"></label>
        <label>Head yaw Δ <input type="range" id="rig-headyaw" min="-75" max="75" value="0"></label>
        <label class="inline"><input type="checkbox" id="rig-markers"> bone markers</label>
      </div>
      <h3>Real animator <span class="muted">(the Core's MobAnimator, ticked at 20 Hz)</span></h3>
      <div class="card controls">
        <label>Timeline
          <select id="pose-preset">
            <option value="full">idle → walk → run → idle</option>
            <option value="attack">attack overlay</option>
            <option value="hurt">hurt overlay</option>
            <option value="death">death</option>
          </select></label>
        <button id="pose-run">Run</button>
        <span id="pose-state" class="code"></span>
        <div id="pose-result"></div>
      </div>
      <p class="muted">Marker colors: orange = HAND_R locator, blue = HAND_L, violet = ITEM;
      the amber box is the spec's hitbox.</p>`);

    const viewer = ctx.viewer;
    const clipSelect = document.getElementById('rig-clip');
    const play = document.getElementById('rig-play');
    const scrub = document.getElementById('rig-scrub');
    const yaw = document.getElementById('rig-yaw');
    const pitch = document.getElementById('rig-pitch');
    const headYaw = document.getElementById('rig-headyaw');
    const markers = document.getElementById('rig-markers');
    const preset = document.getElementById('pose-preset');
    const run = document.getElementById('pose-run');
    const stateReadout = document.getElementById('pose-state');
    const result = document.getElementById('pose-result');

    viewer.onFrameState = (snapshot) => {
        stateReadout.textContent = snapshot;
    };

    const poseSpan = () => (viewer.rig?.poseTrack
        ? viewer.rig.poseTrack.count * viewer.rig.poseTrack.tick : 0);

    clipSelect?.addEventListener('change', () => viewer.playClip(clipSelect.value || null));
    play?.addEventListener('change', () => viewer.setRigState({ playing: play.checked }));
    scrub?.addEventListener('input', () => {
        if (!viewer.rig) return;
        if (viewer.rig.poseTrack) {
            viewer.rig.poseTrack.playhead = (scrub.value / 1000) * poseSpan();
            viewer.setRigState({});
            return;
        }
        if (!clipSelect?.value) return;
        const clip = viewer.rig.clips.get(clipSelect.value);
        if (!clip) return;
        viewer.rig.playhead = (scrub.value / 1000) * clip.lengthSeconds;
        viewer.setRigState({});
    });
    yaw?.addEventListener('input', () => viewer.setRigState({ bodyYaw: Number(yaw.value) }));
    pitch?.addEventListener('input', () => viewer.setRigState({ headPitch: Number(pitch.value) }));
    headYaw?.addEventListener('input', () => viewer.setRigState({ headYaw: Number(headYaw.value) }));
    markers?.addEventListener('change', () => viewer.setMarkersVisible(markers.checked));

    run?.addEventListener('click', async () => {
        const request = ctx.lastMobRequest ?? {};
        const body = {
            ...(request.yml ? { yml: request.yml } : { model: request.model }),
            preset: preset?.value ?? 'full',
        };
        result.innerHTML = '<p class="muted">ticking the real MobAnimator…</p>';
        try {
            const track = await postJson('/api/mob-poses', body);
            if (track.status === 'BROKEN') {
                result.innerHTML = `<div class="card verdict">${chip('BROKEN')}<p>${esc(track.summary ?? '')}</p></div>`;
                return;
            }
            viewer.loadPoseTrack(track);
            if (clipSelect) clipSelect.value = '';
            if (play) play.checked = true;
            scrub.value = 0;
            result.innerHTML = animatorResult(track);
        } catch (e) {
            result.innerHTML = `<div class="card verdict">${chip('BROKEN')}<p>${esc(e.message)}</p></div>`;
        }
    });
}

/** Binding table + flags from a `poses` op response. */
function animatorResult(track) {
    const bound = (track.binding ?? []).map(b => `
        <tr><td>${esc(b.state)}</td><td class="${b.clip === '-' ? 'broken-text' : ''}">${esc(b.clip)}</td></tr>`).join('');
    const events = (track.events ?? []).map(e =>
        `${e.event} @ ${e.time}s`).join(', ');
    const bones = (names, label) => names?.length
        ? `<div>${esc(label)}: ${names.map(esc).join(', ')}</div>` : '';
    return `
      <table class="grid"><tr><th>state</th><th>bound clip</th></tr>${bound}</table>
      <div class="muted">${track.frameCount} ticks × ${track.tickSeconds}s = ${track.durationSeconds}s
        ${track.truncated ? ' (truncated)' : ''}${events ? ` — events: ${esc(events)}` : ''}</div>
      ${bones(track.legBones, 'leg bones')}${bones(track.armBones, 'arm bones')}
      ${track.hasAttackClip ? '' : '<div class="muted">no attack clip — attacks show procedural motion only</div>'}
      ${track.hasDeathClip ? '' : '<div class="muted">no death clip — the rig despawns right away in game</div>'}`;
}
