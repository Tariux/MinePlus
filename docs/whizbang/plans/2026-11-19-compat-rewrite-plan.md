# Mineplus 1.21 → 26.3 Compatibility Rewrite — Implementation Plan

> **For agentic workers:** Use whizbang-execute. Steps use `- [ ]` syntax.

**Goal:** One jar (Spigot+Paper) behaving correctly on Minecraft 1.21.0 → 26.3, with hardened mob AI internals, an improved special-mob AI layer, a cleaned developer API, and migrated example modules.

**Architecture:** Stratified compat layer per the approved design — compile stays against the 1.21 stubs; every newer capability resolves through probe chains and version tables; mob AI internals are refreshed behind unchanged engine semantics; special-mob AI and the `mind(...)` API are additive.

**Tech Stack:** Java 21 (release 21), Gradle, committed stub jars in `libs/`, Gson, JOML, fastutil; plain-Java test runners under `src/test/java/com/mineplus/test/` (no JUnit).

**Spec:** `docs/whizbang/2026-11-19-compat-rewrite-design.md`

## Global Constraints

- Compile floor: only APIs present in `libs/paper-api-1.21.jar` / `libs/spigot-api-1.21.1.jar` may be referenced at compile time. Anything newer = reflective probe with cached `MethodHandle`/`Method`, degrade-with-log, never crash.
- Version range: runtime floor 1.21.0, ceiling 26.3 (calendar versioning — 26.x parses as major 26).
- Branch: all work on branch `compat-26` (created at exec-init), conventional lowercase commits (`compat: …`, `feat: …`, `test: …`), one commit-set per task.
- Core purity: no feature code in `src/main/java/com/mineplus`; new actions are Core-owned *bundled* behaviors, module-consumable.
- Hot paths allocation-free; `ConcurrentHashMap` state; Folia-aware scheduling via the one shared probe after T2.
- `PACK.ENABLED: false` keeps non-pack behavior byte-identical.
- Additive-first API: existing public signatures keep working; new surface is added, old paths delegate.
- Verification commands: before T1 lands `gradle build -q`; from T1 on, `gradle testRunners -q` (new task) plus `gradle build -q` for compile-gate tasks. No test framework exists beyond the plain-Java runners; server-bound classes verify by compile + review + the T12 in-game checklist — never by fabricated unit tests.
- Research-gated tasks (T5, T6, T7): the external brief on Spigot 26.x mappings, pack-format numbers 1.21.7→26.3, mcmeta key shapes, Paper resource-pack API, and 26.x item-definition drift must be folded in before those tasks start. If it has not landed, do independent tasks first; never invent version numbers.

## Review Focus

1. **Inverted version gates for 26.x** — every new gate must be exercised for both `1.21.x` and `26.x` parses in `CompatVersionTest` (major-26 comparisons cross the old `v[0]==1` assumptions).
2. **pack.mcmeta shape drift** — a wrong shape makes modern clients reject the whole artifact silently; T6 asserts the emitted JSON keys per version, T12 verifies in game.
3. **Silent AI isolation failure** — vanilla AI fighting a profile looks like "mob bugs", not "plugin bug"; T8 makes classification failure a logged startup status and keeps `isolationLabel()` truthful.
4. **Failure-cache poisoning** — an `EMPTY` NMS plan cached forever silently kills navigation; T4 must not cache transient failures.
5. **Animation regression** — crossfade math must be golden-tested against existing rest-pose output so today's rigs do not visibly change when idle.

---

### Task 1: Test runner infrastructure + version backbone

**Files:**
- Modify: `build.gradle` (test source set wiring)
- Create: `src/test/java/com/mineplus/test/CompatVersionTest.java`
- Modify: `src/main/java/com/mineplus/util/ServerVersion.java`
- Modify: `src/main/java/com/mineplus/pack/PackFormat.java`
- Modify: `src/main/java/com/mineplus/infrastructure/mob/custom/render/VariantDatapackWriter.java`

**Interfaces:**
- Produces: `ServerVersion.atLeast(int major, int minor, int patch)` (public), `VariantDatapackWriter.datapackFormatFor(String bukkitVersion)` (static, pure, never -1 for any parseable version), Gradle task `testRunners` running every `com.mineplus.test.*Test` main against `test` classes + `libs/*.jar` classpath, wired into `check`.

