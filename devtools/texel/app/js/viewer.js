// Three.js viewport: reference (textured cubes) + texel plate layers.

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { cubeMatrix, plateMatrix, cssRgb, FACE_AXES } from './geom.js';

// BoxGeometry face order: +x, -x, +y, -y, +z, -z  →  east, west, up, down, south, north
const BOX_FACE_ORDER = ['east', 'west', 'up', 'down', 'south', 'north'];

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

        this.layers = {
            reference: new THREE.Group(),
            texel: new THREE.Group(),
        };
        for (const group of Object.values(this.layers)) this.scene.add(group);
        this.wireframes = [];

        this.textures = new Map(); // texture name -> THREE.Texture
        // Centered unit box for mat4Box-style matrices (plates).
        this.boxGeo = new THREE.BoxGeometry(1, 1, 1);
        // Unit-space [0..1] box for cubeMatrix (T·R·S·R' maps [0..1]³, exactly
        // like OccluderSet/DisplayEmitter treat cube local space). Using the
        // centered box here displaced every reference cube by half its scale —
        // the "jumbled overlapping boxes" artifact on multi-cube models.
        this.unitGeo = new THREE.BoxGeometry(1, 1, 1);
        this.unitGeo.translate(0.5, 0.5, 0.5);
        this.plateMaterial = new THREE.MeshLambertMaterial({ vertexColors: false });
        this.wireframeWanted = false;

        this.animate();
        window.addEventListener('resize', () => this.onResize());
        new ResizeObserver(() => this.onResize()).observe(container);
    }

    animate() {
        requestAnimationFrame(() => this.animate());
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

    clearGroup(group) {
        for (const child of [...group.children]) {
            group.remove(child);
            // Dispose geometry unless it is the shared unit box. A throwing
            // dispose here would abort the whole render and leave stale layers
            // from the previous model behind (the "secondary model" artifact),
            // so every step is individually guarded.
            if (child.geometry && child.geometry !== this.boxGeo) {
                try { child.geometry.dispose(); } catch { /* already disposed */ }
            }
            // Material may be a single material, an array (multi-face meshes),
            // or absent (LineSegments share their own material). Arrays have no
            // .dispose — flatten first; a raw array call used to crash with
            // "child.material.dispose is not a function".
            const materials = Array.isArray(child.material) ? child.material
                : (child.material ? [child.material] : []);
            for (const material of materials) {
                if (!material || material === this.plateMaterial) continue;
                try { material.dispose(); } catch { /* already disposed */ }
            }
        }
    }

    setLayerVisible(name, visible) {
        if (this.layers[name]) this.layers[name].visible = visible;
        if (name === 'texel') {
            for (const wf of this.wireframes) wf.visible = visible;
        }
    }

    /**
     * Exclusive view mode: exactly one of reference/texel is visible.
     * Rendering the textured reference and the palette reconstruction at the
     * same time reads as two blended/misaligned textures — the layers are
     * alternative views of the same model, not overlays.
     */
    showView(mode) {
        for (const [name, group] of Object.entries(this.layers)) {
            group.visible = name === mode;
        }
        this.syncWireframes(mode);
    }

    setWireframeVisible(visible) {
        this.wireframeWanted = visible;
        this.syncWireframes(this.currentMode());
    }

    syncWireframes(mode) {
        const visible = this.wireframeWanted && (mode ?? this.currentMode()) === 'texel';
        for (const wf of this.wireframes) wf.visible = visible;
    }

    currentMode() {
        for (const [name, group] of Object.entries(this.layers)) {
            if (group.visible) return name;
        }
        return 'reference';
    }

    textureFor(name, texturesInfo) {
        if (this.textures.has(name)) return this.textures.get(name);
        const info = texturesInfo.find(t => t.name === name);
        if (!info || !info.resolved || !info.path) {
            // Graceful fallback: log once per texture and render a neutral
            // material instead of breaking the whole model render.
            console.warn('[texel devtool] Texture not found:', name);
            this.textures.set(name, null); // negative cache — warn once
            return null;
        }
        const texture = new THREE.TextureLoader().load('/api/asset?p=' + encodeURIComponent(info.path));
        texture.magFilter = THREE.NearestFilter;
        texture.minFilter = THREE.NearestFilter;
        texture.colorSpace = THREE.SRGBColorSpace;
        this.textures.set(name, texture);
        return texture;
    }

    /** Full render of a bake result into all three layers. */
    render(result) {
        this.clearGroup(this.layers.reference);
        this.clearGroup(this.layers.texel);
        this.wireframes = [];

        this.renderReference(result);
        this.renderTexel(result);
        this.focus(result);
        // Normalize to the exclusive view mode (initial state has all groups
        // visible; showView collapses that to exactly one).
        this.showView(this.currentMode());
    }

    renderReference(result) {
        const resW = result.resolution.width;
        const resH = result.resolution.height;
        for (const cube of result.cubes) {
            const M = cubeMatrix(cube);
            const materials = [];
            const faceInfos = new Map(); // BoxGeometry face index -> {face, info}
            for (let i = 0; i < BOX_FACE_ORDER.length; i++) {
                const face = BOX_FACE_ORDER[i];
                const info = cube.faces[face];
                const usable = info
                    && Number.isFinite(info.u1) && Number.isFinite(info.v1)
                    && Number.isFinite(info.u2) && Number.isFinite(info.v2)
                    && Math.abs(info.u2 - info.u1) > 1e-6
                    && Math.abs(info.v2 - info.v1) > 1e-6;
                const texture = usable && info.texture
                    ? this.textureFor(info.texture, result.textures) : null;
                if (usable && texture) {
                    // alphaTest keeps sprite cutouts (wine labels, transparent
                    // bands) see-through instead of rendering black fills.
                    materials.push(new THREE.MeshLambertMaterial({
                        map: texture, side: THREE.DoubleSide, alphaTest: 0.5,
                    }));
                    faceInfos.set(i, { face, info });
                } else {
                    // Missing texture or degenerate UV window: neutral tone,
                    // never a crash (the daemon already logged the miss).
                    materials.push(new THREE.MeshLambertMaterial({ color: 0x8d939e }));
                }
            }
            const geometry = this.unitGeo.clone();
            if (faceInfos.size > 0) this.applyFaceUvs(geometry, faceInfos, resW, resH);
            const mesh = new THREE.Mesh(geometry, materials);
            mesh.matrixAutoUpdate = false;
            mesh.matrix.fromArray(M);
            this.layers.reference.add(mesh);
        }
    }

    /**
     * Position-driven UV assignment: for every vertex of a face, the display
     * fractions (fu, fv) are read from the vertex's own unit-space position
     * through the pipeline's face-axis convention (fu = p[uAxis], top of face
     * = v-axis 1), then mapped through the UV window with the Blockbench
     * in-plane rotation — the same mapping TexelSampler uses. Deriving UVs
     * from positions (instead of three.js's fixed corner order) guarantees
     * the reference texture and the texel plates can never disagree about
     * orientation or mirroring on any face.
     */
    applyFaceUvs(geometry, faceInfos, resW, resH) {
        const uv = geometry.attributes.uv;
        const pos = geometry.attributes.position;
        for (const [index, { face, info }] of faceInfos) {
            const ax = FACE_AXES[face];
            const rotation = ((info.rotation ?? 0) % 360 + 360) % 360;
            for (let i = 0; i < 4; i++) {
                const vi = index * 4 + i;
                const comp = [pos.getX(vi), pos.getY(vi), pos.getZ(vi)];
                const fu = comp[ax.u];
                const fv = 1 - comp[ax.v]; // v-axis 1 = top of the face
                // In-plane rotation (clockwise in texture space) inverts into
                // the source lookup; matches TexelSampler's -θ window rotation.
                let sx, sy;
                switch (rotation) {
                    case 90: sx = fv; sy = 1 - fu; break;
                    case 180: sx = 1 - fu; sy = 1 - fv; break;
                    case 270: sx = 1 - fv; sy = fu; break;
                    default: sx = fu; sy = fv; break;
                }
                const u = (info.u1 + sx * (info.u2 - info.u1)) / resW;
                const v = 1 - (info.v1 + sy * (info.v2 - info.v1)) / resH;
                uv.setXY(vi, u, v);
            }
        }
        uv.needsUpdate = true;
    }

    renderTexel(result) {
        const entries = [];
        for (const plan of result.texel.cubePlans) {
            const cube = result.cubes[plan.cube];
            if (!cube) continue;
            const M = cubeMatrix(cube);
            for (const rect of plan.plates) {
                entries.push({
                    matrix: plateMatrix(M, plan.face, rect, plan.gridWidth, plan.gridHeight, cube.s),
                    paletteIndex: rect[4],
                });
            }
        }
        this.addInstanced(this.layers.texel, entries, result.palette);

        // Wireframe overlay for plate inspection.
        const edges = new THREE.EdgesGeometry(this.boxGeo);
        for (const entry of entries) {
            const line = new THREE.LineSegments(edges, new THREE.LineBasicMaterial({ color: 0x66d9ef, transparent: true, opacity: 0.5 }));
            line.matrixAutoUpdate = false;
            line.matrix.fromArray(entry.matrix);
            line.visible = false;
            this.layers.texel.add(line);
            this.wireframes.push(line);
        }
    }

    addInstanced(group, entries, palette) {
        if (entries.length === 0) return;
        const mesh = new THREE.InstancedMesh(
            this.boxGeo, this.plateMaterial.clone(), entries.length);
        const matrix = new THREE.Matrix4();
        const color = new THREE.Color();
        entries.forEach((entry, i) => {
            matrix.fromArray(entry.matrix);
            mesh.setMatrixAt(i, matrix);
            const packed = (palette[entry.paletteIndex] ?? {}).rgb ?? 0xcfd5d6;
            color.set(cssRgb(packed));
            color.convertSRGBToLinear();
            mesh.setColorAt(i, color);
        });
        mesh.instanceMatrix.needsUpdate = true;
        if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
        group.add(mesh);
    }

    focus(result) {
        const box = new THREE.Box3();
        const v = new THREE.Vector3();
        let any = false;
        for (const cube of result.cubes) {
            const M = cubeMatrix(cube);
            for (let c = 0; c < 8; c++) {
                const p = new THREE.Vector3((c & 1), (c >> 1) & 1, (c >> 2) & 1);
                p.applyMatrix4(new THREE.Matrix4().fromArray(M));
                if (!isFinite(p.x)) continue;
                box.expandByPoint(p);
                any = true;
            }
        }
        if (!any) return;
        const center = box.getCenter(v.clone());
        const size = box.getSize(new THREE.Vector3());
        const dist = Math.max(size.x, size.y, size.z) * 2 + 0.5;
        this.controls.target.copy(center);
        this.camera.position.set(
            center.x + dist * 0.6, center.y + dist * 0.5, center.z + dist * 0.7);
        this.controls.update();
    }
}
