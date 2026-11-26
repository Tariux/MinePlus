# Mineplus model pipeline and hard limits

Everything below is what the Core actually does, not what a generic Blockbench
tutorial assumes. Source paths are relative to
`E:/The Backup/Backups Main/16 July 2026/mineplus`.

## 1. Virtual axis (no resource pack)

`BbModelImporter` streams the `.bbmodel` with a Gson `JsonReader`; embedded PNG
`source` strings and timeline setups are skipped and never allocated.

| Behaviour | Detail |
|---|---|
| Geometry | Cubes only (`BbModelImporter.java:151`). Elements whose `type` is not `cube` are dropped. Meshes, armatures and polyhedra never import. |
| Transforms | `from`/`to` pixels ÷16, per-cube pivot conjugation `T(origin/16)·R·T(-origin/16)`, then the outliner group chain. Negative coordinates are preserved. |
| `inflate` | Added to all six sides before sizing (`BbModelImporter.java:164`). |
| `rescale` | Parsed into `ElementGeometry` for pack serialization only; the virtual transform does not rescale. |
| `light_emission` | Per-cube display brightness (0–15). One display per cube; mixed-material cubes emit plates that inherit the cube's emission. |
| Textures | Texture filename priority is `path` → `relative_path` → `name`; only the last path segment matters, extension stripped (`BbModelImporter.java:283`). |
| Material | Primary face texture in north→south→east→west→up→down order, mapped by `TextureMaterialResolver` (curated → direct Bukkit material → suffix-strip → alias → fuzzy → `WHITE_CONCRETE`). |
| Texture PNG | `TextureImageStore` looks for `<name>.png` **next to the model file**, then in the models root (`TextureImageStore.java:19`). Embedded Base64 is never read. |
| Max dimensions | 4096×4096 for a texture PNG; larger images are rejected by the store. |

### Anchor conventions

`ModelMeta.OriginMode` (`ModelMeta.java:56-127`):

- `CENTER` — free-format Blockbench convention: pixel (0,0,0) is the block centre at
  its base; a block spans x/z −8..8, y 0..16. Single-block models rotate about the centre.
- `GRID` — vanilla `java_block` convention: pixel (0,0,0) is the north-west-bottom corner;
  a block spans 0..16 on every axis.
- `AUTO` (default) — `java_block`/`java_item`/`modded_block` resolve GRID unless the
  geometry's x/z centre is within 2 px of the origin, in which case CENTER is assumed.
  Everything else resolves CENTER.
- Per-model `.meta.json` `originMode` wins. A wrong anchor is the usual cause of a model
  swinging out of its block when placed rotated.

### Collision and placement

- Occupancy is rasterized per transformed cube (SAT, axis-aligned fast path), not from one
  model bounding box. Empty internal cells stay free.
- `GEOMETRY` (default): solid parts solid, voids open. `SURFACE`: also hollows enclosed
  interiors for walk-in structures. `AABB` is a compatibility alias for the geometry path.
- Every occupied cell becomes a barrier. Footprint = all cells the geometry crosses;
  a 2-block-tall model reserves two layers.
- `RotationSnapper` snaps placements to the 24 orientation-preserving axis permutations;
  deviations over 5° are logged (`RotationSnapper.java:19`).
- Standard players require an empty spawn area; creative/op placements clear non-air
  occupants. Failures roll back barriers.

### Per-face emission and UV alignment

- Uniform-material cube → one `BlockDisplay`.
- Mixed-material cube → one display for the dominant material plus thin per-face plates
  (1/1024 block), enabling per-face textures (`DisplayEmitter.java:135,193`).
- The UV layer inspects each face window: a half window becomes a slab-type crop with
  geometry compensation; a wrapping window becomes a native-density tile grid; a
  directional material receives `facing`/`axis` block states so fronts and rings land on
  the intended face.
- Arbitrary sub-pixel crops are out of scope for virtual rendering — use the pack axis.

### Texel surface baking

When a matching PNG exists, faces can be reconstructed pixel-by-pixel from flat vanilla
blocks instead of one material per face.

- `TexelBakingSettings.defaults()`: enabled, `AUTO`, `FACE` detail, **96 plates/face**,
  **150 plates/instance**, grid edge 64, uniform-area merge, adaptive budgeting,
  budget fallback, symmetric-face reuse (`TexelBakingSettings.java:55`).
