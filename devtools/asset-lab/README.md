# Asset Lab

A dev tool to **check mobs, items and blocks — render, model, texture and
mechanism — before going in game**: every asset in the plugin's default
resources runs through the *same pipeline classes the server uses* and renders
in a browser viewport with the *same math the runtime applies*, so what you
validate here is what the client will show.

This tool is **fully separate from the texel devtool** (`devtools/texel`):
its own directory, its own daemon (`AssetDaemon` vs `BakeDaemon`), its own
build outputs (`build/asset-lab/` vs `build/devtool/`), its own port
(**5167** vs 5166). Both can run side by side; texel owns surface baking,
Asset Lab owns presentation validation across all three asset kinds.

## Run

```bash
node devtools/asset-lab/server/server.mjs            # http://localhost:5167
```

Requirements:

- **JDK 21** on the PATH or at `JAVA_HOME` (the server compiles the pipeline +
  daemon with `javac` at startup and on every source change).
- The repo's `libs/` jars (already present). Guava is staged automatically for
  the daemon's headless Bukkit classes (Gradle cache → `server/vendor/guava.jar`).

No npm dependencies. If `javac` is missing the server still serves the UI and
reports the failure in the status bar; the ops stay disabled until it compiles.

## What it checks

| Tab | Inputs | Through the real pipeline | Verdict |
|---|---|---|---|
| **Mobs** | mob `.yml` definitions, standalone `.bbmodel` models | `BbModelParser` / `JavaJsonParser` → `ModelBundleCompiler` → `PackModelEmitter` over a capturing `HeadlessPackApi` | OK / REPAIRED / BROKEN + renderable rig |
| **Items** | gun frame JSONs (raw Java block/item models) | the exact file the pack ships, validated against the client's constraints | OK / REPAIRED / BROKEN + renderable preview |
| **Blocks** | virtual block `.bbmodel`s (+ gun bbmodel sources) | `BbModelImporter` → `ModelMeta` → `TextureImageStore` | OK / REPAIRED + renderable preview |
| **Textures** | every PNG under the watched roots | — | pixel-exact preview |
| **Audit** | everything above in one pass | all ops | tally + per-asset rows |

### Mobs

- The parsed rig (bones, pivots, rest rotations, per-face UV windows) renders
  live; **clips play in the browser** with the server's exact sampler
  (linear/step/catmullrom, loop modes, additive rest+clip Euler `x→y→z`,
  yaw frame `180 − bodyYaw`, head look ±75° on the last `HEAD` bone) — scrub,
  body-yaw, head-pitch and head-yaw sliders let you pose any frame of any clip.
- **Real animator mode** — the *actual* `MobAnimator` the engine ticks
  (constructed exactly like `MobRenderEngine` does it: model clips + the
  definition's `animations:` aliases + `leg`/`arm` bone detection) runs in the
  daemon through a scripted timeline at the engine's 20 Hz tick, and the
  browser plays its per-tick pose output. This exercises the plugin's real
  clip binding, idle/walk/run crossfades, attack/hurt overlays, death hold and
  the procedural leg/arm swing fallback — presets: full movement cycle,
  attack, hurt, death. The state readout shows the animator's own summary
  (`idle`, `walk+attack`, …) per frame.
- The rig viewport overlays the spec's **hitbox** (amber wire box) and colors
  the **attachment markers**: orange = `HAND_R`, blue = `HAND_L`, violet =
  `ITEM` — so hand-item mounts and head look are checkable before spawning
  anything in game.
- Compile report: bone budget (48 max), piece splits with offsets, pack asset
  counts, display estimate per mob, unbound `animations:` aliases.
- Textures embedded in the file (or the sibling PNG) preview inline;
  unresolved textures are flagged instead of failing the load.
- `retexture_vanilla` definitions validate the PNG against the vanilla layout
  (e.g. a pig must be 64×32) and the variant registry for the base entity
  (needs a 1.21.5+ server).
- Definitions registered purely in module code (e.g. MobFun's showcases) are
  not yml files — their `.bbmodel` + PNG can still be opened as *mob models*
  to validate the exact assets they ship.

### Items

Validates the **raw JSON**, not a re-parse, because the pack ships it verbatim:

- UTF-8 BOM (the client rejects the file outright),
- `#key` texture reference chains (≤ 8 hops, like the client),
- element window −16..32 px (outside it the client clamps and the cube
  collapses into a missing-texture box),
- element rotation angles restricted to −45/−22.5/0/22.5/45,
- face UVs inside the 0..16 units, face rotations in multiples of 90°,
- every referenced PNG resolvable on disk (magenta-checkerboard guard).

The preview renders element rotation groups and in-plane UV rotations, and
missing textures show as magenta — exactly the failure the client would show.

### Blocks

Static render of the importer's baked cube matrices with per-face UV windows,
texture resolvability (actual PNGs), the resolved origin mode (`AUTO` →
`CENTER`/`GRID`), collision mode, autoplay clips and the animation clip list.
No texel baking — surface baking belongs to the texel tool.

## Architecture

```
devtools/asset-lab/
├── java/com/mineplus/devtools/assetlab/   # daemon (own JVM, line-JSON over stdio)
│   ├── AssetDaemon.java                   #   op dispatch: ping/block/mob/poses/item/audit
│   ├── MobOps.java                        #   mob yml/model → parse → compile → rig JSON
│   ├── PoseOps.java                       #   real MobAnimator scripted-timeline pose track
│   ├── ItemOps.java                       #   raw item model validation + preview JSON
│   ├── BlockOps.java                      #   block import + textures + clips
│   ├── AuditOps.java                      #   batch audit
│   └── HeadlessPackApi.java               #   capturing PackApi (no Bukkit server)
├── java-stubs/net/kyori/examination/      # linkage stubs for headless paper-api
├── server/server.mjs                      # Node dev server: compile, daemon lifecycle,
│                                          # inventory, op proxies, SSE, /api/asset
└── app/                                   # browser UI (three.js, no build step)
```

The daemon runs the compiled production classes from `src/main/java` directly
— zero production-code modifications, so render parity with the server is
structural, not maintained by discipline. The server watches sources (hot
recompile + daemon restart) and asset roots (inventory refresh), and proxies
ops with per-root path guarding.

Watched roots:

- **blocks**: `examples/mineplus-fun/.../defaults/models`, `plugins/Mineplus/models`
- **mobs**: `examples/mineplus-mobfun/.../mobs`, `plugins/Mineplus/mobs`,
  `src/main/resources/mobs` (core examples)
- **items**: `examples/mineplus-gun/.../defaults/guns` (per-gun frame models,
  bbmodel sources and PNGs)

## HTTP API (for scripting)

- `GET  /api/inventory` — everything scannable, per category
- `POST /api/block` `{model}` · `POST /api/mob` `{yml|model[,texture]}`
- `POST /api/mob-poses` `{yml|model, preset|timeline}` — real-`MobAnimator`
  pose track (`preset`: `full|attack|hurt|death`, or a custom
  `timeline: [{duration, speed (blocks/tick), events: [attack|hurt|death]}]`)
- `POST /api/item` `{model[,textureRoot]}` — textureRoot defaults to the gun folder
- `POST /api/audit` `{blocks?,mobs?,mobModels?,items?}` — empty body = all roots
- `GET  /api/asset?p=<path>` — PNGs, restricted to the watched roots
- `POST /api/recompile` · `GET /api/events` (SSE: `code`, `daemon`, `assets`, `daemon-log`)
