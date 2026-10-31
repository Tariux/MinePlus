# Custom Mobs + Pack Rendering — Fix & Completion Plan

Target: Spigot **1.21.1** (`server-spigot`, API 1.21, pack_format 34, no `item_model` component), mobs loaded two ways — texture-only (`evilpig`) and fully custom bbmodel rigs (`soldier`, `trickster`/forest). Deliverable: fixed Core + MobFun jars, installed into `server-spigot/plugins`, ready for in-game test.

---

## Final checklist (implemented and verified)

### A. Pack and asset compilation

1. [x] Namespaced asset registry keys item models, item definitions and textures by zip entry path, with owner-scoped conflict detection.
2. [x] `MobRigAssetWriter` emits a texture, one item model and one item definition per part; vanilla box-UV is converted to element face windows.
3. [x] Authored per-face UV windows from `.bbmodel` are copied unchanged, including `uv_rotation` and flipped windows.
4. [x] Every element is kept inside the client's −16..32 px item-model window: small overshoot shifted, larger parts split into `<part>_p2`, `<part>_p3`, …
5. [x] Split pieces carry their offset through `BoneRigDefinition.GeometryPiece`, compensated by `BoneRigRenderer` in the pose pass.
6. [x] Legacy Spigot (no `item_model` component) reaches each part through a reserved `custom_model_data` override; modern path uses `item_model`.
7. [x] The pack artifact was inspected: 35 rig models + 35 item definitions + 3 textures, one legacy override per part, no duplicate CMD values.
8. [x] `/mineplus pack audit` reports out-of-window elements, unresolved texture references and duplicate CMDs; `/mineplus pack verify` fetches the served URL and compares SHA-1.
9. [x] `PACK.VALIDATE_ASSETS` soft budgets warn without failing the build; `AUTO` keeps the previous artifact byte-identical.

### B. Rig pose and animation

10. [x] `MobPoseSpace` is the single authored y-up ↔ rig y-down conversion; rest rotations are applied exactly once.
11. [x] Rig yaw frame is `R_y(180 − bodyYaw + yawOffset)`, matching the authored −z front to the entity's facing.
12. [x] `MobModelOverrides` (`mobs/<id>.model.json`) exposes `animationScale`, `walkSpeedReference`, `yawOffsetDegrees`, `poseMode`, `strideBlocks`, `roleOverrides` without Core content changes.
13. [x] Walk/idle clips blend by smoothed movement speed; forward travel drives leg phase forward.
14. [x] `animation.attack` is a one-shot overlay that returns to walk/idle when it ends.
15. [x] Head bone follows head yaw and look pitch; pitch falls back to the entity's own look pitch when the NMS accessor is unreadable.
16. [x] Pose task self-retires at zero rigs, gates on `RENDER_DISTANCE`, and pushes at `RENDER_INTERVAL_TICKS` with `RENDER_INTERPOLATION_TICKS`.

### C. Custom-mob runtime and AI

17. [x] PDC identity + scoreboard tag + UUID cache; chunk restore re-applies identity, attributes and isolation.
18. [x] Reflective plan resolved from the **live handle runtime class**, fixing `CraftVillager.getHandle() → Entity` (no navigation/look/Brain).
19. [x] Isolation clears goal selectors and Brain activities while leaving AI enabled; result reported as `goals`, `brain`, `goals+brain` or `NOT ISOLATED`.
20. [x] All three showcases report `goals+brain` isolation after spawn and chunk restore.
21. [x] `MobNavigator` uses Paper `Pathfinder` when present, else the mob's own `NavigationAbstract`; never teleports or spawns.
22. [x] Every accepted path request turns the look control toward the destination, so body yaw follows the path.
23. [x] A path request refused while airborne is retried on the engine cadence (verified: first request at spawn returns false, requests after landing return true).
24. [x] Custom mobs walked forward toward their target (evil pig +3.18 z, soldier +3.74 z, trickster reached its 1.4-block target) with body yaw matching target bearing.
25. [x] `AttackNearestAction` keeps chase state between evaluations: re-path every 10 ticks or when the target moves 1.5 blocks, stop at reach.
26. [x] Strikes are attributed through `DamageTransactions`, with knockback and sounds.
27. [x] Attribute templates read real donor values (`fromTemplate`), with explicit overrides winning.

### D. Showcases

