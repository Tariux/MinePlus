# Mineplus Integration

How an authored model becomes live content: folders, keys, sidecars, JSON and the
Java registration calls. Source of truth is the plugin tree
`E:/The Backup/Backups Main/16 July 2026/mineplus` (docs in `<plugin>/docs/`).

## 1. Runtime layout

Relative to the running server's `plugins/Mineplus/`:

```
plugins/Mineplus/
├── settings.mp.yml            engine settings (auto-generated)
├── models/                    *.bbmodel scanned recursively; sibling *.png + *.meta.json
├── multiblocks/*.json         machine type definitions
├── recipes/*.json             machine recipes
├── mobs/<id>.model.json       custom-mob presentation sidecars
└── infrastructure.db          SQLite persistence
```

Module jars install their own defaults into this folder
(`context.moduleSupport().installDefault(...)`), and the module resource tree
(`examples/<module>/src/main/resources/`) is where authored models should live:

| Module | Resource root |
|---|---|
| `mineplus-fun` | `examples/mineplus-fun/src/main/resources/defaults/models/` |
| `mineplus-gun` | `examples/mineplus-gun/src/main/resources/defaults/guns/<id>/` |
| `mineplus-mobfun` | `examples/mineplus-mobfun/src/main/resources/mobs/` |

Install policy: `overwrite=true` for models and textures (a module update must fix
rendering), `false` for JSON configs (owner edits survive updates).

## 2. Keys and resolution

| Thing | Rule |
|---|---|
| Loaded model key | Path under `models/` without `.bbmodel`, lowercased (`models/debug/crate.bbmodel` → `debug/crate`) |
| Multiblock render key | Derived by the engine: `<typeId>_lvl_<level>` lowercased |
| Pack item model key | The model file stem passed to `PackItemDefinition.builder(...)` |
| Pack block geometry key | The loaded file stem (`geometryModelKey`) |
| Texture name | Final filename segment, extension stripped, lowercased |
| Texture PNG lookup | Beside the `.bbmodel` first, then the models root |
| Meta file | Sibling of the model, same stem: `crusher_lv1.bbmodel` → `crusher_lv1.meta.json` |

`/mineplus model info <key>` reports the resolved material table, occupancy cells,
texel history and cache status. `/mineplus model models` lists every loaded key.

## 3. `.meta.json`

Every field is optional; a missing key keeps the global default. The file must be
BOM-free (the streaming reader does not strip a BOM).

```json
{
  "originMode": "CENTER",
  "collisionMode": "GEOMETRY",
  "autoplay": ["idle"],
  "texelMode": "AUTO",
  "texelDetail": "FACE",
  "maxTexelPlatesPerFace": 96,
  "maxTexelPlatesPerInstance": 150,
  "texelBrightness": 15,
  "display": {
    "firstperson_righthand": {
      "rotation": [0, -9, 0],
      "translation": [3, -3, -4.5],
      "scale": [0.55, 0.55, 0.55]
    }
  },
  "itemParent": "item/handheld"
}
```

| Key | Values | Default | Effect |
|---|---|---|---|
| `originMode` | `AUTO` / `CENTER` / `GRID` | `AUTO` | Anchor convention. `AUTO` reads the format (java_block/java_item → GRID) and falls back to CENTER when the xz bounds centre is within 2 px of the origin. `LEGACY` is a deprecated alias of `CENTER`. |
| `collisionMode` | `GEOMETRY` / `SURFACE` / `AABB` | global (`GEOMETRY`) | Per-cube occupancy, or per-cube with interior hollowing. `AABB` resolves through the geometry path. |
| `autoplay` | clip names | empty | Clips started when the model renders (level `animations` wins). |
| `texelMode` | `AUTO` / `ON` / `OFF` | `AUTO` | Texel reconstruction from the face's pixels (external file first, else base64). `AUTO` keeps faces named after vanilla blocks as that block; `ON` plates every pixel face. Only matters for `virtual` rendering. |
| `texelDetail` | `FACE` / `SUPERSAMPLE_2X2` / `SUPERSAMPLE_4X4` | `FACE` | Sampling grid per face. |
| `maxTexelPlatesPerFace` | positive int | 96 | Plate ceiling per face. |
| `maxTexelPlatesPerInstance` | positive int | 150 | Plate ceiling per rendered instance. |
| `texelBrightness` | 0..15 | unset | Forces display brightness. |
| `display` | context → transform | imported from bbmodel | Item poses. Meta wins over the model. |
| `itemParent` | model path, e.g. `item/handheld` | model default | Parent model for the generated item model. |

