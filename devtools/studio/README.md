# Mineplus Studio

One dev tool for **everything the plugin renders — models, mobs, items,
blocks, textures, texel baking and the shipped pack — verified before going
in game**: every asset runs through the *same pipeline classes the server
uses* and renders in a browser viewport with the *same math the runtime
applies*, so what you validate here is what the client will show. A terminal
CLI drives the same checks for **AI agents and CI**, with machine-readable
output and exit codes.

This is the merge of the former `devtools/asset-lab` and `devtools/texel`
tools (the texel bake op was already ported into the daemon; the separate
tool and its port 5166 are gone).

## Run

```bash
node devtools/studio/server/server.mjs            # http://localhost:5167
```

Optional flags:

- `--port <n>` — HTTP port (default 5167)
- `--models <dir>` — **extra model/texture root to watch and serve**
  (repeatable: load any directory of models + PNGs without touching the
  repo). Defaults cover `examples/*/src/main/resources` and
  `plugins/Mineplus/{models,mobs}` (when present).
- `--settings <path>` — point the live settings bridge at one `settings.mp.yml`
- `--no-compile` — UI + settings only (`ASSET_LAB_SKIP_JAVAC=1`)
- `--reuse-classes` — boot the daemon from the last good `build/studio` output

Requirements: JDK 21 on PATH or `JAVA_HOME` (the server compiles the pipeline
+ daemon with `javac` at startup and on every source change), Node 18+. Zero
npm dependencies; three.js loads from unpkg (drop `three.module.js` + the
OrbitControls module into `app/vendor/` for fully offline runs). Guava and
SnakeYAML are staged automatically for the daemon's headless Bukkit classes.

## The app

| Tab | What it does | Through the real pipeline |
|---|---|---|
| **Assets** | unified browser over every watched root — block models, mob rigs/definitions, gun folders + frames, textures, pack artifacts; filter box; `j`/`k` walk; `?open=<kind>:<id>` deep links | routing only — opens the tool that owns the asset |
| **Mobs** | rig render (bones, pivots, hitbox, attachment markers), clips played with the server's exact sampler, **real `MobAnimator` mode** (engine-identical binding, crossfades, overlays, procedural walk), compile report | `BbModelParser` → `ModelBundleCompiler` → `PackModelEmitter` over a capturing `HeadlessPackApi` |
| **Items** | gun frame JSON validation + preview (BOM, texture chains, element window, rotation limits, UV rules, texture resolvability) | the exact file the pack ships |
| **Blocks** | importer's baked cube matrices, per-face UV windows, texture resolvability, origin/collision mode, clip list | `BbModelImporter` → `ModelMeta` → `TextureImageStore` |
| **Bake** | the texel surface baker, live: model picker, `.meta.json` aware settings (texel mode/detail, plate budgets, brightness, origin mode), per-bake history, emitted display list | `BbModelImporter` → `FaceUvAnalyzer` → `TexelSurfaceBaker` → `DisplayEmitter` |
| **Pack** | compiled `packs/mp-<hash>.zip` inspection (tree + entries) | the shipped artifact |
| **Textures** | pixel-exact PNG previews | — |
| **Audit** | everything above in one pass | all ops |

Workflow niceties: the server watches pipeline sources (hot recompile +
daemon restart on save; compile errors surface in the status bar) and asset
roots (inventory refresh + re-bake on asset save), and bakes are
generation-guarded so rapid model switching never renders one model's bake
under another's settings.

## Agent CLI (`agent.mjs`)

The terminal twin of the web app — AI agents and CI verify sections while
coding, with exit codes (`0` ok/repaired, `1` broken, `2` tool unavailable,
`3` usage) and `--json` for the raw op response.