28. [x] Evil Pig: vanilla pig descriptor + embedded texture, 6 displays, attack action.
29. [x] Villager Soldier: bbmodel outliner rig, walk/idle clips, spear split into two pieces, hunts hostiles.
30. [x] Woodland Trickster: bbmodel rig with idle/walk/attack, attack overlay on landed hits.
31. [x] All three spawn, attach their displays and clean up with no errors on a headless Spigot 1.21.1 server.

### E. Verification and docs

32. [x] `npm run build` compiles the Core; deployed `mineplus-1.3.0.jar` is newer than every Java source.
33. [x] Runtime pack compile succeeds for Core + MineplusFun + MineplusGun + MobFun (341 assets).
34. [x] Headless rig positions verified against pivots after the yaw frame (head/body/leg offsets land where authored).
35. [x] `docs/custom-mobs.md` documents the client window, splitting, yaw frame, look steering, live-handle plan and new settings.
36. [x] `docs/config-reference.md` documents `RENDER_INTERPOLATION_TICKS` and the 1-tick default interval.
37. [x] `docs/developer-api.md` documents navigation steering, airborne refusal, live-handle resolution.
38. [x] `docs/pack-system.md` marks custom-mob rigs as shipped and texture-only reskins as requiring a newer server.
39. [x] `AGENTS.md` and `CHANGELOG.md` updated.
40. [ ] In-game visual confirmation with a real client: texture placement, animation playback, no missing-texture cubes, walking direction with a player nearby.
41. [x] Walk weight, leg amplitude and clip tempo all measured in blocks per second (was mixing blocks/tick velocity with blocks/s thresholds): live sweep shows soldier 2.09 b/s / weight 1.00, trickster 2.37 / 1.00, evil pig 1.81 / 0.90, with stride phase advancing every pass.
42. [x] Live pose sweep on the final jars drives the pose pass directly: all three attach (6 / 22 / 9 displays), `animation.walk` + `animation.idle` resolve for the two bbmodel rigs, procedural posing for the evil pig, and the pose task retires after cleanup.
43. [x] Attack overlay probe: `playAttack` returns `true` only for the trickster (the only authored attack clip) and its overlay reaches `1.00/1s`, then returns to `idle`; soldier and evil pig return `false`.
44. [x] `/mineplus pack verify` on the served artifact: 342 entries, SHA-1 matches; `/mineplus pack audit` reports 0 unresolved references, 0 out-of-range UVs, 0 duplicate CMDs, 0 skipped assets (the only finding is the pre-existing `fun:alchemy_table` block model re-base).

Items 1–39 and 41–44 are verified headlessly. Item 40 needs a client because Spigot's activation range deactivates entities with no nearby player, which stops their AI and movement after roughly two seconds.

---

## 0. Verified root causes (from the artifact you already shipped, `mp-ddc900fdf763.zip`)