Gradient-heavy art (wood, cloth, paintings) routinely needs
`{"maxTexelPlatesPerFace": 512, "maxTexelPlatesPerInstance": 2048}` — see the
Cabinet's shipped metas.

## 4. Multiblock JSON

`plugins/Mineplus/multiblocks/<file>.json`; relative `model` paths resolve from
`plugins/Mineplus/`.

```json
{
  "id": "crusher",
  "name": "Crusher",
  "gui": "crusher_gui",
  "levels": {
    "1": {
      "model": "models/crusher_lv1.bbmodel",
      "speed": 1.0,
      "durability": 100.0,
      "upgradeCost": { "core_plate": 8 },
      "guiOptions": { "title": "Crusher I" },
      "animations": ["idle"],
      "renderMode": "virtual"
    },
    "2": {
      "model": "models/crusher_lv2.bbmodel",
      "speed": 1.35,
      "renderMode": "virtual"
    }
  }
}
```

| Level field | Default | Meaning |
|---|---|---|
| `model` | — | `.bbmodel` path relative to `plugins/Mineplus/` |
| `speed` | `1.0` | Craft speed multiplier; applies immediately to a running process |
| `durability` | `1.0` | Reserved |
| `upgradeCost` | `{}` | `itemKey -> amount` |
| `guiOptions` | `{}` | Free-form GUI strings |
| `animations` | `[]` | Autoplay clips |
| `renderMode` | `virtual` | Preferred single selector |

| `renderMode` | Renders as | Needs |
|---|---|---|
| `virtual` | Per-cube `BlockDisplay`s / texels | nothing |
| `pack_item` | Item-display model from the pack | pack applied |
| `pack_block` | One `BlockDisplay` on a carrier state | pack + `PackBlockDefinition` |
| `pack_bones` | One item display per bone | `RIGS.ENABLED`, pack |
| `hybrid_item` / `hybrid_block` / `hybrid_bones` | Pack plus virtual fallback | pack |

Legacy `renderBackend` + `renderKind` still parse; `renderMode` wins when both are
present. Omit `gui` when a module hook must decide interactions (the Cannon does
this); when `gui` is set the core opens that GUI on every right-click.

Reload with `/mineplus reload multiblocks` (or `all`). Instances already placed
resolve their level model by the derived key, so changing a file's **path** means
updating existing instances; changing its **contents** is picked up by
`/mineplus reload models`.

## 5. Java registration

Module features implement `ModuleFeature` (`id()`, `onEnable()`, optional
`onDisable()` and `command()`). Register content in `onEnable()`; the module
bootstrap performs one coordinated `reloadAll()` after every feature enabled, and
the pack assets attach during that reload.

### Assets

```java
var support = context.moduleSupport();
support.installDefault(plugin, "defaults/models/crusher_lv1.bbmodel",
        "models/crusher_lv1.bbmodel", true);
support.installDefault(plugin, "defaults/models/crusher_lv1.meta.json",
        "models/crusher_lv1.meta.json", true);
support.installDefault(plugin, "defaults/multiblocks/crusher.json",
        "multiblocks/crusher.json", false);
```

### Pack item

```java
context.packApi().registerItem(PackItemDefinition.builder(
        "fun", "strad_wine", Material.GLASS_BOTTLE, "strad-wine")
    .displayName("Strad Wine Bottle")
    .category(ItemCategory.UTILITY)
    .descriptionLines(List.of("Rendered at full client fidelity."))
    .build());

ItemStack stack = context.packApi().createItem("fun", "strad_wine");
```

