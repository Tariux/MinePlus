# Mineplus 1.21.x → 26.3 Compatibility Rewrite — Design

Status: **DRAFT — awaiting owner approval** (2026-11-19)

## Intent & Why

Minecraft changed to calendar versioning in 2026 (releases 26.1, 26.2, 26.3
"Wilderness Bound"); Paper dropped its internal remapper in 26.1 because Mojang
no longer ships obfuscated server jars, and 26.x servers require Java 25.
Mineplus compiles against 1.21 stubs and probes the runtime, but several seams
silently misbehave on anything past 1.21.7 (pack-format ceiling 64, the
mob-variant datapack writer returning -1 on non-1.x versions), one seam is a
confirmed latent runtime break on 1.21.3+ (`Attribute.GENERIC_*` compile-time
constants in `CustomMobAttributes`), and the mob AI internals
(`NmsEntityPlan` shape probes, goal/brain isolation, packet transport) were
built against 1.21-era NMS and churn with every release. This update makes the
single jar — Spigot and Paper — behave correctly from 1.21.0 through 26.3,
rewrites the mob AI internals so simple mobs behave exactly as today while
special (rigged, animated) mobs get a better AI layer, and cleans the
developer-facing API modules author against.

## Goals / Non-Goals / Anti-Goals

**Goals**
- One jar, Spigot **and** Paper, runtime floor 1.21.0, ceiling 26.3.
- Simple-mob behavior byte-equivalent to today on every supported version.
- Special/custom mobs: improved AI (living idle behavior, better animation
  state machine, combat polish) that works identically across the range.
- Clean, documented developer API for mob authoring; example modules
  migrated in the same update.
- Every known break found in discovery fixed with an honest log when a
  capability cannot exist on a given version (never silent).

**Non-goals**
- No new features outside the compat/mob-AI/API scope (no new texel, pack or
  multiblock features).
