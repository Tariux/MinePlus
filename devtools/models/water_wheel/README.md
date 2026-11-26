# My workspace

Built with ML AI Code.

## Water wheel

Everything lives in one file: `models/water_wheel.bbmodel`.

- 34 cubes, vanilla-style low-detail textures (oak planks, spruce log, stone) embedded in the file
- Built-in `spin` animation (0–360° loop)
- Display settings for all slots: third/first person hands, gui, ground, head, fixed (item frame)
- Use it as an item (attachable) or as a block — same file, just point both at this model

## Gear set (mechanical pack)

All in `models/`, each a single self-contained `.bbmodel` with embedded vanilla-style textures (oak planks, spruce log, stone, iron), a spin animation, and all 8 display slots:

- `gear.bbmodel` — flat gear, spins on Z; teeth reach 8.4 px so two gears on adjacent blocks mesh
- `gear_vertical.bbmodel` — same gear lying flat, spins on Y (horizontal shaft)
- `gear_horizontal.bbmodel` — same gear upright, spins on X (vertical shaft)
- `shaft.bbmodel` — oak rod with iron bands, 3×3 px, spins on Z; slides into any gear hub (±2 px opening)
- `lever_handle.bbmodel` — stone base, oak stick, iron knob; swings ±40° instead of spinning

### Multiblock alignment rules

- Every model is centered on its hub at (0, 8, 0) — the middle of the block, same as the water wheels — so parts line up when placed block-to-block.
- Gears mesh when placed on adjacent blocks: centers 16 px apart, teeth 8.4 px, tooth pitch 45°. Rotate one gear 22.5° (half pitch) and reverse its spin direction to interlock teeth.
- Shaft (3×3) fits the gear hub (4×4 opening) and the water-wheel axle (3×3), so wheel → shaft → gear chain in a straight line.
- All spin animations are 8 s per turn at the same speed; keep partner gears counter-rotating for a working drivetrain.

No separate export files; overlap check: 0 co-planar face pairs in every model.

No separate export files.