`PackApi` is never null: when the pack subsystem is disabled, item identity still
registers and the backing material renders.

### Pack block

```java
context.packApi().registerBlock(PackBlockDefinition.builder(
        "fun", "alchemy_table", "alchemy_table_lvl_1")   // derived render key
    .geometryModelKey("alchemy-table")                    // loaded file stem
    .displayName("Alchemy Table")
    .carrier(PackBlockCarrier.NOTE_BLOCK)
    .build());
```

`modelKey` must match `ModelRenderingManager.buildModelKey(typeId, level)`.
`geometryModelKey` is the key the imported model loads under; pack geometry and
textures attach there on the coordinated reload.

### Custom mob (rig or vanilla texture)

```java
CustomMobModel model = BedrockMobModel.parse(bbmodelBytes)
        .textureName("soldier_villager")
        .partsFromOutliner()
        .animationClips()
        .build(textureBytes);

context.customMobs().register(this, CustomMobDefinition
        .builder("mobfun:villager_soldier", EntityType.VILLAGER, model)
        .displayName("Villager Soldier")
        .attributes(CustomMobAttributes.builder()
                .maxHealth(60.0)
                .movementSpeed(0.3)
                .attackDamage(6.0)
                .followRange(28.0)
                .build())
        .behavior(profile -> profile
                .action(AttackNearestAction.builder()
                        .searchRadius(20.0).reach(3.4).damage(6.0)
                        .build(), 1.0))
        .build());
```

Texture-only variant: `CustomMobModel.vanilla(EntityType.PIG, pngBytes)`.
Module teardown calls `customMobs().unregisterAll(this)`.

### Animation

```java
UUID id = instance.id();
context.animationApi().playAnimation(id, "idle");
context.animationApi().triggerAnimation(id, AnimationSelector.bone("gear"));
context.animationApi().stopAnimation(id, "idle");
```

Clips are addressed by name (with or without the `animation.` prefix), bones by
outliner group name. `AnimationSelector.animation(...)`, `.bone(...)`, `.all()`.

## 6. Delivery and verification

Pack delivery defaults: `PACK.ENABLED: true`, `DELIVERY.MODE: LOCAL`. Players
online when a new artifact compiles are auto-pushed; declined players are
respected. Per-player URLs resolve loopback → server IP → LAN.

| Command | Use |
|---|---|
| `/mineplus reload all\|models\|multiblocks\|recipes` | Re-read content |
| `/mineplus model info <key>` | Materials, occupancy, texel report |
| `/mineplus model models` | Loaded model keys |
| `/mineplus model debugspawn <key>` | Render a model at the block you look at |
| `/mineplus model cleanup` | Remove ghost display entities |
| `/mineplus pack status\|audit\|verify\|recompile` | Artifact, URL, SHA-1, conflicts |
| `/mineplus pack push <player>` | Force delivery |
| `/mineplus render stats` | Router policy and per-mode counts |
| `/mineplus perf [reset\|dump]` | Runtime telemetry |

Module test benches: `/mobfun spawn|fire|despawn|status`, `/mpgun reload`,
`/alchemy place|remove|clear|status`, `/wine ...`, `/cabinet ...`.

## 7. Hard integration rules

- Register pack definitions **before** the coordinated reload; registering after
  it leaves the assets unattached until the next `/mineplus pack recompile`.
- Never bind a pack block to a model key that does not exist yet
  (`<typeId>_lvl_<level>` is created lazily at first render; use
  `geometryModelKey` for the file).
- Pack item and block texture stems are global pack keys. Two models sharing
  `texture.png` overwrite each other.
- Keep `models/` paths stable; active instances store the derived type/level key.
- A pack block carrier must render with the client's `MODEL` render type —
  `note_block` is the shipped carrier; `light`, `structure_void` and `barrier`
  can never carry a display.
- `PACK.ENABLED: false` must leave behaviour identical to the virtual path; treat
  pack-only rendering as an enhancement, never a requirement.
