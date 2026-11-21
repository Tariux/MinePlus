# Item Animations, Triggers & Pack-Block Correctness — Implementation Plan

> **For agentic workers:** Use whizbang-execute. Steps use `- [ ]` syntax.

**Goal:** Make every bbmodel class (items with animations, oversized pack blocks, mobs) render
correctly, add separate-animation-file loading, declarative triggers, vanilla-parent item
inheritance, and smooth mob animation — then sweep all docs.

**Architecture:** Fix the two broken writers at their seams (`BbClipBaker` for items,
`ModelJsonWriter`+`PackBlockRenderer` for pack blocks), add merge/trigger/inheritance seams in
the existing registries, and keep compiled artifacts deterministic.

**Tech Stack:** Java 21, Spigot 1.21 stubs, Gson streaming, JDK only.

**Spec:** `docs/whizbang/2026-11-21-item-anim-multiblock-production-design.md`

## Global Constraints
- Compile-time API ≤ Spigot 1.21 stubs; no Paper-only links.
- Deterministic pack artifacts; content hashing unchanged in shape.
- No gradle build / no test runs permitted this pass (user constraint). Verification = read-back
  + signature consistency + grep sweeps. Every "Run test" step below becomes "Read-back check".

## Review Focus
1. UV-mode auto-detection misclassifying a ≤16-resolution model (must never scale when resolution is 16).
2. Uniform fit `k` breaking collision parity (guard must not be applied to collision lattice — it never was; renderer scale must be about the same pivot).
3. `withAnimations` merge ordering (later file wins) and animator name resolution fallback.
4. Trigger map overriding autoplay (priority must stay above autoplay but below explicit `playAnimation`).
5. Parent/texture-override items must not emit geometry elements or stray texture vars.

### Task 1: BbClipBaker — UV scaling, multi-texture map, display whitelist, field hygiene
**Files:** Modify `src/main/java/com/mineplus/pack/item/BbClipBaker.java`
- [x] Scale UVs by 16/resolution (project-UV auto-detect), emit full textures map, filter display contexts, drop `texture_size`/`format_version`.
**Read-back:** grep `texture_size` gone; textures map built from parsed names; uScale used in `element`.

### Task 2: Example gun asset hygiene
**Files:** Modify `examples/mineplus-gun/src/main/java/com/mineplus/gun/asset/WeaponBbModels.java`, `WeaponAssets.java`
- [x] Display contexts whitelisted in DEFAULT_DISPLAY; sanitizeTextures maps faces instead of deleting.
**Read-back:** no `on_shelf`; no face deletion path.

### Task 3: Item inheritance (parent + texture override)
**Files:** Modify `pack/block/PackItemDefinition.java` wait — correct path `pack/PackItemDefinition.java` (verify), `pack/compile/ItemModelWriter.java` / `ModelJsonWriter.java`, `pack/PackSystem.java`
- [x] `parentModel` + `textureOverride` on definition; minimal model json emission; texture registration.
**Read-back:** compiled json for parented item = `{"parent":…,"textures":{"layer0":…}}`.

### Task 4: Pack-block uniform fit + renderer scale parity
**Files:** Modify `pack/compile/ModelJsonWriter.java`, `pack/asset/PackBlockAsset.java`, `pack/render/PackBlockRenderer.java`, `pack/compile/PackCompiler.java`, `pack/compile/ModelGeometryGuard.java`
- [x] Uniform centering + baked scale k; `modelScale` metadata; renderer 1/k about anchor pivot; guard verification-only for blocks.
**Read-back:** no per-element translate/squash on block axis; scale compensation present in transformationFor.

### Task 5: Case-insensitive texture resolution
**Files:** Modify `infrastructure/virtual/TextureImageStore.java`
- [x] Lowercase scan of model dir/root once, cached per directory.
**Read-back:** `Alchemy_Texture.png` resolves for lookup name `alchemy_texture`.

### Task 6: `VirtualModel.withAnimations` + separate animation file loading
**Files:** Modify `infrastructure/virtual/VirtualModel.java`, `infrastructure/virtual/ModelImportCoordinator.java`, `infrastructure/virtual/BbModelImporter.java`
- [x] Copy helper; `*.anim.bbmodel` sibling scan; name-based animator resolution for animation-only files.
**Read-back:** merged clips land before `registerModel`; no silent animator drops.

