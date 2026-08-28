# UPDATE-PLAN.MD — Blockbench-Parity Virtual Rendering Engine

**Target:** Mineplus (Spigot/Paper 1.21, Java 21, Gradle)
**Scope:** BBModel parsing optimization, vanilla-texture material system, geometry-aware collision proxies, strict voxel-grid alignment, per-face rendering emission.
**Status:** Ready for execution. Phases are ordered by dependency; each phase is independently shippable and backwards-compatible unless flagged.

---

## 0. Executive Summary

Mineplus already renders `.bbmodel` files through a server-side `BlockDisplay` pipeline:

```
.bbmodel file
  → BbModelImporter.parse()          (Gson DOM → VirtualModel/BakedCube/BakedFace)
  → VirtualBlockManager.spawnModel() (barriers + one BlockDisplay per cube)
  → Material resolved per cube via Material.matchMaterial(primaryTexture)
```

Four gaps block Blockbench parity:

| # | Gap | Current behavior | Required behavior |
|---|-----|------------------|-------------------|
| 1 | **Memory** | `JsonParser.parseReader()` builds a full DOM including every texture's Base64 `source` string (KB–MB each) before discarding it | Stream-parse and skip `source` (and other dead fields) without ever materializing them |
| 2 | **Collision** | `VirtualBoundingBox.calculate()` fills the *entire model AABB* with `BARRIER` blocks — internal voids, hollow interiors, and empty space between disconnected geometry are all solid | Per-cube voxelization: a cell is occupied iff actual cube geometry intersects it |
| 3 | **Textures** | One `Material` per cube from `primaryTexture` via naive `matchMaterial`; the purpose-built 370-entry `TextureMaterialResolver` is dead code; `BakedFace` UV/rotation/per-face textures are parsed but never rendered | Filename→vanilla-asset resolution pipeline with alias/fuzzy tiers, per-face material emission (box mapping) and single-texture mode (UV mapping) |
| 4 | **Grid alignment** | Display entities spawn at `origin + (0.5, 0, 0.5)` (half-block visual offset); `VirtualBoundingBox` clamps min coords to ≥ 0, silently deleting negative-extent geometry; rotated offsets use `Math.round` on floats | Canonical corner-anchored convention, negative-extent support, placement rotation snapped to the 24 axis-aligned orientations so the voxel lattice maps onto itself exactly |

This plan specifies the math, the algorithms, and a phase-by-phase implementation inside the existing `infrastructure.virtual` package without breaking `InfrastructureEngine`, persistence, or the lifecycle managers.

---

## 1. Current-State Audit (verified against source)

### 1.1 Files in the blast radius

| File | Role | Verdict |
|------|------|---------|
| `infrastructure/virtual/BbModelImporter.java` | DOM parse of bbmodel → `VirtualModel` | **Rewrite parse loop to streaming**; keep public API `parse(String, File, Logger)` |
| `infrastructure/virtual/VirtualModel.java` | `record(String name, List<BakedCube> cubes, Map<String,String> textureMappings)` | Extend with `resolution`, `textureMode` |
| `infrastructure/virtual/BakedCube.java` | `record(name, translation, leftRotation, scale, rightRotation, faces, primaryTexture)` | Add `lightEmission` field |
| `infrastructure/virtual/BakedFace.java` | `record(u1, v1, u2, v2, rotation, textureReference, textureName)` | Keep as-is |
| `infrastructure/virtual/VirtualBoundingBox.java` | Whole-model AABB + full-cube enumeration | **Deprecate for collision**; keep only as debug/diagnostic utility |
| `infrastructure/virtual/VirtualBlockManager.java` | Spawn/remove, barriers, ghost cleanup | **Major surgery**: delegate to new `VoxelOccupancyCalculator`, `RotationSnapper`, `DisplayEmitter`; adopt `TextureMaterialResolver` |
| `infrastructure/virtual/TextureMaterialResolver.java` | ~370-entry texture→Material map with fuzzy matching — **zero references** | Promote to the resolution engine |
| `infrastructure/virtual/VirtualBlockPlacementHelper.java` | Player ray-trace placement + vanilla-style snapped rotation | Reuse; feeds `RotationSnapper` |
| `infrastructure/core/multiblock/render/ModelRenderingManager.java` | level → model key → `spawnModel` | Unchanged (thin bridge) |

### 1.2 Confirmed defects to fix en route

