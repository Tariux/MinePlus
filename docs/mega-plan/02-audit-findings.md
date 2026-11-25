# 02 Audit findings

Sorted by severity. Java paths are relative to `src/main/java/com/mineplus/`. Line numbers were re-checked with grep on 2026-11-25. Nothing was reproduced on a running server; findings that need a server to confirm are tagged **UNVERIFIED repro**. Effort is in person-days for one developer who knows the code.

## High

### F1. The plugin cannot run on Folia
- Evidence: 17 direct Bukkit scheduler uses: `infrastructure/combat/Ballistics.java:120`, `infrastructure/combat/StanceService.java:312`, `infrastructure/command/sub/PerfCommand.java:110,125`, `infrastructure/core/InfrastructureEngine.java:153`, `infrastructure/core/gui/AbstractMachineGui.java:202`, `infrastructure/core/multiblock/lifecycle/MultiBlockLifecycleManager.java:545`, `infrastructure/core/storage/MultiBlockStorageEngine.java:73`, `infrastructure/mob/MobBehaviorEngine.java:586`, `infrastructure/mob/custom/CustomMobManager.java:155`, `infrastructure/mob/custom/MobRenderEngine.java:204,222,224,1397`, `infrastructure/persistence/PersistenceFacade.java:219`, `infrastructure/virtual/display/DisplayTransport.java:137`, `util/UpdateChecker.java:37`. `src/main/resources/plugin.yml` has no `folia-supported` entry. A wrapper already exists (`util/SchedulerCompat.java:30,50`) and is used at 10 sites.
- Impact: Folia refuses to load the plugin without the flag, and with the flag each of these calls throws (sources in `03-research-benchmark.md`, section 1). `README.md:52` mentions Folia, which overstates support.
- Fix sketch: route every site through `SchedulerCompat`, adding region and entity variants so each task runs on the thread that owns the location or entity it touches; then add the flag. Shared maps touched from several regions need an owner or a concurrent type.
- Effort: 15 days including a Folia test pass.

### F2. No version has been verified on a real server
- Evidence: `docs/version-compatibility.md:16-17` lists "Plugin enabled on a running server of each version" and "Models and animations seen in a real client" as **not done**; only a bytecode link check was done (`:14`). No CI exists in the repo.
- Impact: the claim of one jar for 1.21 to 26.3 rests on linking alone. The reflective NMS adapter (`infrastructure/virtual/display/nms/ReflectionNmsAdapter.java`) can link and still fail at runtime.
- Fix sketch: a smoke job that boots Paper 1.21.1, 1.21.11 and 26.3, Spigot 26.3 and Folia with the plugin, runs `/mineplus status`, and fails on any exception in the log.
- Effort: 5 to 8 days.

### F3. Machine GUI may allow pulling items by double-click (**UNVERIFIED repro**)
- Evidence: `infrastructure/core/gui/AbstractMachineGui.java:101-106` returns without cancelling for any click in the player inventory unless it is a shift-click. There are no checks of `InventoryAction` or `ClickType` anywhere in the source (zero hits), so a double-click that collects matching items to the cursor is not considered. Drag events are handled (`:142-160`). `infrastructure/core/gui/InfrastructureGuiListener.java:18-34` sets no priority and no `ignoreCancelled`.
- Impact: possible item theft from button or take-only slots, or duplication if a display item is collectable.
- Fix sketch: cancel `COLLECT_TO_CURSOR`, and number-key and off-hand swaps into protected slots; set listener priority to HIGH with `ignoreCancelled = true`; add a test per action.
- Effort: 2 days. Reproduce first on a test server.

### F4. Viewer refresh cost is players times all instances
- Evidence: `infrastructure/virtual/display/DisplayTransport.java:435` and `:446` call `refreshViewers(player, instances.values())` for each moved player, although a chunk index exists at `:85`. `:529` walks every chunk set on removal. Movement is fed by `PlayerMoveEvent` (`DisplayTransportListener.java:35`).
- Impact (estimate, not measured): with 100 players and 10,000 instances, one refresh pass is 1,000,000 distance checks; at 100,000 instances it is 10,000,000. Measure with `/mineplus perf` and Spark.
- Fix sketch: iterate only the chunks within view distance of the player through `byChunk`; store each instance's chunk key so removal is one lookup.
- Effort: 3 days.

### F5. Persistence has no schema version and is not world-scoped
- Evidence: `infrastructure/persistence/sqlite/SqliteMigrationRunner.java:16-33` creates tables with an id and a JSON payload, no version table and no indexes. `SqliteMultiBlockRepository.java:53` has a `replaceAll` path. Pool size is 1 (`SqliteConnectionFactory.java:43`).
- Impact: no safe way to change the stored shape; copying or resetting a world leaves machine rows behind; bulk saves rewrite everything.
- Fix sketch: add a version table and numbered migrations; add world and chunk columns with an index; write only dirty rows.
- Effort: 10 days with a migration test from current data.

## Medium

