# 03 Research and benchmark

Sources were fetched on 2026-11-25. Every quoted sentence is copied from the page at the URL beside it. Anything tagged **UNVERIFIED** comes from general knowledge and was not fetched in this run (the web budget for the task ran out), so confirm it before acting on it.

Source quality: "official" means project docs or the project's own repository; "third-party" means a blog, comparison page or community wiki.

## 1. Target platform

| Fact | Quote | Source | Quality |
| --- | --- | --- | --- |
| Paper 26.1+ needs Java 25 | "Paper requires at least Java 25 to run" and the table row "26.1+ / Java 25"; "1.20 to 1.21.11 / Java 21" | https://docs.papermc.io/paper/getting-started/ | official |
| 26.3 is the version Paper's own setup guide targets | `compileOnly("io.papermc.paper:paper-api:26.3.build.+")` with `JavaLanguageVersion.of(25)` | https://docs.papermc.io/paper/dev/project-setup/ | official |
| Version strings changed at 26.1 | "Before `26.1` (`1.21.11` and below), the version string format used was `{VERSION}-R0.1-SNAPSHOT`" | https://docs.papermc.io/paper/dev/project-setup/ | official |
| No obfuscated plugins from 26.1 | "From Minecraft version 26.1 onwards, Paper no longer supports obfuscated plugins due to Mojang themselves removing obfuscation of their server JAR." | https://docs.papermc.io/paper/dev/userdev/ | official |
| Reobfuscated jars stop working | "reobfuscated plugins will not work from Paper 26.1 onwards" | https://docs.papermc.io/paper/dev/userdev/ | official |
| Mapping assumption on 1.20.5 to 1.21.11 | "all Spigot/Bukkit plugins will be assumed to be Spigot-mapped if they do not specify their mappings namespace in the manifest" | https://docs.papermc.io/paper/dev/userdev/ | official |
| Internals are discouraged | "Using Minecraft internals is not recommended." | https://docs.papermc.io/paper/dev/internals/ | official |
| Folia needs an explicit flag | "Plugins can't run on Folia if you don't have this field in your paper-plugin.yml (or plugin.yml, respectively): folia-supported: true" | https://docs.papermc.io/paper/dev/folia-support/ | official |
| Folia has four schedulers | Global, Region, Async and Entity schedulers; on Paper they are "internally handled" | https://docs.papermc.io/paper/dev/folia-support/ | official |
| Bukkit scheduler fails on Folia | "a library that calls the Bukkit scheduler throws `UnsupportedOperationException` on Folia" | https://codexe.eu/en/guides/make-a-folia-plugin | third-party |
| Folia tracks 26.x | A `ver/26.2.x` branch exists; the README warns of "possible race conditions in plugin held data" | https://github.com/PaperMC/Folia/blob/ver/26.2.x/README.md | official |
| Data component API is not stable | "The data component API may experience breaking changes on version updates as Minecraft makes changes to components. Backwards compatibility is not promised." ItemMeta is advised if you "Want to keep cross-version compatibility with your plugin" | https://docs.papermc.io/paper/dev/data-component-api/ | official |

What this means for Mineplus:

- The repo compiles for Java 21 (`build.gradle:10`) and its own compatibility page says "The plugin is built for Java 21, which a Java 25 server loads" (`docs/version-compatibility.md:89-90`). That is consistent with the Paper docs. Keep Java 21 bytecode while 1.21.x is supported; move to 25 only when 1.21.x is dropped.
- The reflective NMS adapter (`src/main/java/com/mineplus/infrastructure/virtual/display/nms/ReflectionNmsAdapter.java`) is the right shape for a single jar across the 26.1 de-obfuscation boundary, because there is nothing to remap. It is also the piece most likely to break silently, so it needs a per-version smoke test.
- Stay on `ItemMeta` plus reflection for `item_model` (already done in `pack/item/PackItemFactory.java:166`). Do not adopt the data component API in the core jar while one jar must serve 1.21 and 26.x.
- **UNVERIFIED:** the deprecation status of `PlayerAnimationEvent`, `PotionEffectTypeCategory` and `PotionBrewer`, `ItemStack` becoming an interface, and a "Paper 26.4 config merge". None was confirmed against Paper javadocs or release notes in this run. Repo impact is known regardless: only `PlayerAnimationEvent` is used (`infrastructure/input/HeldItemInput.java:785`); the others have zero hits.

## 2. Custom content engines