- [x] **Step 1: RED — write `CompatVersionTest`** (plain-Java runner, same style as `CustomMobPipelineTest`: `failures` list, non-zero exit on failure):
  - `parseVersion("26.1.2-R0.1-SNAPSHOT")` == `{26,1,2}`; `parseVersion("26.1-build.23")` == `{26,1,0}`; `parseVersion("1.21.4-R0.1-SNAPSHOT")` == `{1,21,4}`; `parseVersion(null)` == `{0,0,0}`.
  - `atLeast(1,21,4)` true for `{1,21,4}` and `{26,1,0}`, false for `{1,21,3}`; `atLeast(26,1,0)` true only for `{26,1,0}`/`{26,2,0}` — this 3-arg overload does not exist yet (compile failure = RED).
  - `PackFormat.packFormat("26.1.2",0)` == `PackFormat.latestKnownPackFormat()` (regression-lock current honest-best behavior; real numbers arrive in T6).
  - `PackFormat.packFormat("1.21.4",0)` == 46, `("1.21.0",0)` == 34, override `packFormat(any,55)` == 55.
  - `VariantDatapackWriter.datapackFormatFor("26.1.2-…")` != -1 and equals the newest known data-pack format; `("1.21.5-…")` == 71 — method does not exist yet (RED).
- [x] **Step 2: RED — wire `testRunners`** into `build.gradle` (JavaExec over the two runner mains; classpath `test` runtime + `libs/*`); run `gradle testRunners -q`, verify it FAILS on the new assertions (compile errors on the two missing methods count as the observed failure). Note: if wiring makes the *existing* `CustomMobPipelineTest` report failures, record them in the ledger and fix in this task before proceeding.
- [x] **Step 3: GREEN** — make `atLeast(int,int,int)` public; add `datapackFormatFor(String)` as a pure function extracted from `datapackFormat()`'s table with a 26.x branch returning the newest known format (not -1); `PackFormat` delegates its private parse to `ServerVersion.parseVersion` (delete `PackFormat.parseVersion`).
- [x] **Step 4: `gradle testRunners -q` → PASS.**
- [x] **Step 5: Commit** — `test: version backbone suite + gradle testRunners`, `compat: calendar-version-aware gates and 26.x datapack format`.

### Task 2: One scheduler probe (`SchedulerCompat`)
**Files:**
- Create: `src/main/java/com/mineplus/util/SchedulerCompat.java`
- Modify the seven probe sites: `pack/PackScheduling.java:20-27`, `world/DeferredEditQueue.java:92-93`, `virtual/animation/ModelAnimationManager.java:207-208`, `perf/PerfRegistry.java:116-117`, `input/net/InputChannel.java:195-198`, `fx/unit/VisualUnitService.java:316-317`, `input/HeldItemInput.java:562-564` (the unconditional timer)

**Interfaces:**
- Produces: `SchedulerCompat.isFolia()` (static, cached `Class.forName` probe), `SchedulerCompat.runGlobalAtFixedRate(Plugin, long delayTicks, long periodTicks, Runnable)`, `SchedulerCompat.runForPlayer(...)`/`runGlobalDelayed(...)` matching the exact call shapes used at the seven sites (paper scheduler types stay probe-gated inside `SchedulerCompat`, never leak to callers).

- [x] **Step 1: RED — `SchedulerCompatTest`**: `isFolia()` false and no-throw on a plain JVM; the run helpers accept null-plain Bukkit absence without classloading `io.papermc…` (assert via `Class.forName` guard). Compile failure = RED.
- [x] **Step 2: GREEN** — implement `SchedulerCompat`; refactor all seven sites to delegate; `HeldItemInput`'s unconditional `Bukkit.getScheduler()` timer goes through the same gate. Scheduler types (`ScheduledTask`) are compile-time Paper — keep them behind the existing compile-time stub exactly as `InputChannel` does today.
- [x] **Step 3: `gradle testRunners -q` → PASS; `grep -rn "RegionizedServer" src/main/java` returns exactly one hit (inside `SchedulerCompat`).**
- [x] **Step 4: Commit** — `compat: single folia probe (SchedulerCompat)`.

### Task 3: Compile-safe attribute seam

**Files:**
- Modify: `src/main/java/com/mineplus/infrastructure/mob/navigation/MobAttributes.java:117-127` (expose resolver)
- Modify: `src/main/java/com/mineplus/infrastructure/mob/custom/CustomMobAttributes.java:32-38` (+ its doc comment)
- Modify: `src/test/java/com/mineplus/test/CompatVersionTest.java` or new `AttributeSeamTest.java`

