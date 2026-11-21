# Animating Generic-Model Blockbench Items

How a Blockbench `.bbmodel` with free-form geometry (a gun, a tool, a machine
part) becomes an *animated held item* on a vanilla client — what the pack
compiler does, what the host file looks like, and the constraints that shape
both.

## The core problem

A resource pack cannot run animations. The client only ever renders one item
model per state, and the only signals the server can send every tick are:

1. **item model data** — which model the item resolves to (a frame index),
2. **an item cooldown** — a numeric the client can read every tick.

Everything else (bones, keyframes, easing) must be baked ahead of time into
per-frame models and wired to those two signals.

## Baking: `BbClipBaker`

Each Blockbench clip is sampled at N poses (`BbClipBaker.poses(model, style,
clip, frames)`). Every pose becomes a full standalone item model whose
`display` transforms reproduce the clip's bone motion in model space: a bone
that swings 40° in Blockbench is baked as an element rotation plus a display
offset so the held item *moves like* the rig, even though the client has no
rig concept. Consequences:

- Frame count is a quality/perf tradeoff (more frames = smoother, more files).
- Motion outside the display-rotation envelope is approximated; the baker
  clamps rather than breaks the model (see the 45° hinge note in its source).

## Playback: one frame-state write + one cooldown tick

`ItemFrameAnimator` drives playback on the **gameplay clock**: a reload that
takes 34 ticks plays its frames across exactly 34 ticks. The owner module
calls `start(clip)` or `start(clip, ticks)` from its own update loop — the
animator holds no scheduler of its own. One-shot clips report `finished()`;
`next` chains clips (chain depth capped); looping clips never finish.

## The two pack generations

Both express the same contract — thresholds of cooldown mapped to frames —
and both must keep overrides **sorted ascending by (custom_model_data,
cooldown)** because vanilla picks the last matching override:

| | Modern (1.21.4+) | Legacy (<1.21.4) |
|---|---|---|
| Host file | nested `range_dispatch` on `minecraft:cooldown` | `cooldown` predicates in the host model's `overrides` (`ItemModelWriter.legacyHostFiles`) |
| Item component | `minecraft:item_model` | `CustomModelData` |
| Gating call | `PackApi.isAvailable()` | `PackApi.isAvailable()` |

Modules must gate on `PackApi.isAvailable()`; `usesModernItemModels()` on
`PackSystem` is diagnostic only. The representation is chosen per *server*
version in `PackFormat` and consumed by `PackItemFactory`.

## Client compatibility (the ViaVersion trap)

ViaVersion/ViaBackwards translate *protocol*, not resource packs. A client
that joined through ViaBackwards receives the byte-for-byte pack the server
compiled for the server's version:

- a **pre-1.21.4** client cannot read `item_model` and shows nothing/skin for
  pack items compiled with `MODERN_ITEM_MODEL`;
- a **pre-1.21.6** client rejects any item model whose element rotations are
  off the 22.5° grid (see `.mlai/DECISIONS.md`: `ModelGeometryGuard` snaps on
  old targets for that reason);
- the **pack_format** check applies to the client's version, not the server's.

`ViaCompatProbe` (reflective, no ViaVersion dependency) reads the real client
protocol at delivery time and logs a targeted warning when a client's era
cannot render the pack's representation. It informs diagnostics and gating
only — the pack itself is always compiled once, for the server.

## Design rules

1. **Gameplay owns time.** Retiming a clip is always the caller's job
   (`start(clip, ticks)`); the animator never stretches to a declared duration
   against the action.
2. **Authored display wins.** When a model authors `display` transforms,
   contexts it wrote are emitted verbatim; only contexts it never touched fall
   back to the vanilla `handheld` pose (`ModelJsonWriter.withDefaults`). A
   model without any display gets the handheld defaults so tools/guns hold
   like a sword, not like a flat texture.
3. **Never unsort overrides.** Any new writer touching `overrides` must merge
   into the existing ascending (custom_model_data, cooldown) order.
4. **One compile, many clients.** Version differences are handled at the
   guard/gate level (`ModelGeometryGuard`, `ViaCompatProbe`), never by
   compiling a second pack.
