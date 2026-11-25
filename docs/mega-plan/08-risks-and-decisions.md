# 08 Risks and decisions

## Open questions for you (Q1–Q5; per the brief, no more than five)

**Q1. Who is the primary customer of the framework?** (a) Server owners using it as a content platform, (b) plugin developers building on the API, (c) network operators running minigames. It changes the order of Phases B/D/E: if (c), D moves up; if (a), C does. *Working assumption: (b), because the vision names "framework" and the success criterion is developer-facing.*

**Q2. Is Folia a launch requirement or an aspiration?** Real Folia support (F1, 15 days) is a concurrency rework, not a flag. Competitors advertise it (03 §2). If most of your users run Paper, Phase A4 can ship the wrapper without the flag and defer the concurrency work. *Assumption: ship the wrapper in A, flag when a Folia server actually runs your acceptance tests.*

**Q3. Scale target?** The plan's numbers assume up to ~10k display instances and ~100k machines as the stress envelope (F4/F6 estimates). If your real target is 1k machines on one server, Phases A5/B4 shrink and Phase D matters more than C. *Assumption: 10k instances / 100k machines.*

**Q4. Can the core jar depend on a network at runtime?** Pack self-hosting over HTTP (F8) works offline; external CDN hosting does not. If your servers are offline-first, C8 keeps self-host primary and hardens it instead of adding hosts. *Assumption: online-first, self-host retained as fallback.*

**Q5. May `build.gradle` gain real repositories (Paper Maven, test deps)?** The empty `repositories {}` (`build.gradle:14-16`) is a deliberate stub-only compile choice, and it is correct for shipped code — but it blocks MockBukkit, run-paper smoke tests and any CI (F10, 03 §6). Adding repositories **test/dev scope only**, keeping `libs/` stubs for `compileOnly`, preserves the Spigot-link guarantee. *Assumption: yes, test-scope only; this unlocks A1/A6.*

## Decision log

| Decision | Choice | Reason | Source |
| --- | --- | --- | --- |
| D1 | Single jar for 1.21–26.x, reflective NMS seams | Correct shape across the 26.1 de-obfuscation boundary (03 §1); keep and smoke-test it | `ReflectionNmsAdapter` |
| D2 | Stay on `ItemMeta` + reflection for `item_model` | Data component API "backwards compatibility is not promised" | 03 §1 |
| D3 | Java 21 bytecode until 1.21.x is dropped | Paper needs Java 25 only from 26.1; one toolchain serves both now | 03 §1 |
| D4 | ECS rejected for now (05 P3) | P2's active set serves the scale; no tests to protect a rewrite | 02 F10 |
| D5 | Orchestration never in the core jar | Every reference design splits proxy/plugin/manager; existing allocators cover it | 03 §5, §7 |
| D6 | Minestom instance tier rejected | Second implementation of all rendering/mob code | 03 §5 |
| D7 | Annotation/DI frameworks rejected | Real DX problem is `PluginContext` size; builder facade fixes it without magic | 03 §6 |
| D8 | Core shaders and default packet interception avoided | Client-update fragility; already optional in repo | 03 §7 |
| D9 | Persistence goes versioned SQL first, chunk-attached later | F5 must close before machines multiply stored state | 02 F5 |
| D10 | Minigame/network layer is a separate jar over API v1 | Keeps the core lean and the API the contract | 05 P10 |

## Prototype list (spikes before the phase that needs them)

| Spike | Success | Failure ⇒ |
| --- | --- | --- |
| S1 (Phase A): smoke CI boots all five server types and greps for exceptions | all green for current jar | scope claims down to what boots |
| S2 (A): Folia: one machine + one mob running via `SchedulerCompat` entity/region variants on a Folia 26.x server | no `UnsupportedOperationException` in 1h soak | keep flag off; Folia becomes Phase C work |
| S3 (A): GUI exploit repro of F3 on a test server | exploit reproduces ⇒ fix, then regression-test | record as not-exploitable, still harden clicks |
| S4 (B): hot reload: swap a machine definition under a live instance, no state loss | instance keeps inventory/state | reload = restart-boundary for that type |
| S5 (C): energy union-find over 10k machines: place/break storm stays < 1 ms/edit and tick ≤ 2 ms | both budgets met | fall back to Slimefun-style coarse nets + caps |
| S6 (C): no-viewer skip: entity count vs client-visible correctness at 200 viewers | no visual regressions | keep always-on updates, lower instance cap |
| S7 (D): template instance start-to-ready ≤ 15 s | met | pre-warm pool or Slime-format worlds |

Standing risks to watch across all phases: version-coupled NMS breaking silently at each 26.x release (mitigated only by S1 staying in CI forever); pack-related client version drift; and the single- maintainer bus factor visible throughout the repo history.