**Interfaces:**
- Consumes: `MobAttributes.attribute(String canonical)` (exists, private, cached both-spellings probe).
- Produces: `MobAttributes.resolveAttribute(String canonicalName)` (public static, null when the runtime enum lacks the attribute); `CustomMobAttributes.TEMPLATE_ATTRIBUTES` built from canonical names via that resolver, filtered to non-null.

- [x] **Step 1: RED — test** that `MobAttributes.resolveAttribute("MAX_HEALTH")` is non-null on the stub classpath and `resolveAttribute("DEFINITELY_NOT_REAL")` is null without throwing; method missing = compile RED.
- [x] **Step 2: GREEN** — make the resolver public; replace the ten `Attribute.GENERIC_*` literals with canonical-name resolution; fix the doc comment that claims GENERIC-less naming while using `GENERIC_` spellings; template capture skips attributes the runtime does not have (log once at capture).
- [x] **Step 3: Verification sweep (compile-level, no code unless a gap appears):** confirm `Sounds`, `ParticleFx`, `MobYamlLoader.entityType` are already throw-safe (they are, per reading — record in ledger).
- [x] **Step 4: `gradle testRunners -q` → PASS; `gradle build -q` green.**
- [x] **Step 5: Commit** — `compat: reflective attribute resolution in custom mob templates (fixes 1.21.3+ rename break)`.

### Task 4: NMS plan-cache poisoning fix + probe refresh

**Files:**
- Modify: `src/main/java/com/mineplus/infrastructure/mob/navigation/NmsEntityPlan.java` (:156-173 plan cache, :176-250 probe chains, :694-761 shape probes)

**Interfaces:**
- Consumes: existing probe chains unchanged in shape.
- Produces: failed plan resolution is **not** cached (retry next access, one `DebugLogger` warning per class); successful plans still cache for the JVM lifetime.

- [x] **Step 1: Implement** — the cache stores successes only; a failure path logs once and leaves the class unresolved. Re-verify the four shape probes (`moveTo` 4-double, `ControllerLook` 5-arg, goal-selector field discovery, `removeAllGoals(Predicate)`) against mojang names first, spigot names second (order review only unless research shows 26.x breakage).
- [x] **Step 2: `gradle build -q` green** (server-bound; behavior verification is the T12 checklist: pilgrim march + necromancer raid on Spigot).
- [x] **Step 3: Commit** — `compat: nms plan failures no longer poison the cache`.

### Task 5: Packet/input probe refresh for 26.x *(research-gated)*

**Files:**
- Modify: `src/main/java/com/mineplus/infrastructure/virtual/display/nms/ReflectionNmsAdapter.java`
- Modify: `src/main/java/com/mineplus/infrastructure/input/net/InputChannel.java`

**Interfaces:**
- Produces: unchanged public surface; internal class tables extended/verified per the research brief (mojang-mapped-only runtimes, any 26.x packet-shape changes, netty channel reachability).