- No version-split artifacts, no build-per-server matrix.
- No removal of existing configuration keys or JSON formats (additive only —
  follows the repo's migration-guide philosophy).

**Anti-goals (failure even if it "works")**
- Silently degrading on 26.3 the way the variant writer silently writes no
  manifest today.
- Breaking 1.21.x servers to make 26.x simpler.
- Feature code leaking into the Core, or a second fallback path for pack
  degradation outside `RenderRouter`.

## Constraints (exact)

| Constraint | Value |
|---|---|
| Compile floor | stub jars in `libs/` stay at `paper-api-1.21` / `spigot-api-1.21.1`; nothing newer may be referenced at compile time |
| Toolchain | Java 21 `release` (runs unchanged on Java 25 JVMs) |
| Platforms | Spigot + Paper (Folia probing stays; Folia remains best-effort as today) |
| Version floor | 1.21.0 (legacy CMD pack path, pre-1.21.5 variant fallback stay alive) |
| Version ceiling | 26.3 |
| Core purity | no feature code/resources/commands in Core |
| Hot paths | allocation-free; MethodHandles cached once; fastutil sets |
| Scheduling | Folia-aware via one shared probe (not seven copies) |
| Pack-disabled invariant | `PACK.ENABLED: false` keeps non-pack behavior byte-identical |

## Verified external facts

| Fact | Source | Date |
|---|---|---|
| Calendar versioning: 26.1, 26.2, 26.3 ("Wilderness Bound", 2026-09-15) | minecraft.net versioning article; minecraft.wiki Java Edition 26.3 | 2026-11-19 |
| Paper stable builds 26.1.2 / 26.2 / 26.3; Java 25 required | papermc.io/downloads/paper; minecraft.pl guide (2026-10-20) | 2026-11-19 |
| Paper dropped the internal remapper in 26.1 — obfuscated names gone from the server jar; spigot-mapped internals no longer exist there | papermc.io/news/26-1 | 2026-11-19 |
| Paper version strings moved off `-R0.1-SNAPSHOT` (e.g. `26.1.2.build.N`); `Bukkit.getBukkitVersion()` shape on 26.x must be parsed defensively | papermc.io/news/26-1 | 2026-11-19 |
| 26.1 changelog: "Block State Rendering … changes … in places like Enderman-held blocks, Block Display entities, etc." — the pack-block carrier display path must be re-verified in game | feedback.minecraft.net 26.1 article | 2026-11-19 |
| Paper's World-keys/storage changes (26.1) do not touch Mineplus seams (Mineplus never reads world folders or `WorldInfo#getName`) | papermc.io/news/26-1 | 2026-11-19 |

**Pending verification (research brief in flight; folded in before execution):**
exact resource-pack `pack_format` numbers for 1.21.7 → 26.3 and data-pack
formats; whether `pack.mcmeta` gained new required keys (e.g. `min_format`/
`max_format`) or kept `supported_formats`; Spigot's mapping situation on 26.x
and whether unversioned `org.bukkit.craftbukkit.*` holds; the modern Paper
resource-pack send API shape; `api-version` acceptance of `'1.21'` on a 26.3
server; item-definition/model/display-context format drift after 1.21.6; new
mob-variant registries in 26.x; whether Paper ships a public packet API that
could replace netty interception on 26.x. Each item has a design slot below;
missing numbers degrade to the existing override/table mechanism, never to a
silent wrong output.

## Approaches Considered

| Approach | Trade-offs | Decision |
|---|---|---|
| **A. Stratified compat layer** — keep one jar compiled against the 1.21 floor; unify the version backbone; refresh every probe chain for 26.x; rewrite mob AI internals behind the existing API seams; clean the authoring API additively | Most work; keeps the repo's proven probe-once philosophy; zero link risk on old servers; both platforms supported | **Chosen** |
| B. Paper-first modernization — compile against Paper 26.3 API, reflect down | Cleaner compile-time code, but breaks Spigot and risks link errors on 1.21.x (the `Attribute.GENERIC_*` precedent is exactly this failure mode); violates both chosen constraints | Rejected |
| C. Version-split artifacts (1.21 jar + 26.x jar) | Smallest per-jar surface; doubles maintenance, breaks the single-jar distribution (Modrinth/Core-jar contract) and the modules' path-referenced Core jar | Rejected |

## Chosen Design

Nine workstreams. Each names its seams (discovery inventory, verified against
the tree) and its definition of done.

### W1 — Unified version backbone
- `util/ServerVersion` becomes the single parser: handles `1.21.x` and
  `26.x`, tolerates both `-R0.1-SNAPSHOT` and Paper's new build suffixes,
  exposes `atLeast(int,int,int)` publicly and milestone helpers
  (`itemModelComponent()`, `dataDrivenVariants()`, plus new gates as needed).
  `pack/PackFormat` keeps only the pack-domain knowledge and delegates
  parsing to `ServerVersion`.
- `PackFormat.packFormat` table extended with the researched
  1.21.7 → 26.3 numbers; unknown future versions resolve to the newest known
  format (current behavior, kept) and `PACK.PACK_FORMAT_OVERRIDE` remains the
  operator escape hatch.
- `VariantDatapackWriter.datapackFormat()` gets the 26.x data-pack branch
  instead of returning -1 (no manifest on 26.x today = silent feature loss).
- DoD: one parser, zero duplicated version tables, 26.1.2 parses to a
  triple every gate can use.

### W2 — Scheduler / probe consolidation
- One `SchedulerCompat` utility replaces the seven copies of the
  `RegionizedServer` probe (`PackScheduling`, `DeferredEditQueue`,
  `ModelAnimationManager`, `PerfRegistry`, `InputChannel`, `VisualUnitService`,
  bStats gate) with the `HeldItemInput` unconditional timer flagged and fixed.
- DoD: one Folia probe in the source tree; grep proves it.

### W3 — NMS probing refresh (26.x mojang-mapped world)
- `virtual/display/nms/ReflectionNmsAdapter`: keep the dual-mapping table
  (old Spigot servers still need spigot-mapped names), reorder with mojang
  names first (already the case), re-verify every packet constructor/shape
  probe against 26.3 reality; the codec-only mount fallback (:345-360) and
  structural payload-field discovery (:392-406) are the patterns expected to
  survive — verified, not rewritten, unless research shows breakage.
- `input/net/InputChannel`: packet-name tables (`Serverbound*` mojang names +
  `PacketPlayIn*` spigot names) verified for 26.x; the fast path remains
  strictly probe-gated and degrades to event-only handling — that degradation
  gets a startup log today via `HeldItemInput`, kept honest.
- `mob/navigation/NmsEntityPlan`: refresh shape probes for 26.3
  (`NavigationAbstract.moveTo` 4-double shape, look-control 5-arg shape,
  goal-selector field discovery, `removeAllGoals(Predicate)`), and fix the
  `EMPTY` plan-cache poisoning (:158) so a transient failure is retried once
  with a warning instead of poisoning the class for the JVM lifetime.
- DoD: each adapter starts on 26.3 and on 1.21 with a mapped or honestly
  degraded status; `/mineplus status` (existing) shows which seams resolved.

### W4 — Compile-safe seams (the confirmed breaks)
- `CustomMobAttributes`: drop the ten compile-time `Attribute.GENERIC_*`
  constants (:33-38) — resolve through `MobAttributes`' existing
  both-spellings reflective lookup (the exact mechanism that already handled
  the 1.21.3 rename there).
- `MobYamlLoader`/`EntityType.valueOf` and `ParticleFx`/`Sounds`
  `valueOf` call sites: wrap renames with tolerant fallback (enum-name probe
  → key-string path → disable-with-log), so a renamed constant cannot unwind
  a spawn.
- `PackItemFactory` / `ItemFrameWriter`: re-verify `setItemModel`,
  `CustomModelDataComponent` accessors and the primitive/boxed CMD spellings
  against the 26.3 API shape; extend probes if renamed (patterns already
  probe; this is verification + extension).
- `PackDeliveryService`: keep the overload cascade, add the modern Paper
  resource-pack API as the first probe (shape per research).
- `EntityPoolManager` (`World#createEntity`): verify on 26.3; plain-spawn
  fallback stays.
- DoD: Core compiles unchanged against 1.21 stubs; every seam resolved or
  honestly logged on both a 1.21 and a 26.3 runtime.

### W5 — Mob AI rewrite (simple mobs: identical behavior)
- Isolation: `MobAiIsolation` + `NmsEntityPlan` brain/goal clearing
  (named → structural → live-content chains) re-verified for 26.3; the
  `isolationLabel()` honesty channel stays; a failed classification logs at
  startup instead of letting vanilla AI fight the profile silently.
- Navigation: `PaperPathfinderBackend` remains primary on Paper;
  `NmsPathNavigationBackend` remains the Spigot path with refreshed shapes;
  the door/float/`wakeup` Paper-only probes stay probe-gated.
- Behavior engine semantics untouched: odds math, one-action-per-event,
  edit-session budgets, activity claims, journey/crew/pipeline — simple
  profiles behave exactly as today on every version (this is a goal, tested
  by the existing pipeline test + in-game checklist).

### W6 — Special-mob AI layer (the "improved AI")
Additive, Core-owned, module-consumable:
- `MobAnimator` state machine polish: crossfaded transitions instead of
  hard snaps, new optional clip slots (`sit`, `eat`, `ambient`) with the same
  name-matching/alias scheme, hurt-direction bias, death-clip grace verified
  against the pose clock.
- Two new bundled actions: `WanderAction` (home-anchored idle strolls through
  `MobNavigator` + `GroundProbe`, under the run's edit/move budgets) and
  `GuardAnchorAction` (hold an anchor, face the nearest threat) — the missing
  "alive at rest" behavior for core-steered special mobs.
- Combat polish: strike ↔ swing-clip timing stays synced via the existing
  `notifyMeleeSwing` seam; no new damage semantics.
- DoD: an `AttackNearest`-only mob that idles today now wanders/guards when
  the module opts in; a mob with no new clips renders exactly as before.

### W7 — Clean developer API (additive-first)
- Unify the authoring surface: `CustomMobDefinition.Builder` gains a first-
  class `mind(...)` concept (`vanillaAi` / core-profile with
  priority/formation/roles spelled in one place); the existing
  `.vanillaAi()` / `.behavior(...)` calls keep working as thin delegates
  (repo migration philosophy: nothing removed, one release).
- `MobBehaviorApi` gains the W6 actions and a `minds()`/`snapshot()`-style
  status surface consistent with the existing journey/crew/pipeline
  snapshots.
- Both `docs/developer-api.md` and `docs/custom-mobs.md` rewritten for the
  new surface; `DEVELOPMENT_PROMPT.md` updated.
- DoD: mobfun compiles against the new API with **zero** deprecated calls;
  old-style definitions still register.

### W8 — Pack formats for 26.x clients
- `pack.mcmeta` emission per researched shape (new keys if 26.x requires
  them; `supported_formats` retained for 1.20.2+ semantics); artifact hash
  includes the format decision, so a format change produces a new artifact.
- Item-definition dispatch (`minecraft:model` / `range_dispatch` /
  `select` / `cooldown` property / `hand_animation_on_swap`) and the
  `format_version` strings re-verified against 26.x client formats; the
  cooldown-clock animation trick is the most format-fragile construct and
  gets an explicit verification checklist item.
- The 26.1 "Block State Rendering" change: `PackBlockRenderer`'s
  note-block-carrier `BlockDisplay` path is verified in game; if 26.1
  changed carrier rendering, the fallback is the existing one-place degrade
  to virtual (`RenderRouter`), plus a carrier re-selection if needed.
- DoD: one artifact accepted by clients 1.21 → 26.3; `/mineplus pack status`
  prints the resolved format honestly.

### W9 — Modules, docs, verification
- Example modules: mobfun migrated to the W7 API; fun/gun recompiled
  against the new Core; any deprecated-call stragglers cleaned.
- Docs updated in the same change: `AGENTS.md`, `CHANGELOG.md`,
  `docs/config-reference.md` (PACK format table), `docs/pack-system.md`,
  `docs/custom-mobs.md`, `docs/developer-api.md`, `docs/migration-guide.md`
  (new edition for this update).
- Verification ladder (each step before the next):
  1. `npm run build-all` green on Java 21 stubs.
  2. `CustomMobPipelineTest` extended: version-table cases (`1.21.0`,
     `1.21.4`, `1.21.5`, `26.1.2`, `26.3`), mcmeta shapes, animation
     crossfade math, `WanderAction` decision logic (plain-Java, no server).
  3. Runtime on the user's Spigot server (`../server-spigotss`) — user runs
     the in-game checklist (below).
  4. Optional: same checklist on Paper 26.3 if the user has a test server.
- In-game checklist handed to the owner: model place/break + texel, pack
  item + pack block + `/mineplus pack status`, custom mob spawn (both minds,
  walk/attack/death clips, heading), simple-mob profiles (grazer, pilgrim
  march + night camp), gun fire/scope/reload, `/mobfun status`, `/mineplus
  model info`, no stack traces in log.

## Risks

| Risk | Mitigation |
|---|---|
| Spigot 26.x NMS shape unknown until research lands | W3 probes are shape-based; research brief folds in before execution; override/table escape hatches exist |
| 26.1 BlockDisplay rendering change alters carrier visuals | One-place degrade to virtual; carrier re-selection if verified broken |
| `api-version: '1.21'` rejected by 26.3 servers | Researched before execution; if rejected, ship the accepted floor value verified against both |
| Cooldown-clock item animation dropped by a client format change | Explicit checklist item; fallback is the existing server-clock `ItemFrameAnimator` path |
| Probe refresh regressions on 1.21.x | Every adapter keeps its dual table; verification ladder runs the old floor first |
| MobFun rewrite drift | W5 forbids engine-semantics change; W6/W7 are additive and module opt-in |

## Execution order

W1 → W2 → W4 (compile-safe first) → W3 (probes) → W8 (pack) → W5 → W6 →
W7 (API) → W9 (modules/docs/verification). Each workstream compiles green
before the next begins; the whizbang-execute plan expands this into
reviewable tasks.
