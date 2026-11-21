# Advanced Item Models, Animation Triggers & Pack-Block Correctness Design

## Intent & Why
MinePlus renders Blockbench models through two axes (virtual display entities, resource pack).
Today: complicated bbmodel items (hawk, gun family) render black/purple; pack blocks wider than
one block (alchemy table) render scrambled/oversized with missing textures; there is no way to
load animations from a separate bbmodel, no declarative trigger map, and no vanilla-parent item
inheritance. This design closes those gaps so the core handles "all kinds of models" and is
production ready.

## Goals / Non-Goals / Anti-Goals
- Goal: any bbmodel item (multi-texture, any resolution, non-cube-safe) compiles into a correct pack item.
- Goal: clips → baked frames keep working (crossbow-style multi-model states).
- Goal: vanilla-parent inheritance (`parent` + single `layer0` texture) for derived items.
- Goal: animations loadable from a separate `.bbmodel` beside the model file, merged by bone name.
- Goal: declarative per-event animation triggers (`meta.json triggers` + `AnimationApi.triggerEvent`).
- Goal: pack blocks of any size render exactly as authored (uniform fit, no per-element scrambling), textures resolve case-insensitively.
- Goal: smoother custom-mob animation (catmull-rom sampling, robust interpolation window).
- Non-goal: mesh (non-cube) geometry support; client resource-pack UV cropping; live server-side display-driven item animation.
- Anti-goal: any change of default vanilla behavior for carriers, states, or items; breaking byte-compat of compiled artifacts beyond what correctness requires.

## Constraints
- Compile against committed Spigot 1.21 stubs only (AGENTS.MD rule). No Paper-only APIs.
- No second version probe; extend owning seams (`ServerVersion`, `PackFormat`, reflection chains).
- Pack artifacts must stay deterministic (fixed entry order, content hashing).
- No gradle builds / no runtime execution in this pass (verification = read-back + consistency checks).

## Approaches Considered
| Approach | Trade-offs | Decision |
|----------|------------|----------|
| A: Per-element guard fixes (keep squash, add UV compensation) | Preserves one-model-per-block but geometry stays scrambled for >1-block models | Rejected — fundamentally lossy |
| B: **Uniform model-space fit**: one shared translate + bake scale factor k into px, renderer compensates 1/k via display transform | Exact layout, UVs consistent, guard becomes a no-op for blocks | **Chosen** |
| C: Split oversized models into multiple BlockDisplays | Pixel-perfect at 1:1 but new entity axis, persistence/reconcile churn | Rejected this pass — B is lossless enough and far smaller |

For items:
| Approach | Trade-offs | Decision |
|----------|------------|----------|
| A: Fix BbClipBaker in place (scale UVs, full texture map, filter display, strip junk fields) | Smallest diff, keeps frames pipeline | **Chosen** |
| B: Re-route bbmodel items through ModelJsonWriter | Rewrites the frames axis | Rejected |

## Chosen Design

### 1. BbClipBaker correctness (item axis)
- Scale UVs by `16/resolution` exactly like `ModelJsonWriter` (`uScale=16/w`, `vScale=16/h`); auto-detect
  0..16 project-UV authoring (all UVs ≤ 16 while resolution > 16 → no scaling).
- Emit the full textures map (`"0".."N"` + `particle`) from the parsed bbmodel texture names via
  `TextureAsset.normalizePath`; faces reference `#<index>`; register every texture through the
  existing `registerModelTextures` seam (WeaponAssets maps unresolved names instead of deleting faces).
- Filter display contexts to the vanilla whitelist (the 8 known contexts; drop unknowns like `on_shelf`
  with a warning) before folding into frames.
- Stop emitting `texture_size` and `format_version` (vanilla ignores them; `texture_size` misleads).
- Keep the existing frame/dispatch/cooldown pipeline untouched.

### 2. Item inheritance (vanilla-parent extension)
- `PackItemDefinition` gains `parentModel` (e.g. `minecraft:item/handheld`) + `textureOverride`
  (single png). When set, the compiled geometry model is
  `{"parent": "<parent>", "textures": {"layer0": "<ns>:item/<tex>"}}` — a new emerald-sword-style
  item costs one texture. `PackSystem.ensureModelAssets` registers only that texture; no bbmodel needed.

### 3. Separate animation files
- `VirtualModel.withAnimations(List<AnimationClip>)` copy helper (merge by name, later wins).
- `ModelImportCoordinator.importFolder` additionally scans `*.anim.bbmodel` /
  `<stem>.animations.bbmodel` siblings, parses clips via a name-based animator resolution
  (animator uuid resolved against the target model's bone graph by outliner name), and merges
  into the target `VirtualModel` before registration.
- Bone-name keying makes merge safe; the animation-only importer resolves animators by
  `animator.name` when uuids don't match (fixes the "dropping animator" drop path).

### 4. Declarative triggers
- `ModelMeta.triggers`: `Map<String eventName, String clipName>` parsed from
  `models/<key>.meta.json` (`"triggers": {"reload": "reload_clip", ...}`).
- `AnimationInstanceBridge` exposes declared triggers; `ModelAnimationManager.fireTrigger(instanceId, event)`
  resolves event→clip and plays it ONCE with priority above autoplay, returning to base afterwards.
- `AnimationApi.triggerEvent(instanceId, event)` public seam; `MultiBlockHook` unaffected.

### 5. Pack-block uniform fit
- `ModelJsonWriter.writeBlock`: compute model bbox once; apply ONE uniform centering shift to all
  elements (no per-element re-basing); compute `k = 48/maxSpan` when any span exceeds the 48px
  client window and bake `geometry·k` (UVs unchanged — proportional stretch is consistent).
  Emit the chosen `k` through `PackBlockAsset` compile metadata (`modelScale`).
- `PackBlockRenderer.transformationFor`: multiply display scale by `1/modelScale`, keeping the
  world size exactly authored; pivot parity preserved (scale about the anchor block center).
- `ModelGeometryGuard`: block-axis models become verification-only (warn, never rewrite); item axis
  keeps the existing window guard.
- `TextureImageStore`: case-insensitive filename resolution (scan sibling/root dir once, match
  lowercase), fixing `Alchemy_Texture.png` vs `alchemy_texture`.

### 6. Mob animation smoothing
- `ModelAnimation`/`MobAnimation` curve sampling honors catmull-rom keyframe interpolation
  (currently sampled linear) — smooth in-between frames on custom mobs.
- `MobRenderEngine` interpolation window stays `max(renderInterpolationTicks, poseInterval)·divisor`;
  epsilon-skip only skips exact-identical poses (drop the micro-motion freeze at default epsilon).

### 7. Examples + docs
- `WeaponBbModels`: display whitelist fixed; `WeaponAssets.sanitizeTextures` maps face textures
  through the real texture table instead of deleting them.
- Alchemy: add `alchemy-table.meta.json` (origin CENTER explicit); no other module change needed.
- Docs sweep: AGENTS.MD, README.md, docs/* (pack-system, developer-api, config-reference,
  extension-workflows, docs/README.md), CHANGELOG.md.

## Verification (this pass)
No builds allowed: every change is verified by read-back against the calling seams, signature
consistency across tasks, and grep sweeps for stale references. Compiler-verification is listed
as residual risk in the plan ledger.