- [ ] **Step 1: Fold in research** — Spigot 26.x mapping status; whether `org.bukkit.craftbukkit` stays unversioned; 26.x packet class/constructor changes; Paper public packet API (if a supported event API exists, note it in the ledger as a *follow-up option*, not this task's scope).
- [ ] **Step 2: Implement** probe extensions exactly where the brief shows breakage; where the existing structural probes already cover 26.x, change nothing (probe-first philosophy — no speculative rewriting).
- [ ] **Step 3: `gradle build -q` green; ledger note of every probe verified/extended.**
- [ ] **Step 4: Commit** — `compat: 26.x packet/input probe refresh`.

### Task 6: Pack format tables + mcmeta shapes *(research-gated values)*

**Files:**
- Modify: `src/main/java/com/mineplus/pack/PackFormat.java` (table 1.21.7→26.3)
- Modify: `src/main/java/com/mineplus/pack/compile/PackCompiler.java:378-397` (mcmeta emission)
- Modify: `src/main/java/com/mineplus/infrastructure/mob/custom/render/VariantDatapackWriter.java` (data-pack table refinement)
- Modify: `src/test/java/com/mineplus/test/CompatVersionTest.java`

**Interfaces:**
- Produces: `PackFormat.packFormat(v,0)` correct for every researched release 1.21.7→26.3; mcmeta JSON shape per researched client requirements (new keys if any; `supported_formats` retained where valid); `datapackFormatFor` refined with real numbers.

- [ ] **Step 1: RED — extend `CompatVersionTest`** with one pack-format assertion per researched release (e.g. `packFormat("26.1.2",0)` == `<researched N>`); assert the mcmeta JSON for a 26.x version contains the required keys (and `pack_format`/`supported_formats` for a 1.21 version).
- [ ] **Step 2: GREEN** — table + mcmeta emission + datapack table.
- [ ] **Step 3: `gradle testRunners -q` → PASS.** Artifact hash changes automatically (format feeds the hash) — new artifact name is expected, note in ledger.
- [ ] **Step 4: Commit** — `compat: pack format tables for 1.21.7→26.3 + mcmeta shape`.

### Task 7: Modern resource-pack delivery probe *(research-gated)*

**Files:**
- Modify: `src/main/java/com/mineplus/pack/PackDeliveryService.java:208-217`

**Interfaces:**
- Produces: send path probes the modern Paper resource-pack API first (shape per research), falls back to the existing `(url, sha1, prompt)` → `(url, hash)` → `(url)` cascade.

- [ ] **Step 1: Implement** the modern-API probe as first link of the existing cascade; cached once; log which link resolved at first send (DebugLogger-gated).
- [ ] **Step 2: `gradle build -q` green; in-game verification folded into T12 checklist (pack push accepted on Spigot + Paper).**
- [ ] **Step 3: Commit** — `compat: modern resource-pack send probe`.

### Task 8: AI isolation honest-failure status

**Files:**
- Modify: `src/main/java/com/mineplus/infrastructure/mob/navigation/MobAiIsolation.java`
- Modify: `src/main/java/com/mineplus/infrastructure/mob/navigation/NmsEntityPlan.java` (brain/goal chains as needed from T4 review)
- Modify: `src/main/java/com/mineplus/command/sub/StatusSubCommand.java` (surface isolation health per seam)

**Interfaces:**
- Produces: isolation classification failure logs once at first application (not silent); `/mineplus status` gains one line per probe seam: resolved / degraded (reason) / unavailable.

- [ ] **Step 1: Implement** — `MobAiIsolation` records the last result per chain (`goals`, `brain`, `goals+brain`, structural-fallback, FAILED) and `StatusSubCommand` prints the seam table; verify `isolationLabel()` already returns the honest string (it does — keep).
- [ ] **Step 2: `gradle build -q` green; verification is T12 in game (isolation label on a rigged mob shows `goals+brain` or an honest degradation, never blank).**
- [ ] **Step 3: Commit** — `feat: isolation status surface + honest failure logging`.

### Task 9: MobAnimator crossfade + new clip slots

**Files:**
- Modify: `src/main/java/com/mineplus/infrastructure/mob/custom/rig/MobAnimator.java`, `rig/BoneTransformMath.java` (if math lands there)
- Modify: `src/test/java/com/mineplus/test/CustomMobPipelineTest.java` (new checks)

**Interfaces:**
- Consumes: existing clip name-matching/alias scheme (`idle`/`walk`/`run`/`attack`/`hurt`/`death` + `animationMap` aliases).
- Produces: bounded-time crossfade between base-layer clips and one-shot overlays (same public call signatures; `MobRenderEngine` unchanged); optional clip slots `sit`, `eat`, `ambient` joining the name-matching chain; a model without the new clips behaves exactly as today.

- [ ] **Step 1: RED — pipeline-test checks**: crossfade continuity (pose at blend boundaries equals source at t=0 and target at t=1 within EPS), no per-tick allocation in the blend path (assert via a re-used buffer contract — pose map identity across frames), new slot name-matching picks `sit`/`eat`/`ambient` and degrades to idle when absent, rest-pose output for a clip-less model is bit-identical to today (golden values from current run).
- [ ] **Step 2: GREEN** — implement the crossfade (blend window from `CUSTOM_MOBS.RENDER_INTERPOLATION_TICKS`, no new settings unless required) and the three slots.
- [ ] **Step 3: `gradle testRunners -q` → PASS.**
- [ ] **Step 4: Commit** — `feat: mob animator crossfade + sit/eat/ambient clip slots`.

### Task 10: `WanderAction` + `GuardAnchorAction`

**Files:**
- Create: `src/main/java/com/mineplus/infrastructure/mob/action/WanderAction.java`, `src/main/java/com/mineplus/infrastructure/mob/action/GuardAnchorAction.java`
- Modify: `src/test/java/com/mineplus/test/` (new decision-logic checks)

**Interfaces:**
- Consumes: `BehaviorAction` contract, `MobNavigator.moveTo`, `GroundProbe` standable checks, `MobAttributes.pathRange` leg clamping, run edit/move budgets (never raw world writes — these actions only move/look).
- Produces: `WanderAction` (builder: home anchor, radius, min/max dwell ticks) and `GuardAnchorAction` (builder: anchor supplier, threat radius) — module-consumable, engine-semantics untouched.

- [ ] **Step 1: RED — decision-logic tests** (pure, with injected predicates): destination selection rejects non-standable/out-of-radius/beyond-pathRange candidates; dwell timing fires exactly once per interval per odds semantics; guard faces threat only within radius and keeps anchor when no threat. Logic extracted into package-private pure decision functions the actions call.
- [ ] **Step 2: GREEN** — implement both actions on top of the decision functions.
- [ ] **Step 3: `gradle testRunners -q` → PASS; `gradle build -q` green.**
- [ ] **Step 4: Commit** — `feat: wander + guard anchor bundled actions for special mobs`.

### Task 11: `mind(...)` authoring API + mobfun migration

**Files:**
- Modify: `src/main/java/com/mineplus/infrastructure/mob/custom/CustomMobDefinition.java` (+ builder), new `custom/Mind.java`
- Modify: `src/main/java/com/mineplus/infrastructure/mob/MobBehaviorApi.java` (snapshot/status surface only)
- Modify: `examples/mineplus-mobfun/src/main/java/**` (definition sites)

**Interfaces:**
- Produces: `Mind.vanillaAi()`, `Mind.coreProfile(...)` (priority + clips intent in one place); `CustomMobDefinition.Builder.mind(Mind)`; existing `.vanillaAi()`/`.behavior(...)` builders delegate to `Mind` internally (additive-first, nothing removed).
- Consumes: W6 actions for the mobfun special mobs that opt in.

- [ ] **Step 1: Implement `Mind` + builder delegation + `MobBehaviorApi` snapshot additions** (mirror the existing journey/crew/pipeline snapshot style).
- [ ] **Step 2: Migrate mobfun** definition sites to `mind(...)`; zero deprecated-style calls remain in the module; existing behavior selection (which mobs are vanilla-AI vs core-steered) unchanged.
- [ ] **Step 3: `gradle build -q` green for Core; module build green (`npm run build-mineplus-mobfun`).**
- [ ] **Step 4: Commit** — `feat: mind() authoring api + mobfun migration`.

### Task 12: Full build, docs, in-game checklist handoff

**Files:**
- Modify: `AGENTS.md`, `CHANGELOG.md`, `docs/config-reference.md`, `docs/pack-system.md`, `docs/custom-mobs.md`, `docs/developer-api.md`, `docs/migration-guide.md`, `docs/whizbang/2026-11-19-compat-rewrite-design.md` (status → implemented)
- Modify: `examples/mineplus-fun`, `examples/mineplus-gun` only if compile against the new Core demands it

**Interfaces:**
- Produces: the in-game verification checklist for the owner.

- [ ] **Step 1: `npm run build-all` green; `gradle testRunners -q` green.**
- [ ] **Step 2: Docs** — config reference (PACK format table, new settings if any), pack system (26.x formats), custom mobs (Mind, new actions, crossfade slots), developer API (mind authoring), migration guide (new edition: what changed, what is additive), AGENTS.md subsystem notes, CHANGELOG entry.
- [ ] **Step 3: Write the checklist** (final message to owner): Spigot server first — plugin loads with seam-status lines; place/break a virtual+texel model; `/wine place flight`; `/cabinet place texel` and `resourcepack` twin; `/alchemy place`; pack item in hand + `/mineplus pack status`; `/mobfun spawn evilpig` (walk/attack/death clips, heading); `/mobfun fire pilgrim` night camp; grazer/pilgrim simple profiles unchanged; gun fire/scope/reload; `/mineplus model info <key>`; `/mobfun status` isolation labels; log free of stack traces.
- [ ] **Step 4: Commit** — `docs: 26.3 compat rewrite documentation + checklist`.

---

## Final Review & Completion

- Whole-branch review on the most capable model with `code-reviewer.md`; findings re-graded by effect; Critical/Important → one TDD fix pass each.
- Full suite (`gradle testRunners -q` + `gradle build -q` + `npm run build-all`) green before completion.
- Phase 4 integration question to the owner (merge `compat-26` → main / keep branch / PR).