1. **`VirtualBoundingBox.calculate` clamps `min` to ≥ 0** (`Math.max(0, floor(minX))`): any geometry at negative model coordinates (the reference model has `from: [-1,0,-1]`, `from: [-7,0,-7]`) has its lower extent deleted from collision. With the reference bbmodel, the 14×14 base plate loses its `[-7..0)` half.
2. **Half-block display offset**: `displayOrigin = origin + (0.5, 0, 0.5)` while cube translations are corner-relative (`from/16`). Visual geometry is shifted +0.5 in X/Z relative to the barrier lattice. It happens to center origin-symmetric models (the juicer), which is why it went unnoticed.
3. **`Math.round` on rotated float offsets** in `spawnModel`: only exact for 90°-multiple rotations, and silently wrong otherwise; no snap enforcement exists.
4. **`resolveCubeMaterial`** uppercases the filename and regex-strips to `[A-Z0-9_]` — `concrete_orange` (Blockbench/vanilla texture naming) never matches `ORANGE_CONCRETE` (Material naming), so most mapped textures fall back to `WHITE_CONCRETE`. `TextureMaterialResolver` already solves this and is unused.
5. **`light_emission`** is present in every bbmodel element and ignored by the importer.

### 1.3 Build note

`build.gradle` references `libs/*.jar` (paper-api, joml, gson, sqlite-jdbc, adventure, item-nbt-api) but `libs/` is absent from this backup. Restore the jars before compiling. All new code depends only on **JOML, Gson, and the Paper API** — no new dependencies.

---

## 2. Technical Specifications

### 2.1 Coordinate systems and units

Three spaces are involved; all transforms are exact rational multiples, no accumulation of float error at boundaries.

```
BBModel space  M   : right-handed, Y-up, 1 unit = 1/16 block (pixel).
                     North = -Z, East = +X, Up = +Y (matches Minecraft).
                     Origin (0,0,0) is the model anchor.
Model space    m   : M scaled by 1/16 → block units. m = M / 16.
World space    w   : integer block lattice. w = anchor + R_snap · m
```

**Canonical anchor convention (new):** the bbmodel origin `(0,0,0)` maps to the **north-west-bottom corner of the anchor block** (`origin.getBlockX/Y/Z()`). Consequently:

- Model-space voxel `(x, y, z)` (integers, possibly negative) = world block `anchor + (x, y, z)`.
- A cube with `from = [-7, 0, -7]` extends 7 pixels west and 7 north of the anchor block; negative X → west, negative Z → north, negative Y → below.
- Display entities spawn at the exact block corner `origin` (integer coordinates, **no +0.5**), because a `BlockDisplay` renders its block across `[entityPos, entityPos + 1]³`, making `cube.translation()` (which is `from/16 ∈ [0,1]`-relative) directly consistent with the voxel lattice.

**Compatibility flag:** `originMode: LEGACY | GRID` (default `GRID` after migration, see §7 Phase 3). `LEGACY` preserves the `+0.5, +0.5` spawn offset for existing deployed models during transition.

### 2.2 Geometry math (retained from current importer — documented for contract)

Per-cube local transform, exactly as `BbModelImporter` bakes today:

```
S(cube)     = T(from / 16) · diag((to - from) / 16)            // corner + size
P(node)     = T(origin / 16) · R_xyz · T(-origin / 16)         // pivot conjugation
R_xyz       = Quaternionf.rotateXYZ(rad(rx), rad(ry), rad(rz)) // Blockbench Euler order
M_cube      = P(cube) · S(cube)
M_model     = M_parentGroup · M_cube                           // outliner chain, cycle-safe
```

Decomposition into display TRS (already implemented):

```
translation   = M_model.getTranslation()
scale         = M_model.getScale()
leftRotation  = M_model.getUnnormalizedRotation()
rightRotation = identity
```

World composition at spawn (new — removes the silent pre-transform of translation):

```
M_world(cube) = T(anchor) · R_snap · M_model(cube)

Display transformation:
  translation  = R_snap · cube.translation()          (vector rotate, no +0.5)
  leftRotation = R_snap · cube.leftRotation()
  scale        = cube.scale()
  rightRotation= cube.rightRotation()
```

### 2.3 Rotation snapping — the 24 axis-aligned orientations

**Requirement:** models must stay perfectly grid-aligned when rotated on any axis. This is only achievable if the placement rotation maps the integer voxel lattice onto itself, i.e. the rotation is one of the **24 orientation-preserving signed axis permutations** (the rotational symmetry group of the cube).

**Algorithm `RotationSnapper.snap(Quaternionf q) → SnappedRotation`:**

```
1. R = matrix3f from q
2. For each column i ∈ {0,1,2}:
      a_i = argmax_j |R[j][i]|            // dominant axis
      c_i = sign(R[a_i][i]) · e_{a_i}     // snapped signed unit axis
3. Validate: {a_0, a_1, a_2} is a permutation AND det([c_0 c_1 c_2]) = +1
   (if not — e.g. mirrored input — repair by flipping c_2 cross-product sign)
4. Deviation metric: θ = max over columns of angle(R·e_i, c_i)
5. If θ > SNAP_THRESHOLD (default 5°): log a DebugLogger warning (the model
   was placed with a non-grid rotation; snapping is enforced anyway)
6. Represent result as:
      - int[] axisMap   : outputAxis[i] = inputAxis a_i
      - int[] signs     : outputSign[i] = sign
      - Quaternionf qSnapped (reconstructed from the matrix, exact 0/±1 entries)
```