- Palette: 16 concretes (stretchable), 16 concrete powders, 16 terracottas, snow
  (stretchable), 18 flat stones/minerals. Only pure flat concretes and snow may merge into
  stretched rectangles; powders/terracottas/stones stay 1×1. Colours are matched in Oklab
  with directional-shade compensation.
- Alpha < 128 is cutout; enclosed transparency (atlas padding) is infilled from its opaque
  neighbours; centre-occluded texels are rescued when a footprint corner is visible.
- Baking runs async; spawn paths wait boundedly for an in-flight bake. Over-budget faces
  fall back to one dominant-colour plate, so gradient-heavy art degrades to flat tone
  instead of white.
- `texelMode` `AUTO` upgrades only faces that would otherwise use the full-face material;
  `ON` bakes every resolvable face; `OFF` keeps the legacy material pipeline.

### Animation

| Feature | Status |
|---|---|
| Bone animators keyed by outliner group | Imported, names resolved to bones |
| rotation / position / scale, deltas from rest | Imported |
| `linear`, `step` | Exact |
| `catmullrom`, `bezier`, `ease` | Sampled linear (handles are not in keyframe data) |
| Molang string values | Substituted with 0 and reported |
| Effect animators (sounds, particles, timelines) | Dropped |
| Loop `once` / `loop` / `hold` | Supported; `ONCE` returns to rest, `HOLD` freezes the end frame |

Cubes bind to their innermost group (`BakedCube.boneIndex`); a cube outside the outliner
cannot animate. Composition order is `Rz·Ry·Rx` in Blockbench Euler degrees, with
hierarchical deltas `world = parent · T(p) · R(Δr) · S(Δs) · T(-p) · T(Δt)`. The server
pushes poses with client-side interpolation; `ANIMATION.TICK_INTERVAL_TICKS` defaults to 1.

## 2. Pack axis (resource pack)

A pack item/block is still authored as a `.bbmodel`; the compiler serializes it to a
vanilla model instead of spawning displays.

### Pack items

- `PackItemDefinition(namespace, id, backing material, model key)` → `PackApi.registerItem`.
  The model key is the `.bbmodel` stem; the compiler attaches the model and every texture
  it references.
- Modern servers use the additive `item_model` component; legacy uses `CustomModelData`.
  Vanilla items are never redirected.
- Serialization: `ModelJsonWriter` emits `assets/<ns>/models/item/<id>.json` with
  `textures` keys `ns:item/<last-segment>` and PNGs at `assets/<ns>/textures/item/<name>.png`.
- **Coordinates are unshifted for items** (`PackCompiler.java:341-351` forces origin
  resolution to null unless `.meta.json` names one): author 0..16 px. A centered
  free-format model renders off-centre.
- Authored `display` contexts are copied; if none and no `itemParent`, vanilla generated
  item transforms are written. `.meta.json` `display` overrides the bbmodel; `itemParent`
  (e.g. `minecraft:item/handheld`) defers to a vanilla parent.
- Rotations: at most one axis within ±45° are emitted natively; anything else becomes the
  axis-aligned bounding box with a warning.
- UV windows outside 0..resolution are clamped to 0..16 (`ModelJsonWriter.java:292`).

### Pack blocks

- `PackBlockDefinition(namespace, id, model key)` + `geometryModelKey` +
  `renderMode: pack_block` on the multiblock level.
- One `BlockDisplay` per instance carries an allocated **carrier** state. The shipped
  carrier is `note_block`; its complete vanilla state table is generated, and only rare
  mob-head instrument states are allocated (7×25 = **175 slots**).
- A carrier must render with the client `MODEL` render type. `light`, `structure_void` and
  `barrier` are INVISIBLE and can never carry a display.
- CENTER-authored geometry is shifted +8 into block space; GRID is not.
- The vanilla `blocks` atlas only scans `textures/block/` and `textures/item/`; a model
  pointing anywhere else gives the purple/black checkerboard. The compiler writes the
  correct paths — do not hand-edit them.
- Geometry outside the client-safe −16..32 px window is re-based or squashed; multi-block
  structures are better authored per block or as separate models.

### Pack lighting

`PACK.LIGHTING`: `AUTO` (natural light; a model with any emission gets its maximum
emission + sky 15), `NATURAL`, `EMISSIVE`, `FULLBRIGHT`. One display carries one
brightness, so per-face emission is approximated by the model maximum.

## 3. Custom mob rigs

A custom mob is a vanilla base `Mob` with Mineplus identity, attributes, behaviour and an
optional visual rig. Without the pack subsystem the base entity stays visible; gameplay,
AI isolation, persistence and attributes are unaffected.

