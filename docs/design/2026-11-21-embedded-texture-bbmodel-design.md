# Advanced bbmodel handling — hawk_eye R&D design

## R&D findings (hawk_eye.bbmodel, Blockbench 5.0 `free` format, 64×64)

| Aspect | Model reality | Pipeline status |
|---|---|---|
| 5.0 outliner dialect | outliner = bare `{uuid,children}` shells; root `groups` array carries names (`gun`, `barrels`, `hammers`) + pivots | ✅ both parsers join by uuid (`BbModelImporter.joinGroupDefinitions`, `BbModelParser.indexGroups`) |
| Flat keyframe dialect | `keyframes` array, per-keyframe `channel`/`interpolation`/`data_points` (numeric strings) | ✅ both parsers read it |
| Element rotations | `hammer_left/right` −18° list-form element rotations | ✅ parsed as synthetic bones |
| Clip names | `animation.idle` / `animation.fire` / `animation.break_load` / `animation.close` | ✅ `WeaponBbModels.roleOf` strips the `animation.` prefix |
| No `display` block | — | ✅ DEFAULT_DISPLAY fold, contexts whitelisted |
| UV space | texture pixels up to u=60 (64×64 texture) | ✅ baker now scales by 16/64 |
| **Textures** | ONE texture, **embedded base64** (`source: data:image/png;base64,…`), stale internal name **`boom_stick`**, `path: null`; external **`hawk_eye.png`** ships beside it | ❌ **the blocker** — both importers skip base64; resolution by name finds no `boom_stick.png`; the external `hawk_eye.png` is never consulted → pack texture missing (purple), texel bake degrades to palette |

Root cause: every `"free"`-format bbmodel (Blockbench's default) embeds its painted texture. The pipeline's file-based texture resolution can never satisfy it unless the author renames the external PNG to the embedded texture's internal name.

## Design

### 1. Embedded texture materialization (core fix)
`BbModelImporter.parse` decodes each texture's embedded `data:image/png;base64,…` source (bounded: skip > 8 MB decoded, 4096² guard already in the image store) and **materializes it as `<textureName>.png` beside the model file** — once; an existing file is never overwritten. The models folder is already server-writable (installs write there). Every existing consumer then works unchanged: texel baker, `TextureImageStore`, `PackSystem.registerModelTextures`, `/mineplus model info`. Zero new runtime seams.

### 2. Folder-stem fallback
`TextureImageStore.resolveFile`: when `<name>.png` misses (with case-insensitive index), fall back to `<modelFileStem>.png` before giving up — the convention authors actually follow (`hawk_eye.png` next to `hawk_eye.bbmodel`) even when the embedded name drifted.

### 3. Bake-path texture resolver
`BbClipBaker.Style` gains an optional name→reference resolver; default keeps prefix/normalization. `WeaponBbModels.bake` maps every parsed texture name → `mineplusgun:item/<folderId>` — the name the module's flat texture registration actually ships (folder convention beats embedded stale names).

### 4. Docs
`docs/pack-system.md` (texture resolution rules), `examples/mineplus-gun/README.md` (embedded-texture handling), `CHANGELOG.md`.

## Goals / Non-goals
- Goal: a Blockbench `free`-format model with embedded texture + full clip set renders correctly on BOTH axes with no author-side renaming.
- Non-goal: per-texture multi-PNG pack registration for framed items (folder convention covers it); live animation playback for held items.
