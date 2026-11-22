# Version compatibility: Minecraft 1.21 to 26.3

One Mineplus jar is meant to run on every Spigot and Paper release from 1.21 to
26.3. This page lists those releases, what changes between them, which ones to
prefer, and how far the claim has actually been checked.

Data collected on 2026-11-21 from the Mojang version manifest, misode/mcmeta,
minecraft.wiki, the Spigot BuildTools version list and the Paper downloads API.

## What was checked, and what was not

| Check | Result |
| --- | --- |
| Bytecode link check of the core, gun, fun and mobfun jars against every Spigot API (18 lines) and Paper API (15 lines) from 1.21 to 26.3 | 0 unresolved classes, methods or fields; 0 class/interface call mismatches |
| Pack format and data pack format tables in code against the release data | match for all 17 releases |
| Plugin enabled on a running server of each version | **not done** |
| Models and animations seen in a real client of each version | **not done** |

The link check proves the jar's API calls resolve. It does not prove runtime
behaviour: reflection (the NMS display adapter), commands, pack delivery and
what the client draws are untested outside the versions the maintainers play on.

## Releases

Java is the minimum runtime the server needs. "Pack" and "Data" are the
resource pack and data pack format numbers. Spigot has a BuildTools target for
every row. The Paper column is the channel of its newest build on the check date.

| Version | Released | Java | Protocol | Pack | Data | Paper | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1.21 | 2024-06-13 | 21 | 767 | 34 | 48 | stable (130) | Baseline. Legacy `custom_model_data` overrides only. |
| 1.21.1 | 2024-08-08 | 21 | 767 | 34 | 48 | stable (133) | Same as 1.21. The API the plugin is compiled against. |
| 1.21.2 | 2024-10-22 | 21 | 768 | 42 | 57 | no build | `item_model` component added. Replaced by 1.21.3 a day later. |
| 1.21.3 | 2024-10-23 | 21 | 768 | 42 | 57 | stable (83) | API break: `Attribute`, `Biome`, `Sound`, `Art`, `Fluid` become interfaces; `GENERIC_` attribute names dropped. |
| 1.21.4 | 2024-12-03 | 21 | 769 | 46 | 61 | stable (232) | Item model definitions (`assets/<ns>/items/`), `range_dispatch`, cooldown and custom model data properties. |
| 1.21.5 | 2025-03-25 | 21 | 770 | 55 | 71 | alpha (114) | Data-driven mob variants. |
| 1.21.6 | 2025-06-17 | 21 | 771 | 63 | 80 | stable (48) | Element rotation angle no longer limited to 22.5 degree steps (still within -45 to 45). |
| 1.21.7 | 2025-06-30 | 21 | 772 | 64 | 81 | stable (32) | No pack behaviour change found. |
| 1.21.8 | 2025-07-17 | 21 | 772 | 64 | 81 | stable (60) | Same protocol and formats as 1.21.7. |
| 1.21.9 | 2025-09-30 | 21 | 773 | 69.0 | 88.0 | alpha (59) | `pack.mcmeta` uses `min_format`/`max_format`; `supported_formats` removed; formats gain a minor number. |
| 1.21.10 | 2025-10-07 | 21 | 773 | 69.0 | 88.0 | stable (130) | Same protocol and formats as 1.21.9. |
| 1.21.11 | 2025-12-09 | 21 | 774 | 75.0 | 94.1 | stable (132) | Multi-axis element rotation; -45 to 45 limit removed. Last Java 21 and last obfuscated release. |
| 26.1 | 2026-03-24 | 25 | 775 | 84.0 | 101.1 | no build | New year.drop.hotfix numbering. Java 25 required. Server is unobfuscated. |
| 26.1.1 | 2026-04-01 | 25 | 775 | 84.0 | 101.1 | alpha (29) | Same formats as 26.1. |
| 26.1.2 | 2026-04-09 | 25 | 775 | 84.0 | 101.1 | stable (74) | Same formats as 26.1. |
| 26.2 | 2026-06-16 | 25 | 776 | 88.0 | 107.1 | stable (135) | |
| 26.3 | 2026-09-15 | 25 | 777 | 97.1 | 121.0 | stable (257) | Newest release covered. |