### Model sources

1. `CustomMobModel.vanilla(EntityType, png)` — vanilla boxes/pivots/roles, texture only.
2. `CustomMobModel.builder(w, h, png)` — explicit parts with vanilla pixel pivots.
3. `BedrockMobModel.parse(bytes)`:
   - named `.part(...)` mapping, or
   - `partsFromOutliner()` — every outliner group becomes a part/bone, using its authored
     pivot, rest rotation and parent; elements directly inside it are its geometry.
     `animationClips()` attaches clips by bone name.

### Coordinates

- Authored space: y-up, feet at y=0, model front **−z**. Absolute pivots (a part's pivot is
  never relative to its parent; `parent` only decides which rotation carries it).
- `MobPart` stores pivots y-down from `modelHeightPx`; the importer converts.
- Rest rotations are y-down degrees; clip curves are y-up; they are summed before building
  the `Rz·Ry·Rx` quaternion (`MobPoseSpace.java`).
- `partsFromOutliner()` assigns `MobPart.Role.STATIC` to every group. Roles drive only the
  **procedural** pose. Override them in code (`.role(name, ROLE)`) or in
  `plugins/Mineplus/mobs/<id>.model.json` via `legRoleMap`:
  ```json
  { "legRoleMap": { "head": "HEAD", "body": "BODY",
                    "leg_fl": "LEG_FRONT_LEFT", "leg_fr": "LEG_FRONT_RIGHT",
                    "leg_bl": "LEG_BACK_LEFT", "leg_br": "LEG_BACK_RIGHT" } }
  ```
  Same file: `poseMode` (`auto|clips|procedural`), `animationScale`, `walkSpeedReference`,
  `yawOffsetDegrees`, `strideBlocks`.

### Clips and rendering

- Clip names: `animation.idle` and `animation.walk` are blended by movement speed;
  `animation.attack` is a one-shot overlay played on a melee hit.
- Every element must be named and have a positive extent and at least one UV-bearing face;
  `BedrockMobModel` skips others.
- Each part compiles to one texture, one item model and one item definition; the rig
  presents parts as `ItemDisplay`s posed from body yaw/pitch, interval 1 tick,
  interpolation 1 tick.
- Caps (`CustomMobSettings.defaults()`): rendering on, distance 48 blocks,
  `MAX_RIGGED_PER_WORLD` 96, unknown tagged ids removed on load.

## 4. Cross-cutting limits

| Limit | Consequence |
|---|---|
| No non-cube elements on the virtual axis | Meshes, spheres, cylinders, armatures are invisible in game |
| One material per cube virtually | Paint a second material on a split cube |
| No embedded textures | PNG must sit beside the `.bbmodel` (or models root) |
| No Molang evaluation | Animations using expressions collapse to 0 |
| Splines sampled linear | Bezier ease looks mechanical in game |
| Effect animators dropped | Sounds/particles must be driven by the plugin |
| Texel plate budgets | Over-budget faces flatten to one colour |
| Pack: one-axis ±45° rotations | Everything else becomes an AABB |
| Pack: wrapping UVs clamp | Re-tile by splitting faces instead |
| Pack: 175 carrier slots | Uppercase the design limit before shipping many pack blocks |
| Pack: no pack-driven mob animation | Mob clips are Core-side `ItemDisplay` posing, not pack model animations |
| BOM-prefixed JSON | Node/JSON tooling and some importer paths reject it |
| Duplicate texture last segments | Pack assets collide; rename one file |

## Where to read the source

- Importer: `src/main/java/com/mineplus/infrastructure/virtual/BbModelImporter.java`
- Meta/anchors: `.../ModelMeta.java`, `.../VirtualRenderingSettings.java`, `.../RotationSnapper.java`
- Emission: `.../DisplayEmitter.java`; texel: `.../texel/`
- Pack writer: `src/main/java/com/mineplus/pack/compile/ModelJsonWriter.java`,
  `PackCompiler.java`; carrier: `.../pack/block/PackBlockCarrier.java`
- Mob rig: `src/main/java/com/mineplus/infrastructure/mob/custom/` (`BedrockMobModel`,
  `MobPoseSpace`, `MobRigRenderer`, `MobModelOverrides`, `CustomMobSettings`)
- Docs: `docs/config-reference.md`, `docs/pack-system.md`, `docs/custom-mobs.md`,
  `docs/developer-api.md`