**Integer offset transform (replaces `Math.round` on floats):**

```
transform(offset (x,y,z)) = ( signs[0]·x_{axisMap[0]}, signs[1]·y_{axisMap[1]}, signs[2]·z_{axisMap[2]} )
```

This is **exact** — zero rounding error, ever. The same `SnappedRotation` transforms both voxel offsets (collision) and display translations/rotations (visuals), which guarantees collision and geometry can never drift apart.

`VirtualBlockPlacementHelper.calculateVanillaRotation` already produces yaw snapped to 90°; `RotationSnapper` becomes the single enforcement point at `spawnModel` entry, so persisted quaternions (restore path) are also normalized.

### 2.4 Voxel-grid alignment rules

1. **Sub-grid geometry is legal.** Cubes may have fractional extents (`from: [-1,0,-1]` = 1/16 block). The 16×16×16 constraint applies to *placement anchoring and collision cells*, not to visual geometry.
2. **Barrier cells are always full blocks** (`Material.BARRIER`). Collision granularity is therefore 1 block; visual fidelity is 1/16 block. This is inherent to barrier-based proxies.
3. **Voxel coordinate of a point** `p` in model space: `cell(p) = (⌊p.x⌋, ⌊p.y⌋, ⌊p.z⌋)` — note this accepts negative coordinates (fixes the clamp defect).
4. **Inflate handling:** inflated cubes expand geometry; voxelization uses the inflated extents (already the case — the importer adds `inflate` to `from`/`to`).

### 2.5 Texture mapping contract

- **Filename extraction** (already correct in `TextureLookup.extractTextureIdentifier`): take `path` → `relative_path` → `name`, replace `\` with `/`, take the substring after the last `/`, strip `.png`/`.mcmeta`, lowercase. Absolute directories are thus ignored — `"E:\...\textures\blocks\beacon.png"` → `"beacon"`.
- **Resolution pipeline** (per unique texture name, results cached):

```
resolve(name):
  1. exact:            TEXTURE_MAP[name]                      // TextureMaterialResolver's 370 entries
  2. direct material:  Material.matchMaterial(name), must be block, non-air
  3. suffix-strip:     strip one of {_top,_side,_bottom,_front,_back,_left,_right,
                        _inner,_outer,_base,_0.._3}, retry steps 1–2
                        // "beacon" stays "beacon"; "cactus_side"→"cactus"
  4. alias table:      legacy/Blockbench names → Materials
                        // "wool_colored_white"→WHITE_WOOL, "planks"→OAK_PLANKS,
                        //  "log"→OAK_LOG, "concrete_*" already covered by map,
                        //  "hardened_clay_stained_white"→WHITE_TERRACOTTA, ...
  5. token fuzzy:      underscore-insensitive contains-match against map keys
                        // "brick_block"→"brick"→BRICKS
  6. fallback:         FALLBACK (WHITE_CONCRETE) + record in per-model
                        UnresolvedTextureReport (surfaced via /mineplus model info)
```

- **Mapping modes:**
  - `BOX` (default): per-face textures from `BakedFace.textureName` — each face of a cube resolves independently (see §4 emission).
  - `UV`: a single texture applied across the whole geometry. Resolution: prefer the texture with `use_as_default: true` or `particle: true`; else the texture referenced by the most faces; else index 0. Every face of every cube uses that one material.
  - Mode source: optional per-model override file `models/<key>.meta.json` (`{"textureMode": "UV"}`), else `settings.mp.yml` default.

- **Visual parity statement (important, honest scope):** a `BlockDisplay` renders a complete vanilla block state; the vanilla client offers **no API to crop/stretch a vanilla texture to an arbitrary UV rectangle on a display entity's face**. Parity with Blockbench is therefore achieved at the *material selection* level:
  - UV rect aspect and pixel density inform nothing about cropping; they are used for **UV rotation** (a face with `rotation: 90` gets its plate display rotated 90° around the face normal, §4.2) and diagnostics.
  - Faces whose UV rect selects a *sub-region* of a texture (e.g. a 2×16 strip of `beacon.png`) render the full texture of the resolved material — the closest available visual proxy.
  - True per-pixel UV parity would require a generated client resource pack (the discarded `source` data). **Explicitly out of scope** per project constraints; recorded in §9 Future Work.

### 2.6 Brightness

`light_emission` (0–15) per element → importer captures it → `display.setBrightness(new Brightness(max(lightEmission, 0), 15))` when `lightEmission > 0`. Gives emissive cubes (beacon cores, lamp housings) full-bright rendering independent of world light, matching Blockbench's preview intent.

---

## 3. Subsystem A — Streaming Parser & Redundancy Stripping

### 3.1 Problem

`JsonParser.parseReader` materializes the entire document as a Gson DOM — including every texture's `source` field (a Base64 PNG, typically 1–500 KB, multiple per model) plus `animations`, `timeline_setups`, and other unused branches. The data is never read, but it is fully allocated, then garbage. With N models parsed at startup, peak transient allocation is the sum of all sources.

### 3.2 Design

Replace the DOM parse with a **single-pass `com.google.gson.stream.JsonReader`** state machine that:

```
PARSE(file):
  reader = new JsonReader(new BufferedReader(new FileReader(file)))  // buffered!
  expect BEGIN_OBJECT
  while reader.hasNext():
    name = reader.nextName()
    switch name:
      "textures"    → parseTextures(reader)        // build TextureLookup; SKIP source values
      "elements"    → parseElements(reader)        // cube objects only
      "outliner"    → parseOutliner(reader)        // groups: uuid, origin, rotation, visibility, children
      "resolution"  → parseResolution(reader)      // width/height for UV normalization
      "meta"        → parseMeta(reader)            // box_uv only; skip rest
      default       → reader.skipValue()           // ← THE strip: source, animations,
                                                   //   timeline_setups, variable_placeholders,
                                                   //   unhandled_root_fields, ... never allocated
  ...
