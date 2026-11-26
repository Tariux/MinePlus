# mineplus-machine

Create-style mechanical power showcase built **entirely on the core
rotation networks** (see `docs/rotation-networks.md`). Resourcepack
rendering only — no client mod.

## Machines

| Type                  | Behaviour                        | Model clip |
|-----------------------|----------------------------------|------------|
| `water_wheel_weak`    | Source: only moving water beside it; SU = flowing cells × tier rate    | `spin` 10 s |
| `water_wheel`         | Source: only moving water beside it; SU = flowing cells × tier rate    | `spin` 8 s  |
| `water_wheel_strong`  | Source: only moving water beside it; SU = flowing cells × tier rate    | `spin` 6 s  |
| `rotation_shaft`      | Coaxial carrier                  | `spin` 8 s  |
| `cogwheel`            | Carrier, meshes sideways         | `spin` 8 s  |
| `cogwheel_vertical`   | Carrier, meshes sideways         | `spin` 8 s  |
| `cogwheel_horizontal` | Carrier, meshes sideways         | `spin` 8 s  |
| `rotation_brake`      | Consumer; interact to brake      | `swing` 6 s |

Every behaviour above is data — `rotation.*` guiOptions in
`src/main/resources/defaults/multiblocks/*.json`. The Java code only:

1. renders solved members (`spin` replayed at the network's live speed,
   negative rates for counter-rotating meshed gears; `swing` for the
   lever while braking),
2. toggles the brake on right-click (`BrakeListener`), and
3. counts the **flowing** water in each wheel's 3×3×1 plane
   (`WaterFlowTask` + `WaterFlow`) and sets `SU = flowing cells × tier
   rate`. Still water (a lake or pool) is ignored, there is no base
   output, and a freshly placed wheel starts at SU 0 and is rescanned in
   the same place event — it never spins without water beside it.

Machines are pack items as well as blocks: `/machine give <type>` and the
`/machine shop` GUI hand out the registered resourcepack item for the
model (`<type>_lvl_<level>`), so the item in hand renders as the machine
itself and places on right-click.

## Build

```bash
./gradlew build          # in the repo root first (produces the core jar)
./gradlew -p examples/mineplus-machine build
```

Drop `build/libs/mineplus-machine-1.0.0.jar` on a Paper 1.21 server with
Mineplus installed.

## Try it

```
/machine give water_wheel          # resourcepack item in hand; right-click to place
/machine shop                      # GUI listing every machine, click to take one
/machine place water_wheel_strong  # or place directly (axle runs along one axis)
/machine place rotation_shaft      # on the wheel's axle cell (next to the centre)
/machine place cogwheel            # touch the shaft — it spins the other way
/machine status                    # look at any machine
/machine remove
```

A wheel only turns while water is moving beside it: still lakes and pools
give SU 0, streams and waterfalls drive it. Place a shaft touching the
wheel's centre on the axle line to take the rotation.

Place a brake lever touching any machine and right-click it: the whole
network grinds to a stop (over-stressed) and the lever swings; click again
to release.