## How behaviour differs by version

**Item models.** Below 1.21.4 an item can only change shape through
`custom_model_data` overrides on a vanilla host model. From 1.21.4 the pack
ships item model definitions. Modules should ask `PackApi.isAvailable()` rather
than test the version.

**Animation.** Frames play on the client clock through an item cooldown. 1.21.4
and later use a `range_dispatch` on the cooldown; older versions use `cooldown`
predicates in the host model's overrides, which must stay sorted ascending.

**Element rotation.** Clients below 1.21.6 reject a whole model, and draw the
purple and black cube, if any element angle is off the 22.5 degree steps.
`ModelGeometryGuard` snaps angles for those servers, so hinges look coarser
there. Open point: angles beyond 45 degrees became legal only in 1.21.11, and
how the guard treats them on 1.21.6 to 1.21.10 was not re-checked for this page.

**ViaVersion / ViaBackwards clients.** Via translates *protocol*, not
resource packs: a client that joins through Via receives, byte for byte, the
pack the server compiled for the *server's* version. So a pre-1.21.4 client
on a 1.21.4+ server cannot read `item_model` pack items (it shows the vanilla
skin), and a pre-1.21.6 client rejects models with off-grid element angles
(the purple and black cube) even though the server version allows them. The
pack is still compiled exactly once for the server — no per-client packs.
`ViaCompatProbe` (reflective, no ViaVersion dependency) reads the real client
protocol at delivery time and logs a targeted warning when that client's era
cannot render the pack's representation; modules should gate animation
gameplay on `PackApi.isAvailable()`, which reports whether the pack
subsystem can present items at all.

**pack.mcmeta.** `PackFormat.usesFormatRange` switches the manifest shape at
1.21.9. A server newer than the table gets the newest known format.

**Server API.** The jar is compiled against 1.21.1. From 1.21.3 several enums
are interfaces, so code must not call methods on `Biome` or `Sound` directly,
nor read `Attribute.GENERIC_*`. Use `MobAttributes.get(entity, "MAX_HEALTH",
fallback)`, read a biome name through `Keyed`, and pass `Sound` constants to
`Sounds.play(Location, Sound, float, float)`. `ApiShapeGuardTest` fails the
build if a compiled class breaks this rule.

**Java.** 1.21 to 1.21.11 run on Java 21; 26.1 and later need Java 25. The
plugin is built for Java 21, which a Java 25 server loads.

## Recommended versions

These are recommendations from the data above, not test results.

| Use | Version | Why |
| --- | --- | --- |
| Best overall | Paper 26.3 | Newest, stable channel, full item models and free rotation. |
| Best on Java 21 | Paper 1.21.11 | Last Java 21 release; models render exactly as authored. |
| Oldest with models as authored | Paper 1.21.6 to 1.21.8 | Free angles within 45 degrees. |
| Oldest with modern item models | Paper 1.21.4 | Angles are snapped to 22.5 degree steps. |
| Avoid | 1.21.2, 26.1 | No Paper build; each was replaced within days. |
| Avoid on Paper | 1.21.5, 1.21.9, 26.1.1 | Newest Paper build is still alpha; use the next patch. |
| Use only if you must | 1.21, 1.21.1, 1.21.3 | Legacy overrides, snapped angles. |

Spigot works on the same versions through BuildTools. Paper is preferred.

## Decision: one jar, compiled against the oldest API

Options considered:

1. **One jar on the 1.21.1 API with guarded seams (chosen).** One download for
   every server. Cost: newer API must be reached by name or reflection, and
   nothing at compile time catches a call that only exists on old versions.
2. **One jar per version family.** Each compiles against its own API, so the
   compiler catches breaks. Cost: three or more artifacts to build, test and
   explain to server owners.
3. **Multi-module adapters per version.** Cleanest code. Cost: the largest
   rewrite, for a problem three call patterns currently cover.

Risk to prove first: the plugin enabling on a real 1.21.3+ and a real 26.x
server. Next steps, each reversible: boot test on Paper 1.21.1, 1.21.11 and
26.3 and on Spigot 26.3; then an in-game model and animation check on one
legacy and one modern client; then add the link check to the build.