```

`skipValue()` on a `JsonReader` traverses the token tree **without building objects** — a Base64 `source` is streamed past as a single string token; its characters are never retained (Gson's streaming reader does not buffer skipped strings).

**In `parseTextures`**, per texture object, read only: `path`, `relative_path`, `name`, `id`, `use_as_default`, `particle`; `skipValue()` everything else — critically `source` (which can be the largest field in the file).

**In `parseElements`**, per cube read only: `uuid`, `name`, `type`, `from`, `to`, `origin`, `rotation`, `inflate`, `export`, `faces` (`uv`, `rotation`, `texture`), `light_emission`; skip the rest (`rescale`, `locked`, `color`, `autouv`, `render_order`, `allow_mirror_modeling`, `box_uv`, ...).

**In `parseOutliner`**, read group `uuid`, `origin`, `rotation`, `visibility`, `children`; string primitives are element UUIDs. The existing recursive `applyOutlinerEntry` matrix accumulation (`P(group) = T(origin/16)·R·T(-origin/16)` chained through parents, cycle-safe via `visited` set) is preserved verbatim — only its input construction changes.

### 3.3 Contract preservation

- `VirtualModel parse(String name, File file, Logger logger)` signature unchanged; `ModelRenderingManager`'s lazy parse and `VirtualBlockManager.loadExternalModelDefinitions` are untouched.
- `VirtualModel` gains fields (additive record change): `Resolution resolution` (`record Resolution(int width, int height)`, default 16×16) and `TextureMode textureMode` (enum `BOX | UV`, default `BOX`, from meta override).
- `BakedCube` gains `int lightEmission` (default 0) — appended field, all constructors updated.
- Failure semantics preserved: return `null` + logger warning on malformed input; a model with zero surviving cubes is rejected by `loadExternalModelDefinitions` exactly as today.

### 3.4 Memory profile after change

| Field | Before | After |
|-------|--------|-------|
| `source` (×T textures) | fully allocated transiently | streamed past, 0 allocations |
| Unused cube fields (×E elements) | allocated in DOM | skipped |
| Retained model graph | `List<BakedCube>` + texture name map | identical + resolution/mode |
| Steady-state per model | unchanged (records are compact) | unchanged |

---

## 4. Subsystem B — Per-Face Rendering Emission (Box/UV Mapping)

### 4.1 Emission algorithm

New class `DisplayEmitter` (package `infrastructure.virtual`), called from `VirtualBlockManager.spawnModel` per cube:

```
EMIT(cube, M_world context, textureMode):
  if textureMode == UV:
      material = resolveModelTexture(model)          // §2.5 UV mode
      emitBlockDisplay(material, cubeTRS)            // single display — current behavior
      return

  materials[face] = TextureMaterialResolver.resolve(face.textureName)   // §2.5 pipeline
  distinct = { materials[f] : f ∈ 6 faces, face defined }

  if |distinct| ≤ 1:
      // FAST PATH — majority of cubes (reference model: every cube is uniform)
      emitBlockDisplay(materials[primary] ?? FALLBACK, cubeTRS)
      return

  // SPLIT PATH — box mapping with differing faces
  emitBlockDisplay(materials[primary], cubeTRS)      // base solid (interior + primary faces)
  for face f where materials[f] ≠ materials[primary]:
      emitFacePlate(materials[f], f, cubeTRS)        // thin overlay plate