### F6. Machine tick and heartbeat walk every instance
- Evidence: `infrastructure/core/multiblock/lifecycle/MultiBlockLifecycleManager.java:455` and `:569-576` loop over `registry.getInstances()`; the interval is 20 ticks (`:37`).
- Impact (estimate): linear in total machines, including idle and unloaded ones. Fine at 1,000; a visible spike once per second at 100,000.
- Fix sketch: keep an active set (loaded chunk and has work), and spread it across ticks under a time budget.
- Effort: 8 days.

### F7. Area clear bypasses events and protection
- Evidence: `infrastructure/virtual/VirtualBlockManager.java:257-271` sets blocks to air directly. `MultiBlockLifecycleManager.java:273-275` grants full access when the actor is null, op or creative. No hooks for WorldGuard, GriefPrevention or similar exist (zero hits).
- Impact: custom structures can remove blocks inside protected regions and drop nothing.
- Fix sketch: fire a cancellable event per edit batch and call `BlockBreakEvent` for player-caused edits; add optional protection hooks.
- Effort: 5 days.

### F8. Pack delivery: open bind address, plain HTTP, single pack
- Evidence: default `LOCAL_HOST: 0.0.0.0` (`config/ConfigManager.java:299`), port 8163 (`:302`); `pack/LocalPackEndpoint.java:61` serves plain HTTP with no rate limit; `pack/PackDeliveryService.java:227-235` uses `setResourcePack`, which replaces any other pack.
- Impact: an extra public port by default; cannot coexist with another plugin's pack.
- Fix sketch: document `PUBLIC_URL` (`:306`) as the production path, add external host upload, use `addResourcePack` where the server has it, add a simple per-address limit.
- Effort: 5 to 10 days.

### F9. No stable extension surface
- Evidence: no Bukkit events fired and no ServicesManager use (zero hits); addons reach in through `infrastructure/PluginContext.java` (seven `bootstrap` overloads at `:113-217`); `core/events/HookBus.java:26-53` has no cancellation or priorities. Only 3 `@Deprecated` and no `@since` markers exist.
- Impact: any internal refactor can break addons; other plugins cannot react to Mineplus actions.
- Fix sketch: a small versioned API interface registered as a service, plus cancellable events for place, break, interact, machine start and finish.
- Effort: 20 days.

### F10. Thin tests, no CI
- Evidence: 9 runners with 1,719 lines for 64,967 lines of source; `build.gradle:14-16` declares no repositories, so JUnit or a mock server cannot be added without a build change.
- Fix sketch: add repositories for test scope only; golden-file tests for pack output; CI running `./gradlew check` and the Blockbench plugin test.
- Effort: 10 days.

### F11. Periodic whole-server scans
- Evidence: `getOnlinePlayers` in tick paths at `infrastructure/mob/MobBehaviorEngine.java:627`, `infrastructure/mob/custom/MobRenderEngine.java:641,1081`, `infrastructure/virtual/animation/ModelAnimationManager.java:370`; `getNearbyEntities` at `infrastructure/mob/BehaviorContext.java:535,835`, `MobBehaviorEngine.java:212,736`, and in three actions. Whether each is per tick is **UNVERIFIED**.
- Fix sketch: share one nearby-player cache per tick; throttle target scans per mob.
- Effort: 5 days.

### F12. Version-sensitive API use
- Evidence: `PlayerAnimationEvent` at `infrastructure/input/HeldItemInput.java:785`; a `ServerPlayer` reference at `ReflectionNmsAdapter.java:324` (whether the handle is cached is **UNVERIFIED**). Version parsing uses a generic pattern (`util/ServerVersion.java:19`), which does not assume a leading `1.`. No hits for `PotionEffectTypeCategory`, `PotionBrewer` or subclasses of `ItemStack`.
- Fix sketch: cover both in the smoke test; keep the Paper event probe already in `HeldItemInput`.
- Effort: 2 days.

## Low

| Id | Finding | Evidence | Fix | Effort |
| --- | --- | --- | --- | --- |
| F13 | Docs and version drift | `README.md:10` badge says 1.0.0 against `build.gradle:6` 1.3.0; Blockbench `README.md:1` says v5.0 against plugin v6.1.0 (`mineplus_generic_display.js:412`); `CHANGELOG.md:8-10` describes plugin v2 | Sync all three | 0.5 day |
| F14 | Legacy text API | 259 `ChatColor` uses in 12 files; Adventure jars are in `libs/` | Move to Adventure components | 5 days |
| F15 | Metrics and update check run unconditionally | `MineplusPlugin.java:42-43` | Config switch for the update check; bStats has its own opt-out | 0.5 day |
| F16 | Studio dev server listens on all interfaces | `devtools/studio/server/server.mjs:1257` | Bind to 127.0.0.1 by default | 0.5 day |
| F17 | Two `.bbmodel` parsers | `infrastructure/virtual/BbModelImporter.java` (1,255 lines) and `infrastructure/mob/custom/model/BbModelParser.java` (912) | Merge behind one reader | 5 days |

## Checked and found sound

- Player-quit cleanup exists in the stance, GUI, input, transport and pack trackers; no per-player leak was found.
- The pack endpoint accepts GET only and matches artifact names against a pattern before reading files (`pack/LocalPackEndpoint.java:105-129`).
- The Netty input path is optional and off by default (`config/ConfigManager.java:416`).
- Pack format selection has overlay handling (`pack/PackFormat.java:101,110`).
