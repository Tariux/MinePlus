// Three.js viewport for the Asset Lab: mob rigs (per-bone pose playback),
// raw item models (element rotation groups) and block models (baked cube
// matrices), all rendered with the same conventions the server pipeline uses.

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import {
    mat4Mul, mat4Box, mat4Trs, mat4Translate,
    quatRotAxis, rigLocalMatrix, rigYawFrame, headPitchModel, headYawModel,
    sampleCurve, wrapClipTime,
} from './geom.js';

// BoxGeometry face order: +x, -x, +y, -y, +z, -z → east, west, up, down, south, north
const BOX_FACE_ORDER = ['east', 'west', 'up', 'down', 'south', 'north'];

// Face axis conventions — must match the pipeline's face mapping.
export const FACE_AXES = {
    north: { n: 2, pos: false, u: 0, v: 1 },
    south: { n: 2, pos: true, u: 0, v: 1 },
    west: { n: 0, pos: false, u: 2, v: 1 },
    east: { n: 0, pos: true, u: 2, v: 1 },
    up: { n: 1, pos: true, u: 0, v: 2 },
    down: { n: 1, pos: false, u: 0, v: 2 },
};

const MISSING = 0xff00ff; // what the client shows for a missing texture

function flatArray(m) {
    const out = new Float32Array(16);
    for (let c = 0; c < 4; c++) for (let r = 0; r < 4; r++) out[c * 4 + r] = m[c * 4 + r];
    return out;
}

export class Viewer {
    constructor(container) {
        this.container = container;
        this.scene = new THREE.Scene();
        this.scene.background = new THREE.Color(0x212329);

        this.camera = new THREE.PerspectiveCamera(
            50, container.clientWidth / container.clientHeight, 0.01, 500);
        this.camera.position.set(2.5, 2.2, 2.8);

        this.renderer = new THREE.WebGLRenderer({ antialias: true });
        this.renderer.setSize(container.clientWidth, container.clientHeight);
        this.renderer.setPixelRatio(window.devicePixelRatio);
        container.appendChild(this.renderer.domElement);

        this.controls = new OrbitControls(this.camera, this.renderer.domElement);
        this.controls.enableDamping = true;
        this.controls.dampingFactor = 0.08;

        this.scene.add(new THREE.AmbientLight(0xffffff, 1.4));
        const dir = new THREE.DirectionalLight(0xffffff, 1.6);
        dir.position.set(5, 10, 7);
        this.scene.add(dir);
        const dir2 = new THREE.DirectionalLight(0xffffff, 0.6);
        dir2.position.set(-6, 4, -5);
        this.scene.add(dir2);

        this.grid = new THREE.GridHelper(16, 16, 0x3a3f4a, 0x2b2f38);
        this.scene.add(this.grid);

        this.content = new THREE.Group();
        this.markers = new THREE.Group();
        this.markers.visible = false;
        this.scene.add(this.content, this.markers);

        this.unitGeo = new THREE.BoxGeometry(1, 1, 1);
        this.unitGeo.translate(0.5, 0.5, 0.5);
        this.markerGeo = new THREE.BoxGeometry(0.06, 0.06, 0.06);
        this.markerMats = new Map(); // color -> MeshBasicMaterial (shared, survives clear)
        this.markerMat = this.markerMaterial(0xff5533);
        this.textures = new Map(); // cache key -> THREE.Texture | null

        this.rig = null; // live rig state while a mob is shown

        this.lastTime = performance.now();
        this.animate();
        window.addEventListener('resize', () => this.onResize());
        new ResizeObserver(() => this.onResize()).observe(container);
    }

    animate() {
        requestAnimationFrame(() => this.animate());
        const now = performance.now();
        const dt = Math.min(0.1, (now - this.lastTime) / 1000);
        this.lastTime = now;
        if (this.rig) this.updateRig(dt);
        this.controls.update();
        this.renderer.render(this.scene, this.camera);
    }

    onResize() {
        const w = this.container.clientWidth, h = this.container.clientHeight;
        if (w === 0 || h === 0) return;
        this.camera.aspect = w / h;
        this.camera.updateProjectionMatrix();
        this.renderer.setSize(w, h);
    }