| # | Symptom | Cause (proven) |
|---|---------|----------------|
| A | Purple-and-black box on the soldier's hand/weapon | `assets/mobfun/models/item/entity/villager_soldier/weapon.json` contains elements with `from:[7.8,32.0,7.8] to:[8.2,34.0,8.2]`. The client's model element window is **−16..32 px**; out-of-range coordinates are clamped, the element collapses, and the cube renders the missing-texture checker. Geometry is emitted raw by `MobRigAssetWriter.element()` with no range guard, so any authored part taller than 48 px (the spear is 36 px tall from its pivot) is corrupt by construction. |
| B | Legs of the small trickster visible, purple box around the mob | Same class of defect as A for the trickster's hood/cloth/head parts (rotated origins + `+8` item-render offset pushes geometry past the window), combined with C. |
| C | Rigs break entirely on Paper ≥1.21.4 (would be the next support matrix) | `PackItemFactory.createModelDisplay()` builds `new NamespacedKey(namespace, "item/entity/…/hand_left")` — Bukkit `NamespacedKey` rejects `/`, so `applyItemModelComponent` fails and the factory **returns a plain PAPER stack and never falls back to the reserved legacy `custom_model_data` override** (that fallback exists at `PackSystem.registerDisplayModel`, it is just unreachable in the modern branch). `BoneRigRenderer.attach()` then spawns geometry-less displays. |
| D | Walk animation plays backward / wrong | `MobRigRenderer.poseClips()` multiplies the clip rotation by `rig.base(i)` (`:533`) and then `BoneRigRenderer.pose()` multiplies `blueprint.rest(bone)` **again** (`:391-392`) — the rest pose is applied twice. Rest is never passed into `BoneRigDefinition.Bone` (`MobRigRenderer.blueprintFor()` passes `null` at `:311`). Layer on top: Blockbench clip degrees are y-up deltas while the rig composes in y-down space (`baseQuaternion()` flips X/Z signs), so a clip axis is applied with the wrong sign — legs swing opposite to travel. |
| E | Animation feels laggy / stuttery | `MobRigRenderer.attach()` passes `settings.renderIntervalTicks()` (=2, no interpolation setting exists in `CUSTOM_MOBS`) as both `setTeleportDuration` and `setInterpolationDuration`, and it runs on its own `runTaskTimer`, outside the Core animation pipeline (`ANIMATION.PIPELINE: ASYNC_COMPUTE`) that multiblocks already use. Movement weight is derived from per-interval displacement (`distance / 0.1 s`), which is tick-quantised noise. |
| F | Control/behavior "not working" | The soldier, trickster and lamplighter/pilgrim are **villager-based**. `MobAiIsolation.isolate()` returns `false` for Brain-driven entities (documented at `MobAiIsolation:33-36`) — villagers keep their full vanilla brain, so the mob's own wander/panic AI fights `AttackNearestAction`. `CustomMobManager.applyDefinition()` logs nothing actionable when isolation fails. |
| G | Attack/movement not continuous | `MobBehaviorEngine` fires at most one action per evaluation with `1/(interval·checksPerSecond)` odds, and `AttackNearestAction.execute()` returns `false` whenever the repath gate is not due (`:120-126`) — the mob therefore stands still between decisions and only twitches. Attack clip restarts on each landed hit (`playAttack` resets `attackClock`) and never fires on a swing that misses. |
| H | "Resource pack not loading" | Delivery state is invisible: no per-player applied/declined reporting in `/mineplus pack status`, `LocalPackEndpoint` serves with `Cache-Control: public, max-age=86400` (a stale pack can survive a hash change on some client paths), and there is no self-check that the served artifact actually parses. |

---

## 1. Phase 1 — Pack truth & delivery (unblocks everything visual)

**1.1 `PackCompiler` model sanitizer (Core, new `pack/compile/ModelGeometryGuard.java`)**
- Parse every generated model entry before writing it; clamp/rebase each part's element window into −16..32 px; report every model that had to move.
- This is the authoritative fix for A/B: nothing outside the client window can reach the artifact again.

**1.2 `ModelAssetValidator` hard rules (`src/main/java/com/mineplus/pack/compile/ModelAssetValidator.java`)**
- Add real (not advisory) checks: element bounds, `uv` within 0..16, rotation origin bounds, texture reference resolvable **against the zip's own texture entries**, per-host-file `custom_model_data` uniqueness (duplicate CMD = last override wins = wrong model on the client).
- Findings surface in `/mineplus pack audit`, not just a log line.

**1.3 Representation fallback chain (`PackItemFactory`, `PackSystem`)**
- `createModelDisplay()`: try `item_model` (valid `NamespacedKey` only) → else legacy PAPER `custom_model_data` host override → else `null` (caller keeps the base entity). Fixes C on every server generation.
- Honour `PACK.APPEARANCE_MODE` on the rig display path (AUTO/LEGACY/RANGE/SELECT), currently bypassed by `createModelDisplay`.

**1.4 Delivery hardening**
- `LocalPackEndpoint`: `Cache-Control: no-cache` (the filename already carries the content hash).
- `PackDeliveryService`: force re-push when the artifact hash changes, mark `DECLINED`/`FAILED` distinctly, never `REQUESTED`-only.
- New subcommands: `/mineplus pack status` (+ per-player state list), `/mineplus pack audit` (asset-level report above), `/mineplus pack verify` (the plugin GETs its own URL and compares SHA-1 — proves the endpoint the client will hit).

Acceptance: `pack audit` reports 0 out-of-window elements, 0 unresolved texture refs, 0 duplicate CMDs; `weapon.json` geometry fully inside the window.

---

## 2. Phase 2 — Geometry compilation (two mob loading modes)

**2.1 Client-window-safe emission (`MobRigAssetWriter`, `BoneRigAssetWriter`)**
- Compute each part's bounding box; choose an emission offset that centres it in the −16..32 window instead of the fixed `+8` (`ITEM_RENDER_OFFSET_PX`).
- Parts whose span still exceeds 48 px on an axis are sliced into N sub-models (splits along the overflowing axis only), each with its own offset.
- `BoneRigBlueprint`/`BoneRigDefinition.Bone` gain per-bone **geometry entries** (`path + Vector3f pixelOffset`) instead of one path; `BoneRigRenderer` spawns one display per entry and applies the offset in the pose pass. Shared by mob rigs and `renderKind: bones` multiblock rigs, so the pack-bone axis gets the same guarantee.

