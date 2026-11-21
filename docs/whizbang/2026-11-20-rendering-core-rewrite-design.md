# Rendering Core Rewrite — Design

## Intent & Why

Custom mobs do not load their models correctly, complicated Blockbench models
parse badly or partially, and animations frequently do not play. The failure is
not one regression: `main` and `compat-26` differ only in version-compat seams
(verified by diff — the render engine, emitter and compiler changes on
compat-26 are safe API swaps), so the root causes are structural and live on
both branches. The user chose a **rendering core rewrite**: rebuild the broken
seams from first principles on top of compat-26's architecture, with surgical
fixes for items/blocks. Multiblocks, texel baking and pack delivery are healthy
and stay untouched.

## Goals / Non-Goals / Anti-Goals

- **Goal:** every registered custom mob presents its authored model, or fails
  loudly with a precise reason on `/mineplus mob inspect`.
- **Goal:** one Blockbench pipeline: a single parser contract shared by the
  custom-mob axis and (where practical) the virtual/multiblock axis.
- **Goal:** animations play when authored: walk/idle base layer, attack/hurt/
  death overlays, correct bone binding for classic and 5.0 `.bbmodel` files.
- **Goal:** deterministic, diagnosable asset emission — no silent empty bundles.
- **Non-goal:** rewriting item/block loading architecture, multiblock lifecycle,
  texel baking, pack delivery/delivery modes, mob behavior engine.