| Project | What the source says | Source | Quality |
| --- | --- | --- | --- |
| Nexo | "Nexo has been tested on Paper & Folia."; "Nexo automatically handles Resource Pack generation." | https://docs.nexomc.com/ | official |
| CraftEngine | "A Spigot/Paper/Folia plugin that enables dynamic creation of custom blocks, items and recipes through configuration." The project page also describes runtime class generation for block states, pack hosting through S3, Dropbox, Lobfile or a custom API, and "smart packet interception" | https://github.com/Xiao-MoMi/craft-engine | official |
| Nexo and CraftEngine block solidity | "Nexo and CraftEngine instead have you list which block positions count as solid." | https://minemodelai.com/en/blog/itemsadder-vs-nexo-vs-oraxen-vs-craftengine | third-party |
| ItemsAdder, Oraxen | **UNVERIFIED**: both are config-driven engines that use note block, tripwire and similar block states as carriers for real custom blocks and item-display or armor-stand furniture. Not fetched. | none | none |

Block strategies, and where Mineplus sits:

- Mineplus has one carrier, `NOTE_BLOCK`, and uses it only as the model that a `BlockDisplay` shows (`pack/block/PackBlockCarrier.java:41-44`). There is no real placed block state, which is why there are no listeners for note-block physics or pistons (zero hits for `BlockPhysicsEvent`, `NotePlayEvent`, `BlockPistonExtendEvent`).
- Display-based blocks avoid the known note-block problems (instrument updates on neighbour change, limited state count), and cost an entity per block. Real block states cost nothing per block at rest but need physics cancelling and a state budget.

Adopt: Folia as a tested target (both Nexo and CraftEngine advertise it); external pack hosting options like CraftEngine's.
Adapt: a hybrid block model. Keep display blocks for anything animated or oddly shaped; add real block states only for high-count static blocks such as ores, and only after spike S3 in `08-risks-and-decisions.md`.
Avoid: packet interception as the default path. CraftEngine does it, and it ties the plugin to protocol details on every release. Mineplus already keeps its Netty path optional and off (`config/ConfigManager.java:416`); keep it that way.

## 3. Blockbench runtime engines

| Project | What the source says | Source | Quality |
| --- | --- | --- | --- |
| BetterModel | "Built on the `item-display` packet."; pack generation "with zero reliance on core shaders"; lists Molang, IK and per-player animation; tested on Paper, Purpur, Spigot and Folia; release 3.7.0 notes "Skip animation packet bundling when a model has no viewers" | https://github.com/toxicity188/BetterModel/ | official |
| ModelEngine, FreeMinecraftModels, Animated Java, blockbench-import-library | **UNVERIFIED**: not fetched. | none | none |

Mineplus compared with BetterModel:

- Same core idea: bones as item displays driven by packets (`infrastructure/virtual/display/DisplayTransport.java`, `infrastructure/mob/custom/MobRenderEngine.java`), with a pose thread (`infrastructure/virtual/animation/ModelAnimationManager.java:857`) and bundle batching.
- Mineplus already has Molang (`src/test/java/com/mineplus/test/MolangEvaluatorTest.java`) and reads `animations`, `animators`, `keyframes`, `interpolation`, `locator` and `null_object` from `.bbmodel` (`infrastructure/virtual/BbModelImporter.java`).
- Gaps against BetterModel: no IK, no documented per-player animation API, and hitboxes are ray-tested in code (`infrastructure/world/HitTestApi.java:23-31`) with no interaction entities (zero hits for `EntityType.INTERACTION`).
- The 3.7.0 note is a direct hint for Mineplus: skip pose and bundle work for models nobody is viewing. Check whether `ModelAnimationManager` already does this before adding it (**UNVERIFIED** either way).

Adopt: no-viewer skip; a per-model viewer count exposed in `/mineplus perf`.
Adapt: interaction-entity hitboxes for furniture and mobs where vanilla click and attack semantics matter.
Avoid: core shaders. BetterModel advertises working without them, and they break on client updates.

## 4. Machines and automation

| Project | What the source says | Source | Quality |
| --- | --- | --- | --- |
| Slimefun | "the default for this setting is set to run these tasks every 10 MC ticks"; "The default of 200 possible network members"; a `cargo-ticker-delay` setting | https://github.com/Slimefun/Slimefun4/wiki/Server-Optimizations | official |
| Slimefun energy nets | Regulator-based networks with generators, consumers, capacitors and connectors, range 6 (community wiki summary) | deepwiki page for Slimefun4, **UNVERIFIED** (third-party, URL not kept) | third-party |
| Create | **UNVERIFIED**: kinetic networks carry a rotation speed, sources add stress capacity, consumers add stress impact scaled by speed, and a network that exceeds capacity stops. Not fetched. Pointer: https://github.com/Creators-of-Create/Create | none | none |

Lessons that carry over with evidence:

- Slimefun ticks machines on a coarse interval and caps network size. Mineplus already ticks machines every 20 ticks (`infrastructure/core/multiblock/lifecycle/MultiBlockLifecycleManager.java:37`) but walks every instance on each pass (`:455`) and has no network concept beyond pairwise links (`infrastructure/core/multiblock/linking/MultiBlockLinkingSystem.java:26-53`).
- A member cap per network is cheap insurance and should be in the first energy design.

Proposed server-side equivalent (design in `05-architecture-proposals.md`, section 5): networks as connected components with a cap, recomputed only when a member is added or removed, solved on a coarse interval. Kinetics is the same graph with two scalars (speed, stress) and one rule (over capacity means stopped). Visible rotation comes from the existing animation system, since nothing physical moves on the server.

## 5. Network architecture

| Project | What the source says | Source | Quality |
| --- | --- | --- | --- |
| Velocity plugin messaging | "On your backend server, only listen for plugin messages if you are sure only a trusted proxy can send them to your server." | https://docs.papermc.io/velocity/dev/plugin-messaging/ | official |
| BlueDragon | A game server, a Velocity proxy plugin and a manager service (queue, parties, allocation) talking gRPC, deployed on Kubernetes with Agones | https://www.bswanson.dev/projects/bluedragon/ and https://developer.bluedragonmc.com/deployment/kubernetes/ | official (project author) |
| ArenaNet | Velocity, Redis, MariaDB and an orchestrator | https://systemlukas.link/projects.html | third-party |
| Shulker | **UNVERIFIED** (URL not kept): a Kubernetes operator with proxy and server fleets on Agones and Redis proxy sync | none | none |
| Minestom, Slime world format, Hypixel SLS | **UNVERIFIED**: not fetched. Pointer: https://github.com/Minestom/Minestom | none | none |

Assessment:

- Every reference design has the same three parts: proxy plugin, shared state (Redis or gRPC service), and something that allocates game servers. None of it belongs inside a gameplay plugin jar.
- Mineplus has zero networking code today (zero hits for `PluginMessage`, `BungeeCord`, `Redis`). Starting this layer is a new product, not an extension.
- Paper or Minestom for the instance tier: Mineplus is built on Bukkit entities, events and NMS reflection, so a Minestom tier would need a second implementation of rendering and mobs. Use Paper instances until a measured need appears (for example more than about 50 instances per node with startup time as the bottleneck). **UNVERIFIED** threshold; it is a planning assumption.

Adopt: the Velocity warning as a hard rule (authenticate anything that arrives by plugin message).
Adapt: BlueDragon's split into proxy plugin, game plugin and manager service, with Redis instead of gRPC to keep the footprint small.
Avoid: building orchestration. Integrate with an existing allocator and keep Mineplus's part to "report state, accept drain".

## 6. Developer frameworks and tooling

All items here are **UNVERIFIED** in this run (not fetched). Pointers for whoever confirms them:

- Paper Lifecycle API and plugin bootstrapper for registry work before freeze: https://docs.papermc.io/paper/dev/lifecycle/
- MockBukkit for unit tests against a mocked server: https://github.com/MockBukkit/MockBukkit
- `run-paper` Gradle plugin (`runServer` task) for a dev server from the build: https://github.com/jpenilla/run-task
- Brigadier command registration through Paper's command API.

Position taken regardless of those details:

- The root build has no repositories at all (`build.gradle:14-16`) and compiles against jar stubs in `libs/`. That blocks every one of these tools. Adding the Paper Maven repository for test and dev-server use only (keeping `libs/` stubs for the shipped compile) is the unlock, and it is a decision for the maintainer (question Q5 in `08-risks-and-decisions.md`).
- Annotation scanning and DI containers are rejected for the core. The examples show the real DX problem is the size of `PluginContext` (seven `bootstrap` overloads at `infrastructure/PluginContext.java:113-217`), which a small builder-style facade fixes without a framework.

## 7. Adopt, adapt, avoid summary

| Decision | Item | Reason |
| --- | --- | --- |
| Adopt | Folia as a tested target | Competitors advertise it; Folia refuses plugins without the flag |
| Adopt | Coarse machine tick plus network member cap | Slimefun's documented defaults |
| Adopt | No-viewer animation skip | BetterModel 3.7.0 release note |
| Adopt | Authenticated proxy messaging | Velocity docs |
| Adapt | Hybrid display and block-state blocks | Display blocks for shape freedom, block states for volume |
| Adapt | External pack hosting | CraftEngine supports several hosts; Mineplus only self-hosts over HTTP |
| Avoid | Data component API in the single jar | Paper says compatibility is not promised |
| Avoid | Default-on packet interception | Version fragility; already optional in Mineplus |
| Avoid | Core shaders | Client update fragility |
| Avoid | Orchestrator inside the plugin | Out of scope; existing tools cover it |