**2.2 Mode A — texture-only reskin (`evilpig`)**
- `CustomMobDefinition.visual(VisualMode.BASE | RIG | AUTO)`: `BASE` keeps the base entity visible and ships only the texture/model assets (no ItemDisplays); `RIG` forces the per-part rig; `AUTO` (default) rigs when the model has parts and the pack is applied, otherwise stays on the base.
- 1.21.1 has no `entity_to_model.json`, so a true texture-only entity override is impossible server-side: `BASE` is documented as "vanilla silhouette, pack assets reserved for 1.21.5+ delivery", `RIG` remains the working path. Keep the vanilla-derived rig cheap: evil pig = 6 displays, unchanged.

**2.3 Mode B — custom model + clips**
- `BedrockMobModel` keeps outliner-as-rig; add per-model authored overrides read from `mobs/<id>.model.json` next to the `.bbmodel`: `animationScale`, `walkSpeedReference`, `yawOffsetDegrees`, `poseMode: clips|procedural`, `strideBlocks`, `legRoleMap`. Removes all guesswork from imported assets without hardcoding content into Core.

Acceptance: soldier (22 parts, 36 px spear → 2 slices), trickster (8 parts) compile with every element inside the window; pack byte-diff reviewed against `mp-ddc900fdf763.zip`.

---

## 3. Phase 3 — Rig pose math (fixes D)

- **Single rest application**: build the blueprint with `restRotation = baseQuaternion(part)` and remove the `local.mul(rig.base(i))` term in `poseClips`; procedural head tracking computes its delta relative to rest (`rest⁻¹ · target`), so nothing is applied twice.
- **One documented space convention** in a new `MobPoseSpace` helper: authored y-up Blockbench degrees → rig y-down space (X/Z negated, Y kept), used by both clip sampling and rest baking. `MobRigAssetWriter`/`BedrockMobModel` keep their existing conversions but reference the same helper.
- **Yaw frame**: keep `R_y(180° − entityYaw)` for `BASE`-derived models; expose the per-model `yawOffsetDegrees` from 2.3 so a model authored facing a different axis is corrected by data, not code.
- **Walk phase direction**: derive leg phase from signed forward movement along the body yaw (not raw displacement), so a mob strafing or walking backwards animates accordingly instead of reversing.
- `MobRigRenderer` unit surface: `poseSnapshot(uuid)` returning each bone's absolute quaternion, used by `/mobfun rig <id>` to prove a pose is right without the game.