    clear() {
        this.rig = null;
        for (const child of [...this.content.children]) {
            this.content.remove(child);
            if (child.geometry && child.geometry !== this.unitGeo) {
                try { child.geometry.dispose(); } catch { /* shared */ }
            }
            const materials = Array.isArray(child.material) ? child.material
                : (child.material ? [child.material] : []);
            for (const material of materials) {
                try { material.dispose(); } catch { /* shared */ }
            }
        }
        for (const child of [...this.markers.children]) this.markers.remove(child);
    }

    setMarkersVisible(visible) {
        this.markers.visible = visible;
    }

    /** Attachment-coded marker materials: hands/items stand out from plain bones. */
    markerMaterial(color) {
        let mat = this.markerMats.get(color);
        if (!mat) {
            mat = new THREE.MeshBasicMaterial({ color });
            this.markerMats.set(color, mat);
        }
        return mat;
    }

    // ---------------------------------------------------------------- textures

    textureFromBase64(base64) {
        const key = 'b64:' + base64.slice(0, 64) + ':' + base64.length;
        if (this.textures.has(key)) return this.textures.get(key);
        const image = new Image();
        image.src = 'data:image/png;base64,' + base64;
        const texture = new THREE.Texture(image);
        texture.magFilter = THREE.NearestFilter;
        texture.minFilter = THREE.NearestFilter;
        texture.colorSpace = THREE.SRGBColorSpace;
        texture.needsUpdate = true;
        this.textures.set(key, texture);
        return texture;
    }

    textureFromPath(path) {
        const key = 'path:' + path;
        if (this.textures.has(key)) return this.textures.get(key);
        const texture = new THREE.TextureLoader().load('/api/asset?p=' + encodeURIComponent(path));
        texture.magFilter = THREE.NearestFilter;
        texture.minFilter = THREE.NearestFilter;
        texture.colorSpace = THREE.SRGBColorSpace;
        this.textures.set(key, texture);
        return texture;
    }

    // ---------------------------------------------------------------- UVs

    /**
     * Position-driven UV assignment on a unit [0..1]³ box geometry, mirroring
     * the pipeline's face-axis convention. `windowFor(face)` returns
     * {u1, v1, u2, v2, rotation} in texture pixels plus `spanU`/`spanV` — the
     * pixel span the window is normalized by (texture width/height, or 16 for
     * vanilla item UV units).
     */
    applyFaceUvs(geometry, faceInfos) {
        const uv = geometry.attributes.uv;
        const pos = geometry.attributes.position;
        for (const [index, { face, info }] of faceInfos) {
            const ax = FACE_AXES[face];
            if (!ax) continue;
            const rotation = (((info.rotation ?? 0) % 360) + 360) % 360;
            for (let i = 0; i < 4; i++) {
                const vi = index * 4 + i;
                const comp = [pos.getX(vi), pos.getY(vi), pos.getZ(vi)];
                const fu = comp[ax.u];
                const fv = 1 - comp[ax.v]; // v-axis 1 = top of the face
                let sx, sy;
                switch (rotation) {
                    case 90: sx = fv; sy = 1 - fu; break;
                    case 180: sx = 1 - fu; sy = 1 - fv; break;
                    case 270: sx = 1 - fv; sy = fu; break;
                    default: sx = fu; sy = fv; break;
                }
                const u = (info.u1 + sx * (info.u2 - info.u1)) / info.spanU;
                const v = 1 - (info.v1 + sy * (info.v2 - info.v1)) / info.spanV;
                uv.setXY(vi, u, v);
            }
        }
        uv.needsUpdate = true;
    }