- **Non-goal:** client resource-pack requirement — server-side only stays.
- **Anti-goal:** silently degrading to generic cube/base entity without a
  logged, inspectable reason (today's dominant failure mode).
- **Anti-goal:** adding a third Blockbench parser.

## Constraints

- Compile against the committed **Spigot 1.21 stub jar only**; Paper-only APIs
  resolve reflectively at runtime (project rule, see AGENTS.md).
- Target 1.21.0 → 26.3 on Spigot/Paper/Folia via `ServerVersion`,
  `PackFormat`, `SchedulerCompat` seams — no second copy of any probe.
- Keep the `PackApi` / `InfrastructureApi` module-facing signatures stable;
  example modules must compile unchanged except where the plan says otherwise.
- Pack hash determinism must be preserved (same input → same bytes).

## Root Causes (evidence)

| # | Cause | Evidence |
|---|-------|----------|
| R1 | **Silent pack-unavailable emission.** `PackModelEmitter.emit` returns an empty result when the pack is not yet available (PackModelEmitter.java:104) at registration time (during `onEnable`, before the pack compiles). The broken bundle is cached in `Entry` and never re-attempted until a manual reload — mob spawns get no rig or the generic cube. | PackModelEmitter.java:104-106; MobRenderEngine.java:277-305, 491-512 |
| R2 | **Legacy-server null stack rollbacks.** On pre-1.21.4 servers `createModelDisplay` returns null unless a legacy CMD predicate was reserved (PackItemFactory.java:107-110); one missing piece rolls the *entire* rig back (MobRenderEngine.java:1594-1604) instead of degrading per-piece. | PackItemFactory.java:107; MobRenderEngine.java:1588-1604 |
| R3 | **Window-split ignores inflate.** `split()` bounds are computed from raw cube from/to (PackModelEmitter.java:207-270) but `element()` adds `inflate` afterwards (PackModelEmitter.java:333-338) — cubes near the ±16/32 px window edge overflow and clip. Complicated models with inflated parts are exactly the ones that break. | PackModelEmitter.java:207, 333 |
| R4 | **`simplify()` stale volumes.** Merge volumes computed once (ModelBundleCompiler.java:104-113), never updated as cubes merge — large models reduce the wrong bones first. | ModelBundleCompiler.java:115-146 |
| R5 | **Two divergent Blockbench pipelines.** `BbModelParser` (mob axis) vs `BbModelImporter` (virtual axis) implement overlapping but different feature sets; features supported by one are dropped by the other, so "complicated" models hit whichever parser lacks the feature. Consolidation target: shared geometry/UV/animation extraction, axis-specific tail. | infrastructure/mob/custom/model/BbModelParser.java; infrastructure/virtual/BbModelImporter.java |
| R6 | **Animation binding gaps.** Leg/arm bones detected by `name.contains("leg"/"arm")` (MobRenderEngine.java:1539-1543); death clip never plays from its start (`markDead` never resets the clock, MobAnimator.java:207-249 → playhead pinned to last frame); missing/zero clip `length` freezes every clip at keyframe 0 (ModelAnimation.java:173-182); zero-thickness cubes (flat plates — wings, fins, detail geometry) silently dropped (BbModelParser.java:384-386); box-UV `down` face window one band low (BbModelParser.java:420); per-face UVs scaled by PNG size instead of project `resolution` (BbModelParser.java:439-472, resolution read but unused at :116-120); 48-bone `simplify` silently drops merged bones' rest/synthetic rotations (ModelBundleCompiler.java:115-145); rig samples `Curve.sample` (holds first key before first keyframe) while the baker uses `sampleFromRest` (MobAnimator.java:336 vs BbClipBaker.java:239). Full ranked fix list (16 items, animation explorer report): see Phase 2/3 task tables. | BbModelParser.java; MobAnimator.java; ModelAnimation.java; ModelBundleCompiler.java |
| R6b | **Virtual axis 5.0 gap (the multiblock/Gear animation killer).** `BbModelImporter` never reads the 5.0 root `groups` array (BbModelImporter.java:100-110, 365-398) → every bone pivot (0,0,0), group transforms baked identity, animations pivot about the model origin and explode into arcs. Also: box UV unsupported, classic per-channel animation dialect unread (BbModelImporter.java:425-453), catmullrom/bezier flattened to lerp, interpolation applied one keyframe late (`next` vs `prev`, AnimationEvaluator.java:175 vs ModelAnimation.java:97). The two axes fail complementarily — exactly the "some of them" symptom. | BbModelImporter.java; AnimationEvaluator.java; KeyframeInterpolation.java |
| R7 | **Diagnosability.** `logOnce` keys collapse per-mob failures into one line; `/mineplus mob inspect` shows the last status string, not the emission/parse history. Operators cannot tell R1 from R2 from R3. | MobRenderEngine.java:402-413, 1016-1037 |
| R8 | **YAML-mob ownership.** `MobYamlLoader` registers under the Core plugin (MobYamlLoader.java:149), so `unregisterAll(module)` cannot manage YAML mobs and reload ordering with modules is fragile. | MobYamlLoader.java:149 |
| R9 | **Module assets untracked.** `.gitignore` `**.png` (line 35) excludes every module texture; MobFun reads mob textures/models from its own jar (`loadResource("mobs/evil_pig.png")`, MobFunPlugin.java:247, guarded by `hasBoth`, MobFunPlugin.java:339-348). Files exist on this machine only — a fresh clone/CI build ships jars with **no** textures, so all three custom-mob showcases silently never register. Gun sounds genuinely absent (`defaults/sounds/mineplusgun/PLACEHOLDER.md` only) while `sounds.json` references ~15 samples → compiled pack points at nonexistent `.ogg`s. | .gitignore:35; MobFunPlugin.java:247; WeaponAssets.java:189,259 |
| R10 | **Silent asset-install degradation.** `ModuleSupport.installDefault` logs "Missing embedded resource" and continues (ModuleSupport.java:72-73); `WeaponAssets.origin` returns null and "reports zero assets rather than failing" (WeaponAssets.java:341-356); `ModuleSupport.registerCommand` swallows label collisions (ModuleSupport.java:170-172). A module can appear enabled with zero content. | ModuleSupport.java:72,170; WeaponAssets.java:341 |
| R11 | **Doc drift.** `DEVELOPMENT_PROMPT.md` teaches the legacy `renderBackend`/`renderKind` pair while shipped JSON uses `renderMode: "pack_block"` (cabinet_pack.json:10); references `mineplus-1.0.0.jar` vs actual `mineplus-1.3.0.jar` (build.gradle:19); claims a module-local `ModuleFeature` that doesn't exist (§7). | DEVELOPMENT_PROMPT.md §7,§10b,§12 |
| R12 | **Enable-path isolation gaps.** Neither MineplusFun (MineplusFunPlugin.java:75-89) nor MineplusGun (MineplusGunPlugin.java:92-104) guards the coordinated `reloadAll()`/command loop; `ModuleFeature.start()` logs exceptions without stack traces (ModuleFeature.java:64-65); MobFun wraps manually — inconsistent across modules. MobFun bypasses `ModuleFeature` entirely (imitation, not contract). | MineplusFunPlugin.java:75; ModuleFeature.java:64 |

## Approaches Considered

| Approach | Trade-offs | Decision |
|----------|------------|----------|
| A. Revert to main + patch | Fastest to "a little bit works"; keeps both parsers, keeps silent-failure modes; per-bug patches re-fight the same war | Rejected: user chose rewrite; structural debt stays |
| B. Full-tree rewrite (Core + modules) | Cleanest; but multiblocks/texel/pack delivery are healthy — re-breaks what works; months of regression risk | Rejected: user picked rendering core scope |
| C. **Rendering core rewrite in place** — rebuild emission, parsing, animation binding and session lifecycle behind the existing seams; surgical fixes elsewhere | Keeps healthy subsystems; each phase compiles and is testable on a live server; the API surface modules see stays stable | **Chosen** |

## Chosen Design

### Phase 0 — Baseline & harness (gate for everything)
- **Asset untracking (R9, first commit of the branch):** narrow .gitignore
  exceptions for module-shipped assets (`!examples/**/src/main/resources/**`)
  so built jars carry textures/models on every machine; document that sound
  `.ogg`s are absent and make `sounds.json` references conditional on staged
  samples (or ship a generated placeholder so the pack never points at
  nothing).
- Build current `compat-26` HEAD; record `/mineplus mob inspect`, `cost`,
  and pack `status` output for the three shipped rigs (Evil Pig, Villager
  Soldier, Woodland Trickster) on a 1.21.x Paper test server.
- Add `CustomMobPipelineTest` coverage for: emitter window math (with
  inflate), simplify volume monotonicity, parse-and-emit round trip of the
  three example models.
- **Exit:** tests red where R3/R4 apply; build green; a fresh-clone jar
  contains the mob assets.

### Phase 1 — Emission correctness (R1, R2, R3, R4)
- `PackModelEmitter`: inflate-aware window bounds (R3); deterministic
  post-merge volume update in `ModelBundleCompiler.simplify` (R4).
- Deferred-until-ready emission: `Entry` gains a `needsCompile` state;
  `MobRenderEngine` retries `rebuildAssets` when the pack reports available
  (hook the existing pack recompile callback) instead of caching an empty
  bundle (R1).
- Per-piece degradation: one unresolvable piece logs and renders as its
  bone's dominant color cube instead of rolling back the whole rig (R2).
- **Exit:** all Phase-0 tests green; live server: all three rigs present.

### Phase 2 — Unified Blockbench pipeline (R5, R6b)
- Extract a shared `BbModelCore` (geometry extraction, box/per-face UV,
  embedded-vs-sibling texture resolution, outliner graph, pivot conjugation)
  used by both parsers; keep `BbModelImporter`'s streaming wrapper and
  `BbModelParser`'s rig-specific tail (clips, roles).
- **BbModelImporter 5.0 fix (highest leverage):** read the root `groups`
  array in the streaming pass and join bare nodes by uuid, mirroring the mob
  parser's `indexGroups` — restores pivots and group transforms for current
  Blockbench exports on the multiblock/Gear path.
- Shared classic per-channel animation dialect in both parsers; shared
  `length = maxTime` fallback; unified interpolation semantics (segment-start
  key governs; Catmull-Rom implemented once, not flattened to lerp).
- Mob parser repairs in the same phase: zero-thickness cubes accepted (or
  issue-logged), box-UV `down` window corrected, per-face UV scale from
  project `resolution`, face `rotation` carried through to emission, element
  uuid→`_rotN` sub-bone binding, visibility/export honored, multi-file
  sibling-texture resolver, issue records for every silent skip.
- Feature matrix test: rotated elements, negative coords, 5.0 uuid joins,
  embedded textures, per-face UV, resolution field, zero-thickness cubes,
  per-channel clips — one test model per feature, both parsers must agree on
  the shared subset.
- **Exit:** feature matrix green; complicated user models that previously
  failed parse to identical geometry on both axes.

### Phase 3 — Animation correctness (R6)
- Death clip: `markDead()` records the start time; sampled with
  `wrap(time - deathStart)` so it plays from frame 0.
- Clip length fallback `maxTime` shared with the importer; `wrap` never
  returns a frozen 0.
- Rig switches to `sampleFromRest` (baker + Blockbench semantics); per-state
  playheads so idle↔walk crossfade does not pop; scale channel carries y/z.
- Bone role resolution from `MobPart.Role`/`ModelBone.attachment()` first,
  raw-name matching (pre-sanitize) as fallback.
- 48-bone cap: merged bones' rotations baked into re-based cubes instead of
  silently dropped; SIMPLIFIED degradation surfaced in inspect output.
- Clip binding verified per rig via `poseSnapshot`; overlays (attack/hurt/
  eat/ambient/sit) never restart the base layer.
- **Exit:** all authored clips of the three example rigs play on cue,
  including death-from-frame-0 on a rig whose only clip is the death clip.

### Phase 4 — Diagnosability & ownership (R7, R8, R10, R12)
- `Entry` carries a structured `PresentationLog` (parse, emit, compile,
  per-piece resolution) printed by `/mineplus mob inspect`.
- `MobYamlLoader` gains an owner plugin parameter; Core YAML mobs get their
  own owner token so reload/unregister semantics are explicit (R8).
- Loud asset installs (R10): `installDefault` and jar-resource loads fail the
  feature (not the plugin) with the missing path in the message;
  `registerCommand` collisions logged severe; `WeaponAssets.origin` null is a
  hard enable failure.
- Enable-path guard (R12): `reloadAll()` and the command loop wrapped and
  reported; `ModuleFeature.start/stop` log stack traces; MobFun migrated onto
  `ModuleFeature` (it already replicates the semantics manually).
- **Exit:** every failure class R1–R4 and R9–R10 produces a distinct,
  greppable inspect/enable line.

### Phase 5 — Docs & examples pass
- Reconcile `DEVELOPMENT_PROMPT.md` drift (R11): `renderMode: pack_block` as
  the canonical spelling, real jar version, Core `ModuleFeature` reference.
- Update `docs/custom-mobs.md`, `extension-workflows.md` (model authoring
  constraints: window, inflate, texture layout; the R9 asset-shipping rule).
- Defer-to-later (explicit non-goals this branch): generalizing the gun
  module's jar-discovery seam to the Core, Core `economy()` seam, hash-reconcile
  for deployed JSON configs — recorded here so they are not lost.
- Example modules compile unchanged (or with the documented owner-parameter
  change only).

## Verification plan
- Unit: Phase-0 harness grown each phase (window math, simplify, feature
  matrix, clip binding).
- Live: 1.21.x Paper server; per-phase rig regression script: place →
  `/mobfun spawn evilpig|soldier|trickster` → inspect → walk/attack/death
  observation checklist.
- Pack determinism: compile twice, hash equal.
- Compile gate after every phase: `gradlew build` against Spigot-only stubs.

## What is left to the builder (curly execution)
- Exact insertion points for the deferred-compile hook (PackSystem
  recompile callback → MobRenderEngine.refresh).
- Molang strategy: constant-foldable subset (`q.anim_time` linear,
  `math.sin/cos` of it) vs per-clip unsupported-key reports — decide on the
  example rigs' actual clip contents.
- Deferred Core-seam absorptions recorded in Phase 5 (jar-discovery seam,
  economy seam, JSON hash-reconcile) — separate branch after this one ships.

## Explorer reports (evidence base)
- Item/block loading + example modules report (this design, R1–R12).
- Animation/parser root-cause report: 16 ranked fixes, ranked #1 = importer
  5.0 `groups` gap, #2 = death-clip clock, #3 = length fallback, #4 = 48-bone
  rotation drop; fold-in targets mapped to Phases 2–3 above.
