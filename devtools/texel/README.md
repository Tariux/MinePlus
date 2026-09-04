# Texel Hot-Reload Devtool

A standalone web environment for iterating on the texel surface baking pipeline
**without restarting a Minecraft server**. The tool runs the *actual compiled
pipeline classes* (`BbModelImporter`, `FaceUvAnalyzer`, `TexelSurfaceBaker`, …)
in a headless JVM daemon — render parity with the server is structural, not
maintained by discipline.

## Run

```bash
node devtools/texel/server/server.mjs            # http://localhost:5166
```

Optional flags:

- `--port <n>` — HTTP port (default 5166)
- `--models <dir>` — extra model root to watch/serve (repeatable). Defaults to
  `examples/mineplus-fun/src/main/resources/defaults/models` and
  `plugins/Mineplus/models` (when present).

Requirements: JDK 21 (`javac`/`java` on PATH), Node 18+. Zero npm dependencies;
the page pulls three.js from unpkg (internet required) — to run fully offline,
drop `three.module.js` + the OrbitControls module into `app/vendor/` and adjust
the import map in `app/index.html`. The daemon additionally needs a guava jar
at runtime (paper-api's `Material` static initializer links against it); the
server auto-stages one from the local Gradle cache, or you can drop
`guava-*.jar` into `devtools/texel/server/vendor/`.

## Workflow

1. **Pick a model** in the left panel — it bakes immediately (selection is the
   trigger; the Bake button is only a manual re-run).
2. **Per-model config loads automatically**: the model's `.meta.json` (reported
   by the daemon as `meta`, with a `meta` badge next to each affected setting)
   becomes the panel defaults; models without a meta file fall back to the
   standard defaults (AUTO modes, 96/150 plate budgets).
   Fields you edit afterwards are sent as explicit overrides and win over the
   meta file for that model until you select another.
3. **Tweak settings** (texel mode/detail, budgets, origin mode) —
   every change re-bakes through the daemon in milliseconds.
4. **Edit pipeline Java** (`src/main/java/com/mineplus/infrastructure/virtual/...`)
   and save — the server recompiles, restarts the daemon, and the page re-bakes
   automatically. Compile errors show up in the diagnostics panel instead of a
   silent stale build.
5. **Edit assets** (`.bbmodel`, `.png`, `.meta.json`) in a watched model root —
   the page re-bakes on save.

Bakes are generation-guarded: selecting a new model while a bake is running
queues the latest request and drops superseded responses, so rapid switching can
never render one model's bake under another's settings.

Layers: **Reference** shows the source-textured cubes (the goal), **Texel
plates** shows the merged palette-quantized plate reconstruction (what the
server would spawn). The diagnostics panel mirrors `/mineplus model info`
(strategy, plate counts vs. budgets, grid histogram, palette usage).

**View** (right panel) is exclusive: Reference / Texel plates are
alternative views of the same model — radios, not checkboxes, because showing
the textured reference and the palette reconstruction at once reads as two
blended, misaligned textures. Plate wireframes are a sub-option of the texel
view.

Fidelity notes: all three layers share one geometric convention — cube display
matrices are the pipeline's `T·R·S·R'` over the unit box `[0..1]³` (matching
`OccluderSet`/`DisplayEmitter`), reference UVs are derived per-vertex from the
pipeline's face-axis table (`CubeFace.uAxis/vAxis/normalAxis`, including
90°/180°/270° in-plane rotations and alpha cutout), and plate offsets are
constant world-space epsilons divided by the normal-axis scale — the same
`PLATE_SURFACE_OFFSET_BLOCKS / normalScale` math the baker's occlusion probes
use. Plate placement is machine-verified: every plate's world AABB lies within
its cube's face bounds at the exact expected offset. Missing textures or
degenerate UV windows render neutral gray with a "Texture not found" console
warning; degenerate flat cubes (zero normal scale, e.g. decal layers) collapse
to zero-thickness planes exactly as the pipeline's own math produces.

## Architecture

```
browser (app/)                dev server (server.mjs)              bake daemon (JVM)
┌───────────────┐   HTTP/SSE  ┌──────────────────────┐   stdio JSON ┌──────────────┐
│ three.js view │◄───────────►│ static + API proxy   │◄────────────►│ BakeDaemon   │
│ settings UI   │             │ javac watcher        │              │ runs REAL    │
│ diagnostics   │             │ daemon lifecycle     │              │ pipeline     │
└───────────────┘             └──────────────────────┘              │ classes      │
                                                                    └──────────────┘
```

- **`java/com/mineplus/devtools/texel/BakeDaemon.java`** — line-delimited JSON
  over stdin/stdout; one `bake` request runs import → texture scan → texel bake
  and returns the full plan (cubes, per-face plates, diagnostics, palette). Logs
  go to stderr only.
- **`java-stubs/`** — linkage stubs (`net.kyori.examination`) needed to load
  paper-api's `Material` headlessly; never invoked.
- **`server/server.mjs`** — compiles `src/main/java` + daemon with `javac`,
  owns the daemon process (restart on crash/recompile), watches sources and
  asset roots, serves the app and the API (`/api/bake`, `/api/models`,
  `/api/asset`, `/api/events` SSE, `/api/recompile`).
- **`app/`** — ES-module browser app; plate geometry is computed with the
  same `T·R·S·R` display-matrix composition and face-axis conventions as
  `DisplayEmitter` (see `js/geom.js`).

Hot-reload latency: asset or setting change < 100 ms; Java change =
compile + daemon restart (a few seconds) — versus a full server relaunch.