    faceMaterials(cubeFaces, textureInfoFor) {
        const materials = [];
        const faceInfos = new Map();
        for (let i = 0; i < BOX_FACE_ORDER.length; i++) {
            const face = BOX_FACE_ORDER[i];
            const info = cubeFaces[face];
            const usable = info && Number.isFinite(info.u1) && Number.isFinite(info.v1)
                && Number.isFinite(info.u2) && Number.isFinite(info.v2)
                && Math.abs(info.u2 - info.u1) > 1e-6 && Math.abs(info.v2 - info.v1) > 1e-6;
            const texture = usable ? textureInfoFor(face, info) : null;
            if (usable && texture) {
                materials.push(new THREE.MeshLambertMaterial({
                    map: texture, side: THREE.DoubleSide, alphaTest: 0.5,
                }));
                faceInfos.set(i, { face, info });
            } else {
                materials.push(new THREE.MeshLambertMaterial({ color: MISSING }));
            }
        }
        return { materials, faceInfos };
    }

    addCubeMesh(matrix, cubeFaces, textureInfoFor) {
        const { materials, faceInfos } = this.faceMaterials(cubeFaces, textureInfoFor);
        const geometry = this.unitGeo.clone();
        if (faceInfos.size > 0) this.applyFaceUvs(geometry, faceInfos);
        const mesh = new THREE.Mesh(geometry, materials);
        mesh.matrixAutoUpdate = false;
        mesh.matrix.fromArray(flatArray(matrix));
        this.content.add(mesh);
        return mesh;
    }

    // ---------------------------------------------------------------- mob rig

    /**
     * Builds a live rig from a `mob` op response: one mesh set per bone (the
     * parsed ModelData cubes, pivot-relative px), animated per frame by
     * {@link updateRig} through the same composition the server's tick uses
     * (yaw frame, additive rest+clip Euler, head look, parent chain).
     */
    renderRig(response, state) {
        this.clear();
        const bones = response.bones ?? [];
        const textures = response.textures ?? [];
        const scale = response.spec?.scale ?? 1;

        const boneInfos = bones.map(b => ({
            index: b.index,
            name: b.name,
            parent: b.parentIndex,
            pivot: b.pivot,
            rest: { x: b.rest[0], y: b.rest[1], z: b.rest[2] },
            attachment: b.attachment ?? 'NONE',
            head: false,
            rel: [0, 0, 0],
            meshes: [],
            marker: null,
        }));
        // The engine aims exactly one bone (the last HEAD attachment) at head
        // look (MobRenderEngine.prepareTransforms: headBone is overwritten in
        // preorder, so the last one wins).
        let headIndex = -1;
        for (const b of bones) {
            if (b.attachment === 'HEAD') headIndex = b.index;
        }
        for (const bone of boneInfos) {
            bone.head = bone.index === headIndex;
        }
        for (const bone of boneInfos) {
            const parentPivot = bone.parent >= 0 ? boneInfos[bone.parent].pivot : [0, 0, 0];
            bone.rel = [
                bone.pivot[0] - parentPivot[0],
                bone.pivot[1] - parentPivot[1],
                bone.pivot[2] - parentPivot[2],
            ];
        }

        const textureFor = (index) => {
            const info = textures[index];
            if (!info || !info.resolved || !info.pngBase64) return null;
            return this.textureFromBase64(info.pngBase64);
        };

        for (const cube of response.cubes ?? []) {
            const bone = boneInfos[cube.bone];
            if (!bone) continue;
            const inflate = cube.inflate ?? 0;
            const min = [
                (cube.from[0] - inflate) / 16,
                (cube.from[1] - inflate) / 16,
                (cube.from[2] - inflate) / 16,
            ];
            const max = [
                (cube.to[0] + inflate) / 16,
                (cube.to[1] + inflate) / 16,
                (cube.to[2] + inflate) / 16,
            ];
            const faces = {};
            for (const [name, uv] of Object.entries(cube.faces ?? {})) {
                faces[name] = {
                    u1: uv.u1, v1: uv.v1, u2: uv.u2, v2: uv.v2, rotation: 0,
                    spanU: response.textureWidth || 64,
                    spanV: response.textureHeight || 64,
                };
            }
            const textureIndex = cube.textureIndex;
            const mesh = this.addCubeMesh(mat4Box(min, max), faces,
                () => textureFor(textureIndex));
            bone.meshes.push({ mesh, box: mat4Box(min, max) });
        }

        const markers = [];
        for (const bone of boneInfos) {
            const color = bone.attachment === 'HAND_R' ? 0xffa726
                : bone.attachment === 'HAND_L' ? 0x4fc3f7
                : bone.attachment === 'ITEM' ? 0xba68c8
                : 0xff5533;
            const marker = new THREE.Mesh(this.markerGeo, this.markerMaterial(color));
            marker.matrixAutoUpdate = false;
            this.markers.add(marker);
            markers.push({ bone: bone.index, mesh: marker });
        }

        // Entity hitbox outline (spec blocks): the collider the rig must fit.
        const hitbox = response.spec?.hitbox;
        if (hitbox && hitbox[0] > 0 && hitbox[1] > 0) {
            const [width, height] = hitbox;
            const boxGeo = new THREE.BoxGeometry(width, height, width);
            boxGeo.translate(0, height / 2, 0);
            const edges = new THREE.EdgesGeometry(boxGeo);
            boxGeo.dispose();
            const outline = new THREE.LineSegments(edges,
                new THREE.LineBasicMaterial({ color: 0xffb347, transparent: true, opacity: 0.85 }));
            outline.matrixAutoUpdate = false;
            this.content.add(outline);
        }

        const clips = new Map();
        for (const clip of response.animations ?? []) clips.set(clip.name, clip);

        this.rig = {
            bones: boneInfos,
            markers,
            clips,
            scale,
            playhead: 0,
            playing: false,
            clipName: null,
            poseTrack: null,
            bodyYaw: state?.bodyYaw ?? 0,
            headPitch: state?.headPitch ?? 0,
            headYaw: state?.headYaw ?? 0,
        };
        this.updateRig(0);
        this.focus();
    }

