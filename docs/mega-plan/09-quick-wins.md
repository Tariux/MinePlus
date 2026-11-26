# 09 Quick wins

Each under one developer-day, each independently shippable. Ordered by value. Evidence in `02-audit-findings.md`.

| # | Change | Benefit | Where |
| --- | --- | --- | --- |
| 1 | Cancel `COLLECT_TO_CURSOR`, number-key and off-hand swaps into protected GUI slots; listener priority HIGH + `ignoreCancelled` | Closes the only known exploitable class of bug (F3) — even if the repro fails, the hardening is 2h | `infrastructure/core/gui/AbstractMachineGui.java:101-106`, `InfrastructureGuiListener.java:18-34` |
| 2 | Fix version drift: README badge → 1.3.0, Blockbench README → plugin v6.1.0, rewrite CHANGELOG plugin entry | Stops users from trusting a wrong version (F13) | `README.md:10`, `devtools/blockbench-plugin/README.md:1`, `CHANGELOG.md:8-10` |
| 3 | Make the update check conditional on config (default off for new installs) | One less unconditional network call per boot (F15) | `MineplusPlugin.java:42` |
| 4 | Bind the pack endpoint to `127.0.0.1` unless explicitly overridden; document `PUBLIC_URL` for production | Removes an accidental public HTTP listener (F8) | `config/ConfigManager.java:299` |
| 5 | Add `folia-supported` documentation guard: a startup log warning listing the raw Bukkit-scheduler count when Folia is detected | Converts "README says Folia" (F1) into an honest runtime signal before the real port | new check in `MineplusPlugin.onEnable` |
| 6 | Cache `ServerVersion` parse result (if not already) and add a unit test with `26.3` and `1.21.11` strings | Locks the version-parsing seam the whole compat story rests on (F12) | `util/ServerVersion.java:19` |
| 7 | Replace `replaceAll` bulk save with dirty-row tracking in the multiblock repository (no schema change yet) | Removes full-rewrite saves now; schema migration (F5) stays a separate PR | `SqliteMultiBlockRepository.java:53` |
| 8 | Store each instance's chunk key at registration so viewer-removal is one map lookup | Cheap first half of the F4 fix, before the indexed refresh | `DisplayTransport.java:529` |
| 9 | Add `repositories {}` for test scope + one MockBukkit-free pure-logic test of pack format selection | First step of F10 without touching the stub-only compile guarantee | `build.gradle:14-16` |
| 10 | Fire the existing PerfRegistry timers around the machine heartbeat and viewer refresh loops | Makes F4/F6 impacts measurable the same day instead of estimated | `MultiBlockLifecycleManager.java:455`, `DisplayTransport.java:435` |
| 11 | Add a cancellable batch event around area-clear in the virtual block manager (no protection hooks yet, just the event) | First API-v1 event ships with zero behavior change; WorldGuard work later plugs in | `infrastructure/virtual/VirtualBlockManager.java:257-271` |
| 12 | Delete-or-sync the stale `CHANGELOG.md` plugin section and add a release checklist linking `docs/version-compatibility.md` | Keeps the version story honest between releases | `CHANGELOG.md` |

Not quick wins, deliberately: schema migration (F5), scheduler conversion (F1), API facade (F9) — each is a real project with a migration path in `05`/`07`.
