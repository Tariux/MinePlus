# Whizbang ledger — plan: docs/whizbang/plans/2026-11-19-compat-rewrite-plan.md
Task 1: complete (commits 09db627..be837ed, tests: gradle testRunners -q → CustomMobPipelineTest: all checks passed)
Ruling: task-done script word-splits space-containing repo paths (xargs dirname); ledger maintained manually
Task 2: complete (commits be837ed..7d9840a, tests: gradle build -q → SchedulerCompatTest: all checks passed)
Ruling: bStats vendored Metrics.java keeps its own Folia detection (upstream code, untouched by contract)
Ruling: DisplayTransport/MobRenderEngine/InfrastructureEngine plain Bukkit timers predate this plan and have no probe — left unchanged (out of scope)
Ruling: VisualUnitService failure log drops the scheduler cause (repeat() swallows internally); message now generic
Task 3: complete (commits 7d9840a..1ee48bd, tests: gradle testRunners -q → SchedulerCompatTest: all checks passed)
Ruling: verification sweep confirmed Sounds/ParticleFx/MobYamlLoader.entityType already throw-safe (no change needed)
Task 4: complete (commits 1ee48bd..cb0456c, tests: gradle build -q → SchedulerCompatTest: all checks passed)
Ruling: shape-probe order verified already mojang-first with structural fallbacks — unchanged pending research (T5)
Task 9: complete (commits cb0456c..5487164, tests: gradle testRunners --no-daemon → CustomMobPipelineTest: all checks passed)
Ruling: existing base-layer transition sampled the current clip at full weight (fade-out, not crossfade); current clip now weights by blend — contract per design
Ruling: hurt-direction bias deferred (needs direction plumbed through notifyHurt; not in task steps)
Ruling: gradle daemon hangs on this machine during the session; verification uses --no-daemon
Task 5: complete (research folded in; ReflectionNmsAdapter/InputChannel probes verified mojang-first + structural — no 26.x packet break found, no change needed per probe-first philosophy)
Task 7: complete (Paper 26.2 javadoc still documents setResourcePack(url, hash, ...) overloads; existing cascade remains valid — no new probe needed)
Task 11: complete (Mind + mind() builder + mindSnapshot; mobfun definitions migrated, evil pig wanders)
Task 12: complete (docs: CHANGELOG/migration-guide/custom-mobs/pack-system/config-reference/AGENTS; verification: npm run build-all green x4 + gradle testRunners green x5)
Ruling: T5/T7 closed as verified-no-change (probe-first philosophy; research folded in via minecraft.wiki Pack format page + Paper 26.2 javadoc)
Ruling: api-version 1.21 cannot be rejected by a newer server (floor declaration semantics) - risk retired
Ruling: scripts/pack.js ceiling bumped locally but the scripts/ dir is gitignored (stays untracked by repo policy)