    /**
     * Per-frame pose update — mirrors MobRenderEngine.animate per bone.
     * Two sources feed the pose: a raw single clip (curve sampler) or a
     * `poses` op track — per-tick snapshots of the Core's real MobAnimator,
     * linearly blended between frames exactly like the client's display
     * interpolation smooths the engine's 20 Hz output.
     */
    updateRig(dt) {
        const rig = this.rig;
        if (rig.poseTrack && rig.playing) {
            const track = rig.poseTrack;
            const span = track.count * track.tick;
            track.playhead = (track.playhead + dt) % span;
        } else if (rig.playing && rig.clipName && rig.clips.has(rig.clipName)) {
            rig.playhead = wrapClipTime(rig.clips.get(rig.clipName), rig.playhead + dt);
        }
        let frameIndex = -1;
        if (rig.poseTrack) {
            const track = rig.poseTrack;
            frameIndex = Math.min(track.count - 1, Math.floor(track.playhead / track.tick));
            track.frac = Math.min(1, (track.playhead - frameIndex * track.tick) / track.tick);
            if (frameIndex !== track.lastFrame) {
                track.lastFrame = frameIndex;
                this.onFrameState?.(track.snapshots[frameIndex] ?? '');
            }
        }
        const clip = !rig.poseTrack && rig.clipName ? rig.clips.get(rig.clipName) : null;
        const yawFrame = rigYawFrame(rig.bodyYaw);
        const headPitch = headPitchModel(rig.headPitch);
        const headYaw = headYawModel(rig.headYaw, rig.bodyYaw);
        const out = [0, 0, 0];
        const absolutes = [];

        for (const bone of rig.bones) {
            let rx = bone.rest.x, ry = bone.rest.y, rz = bone.rest.z;
            let rel = bone.rel;
            let scl = 1;
            if (rig.poseTrack) {
                const packed = rig.poseTrack.packed.get(bone.name);
                if (packed) {
                    const track = rig.poseTrack;
                    const base = frameIndex * 7;
                    const next = ((frameIndex + 1) % track.count) * 7;
                    const f = track.frac;
                    rx += packed[base] + (packed[next] - packed[base]) * f;
                    ry += packed[base + 1] + (packed[next + 1] - packed[base + 1]) * f;
                    rz += packed[base + 2] + (packed[next + 2] - packed[base + 2]) * f;
                    rel = [
                        bone.rel[0] + packed[base + 3] + (packed[next + 3] - packed[base + 3]) * f,
                        bone.rel[1] + packed[base + 4] + (packed[next + 4] - packed[base + 4]) * f,
                        bone.rel[2] + packed[base + 5] + (packed[next + 5] - packed[base + 5]) * f,
                    ];
                    scl = packed[base + 6] + (packed[next + 6] - packed[base + 6]) * f;
                }
            } else {
                const track = clip ? clip.tracks[bone.name] : null;
                if (track) {
                    if (track.rotation) {
                        sampleCurve(track.rotation, rig.playhead, out);
                        rx += out[0]; ry += out[1]; rz += out[2];
                    }
                    if (track.position) {
                        sampleCurve(track.position, rig.playhead, out);
                        rel = [bone.rel[0] + out[0], bone.rel[1] + out[1], bone.rel[2] + out[2]];
                    }
                    if (track.scale) {
                        sampleCurve(track.scale, rig.playhead, out);
                        scl = out[0];
                    }
                }
            }
            if (bone.head) {
                rx += headPitch;
                ry += headYaw;
            }
            const local = rigLocalMatrix(rel, { x: rx, y: ry, z: rz }, rig.scale * scl);
            const absolute = bone.parent >= 0
                ? mat4Mul(absolutes[bone.parent], local)
                : mat4Mul(yawFrame, local);
            absolutes[bone.index] = absolute;
            for (const { mesh, box } of bone.meshes) {
                mesh.matrix.fromArray(flatArray(mat4Mul(absolute, box)));
            }
        }
        for (const { bone, mesh } of rig.markers) {
            const a = absolutes[bone];
            mesh.matrix.fromArray(flatArray(a));
        }
    }

