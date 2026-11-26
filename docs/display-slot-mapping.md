# Display slot mapping: Blockbench → Minecraft

Since the derived-display workaround layer was removed, a model's held, GUI
and ground presentation comes from exactly one place: the `display` block the
author set on the model. Nothing in the pipeline invents, flips, lifts or
auto-fits a pose anymore. This page is the complete mapping table.

## Where the display block lives

1. **In the `.bbmodel` itself** — a top-level `"display"` object whose keys are
   the vanilla context names below. This is what the
   `mineplus_generic_display` Blockbench plugin edits (see
   `devtools/blockbench-plugin/`).
2. **In the model's meta file** — a `"display"` object with the same shape.
   A meta `display` overrides the bbmodel's per context; contexts the meta
   does not mention fall back to the bbmodel's.

The compiled model JSON contains exactly the contexts the author set (minus
unknown ones, which are skipped with a logged warning). Vanilla's own
defaults are used for anything the author left out — either directly, or
through the model's parent when the item model declares one.

## Context table

| Blockbench / pack context | Where the player sees it |
|---|---|
| `thirdperson_righthand` | Right hand, viewed by other players (or F5) |
| `thirdperson_lefthand` | Left hand, viewed by other players (or F5) |
| `firstperson_righthand` | Right hand, first person |
| `firstperson_lefthand` | Left hand, first person |
| `head` | Worn on the head (hat slot rendering) |
| `gui` | Inventory slot, hotbar, item frame-less tooltips |
| `ground` | Dropped item entity |
| `fixed` | Item frame |

Each context accepts `rotation` (degrees, XYZ), `translation` (in display
pixels) and `scale` (multiplier; a missing or all-zero scale is treated as
1,1,1).

## Value rules (validated at bake time)

- Rotation components are clamped to ±180°.
- Translation components are clamped to ±80 pixels.
- Scale components are clamped to 0.01–100.
- NaN in any component becomes 0 (scale: keeps the unit-scale fallback).

These bounds exist because one malformed field can make the client reject the
entire model file.

## Authoring a held pose (Blockbench)

Install the `mineplus_generic_display` plugin (see its README), select the
model's root group, and use the plugin's display editor: it manipulates the
same eight contexts, showing the rotated/translated result in the preview.
Save the `.bbmodel`; the next bake copies the values verbatim into the pack.

Hand-authoring without the plugin: add a top-level `"display"` object to the
`.bbmodel` JSON:

```json
"display": {
  "firstperson_righthand": {
    "rotation": [0, -45, 0],
    "translation": [0, 4, 2],
    "scale": [1, 1, 1]
  }
}
```

## What was removed, and why

The previous pipeline auto-derived held poses from cube geometry
(`HeldPoseSolver`, `ItemDisplaySolver`, `HeldOrientationFix`,
`BbClipBaker.autoHeldPose`) and offered `pack.held-yaw` style config to tune
the result. That contradicted the principle that the `.bbmodel` is the single
source of truth: the same model could render differently depending on cube
naming ("grip", "muzzle"…) or calibration constants invisible to the author.
Models that relied on the old auto-pose must now author their display block
(above); nothing else in the pipeline changed.