Acceptance: forward travel ⇒ legs swing forward; soldier head/arms sit at authored rest; no part doubles its rest rotation (verified by `poseSnapshot` against the bbmodel's authored group rotations).

---

## 4. Phase 4 — Smoothness (fixes E)

- New `CUSTOM_MOBS.RENDER_INTERPOLATION_TICKS` (default 1) and default `RENDER_INTERVAL_TICKS: 1`; `attach()` passes the interpolation window explicitly rather than reusing the push interval.
- Feed speed from `entity.getVelocity()` + the mob's `MOVEMENT_SPEED` attribute (continuous) instead of per-tick displacement (quantised); amplitude and walk weight use an exponential smoother tuned per pose pass.
- Route mob rig poses through the existing animation pose scheduler (`ANIMATION.PIPELINE`: `TICK` / `ASYNC_COMPUTE` with `MAX_APPLY_PER_TICK`), so rigs batch display writes like multiblocks instead of running their own timer; keep the `isNearAnyPlayer` gate and the viewer-facing LOD skip.
- Head-yaw unwrap before interpolation (avoid the ±180° spin), and slerp local rotations between passes so a 2-tick gap never snaps.

Acceptance: 20-tick walk produces 20 pose pushes (or one batched pass with equal coverage), no visible step at 1.2-blocks/s walk, CPU cost unchanged at 0 rigs.

---

## 5. Phase 5 — Behavior, AI and attribute inheritance (fixes F, G)

**5.1 Attribute templates** — `CustomMobAttributes.template(EntityType)` / `.fromTemplate(type)`:
- Capture a donor's default attributes once per type through a **transient** entity (`World#spawn(loc, type, consumer, false)` — never added to the world), cache `<EntityType, Map<Attribute, Double>>` for the JVM lifetime, then apply onto the mob: `MOVEMENT_SPEED`, `FOLLOW_RANGE`, `ATTACK_DAMAGE`, `ATTACK_KNOCKBACK`, `KNOCKBACK_RESISTANCE`, `MAX_HEALTH`, `SCALE`, `JUMP_STRENGTH`, `GRAVITY`.
- Explicit per-field overrides still win over the template. → `soldier` = iron-golem movement profile, `trickster`/forest = zombie profile, exactly as asked.

**5.2 Brain-driven entities** — extend `MobAiIsolation`:
- Reflective clear of the villager `Brain`'s active behaviors/sensors (goal-less, still path-navigable), with a per-runtime probe resolved once at startup; if the clear is unavailable, the manager reports it through `/mobfun status` instead of silently leaving two AIs fighting.
- Re-asserted on the existing periodic isolation pass, since brains re-arm schedules.

**5.3 Vanilla-goal inheritance** — `CustomMobDefinition.inheritGoalsFrom(EntityType)`:
- Skips isolation so the mob keeps its donor AI (and the donor base type), used with a rig for visuals: "forest = a real zombie that looks like the trickster". Documented as the cheap path when advanced AI is wanted without writing behavior code.

**5.4 Continuous hunting** — `AttackNearestAction`:
- Walk state persists between evaluations: reaching reach-distance stops navigation, but a `false` return on the repath gate no longer implies "idle"; add an action-scoped locomotion latch (`BehaviorContext.navigate` holds until repath/reach/timeout).
- Add a `continuous()` marker consumed by `MobBehaviorEngine`: while a continuous action owns the mob, the per-fire odds gate is replaced by "advance the ongoing goal every evaluation", so movement and attack cadence are steady rather than probabilistic.
- Retarget stays sticky (60 ticks); swing fires on the attack attempt (in reach + line of sight) rather than only on a landed hit.

**5.5 Attack animation continuity**:
- `MobRigRenderer.onRigDamageDealt` → `playAttack` when the mob swings (module can call `playAttack(mob)` from its own attempt path), the overlay is **not** restarted while it is still within its wind-up window, and the walk/idle blend resumes through a short cross-fade instead of a cut.
- `EntityAttackEvent` / `EntityDamageByEntityEvent` (damager side) both route to the rig; a mob with an `attack` clip plays it once per swing.

Acceptance: soldier walks and strikes without freezing, trickster swings on every attack attempt including misses, no villager AI wander pulling the mob off its target.

---

## 6. Phase 6 — Module content, test bench, docs

- MobFun: `soldier` and `trickster` rebuilt on the new surface (attribute templates, `visual` mode, `.model.json` overrides, continuous attack).
- `/mobfun status` gains: rig pose snapshot, pack state per player, isolation result per mob (goal vs brain), template applied.
- `/mobfun pack` — prints the current artifact, its URL, SHA-1 and any audit findings from the Core.
- Docs: `docs/custom-mobs.md` (two loading modes, geometry window, templates), `docs/pack-system.md` (representation fallback chain, audit), `docs/config-reference.md` (new `CUSTOM_MOBS` and `PACK` keys), `AGENTS.md` (mob-axis summary), `CHANGELOG.md`.

---

## 7. Build & hand-off

1. `npm run build` — Core jar → `server-spigot/plugins` (waits for the file lock).
2. `npm run build-mineplus-mobfun` — MobFun jar → same plugins dir.
3. Start server once to recompile the pack, run `/mineplus pack audit` — must be clean before you test.
4. Report back: what renders, what still looks wrong, `/mobfun status` + `/mineplus pack status` output.

No other action at the end — you import the two jars and test in game.

---

## 8. Risks / explicit non-goals

- **Texture-only entity reskin is impossible on 1.21.1** (no `entity_to_model.json`/`renderers.json`); `BASE` mode is a forward-compatible placeholder for 1.21.5+ until you can run a newer server.
- Part slicing increases display count for oversized parts (spear = 2 instead of 1); bounded by `RIGS.MAX_DISPLAYS_PER_INSTANCE` and `CUSTOM_MOBS.MAX_RIGGED_PER_WORLD`.
- Reflective brain clearing is version-sensitive; it is probed once and degrades to "donor AI stays on" with a visible report, never a silent half-isolated mob.
- Server-side pose rate is capped by the 20 tps tick loop — smoothness means correct interpolation windows and continuous inputs, not sub-tick motion.