    /**
     * Plays a `poses` op response's frame track: the real MobAnimator's
     * output, packed per bone into Float32Arrays ([rx, ry, rz, ox, oy, oz,
     * scale] per frame) for fast per-frame sampling. Looping.
     */
    loadPoseTrack(response) {
        if (!this.rig) return;
        const frames = response?.frames ?? [];
        const count = frames.length;
        if (count === 0) return;
        const names = new Set();
        for (const frame of frames) {
            for (const name of Object.keys(frame.p ?? {})) names.add(name);
        }
        const packed = new Map();
        for (const name of names) {
            const arr = new Float32Array(count * 7);
            for (let i = 0; i < count; i++) arr[i * 7 + 6] = 1; // scale: absent pose = 1
            packed.set(name, arr);
        }
        frames.forEach((frame, i) => {
            for (const [name, pose] of Object.entries(frame.p ?? {})) {
                const arr = packed.get(name);
                if (!arr || !Array.isArray(pose)) continue;
                for (let c = 0; c < 7; c++) arr[i * 7 + c] = Number.isFinite(pose[c]) ? pose[c] : 0;
            }
        });
        this.rig.poseTrack = {
            packed,
            count,
            tick: response.tickSeconds > 0 ? response.tickSeconds : 0.05,
            playhead: 0,
            frac: 0,
            lastFrame: -1,
            snapshots: frames.map(f => f.s ?? ''),
        };
        this.rig.clipName = null;
        this.rig.playhead = 0;
        this.rig.playing = true;
        this.updateRig(0);
    }

    stopPoseTrack() {
        if (!this.rig?.poseTrack) return;
        this.rig.poseTrack = null;
        this.rig.playing = false;
        this.rig.playhead = 0;
        this.onFrameState?.('');
        this.updateRig(0);
    }

    setRigState(patch) {
        if (!this.rig) return;
        Object.assign(this.rig, patch);
        if (!('playing' in patch)) this.updateRig(0);
    }

    playClip(name) {
        if (!this.rig) return;
        this.rig.poseTrack = null;
        this.rig.clipName = name;
        this.rig.playhead = 0;
        this.rig.playing = name != null;
        if (name == null) this.onFrameState?.('');
        this.updateRig(0);
    }

    // ---------------------------------------------------------------- item