```

### 4.2 Face plate construction (box-mapping parity)

A plate is a `BlockDisplay` of the face's material, scaled to the face rectangle with one-pixel thickness, hugging the cube's face:

```
For face f with outward normal n_f (model space: UP=(0,1,0), NORTH=(0,0,-1), ...):
  faceSize(f) = cube.scale with the n_f component replaced by PLATE_THICKNESS (1/16 block)
  faceCenter(f) = cube.translation + cube.scale/2 + n_f · (cube.scale[n_f-axis]/2 + EPS_OUT)
       where EPS_OUT = 1/1024 (anti-z-fight outward offset)
  plateTRS = M_cube_conjugated:
       translation  = R_snap · (faceCenter) + ... composed with cube rotation:
       full local matrix:  M_plate = M_cube · T(faceCenter_local) · R_align(n_f) · S(faceSize_local)
       where M_plate maps the unit display cube onto the plate rectangle;
       R_align orients the display's local axes so the plate's large faces
       face along n_f; decompose M_plate to (translation, leftRotation, scale) as in §2.2.
  UV rotation: leftRotation premultiplied by R_n(−face.rotation) — rotate the plate
       90°·k around n_f in the plate plane, matching Blockbench's per-face UV rotation.
```

**Entity budget:** fast path = 1 entity/cube (unchanged from today). Split path = 1 + (faces differing from primary) ≤ 6. Models authored with one texture per cube (the norm, and both shipped juicer models) see **zero** entity growth. The reference model at the end of this document: 3 cubes × uniform materials = 3 displays, same as today.

### 4.3 BlockData selection

`Material.createBlockData()` (default state) is retained. Enhancement for high-parity cases: a per-material `Function<Material, BlockData>` table for blocks whose default state is degenerate — e.g. set `facing` on directional blocks whose Blockbench face implies orientation, `rotation` for logs' `axis`. Table starts empty; entries added opportunistically.

---

## 5. Subsystem C — Geometry-Aware Collision Proxies

### 5.1 Core algorithm — per-cube SAT voxelization

New class `VoxelOccupancyCalculator` replaces `VirtualBoundingBox` in `spawnModel`:

```
OCCUPANCY(model, snapped R, mode):
  cells = HashSet<Long>  (packed coords, §6.3)

  for cube in model.cubes():
      // cube OBB in model space (block units)
      M = T(cube.translation) · R(cube.leftRotation) · S(cube.scale) · R(cube.rightRotation)
      // apply placement rotation — exact integer-lattice map
      M' = R_snapped · M     (R_snapped has 0/±1 entries ⇒ M' stays exact)

      // candidate cell range from the OBB's AABB
      (min, max) = aabb(M' · unitCubeVertices)
      for cx in [floor(min.x) - 0 .. ceil(max.x)):
        for cy in ...:
          for cz in ...:
            cell = (cx, cy, cz)
            if cell ∈ cells: continue                      // early-out: union
            if cubeIntersectsCell(M', cell, eps):
                cells.add(pack(cell))

  if mode == SURFACE: cells = hollowInterior(cells)         // §5.3
  return cells → List<Vector> offsets (integers, may be negative)
```

**`cubeIntersectsCell(M', cell, eps)` — separating-axis test between the transformed unit cube (OBB) and the axis-aligned cell `[c, c+1]³` shrunk by `eps` (default `1/1024`):**

```
axes = { e_x, e_y, e_z,                     // 3 cell axes
         M'.columns[0..2] }                 // 3 OBB axes
       ∪ { e_i × M'.col_j : i∈0..2, j∈0..2 } // 9 cross products  → 15 axes total

for axis a in axes:
    projBox = extent of OBB on a  (for OBB axes: |M'.col_j · a| sums)
    projCell = extent of shrunk cell on a
    if projBox.max < projCell.min or projBox.min > projCell.max:
        return false        // separated → no intersection
return true
```

**Axis-aligned fast path:** when `cube.leftRotation()` ≈ identity ( quaternion identity check: `w² ≈ 1`), the SAT degenerates to interval overlap per axis — `max(Min) < min(Max)` against the shrunk cell — 3 comparisons instead of 15. This covers the overwhelming majority of Blockbench cubes.

### 5.2 Properties this guarantees

- **No internal-void filling:** a hollow tower built from four thin wall cubes produces barrier cells only along the walls; the interior — covered by no cube — receives none. An empty atrium between two pillars stays empty.
- **Scale agnostic:** the loop bounds derive from each cube's own AABB; a 2×64×2 pillar (reference model) yields exactly the cells its geometry touches; a 14×14 plate yields a 2×2... precisely, the cells `[floor(-7/16), ceil(7/16))³` that its geometry intersects.
- **Exact under rotation:** `R_snapped` is integer-valued, so cell coordinates map through it with **integer arithmetic** — no `Math.round`, no drift (§2.3). Rotation by 90° about any axis, and all 24 orientations, preserve the lattice exactly.
- **Negative extents supported:** `floor` accepts negatives; the clamp-to-zero defect is gone. The anchor is simply the block containing model origin `(0,0,0)`; offsets may point west/north/down.

### 5.3 Collision modes

Configurable per model (meta override) with global default in `settings.mp.yml`:

| Mode | Semantics | Use case |
|------|-----------|----------|
| `AABB` | legacy full-box fill (current behavior, via `VirtualBoundingBox`) | compat escape hatch |
| `GEOMETRY` (default) | per-cube volumetric voxelization (§5.1) | standard machines; solid parts solid, voids open |
| `SURFACE` | `GEOMETRY`, then remove any cell whose 6 axis-neighbors are all occupied (union-interior hollowing) | walk-in structures: giant hollow furnaces, ship hulls — players enter through any opening ≥ 1 block; interior of thick walls stays collision-free while surfaces block passage |

`hollowInterior(cells)`: single pass, `cell` removed iff `cell ± e_x, ± e_y, ± e_z ∈ cells`. Pure set operation, O(n).

### 5.4 Barrier placement policy (in `spawnModel`)

For each occupied cell transformed to world coordinates:

- air → `Material.BARRIER`, tracked (unchanged).
- non-air → current code **silently skips**. New policy per settings: `SKIP` (default, unchanged) or `STRICT` (abort spawn, roll back placed barriers, return `null` → `MultiBlockLifecycleManager.place()` already rolls back on null model; extend the same path for placement collisions).

### 5.5 Occupancy caching

Voxelization is deterministic per `(modelKey, orientationIndex)` where `orientationIndex ∈ [0,24)`. Cache computed offsets (as `int[]` triples, see §6.3) in a bounded `ConcurrentHashMap<CacheKey, int[]>` on `VoxelOccupancyCalculator`. A server running 40 identical juicers pays voxelization **once**. Cache invalidated by `reloadModelDefinitions()` (clears with the model map).

---

## 6. Optimization Strategies

### 6.1 Parse-time memory (Subsystem A)
- Streaming `JsonReader` + `skipValue()` for `source` and all dead branches (§3) — the dominant win.
- `BufferedReader` wrapper (8 KB default) to avoid syscall-per-token pathologies.
- **String interning** of texture names and face texture names (`String.intern()` or a per-parse `HashMap<String,String>` dedup map) — the same names repeat across faces/cubes.
- Parse errors fail the whole model (current contract); no partial states retained.

### 6.2 Texture resolution cost
- `TextureMaterialResolver`: add a `private static final ConcurrentHashMap<String, Material> RESOLVE_CACHE`; the alias/fuzzy tiers (3–5) run at most once per unique name per JVM lifetime.
- Resolution happens at **emit time**, not parse time, so `reloadModelDefinitions` does not pay for unused models beyond parse; the cache makes repeat spawns free.
- Per-model `UnresolvedTextureReport` (list of names that hit fallback) computed once per model load and stored on the `VirtualModel` — surfaced through `/mineplus model info`, logged once via `DebugLogger` (gated by `ADDITIONAL_DEBUG_LOGS` as usual).

### 6.3 Voxel set representation
- Pack cell coords into a `long`: `(x & 0x1F_FFFF) << 42 | (y & 0x1F_FFFF) << 21 | (z & 0x3F_FFFF)` (21/21/22 bits signed ranges — ±1M blocks is far beyond any model). `HashSet<Long>` during computation; final cached form is `int[]` triples (primitive, GC-friendly, ~12 bytes/cell).
- No `org.bukkit.util.Vector` allocation during voxelization; `Location` objects are created only for the final barrier placement set.

### 6.4 Entity budget
- Fast-path single display per uniform cube (§4.1) — no regression vs today.
- Plate emission only for faces differing from primary.
- `setBrightness` only when `lightEmission > 0` (avoids NBT for the common case).
- `spawnEntity` loop unchanged otherwise; ghost-cleanup and scoreboard tagging (`mineplus_vblock:<uuid>`) continue to work because plates are ordinary tagged `BlockDisplay`s.

### 6.5 Startup and reload
- Model parse order already sorted and sequential (good for determinism); with streaming, peak heap during a 50-model load drops from Σ(all sources) to max(single source) ≈ 0.
- `reloadAll()` path (`VirtualBlockManager.reloadModelDefinitions` + `lifecycleManager.reloadModels`) is untouched; occupancy caches clear with models.

---

## 7. Implementation Roadmap

Ordered phases; each ends compilable. Estimated sizes assume the verified current sources.

### Phase 0 — Groundwork & tests scaffold
1. Restore `libs/` jars (build blocker, outside code).
2. Add `src/test/java` + JUnit 5 (`testImplementation` — jars to `libs/`): pure-JVM tests need only gson + joml + junit; no Paper API in math tests.
3. Snapshot current rendering output as golden diagnostics (optional debug model render log via `ADDITIONAL_DEBUG_LOGS`).

**Files:** `build.gradle`, new `src/test/java/com/mineplus/infrastructure/virtual/*`.

### Phase 1 — Streaming importer (Subsystem A)
1. Rewrite `BbModelImporter.parse(Reader)` internals to `JsonReader` state machine (§3.2); keep the outliner matrix accumulation logic verbatim.
2. Capture `resolution` and per-cube `light_emission`; extend `VirtualModel` (`resolution`, `textureMode`) and `BakedCube` (`lightEmission`).
3. Meta override loading: `models/<key>.meta.json` (`textureMode`, `originMode`, `collisionMode`) — tiny loader beside `BbModelImporter`.
4. Tests: bbmodel fixture with a multi-KB dummy `source` asserts (a) parse succeeds, (b) importer never surfaces it, (c) heap-sane parse of the reference model; negative-coordinate `from/to` preserved; outliner nesting bake matches golden matrices.

**Files:** `BbModelImporter.java`, `VirtualModel.java`, `BakedCube.java`, new `ModelMeta.java`.

### Phase 2 — Texture resolution engine (Subsystem B, resolution half)
1. Wire `TextureMaterialResolver` into `VirtualBlockManager` (replace `resolveCubeMaterial`); add `resolve(String) → Material` full pipeline with `RESOLVE_CACHE`, suffix-strip tier, alias table, token-fuzzy tier (§2.5).
2. Add `UnresolvedTextureReport` on `VirtualModel`; log once per load behind `DebugLogger`.
3. Tests: `"beacon"→BEACON`, `"clay"→CLAY`, `"bookshelf"→BOOKSHELF`, `"brick"→BRICKS`, `"concrete_orange"→ORANGE_CONCRETE`, `"wool_colored_white"→WHITE_WOOL`, `"garbage_name"→WHITE_CONCRETE + report`.

**Files:** `TextureMaterialResolver.java`, `VirtualBlockManager.java` (resolution call sites only), `VirtualModel.java`.

### Phase 3 — Grid convention & rotation snapping
1. New `RotationSnapper` (§2.3) + unit tests for all 24 orientations and repair path.
2. `spawnModel`: snap placement rotation at entry; replace float `Math.round` offset transform with integer `SnappedRotation` transform; adopt corner-anchored `displayOrigin = origin` under `originMode: GRID` (default), keep `+0.5` under `LEGACY` (meta override).
3. Fix `VirtualBoundingBox` min-clamp (or supersede it in Phase 4 and mark deprecated).
4. Manual validation: `/mineplus model place` the juicer in both modes; barrier lattice and visual cube edges must coincide exactly in `GRID` mode.

**Files:** new `RotationSnapper.java`, `VirtualBlockManager.java`, `VirtualBlockPlacementHelper.java` (unchanged logic, now feeding snapper).

### Phase 4 — Geometry-aware occupancy (Subsystem C)
1. New `VoxelOccupancyCalculator` (§5.1): SAT core, axis-aligned fast path, packing, `GEOMETRY`/`SURFACE`/`AABB` modes, `(model, orientation)` cache.
2. `spawnModel` barrier loop consumes calculator output; non-air policy `SKIP|STRICT`.
3. Tests: reference model (§8) → expected exact cell sets; hollow-tower fixture (4 wall cubes + roof) → interior empty in `GEOMETRY`; 2×2 interior in `SURFACE` mode for a 6×6 solid block fixture; rotated (90° yaw, 90° pitch) fixtures → identical cell counts modulo exact lattice permutation.

**Files:** new `VoxelOccupancyCalculator.java`, `VirtualBlockManager.java`, `VirtualBoundingBox.java` (deprecate), tests.

### Phase 5 — Per-face emission (Subsystem B, render half)
1. New `DisplayEmitter` (§4): fast path + plate decomposition + UV rotation + brightness.
2. `spawnModel` cube loop delegates to emitter; `textureMode` `BOX|UV` honored.
3. Tests: emitter output count and plate transforms for a mixed-material fixture; golden transformation matrices.

**Files:** new `DisplayEmitter.java`, `VirtualBlockManager.java`, tests.

### Phase 6 — Config, commands, docs
1. `settings.mp.yml` new section (tolerant read style, mirroring `ADDITIONAL_DEBUG_LOGS` handling):
   ```yaml
   VIRTUAL_RENDERING:
     COLLISION_MODE: GEOMETRY        # AABB | GEOMETRY | SURFACE
     COLLISION_EPSILON: 0.001
     COLLISION_NON_AIR_POLICY: SKIP  # SKIP | STRICT
     ROTATION_SNAP: true
     ROTATION_SNAP_THRESHOLD_DEGREES: 5
     PER_FACE_RENDERING: true
     TEXTURE_MODE: BOX               # BOX | UV
     ORIGIN_MODE: GRID               # GRID | LEGACY
   ```
2. `/mineplus model info <key>` subcommand: cube count, display-entity estimate, resolved texture table (name → material / FALLBACK), occupancy cell count + ASCII layer dump, cache status.
3. Update `AGENTS.md` (it is stale — references `BlockRegistry`, `ManagedBlockService`, `RecipeRegistry`, `ItemUseService` which no longer exist) to document the new virtual-rendering architecture and config.

**Files:** `ConfigManager.java`, `MineplusConfig.java`, `ModelSubCommand` (command sub package), `AGENTS.md`.

### Phase 7 — Validation
1. In-game matrix: juicer L1/L2, reference big model, a hollow-structure model, rotated placements (all 4 yaws + wall/ceiling mounts).
2. Performance: `/spark` or timings during 50-instance spawn; heap delta across `reloadAll`; entity counts vs Phase-0 baseline.
3. Persistence round-trip: place → restart → `restoreForState` re-spawns identical barriers/displays (rotation snap is idempotent, so persisted quaternions re-normalize cleanly).

---

## 8. Reference Model Walkthrough (supplied bbmodel)

`test-big-model-1-4-1`: resolution 16×16, 3 cubes, textures `brick, clay, bookshelf, beacon` by index.

| Cube | from→to | Size (blocks) | Textures |
|------|---------|---------------|----------|
| 1 | [-1,0,-1]→[1,64,1] | 0.125 × 4 × 0.125 | N/S: brick(3), E/W: bookshelf(2), U/D: none→primary |
| 2 | [-7,0,-7]→[7,2,7] | 0.875 × 0.125 × 0.875 | all: clay(1) |
| 3 | [-6,51,-6]→[6,66,6] | 0.75 × 0.9375 × 0.75 | all: beacon(0) |

**Expected outcome under the new system:**

- Parse: `source` fields of 4 textures streamed past (~4 allocations avoided per load); `resolution 16×16` captured; `light_emission 0` captured.
- Textures: `brick→BRICKS`, `clay→CLAY`, `bookshelf→BOOKSHELF`, `beacon→BEACON` — all first-tier map hits. Cube 1 is **split-path** (brick vs bookshelf faces): 1 base display (brick) + 4 side plates (bookshelf, N/S/E/W) = 5 entities. Cubes 2, 3 fast path: 1 entity each. Total 7 displays vs 3 today — the price of genuine box mapping on a mixed-material cube.
- Collision (`GEOMETRY`): cube 1 occupies cells `{(-1..0) × (0..63) × (-1..0)}` = 2×64×2 = 256 cells (thin pillar — exact geometry contact, no inflation to a full AABB column); cube 2 occupies `(-1..0) × {0} × (-1..0)` = 4 cells; cube 3 occupies `(-1..0) × (3..4) × (-1..0)` = 8 cells. Union ≈ 264 barriers vs **legacy: full AABB = 2×66×2 = 264**… nearly identical here *only because* this model is a dense pillar stack; a hollow variant would show the difference dramatically (legacy fills everything, geometry leaves the void).
- Rotation: placed facing east (yaw 90°): `R_snap` permutes axes exactly; the 64-tall pillar becomes 64 cells along X; displays rotate rigidly with it. Wall-mount (pitch 90°): pillar lies horizontal, still lattice-exact.

---

## 9. Future Work (explicitly out of scope)

1. **True UV parity via generated resource pack:** the bbmodel `source` textures could be written into a server-hosted pack giving per-face UV-cropped rendering. Deferred — conflicts with the "default game textures, no external assets" constraint; revisit only if the constraint is lifted.
2. Sub-block collision (player hitboxes finer than 1 block) — requires non-barrier techniques (interaction entities have no collision; client mod is out of the question).
3. Animations (`timeline_setups` are skipped today) via `Display.setInterpolationDuration` + teleport transitions.
4. Texture atlas deduplication reporting across models.

---

## 10. Risk Register

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| `GRID` origin shift moves existing deployed models visually by 0.5 block | Certain | `originMode: LEGACY` meta override per model; migrate during a maintenance window; persisted anchors unchanged |
| Per-face plates raise entity counts on mixed-material models | Medium | Fast path keeps uniform cubes at 1 entity; budget documented (§8); `PER_FACE_RENDERING: false` kills plates globally |
| SAT voxelization cost on huge models (10k+ cubes) | Low | Occupancy cache per (model, orientation); axis-aligned fast path; complexity is O(cells × cubes_in_range) with early-out |
| Snapping a legitimately-angled placement (custom API quaternions) | Low | Threshold warning via `DebugLogger`; snap is intentional per the grid-alignment requirement |
| `libs/` absent blocks compile | Certain (this backup) | Restore jars (Phase 0); no new dependencies introduced |
| Gson streaming nuances (malformed files mid-skip) | Low | `skipValue` is strict; existing fail-model contract (null + warning) covers it; fixtures in tests |

---

## 11. Acceptance Criteria

1. Parsing any `.bbmodel` allocates no `source` string (verifiable by heap dump or by instrumented fixture test).
2. `/mineplus model info` shows ≥ 95% of textures on a real asset library resolving past the fallback tier.
3. Hollow reference structure: zero barriers in interior cells; players can walk inside.
4. All 24 snapped orientations render with barrier lattice exactly matching visual geometry edges (no half-block drift, no rounding seams).
5. Uniform-material model entity count unchanged vs current build; mixed-material delta ≤ 6× worst case, documented.
6. `reloadAll()` + restart + restore produce byte-identical barrier sets for all persisted instances.
7. Startup heap delta with 50 models loaded reduced vs baseline (measure in Phase 7).
