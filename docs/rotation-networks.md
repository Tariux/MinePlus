# Rotation networks (Create-style kinetic power)

Core module: `com.mineplus.infrastructure.core.network.rotation.RotationNetworkService`
backed by the P6 `KineticGrid` solver. This is the kinetic counterpart of the
energy grid (`EnergyNetworkService`) and is consumed through the same
`PluginContext` accessor.

## What it does

Machines placed as multiblock instances join rotation networks by
6-adjacency of their anchor (centre) cell: only a machine whose anchor
touches another machine's anchor face-on links. A water wheel's ring cells
never link, so a shaft has to sit on the wheel's centre line; diagonal or
ring-edge touches stay out of the network. The service does not yet read
the wheel's facing, so any of the six face neighbours of the centre cell
links. Links are rebuilt from placed instances, so break/re-place and
reload restore the same network. Each solved network computes, Create-style:

- **Stress balance** — sources (water wheels) contribute SU, consumers
  contribute stress demand. Saturation `min(1, SU/stress)` fairly scales
  every member's speed, so an under-powered network slows everything down
  instead of failing.
- **Direction** — coaxial links (shafts, gears stacked on an axle) keep the
  sign; two cogwheels meshed side-on flip it, so meshed gears visibly
  counter-rotate.
- **Brakes** — a braked node joins its network as a huge-stress consumer,
  driving the whole network over-stressed to a standstill.

Solves are dirty-driven (only touched networks re-solve) and run in the
lifecycle post-tick pass. Per-instance accumulated angles are integrated in
the service so animation can play continuously at fractional speeds.

## Declaring a kinetic machine (config only, no code)

Add `rotation.*` keys to the type's min-level `guiOptions`:

```json
"guiOptions": {
  "title": "Water Wheel",
  "rotation.kind": "source",
  "rotation.su": "512",
  "rotation.speed": "6.0"
}
```

| Key                | Meaning                                                       |
|--------------------|---------------------------------------------------------------|
| `rotation.kind`    | `source` (makes SU) / `consumer` (stress demand) / `shaft`    |
| `rotation.su`      | Source capacity in stress units (base value; plugins can make it dynamic via `setSourceSu`) |
| `rotation.stress`  | Consumer demand in stress units                               |
| `rotation.speed`   | Source speed, deg/tick at 1.0 saturation                      |
| `rotation.cogwheel`| `true` — meshes sideways with other cogwheels (sign flip)     |

Types without `rotation.kind` are ignored. Profiles register automatically
for every type in the registry (existing and future) — see
`PluginContext.finalizeSetup()`.

## Consuming the API

```java
RotationNetworkService rotation = context.rotationNetworks();
rotation.setStateSink((instance, state) -> {
    // state.speed(): signed deg/tick (0 when braked/over-stressed)
    // state.direction(): +1 / -1 propagation sign
    // state.angle(): integrated 0..360
});
rotation.setBraked(leverInstance, true);   // engage brake
rotation.connected(idA, idB);              // same network?
rotation.setSourceSu(wheelInstance, su);   // dynamic capacity (water flow, wind, ...)
```

The `machine` example shows the dynamic pattern: `WaterFlowTask` listens
to water-change events (`BlockFromToEvent`, bucket use, water physics),
recounts only the **flowing** water in the wheel's 3x3x1 plane and applies
`SU = flowing water × tier-rate` through `setSourceSu` — with no base
output, so a wheel with no moving water beside it sits at SU 0 and stops.
Still pools never turn a wheel; a still source counts only when it is
draining into a moving neighbour. A wheel also starts at SU 0 the moment
it is placed and is rescanned in the same place event, so it never spins
before its water has been counted.

The flow rule itself is a plain helper, `com.mineplus.infrastructure.world
.WaterFlow` (`level(String)`, `isFlowing(level, neighbours)`), so the same
"is this water moving?" test can be reused by other water-driven sources
and is covered by `MachineWiringTest`.

The sink fires per solved member each tick; drive your model animation
from it (see the `machine` example).

## Rendering and items (pack-first)

Every machine renders as its resourcepack model by default (`PACK_ITEM`).
Pack assets are compiled for each model when it loads, from the texture
embedded as base64 in the `.bbmodel`; an external PNG beside the model only
overrides it. The texel/virtual engine is only a fallback: when a pack
render returns nothing, `PackFallbackNotice` logs one WARNING per model
naming it and the reason (see `docs/pack-system.md`).

`/machine give <type>` and the `/machine shop` GUI hand out the registered
pack item (`<type>_lvl_<level>`), so the item in hand is the machine model
and places on right-click. Breaking a machine in survival removes its
displays and its invisible collision blocks together.

## Water wheel output

A wheel produces SU only from **flowing** water in its 3x3x1 plane,
counted by the shared `WaterFlow` rule (`SU = flowing cells x tier rate`).
Still water counts 0, there is no configured base output, and a freshly
placed wheel starts at SU 0 and is rescanned in its place event.

## Filling space and water flow (per-model `fillBox`)

Collision blocks are derived from the model's baked geometry, so a machine
can never be walked through where its model has parts. But a lattice over
the *whole* occupied volume blocks world content: a water wheel solidifies
the very cells a river needs. Two mechanisms fix that:

1. **Per-model filling space** — `models/<name>.bbmodel.meta.json`:

   ```json
   { "fillBox": [0, 0, 0, 0, 0, 0] }
   ```

   `fillBox` is inclusive min/max cell coordinates relative to the anchor
   (`[minX,minY,minZ,maxX,maxY,maxZ]`). Only those cells may become
   invisible blocks; geometry elsewhere never does. The example water
   wheels declare the single center cell, so the 3x3x1 ring stays open and
   river water flows through untouched. Omit the field to fill the whole
   geometry volume (shafts, cogwheels and the brake lever do).

2. **Water is never replaced** — during placement the lattice skips any
   cell containing water, in every model, regardless of `fillBox`. A wheel
   dropped into a river doesn't delete the river.

Together these keep survival behaviour smooth: placing over water leaves
the flow intact, and breaking any machine block removes only its own
center-cell invisible block.

### About model-derived collision shapes

Like vanilla anvils, collision comes from the model's shape — done here by
deriving the occupied cell lattice from the baked geometry and placing
server-side invisible blocks in it. Two protocol limits to know:

- Cell-level (1 m) granularity: a barrier block is either there or not.
  Sub-block precision is impossible from the server; use `anchorMode`/
  `fillBox` to choose *which* cells are solid, not how full they are.
- Client-visual-only alternatives (display-entity hitboxes, shield-style
  packets) are not enforced for survival interactions; server blocks are
  the only form other players and water/respect.

## Reference example

`examples/mineplus-machine` — water wheels (weak 256 SU @ 3.0, regular
512 SU @ 6.0, strong 1024 SU @ 10.0), shaft, three cogwheel orientations
(vertical/horizontal/side meshes), a brake lever and a `/machine` test
bench (`/machine place|give <type>|shop|status|remove`; `give` hands a
placeable machine item — right-click a block face to place, the machine
shop lists every type clickable). All machine behaviour lives in
`defaults/multiblocks/*.json`; the plugin code only renders `spin`/`swing`
from the solved states and toggles the brake on interact. Models render
through the resource pack by default (`renderMode: "pack_item"`).