### Task 7: Declarative triggers
**Files:** Modify `infrastructure/virtual/ModelMeta.java`, `infrastructure/virtual/animation/ModelAnimationManager.java`, `infrastructure/virtual/animation/AnimationInstanceBridge.java`, `core/api/AnimationApi.java`, `core/api/MineplusAnimationApi.java`
- [x] `triggers` map in meta; `fireTrigger`; `triggerEvent` API; priority above autoplay.
**Read-back:** event→clip resolution; ONCE playback with return-to-base.

### Task 8: Mob animation smoothing
**Files:** Modify `infrastructure/mob/custom/model/ModelAnimation.java`, `infrastructure/mob/custom/MobAnimation.java`, `infrastructure/mob/custom/MobRenderEngine.java`
- [x] Catmull-rom sampling when keyframes request it; epsilon-skip only on exact equality.
**Read-back:** sample() branches on interpolation; pose-skip epsilon exact.

### Task 9: Examples — alchemy meta + trigger demo
**Files:** Create `examples/mineplus-fun/src/main/resources/defaults/models/alchemy-table.meta.json`; wire one trigger in a feature (gear) if trivially possible.
- [x] Meta pins originMode CENTER; trigger example documented.

### Task 10: Docs sweep
**Files:** Modify `AGENTS.MD`, `README.md`, `CHANGELOG.md`, `docs/README.md`, `docs/pack-system.md`, `docs/developer-api.md`, `docs/config-reference.md`, `docs/extension-workflows.md`
- [x] All new seams documented (item inheritance, triggers, anim files, uniform fit, texture resolution).

## Execution Ledger (whizbang-execute, native inline)

- All 10 tasks executed; verification mode = read-back + signature consistency + grep sweeps
  (no gradle build / test run permitted this pass — user constraint). Compiler verification is
  residual risk.
- Ruling: `ModelMeta` record grew two components (`animationFile`, `triggers`); both constructor
  call sites updated.
- Ruling: 36 hand-authored gun frame JSONs sanitized at rest (non-vanilla display context `"n"`
  removed by script; generated-bake path whitelists contexts itself).
- Ruling: guard skip keyed on the block-axis zip path `/models/block/` — the only block-model
  entry convention the compiler emits.
- Deferred (minor): legacy `MobAnimation` curve remains linear-only (deprecated pipeline bridge,
  unused by bbmodel rigs); split-display rendering for oversized pack blocks rejected in design
  (uniform fit chosen).

### Resume pass (2026-11-21, follow-up)

- [x] Sanitized the remaining 8 hand-authored frame JSONs under
  `defaults/frames/*` (non-vanilla display context `"n"` removed) — the first
  pass covered only `defaults/guns/*/models`; `longshot.bbmodel` keeps its
  authored `on_shelf` (the virtual importer filters it; the baker whitelists).
- [x] Example docs updated: `mineplus-fun/README.md` (alchemy command table +
  trigger row + meta/fit notes), `mineplus-fun/DEVELOPMENT_PROMPT.md`
  (trigger subcommand + uniform fit), `mineplus-gun/README.md` (bake
  correctness paragraph), `examples/README.md` (module table).
- [x] `docs/migration-guide.md`: new-API table rows (triggerEvent, parent/
  texture inheritance, meta keys, animation files) + behavior notes
  (UV fix, uniform fit, case-insensitive textures).
- [x] `docs/config-reference.md`: animation section documents triggers +
  animation files.

### Claim-correctness pass (2026-11-21, follow-up 2)

- [x] Removed the plugin-wide "No resource packs / No downloads / 100% vanilla"
  claims: the resource-pack axis (self-compiled, self-hosted, auto-delivered
  packs with per-player accept/decline) is a core feature, so those claims were
  false. Every remaining "no pack" statement is now explicitly scoped to the
  virtual/texel axis, and the pack axis is described as what it is: opt-in
  per level/item, compiled + hosted + pushed automatically by the plugin, with
  graceful degradation for declined clients.
- Files corrected: `README.md` (hero line, Big Idea table, new axis explainer,
  pack bullet, Quick Start step 5), `docs/config-reference.md` (virtual-engine
  framing + UV-crop note), `docs/developer-api.md` (texel section + pack-axis
  pointer), `docs/README.md` (module hub line), `examples/mineplus-fun/
  README.md` + `DEVELOPMENT_PROMPT.md` (cannon/wine wording), `examples/
  mineplus-gun/README.md` (pack-delivery note in the intro), `AGENTS.MD`
  (wine wording), `CHANGELOG.md` (1.0/1.2 entries re-scoped to the virtual
  axis with a pointer to the later pack axis).
