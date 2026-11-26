---
name: mineplus-wizhbang
description: Turn a model idea (a machine, mechanism or animated contraption) into a Mineplus-core-compatible design — decide placement (core engine vs example plugin), author config-only multiblock types with rotation.* / guiOptions behaviour, verify the .bbmodel against the core importer, and wire pack-side rendering driven by core networks. Use when the user says "make a Create-style X", "add a machine that spins/powers", or asks where a new mechanism belongs in Mineplus.
---

# Mineplus Wizhbang — model idea to core-compatible machine

A repeatable path from "wouldn't it be cool if…" to a shipped, buildable
Mineplus machine. Optimised for the strongest constraint this repo keeps
re-learning: **the core already has engines** — check before you build.

## 0. Check the core first (mandatory)

Before writing any engine code, search `src/main/java/com/mineplus/infrastructure/core/`:

| Idea mentions | Existing core engine |
|---|---|
| spins, rotates, power, stress, gears, shafts | `core/network/KineticGrid` + `core/network/rotation/RotationNetworkService` |
| energy, fuel, charge | `core/network/EnergyGrid` / `EnergyNetworkService` |
| items moving, belts, pipes | `core/network/LogisticsGrid` |
| messages between machines | `core/network/MessageBus` / `CommandRelay` |
| crafting, processing | `core/multiblock` `MachineProcessManager` |
| mob behaviour | `core/mob` behavior engine |

If the engine exists, your job is config + a thin feature class. If it
genuinely does not exist, see §4 for where it goes.

## 1. Decide placement: core vs example plugin

Rule: **mechanisms live in core, machines live in examples.**

- **Core** (`src/main/java/.../infrastructure/…`): anything several modules
  would reuse — network solvers, lifecycle wiring, per-tick tick passes,
  the public `PluginContext` accessor. Pure computation stays split from
  Bukkit types (see `KineticGrid` vs `RotationNetworkService`).
- **Example plugin** (`examples/mineplus-<name>/`): concrete machines, their
  models, JSON types, listeners and test commands. They compile against
  `build/libs/mineplus-<version>.jar` and only render/consume core state.
- Litmus test: could a different plugin author want this behaviour with
  different content? Behaviour → core; content → example.

## 2. Author the models (.bbmodel)

Follow `mineplus-model-authoring`; for rotating machines add:

- One bone per moving part; name its loop clip `spin` (continuous) or
  `swing` (toggle/lever). Loop mode LOOP, embedded textures.
- Author the loop for 1x speed; the runtime replays the clip at the
  network's solved rate, so loop duration = one revolution at base speed.
- Verify through the real pipeline, never by eye:
  ```bash
  # parse with the core importer (VirtualModel: cubes/bones/animations)
  # then full audit:
  node devtools/studio/agent.mjs audit --json
  ```
  Models outside watched roots are not audited by path — import-check them
  explicitly.

## 3. Declare machines config-first

One JSON per type in `defaults/multiblocks/`; behaviour goes in
`guiOptions` so no feature code is needed:

- Kinetic (rotation networks): `rotation.kind` (`source|consumer|shaft`),
  `rotation.su`, `rotation.stress`, `rotation.speed`, `rotation.cogwheel`
  — full table in `docs/rotation-networks.md`.
- GUI title, upgrades, model path and levels use the standard multiblock
  JSON schema (copy any `examples/mineplus-fun` type as a template).

The core picks these up automatically at type registration
(`PluginContext.finalizeSetup()` registers profiles for every type,
present and future).

## 4. Wire the thin feature class

Pattern (copy `examples/mineplus-machine`):

1. `ModuleFeature` subclass; `onEnable()` installs models + JSONs via
   `context.moduleSupport().installDefault(...)`.
2. Consume the core service: `context.rotationNetworks().setStateSink(...)`
   and drive `AnimationApi.playAnimation(instanceId, clip,
   AnimationPlayback.speed(rate))` from solved state — never animate
   machines off your own scheduler.
3. Interactions: a small `Listener` plus
   `context.moduleSupport().resolveLooked(player, reach, null)`.
4. Test bench: override `command()` returning a `SubCommand`
   (`place <type>` / `status` / `remove`), like `MachineSubCommand`.

If you added core engine code in step 1: expose it as a `PluginContext`
accessor, wire lifecycle + tick pass in `finalizeSetup()`, and add a
plain-Java test under `src/test/java/com/mineplus/test/` following
`KineticGridTest` (runners run via `./gradlew testRunners`).

## 5. Verify before claiming done

```bash
./gradlew compileJava testRunners            # core green
./gradlew -p examples/mineplus-<name> build  # plugin jar builds
node devtools/studio/agent.mjs audit --json  # pack audit
```

Baseline rule: if a suite fails, check whether it failed before your
change (`git stash` if needed) — pre-existing failures are reported, not
fixed silently.