    /** Renders a raw item model response: elements + vanilla rotation groups. */
    renderItem(response) {
        this.clear();
        const textures = response.textures ?? {};
        const pngFor = (key) => {
            const tj = textures[key];
            return tj && tj.png && tj.png.found && tj.png.pngBase64
                ? this.textureFromBase64(tj.png.pngBase64) : null;
        };

        for (const element of response.elements ?? []) {
            let elementMatrix = mat4Translate(-0.5, -0.5, -0.5); // center the item
            if (element.rotation) {
                const r = element.rotation;
                const q = quatRotAxis(r.axis, r.angle);
                const origin = [r.origin[0] / 16, r.origin[1] / 16, r.origin[2] / 16];
                elementMatrix = mat4Mul(elementMatrix, mat4Mul(
                    mat4Mul(mat4Translate(origin[0], origin[1], origin[2]),
                        mat4Trs([0, 0, 0], q, [1, 1, 1])),
                    mat4Translate(-origin[0], -origin[1], -origin[2])));
            }
            const min = [element.from[0] / 16, element.from[1] / 16, element.from[2] / 16];
            const max = [element.to[0] / 16, element.to[1] / 16, element.to[2] / 16];
            const faces = {};
            for (const [name, face] of Object.entries(element.faces ?? {})) {
                const uv = face.uv ?? [0, 0, 16, 16];
                faces[name] = {
                    u1: uv[0], v1: uv[1], u2: uv[2], v2: uv[3],
                    rotation: face.rotation ?? 0,
                };
            }
            const resolved = {};
            for (const [name, face] of Object.entries(element.faces ?? {})) {
                const key = (face.texture ?? '').startsWith('#') ? face.texture.slice(1) : null;
                resolved[name] = key;
            }
            this.addCubeMesh(mat4Mul(elementMatrix, mat4Box(min, max)), faces,
                (faceName) => {
                    const key = resolved[faceName];
                    return key != null ? pngFor(key) : null;
                });
        }
        this.focus();
    }

    // ---------------------------------------------------------------- block

    /** Renders a `block` op response: baked cube matrices, texel-style. */
    renderBlock(response) {
        this.clear();
        const textures = response.textures ?? [];
        const pathFor = (name) => {
            const info = textures.find(t => t.name === name);
            return info && info.resolved && info.path ? info.path : null;
        };
        const resW = response.resolution?.width ?? 16;
        const resH = response.resolution?.height ?? 16;
        for (const cube of response.cubes ?? []) {
            const matrix = mat4Mul(
                mat4Trs(cube.t, cube.lr, cube.s),
                mat4Trs([0, 0, 0], cube.rr, [1, 1, 1]));
            const faces = {};
            for (const [name, face] of Object.entries(cube.faces ?? {})) {
                faces[name] = {
                    u1: face.u1, v1: face.v1, u2: face.u2, v2: face.v2,
                    rotation: face.rotation ?? 0, spanU: resW, spanV: resH,
                };
            }
            this.addCubeMesh(matrix, faces,
                (faceName) => {
                    const path = pathFor(cube.faces?.[faceName]?.texture);
                    return path ? this.textureFromPath(path) : null;
                });
        }
        this.focus();
    }

    // ---------------------------------------------------------------- camera

    focus() {
        const box = new THREE.Box3();
        const v = new THREE.Vector3();
        let any = false;
        for (const mesh of this.content.children) {
            for (let c = 0; c < 8; c++) {
                const p = new THREE.Vector3(c & 1 ? 1 : 0, c & 2 ? 1 : 0, c & 4 ? 1 : 0);
                p.applyMatrix4(new THREE.Matrix4().fromArray(mesh.matrix.elements));
                if (!isFinite(p.x)) continue;
                box.expandByPoint(p);
                any = true;
            }
        }
        if (!any) return;
        const center = box.getCenter(v.clone());
        const size = box.getSize(new THREE.Vector3());
        const dist = Math.max(size.x, size.y, size.z, 0.5) * 2 + 0.5;
        this.controls.target.copy(center);
        this.camera.position.set(
            center.x + dist * 0.6, center.y + dist * 0.5, center.z + dist * 0.7);
        this.controls.update();
    }
}