```bash
node devtools/studio/agent.mjs status               # daemon + compile + settings health
node devtools/studio/agent.mjs list [kind]          # inventory: block|mob|mobdef|item|texture|pack
node devtools/studio/agent.mjs audit                # whole-repo audit pass (exit 1 on any BROKEN)
node devtools/studio/agent.mjs verify hawk_eye      # one asset, right op auto-picked
node devtools/studio/agent.mjs block alchemy-table  # bake + display list for one model
node devtools/studio/agent.mjs item frontier_eye    # all frames of one gun
node devtools/studio/agent.mjs mob evil_pig --json  # mob definition/rig verdict
node devtools/studio/agent.mjs poses soldier --duration 2 --attack
node devtools/studio/agent.mjs pack                 # compiled pack artifact check
node devtools/studio/agent.mjs wait                 # block until the daemon is up
```

`verify <key>` is the command to run after editing an asset or pipeline code:
it picks the right op from the inventory and answers with the same
`OK / REPAIRED / BROKEN` verdict the UI shows. Every command goes through the
same daemon ops the UI uses, so an agent verdict **is** the web verdict.
`--base http://host:port` (or `STUDIO_URL`) targets a remote studio.

## Architecture

```
devtools/studio/
├── agent.mjs                            # agent/CI CLI (same ops, exit codes, --json)
├── java/com/mineplus/devtools/studio/   # daemon (own JVM, line-JSON over stdio)
│   ├── StudioDaemon.java                #   op dispatch: ping/block/mob/poses/item/bake/pack/audit
│   ├── MobOps.java                      #   mob yml/model → parse → compile → rig JSON
│   ├── PoseOps.java                     #   real MobAnimator scripted-timeline pose track
│   ├── ItemOps.java                     #   raw item model validation + preview JSON
│   ├── BlockOps.java                    #   block import + textures + clips
│   ├── BakeOps.java                     #   texel bake + emitted display list
│   ├── PackOps.java                     #   pack artifact inspection
│   ├── AuditOps.java                    #   batch audit
│   └── HeadlessPackApi.java             #   capturing PackApi (no Bukkit server)
├── java-stubs/net/kyori/examination/    # linkage stubs for headless paper-api
├── server/server.mjs                    # Node dev server: compile, daemon lifecycle,
│                                        # inventory, op proxies, SSE, /api/asset
│   + settings-bridge.mjs                # live settings.mp.yml lighting policy
└── app/                                 # browser UI (three.js, no build step)
```

The daemon runs the compiled production classes from `src/main/java` directly
— zero production-code modifications, so render parity with the server is
structural, not maintained by discipline. The server watches sources and
asset roots, and proxies ops with per-root path guarding.

Watched roots (extra `--models` dirs are appended to the block roots):

- **blocks**: `examples/mineplus-fun/.../defaults/models`, `plugins/Mineplus/models`
- **mobs**: `examples/mineplus-mobfun/.../mobs`, `plugins/Mineplus/mobs`,
  `src/main/resources/mobs` (core examples)
- **items**: `examples/mineplus-gun/.../defaults/guns` (per-gun frame models,
  bbmodel sources and PNGs)

## HTTP API (for scripting)

- `GET  /api/inventory` — everything scannable, per category
- `GET  /api/models` — block-model roots + per-root model lists
- `POST /api/block` `{model}` · `POST /api/mob` `{yml|model[,texture]}`
- `POST /api/mob-poses` `{yml|model, preset|timeline}` — real-`MobAnimator`
  pose track (`preset`: `full|attack|hurt|death`, or a custom
  `timeline: [{duration, speed (blocks/tick), events: [attack|hurt|death]}]`)
- `POST /api/item` `{model[,textureRoot]}` — textureRoot defaults to the gun folder
- `POST /api/bake` `{model[,textureRoot,detail,mode,maxPlatesPerFace,…]}` —
  texel bake + emitted display list
- `POST /api/pack` `{file?,tree?,entry?}` — pack artifact inspection
- `POST /api/audit` `{blocks?,mobs?,mobModels?,items?}` — empty body = all roots
- `GET  /api/asset?p=<path>` — PNGs, restricted to the watched roots
- `POST /api/recompile` · `GET /api/events` (SSE: `code`, `daemon`, `assets`, `daemon-log`)
- `GET  /api/health` — daemon/compile/settings snapshot (what `status` prints)

Prefer the CLI (`agent.mjs`) over raw HTTP: it resolves names to watched
paths, aggregates gun frame verdicts, and maps results to exit codes.
