# mineplus-machine

Create-style mechanical power showcase built **entirely on the core
rotation networks** (see `docs/rotation-networks.md`). Resourcepack
rendering only — no client mod.

## Machines

| Type                  | Behaviour                        | Model clip |
|-----------------------|----------------------------------|------------|
| `water_wheel_weak`    | Source, base 256 SU @ 3.0 deg/tick, scaled by adjacent flowing water | `spin` 10 s |
| `water_wheel`         | Source, base 512 SU @ 6.0 deg/tick, scaled by adjacent flowing water | `spin` 8 s  |
| `water_wheel_strong`  | Source, base 1024 SU @ 10.0 deg/tick, scaled by adjacent flowing water | `spin` 6 s  |
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
3. scans each wheel's 3×3 shell every second (`WaterFlowTask`) and sets
   its SU to `8 + water × tier-rate`, so output follows the water that
   actually drives it.

## Build

```bash
./gradlew build          # in the repo root first (produces the core jar)
./gradlew -p examples/mineplus-machine build
```

Drop `build/libs/mineplus-machine-1.0.0.jar` on a Paper 1.21 server with
Mineplus installed.

## Try it

```
/machine place water_wheel_strong
/machine place rotation_shaft        # touch the wheel
/machine place cogwheel              # touch the shaft — it spins the other way
/machine status                      # look at any machine
/machine remove
```

Place a brake lever touching any machine and right-click it: the whole
network grinds to a stop (over-stressed) and the lever swings; click again
to release.
