# Mine Plus model authoring guide

End-to-end workflow: design a model in Blockbench, export it, install it into
the Mine Plus pack, and verify it matches in game. Written for designers
(Blockbench work) and developers (pack + API work). For the plugin's own tool
reference see `devtools/blockbench-plugin/README.md`; for the
display-context slot mapping see `docs/display-slot-mapping.md`.

## 1. Plan the model

1. Decide what the object is: a held item (gun, tool), a placeable block, or a
   custom mob. This decides the format you start from and where display poses
   matter.
2. Keep scale in mind: Blockbench works in pixels (16 px = 1 block). Mine Plus
   imports cubes 1:1, so build at the size it should appear in world.
3. Name every group you intend to animate — group names become **bone
   names** in game (uuid-linked, name-independent, but readable names make
   debugging and animation code far easier). Use `snake_case`, no spaces.

## 2. Set up Blockbench

1. Install the Mineplus plugin: copy
   `devtools/blockbench-plugin/` into your Blockbench plugins
   folder, restart, enable it under File > Plugins.
2. Recommended starting points (Tools menu):
   - **Mineplus: Open Generic / Java Item Model** — start from a vanilla item
     project, best for guns and tools.
   - A plain **bbmodel** project — best for blocks and mobs; convert later.
3. Tools > **Mineplus: Toggle Tools** shows/hides all Mineplus entries if you
   want the menu quiet while modelling.

## 3. Model, texture, animate

- Model and UV-map as usual. Textures must be embedded or referenced with
  relative paths — they are packed into the resource pack at build time.
- Animate in the **Animate** tab. Mine Plus supports the full Blockbench
  animation model: per-bone keyframes on all three channels — **rotation**,
  **position** and **scale** — per animation clip, imported verbatim.
- Run **Mineplus: Validate Display** (or Fix Duplicates if a texture edit ever
  duplicated cubes) before every export.

## 4. Author the display block (held/GUI/ground pose)

The Display tab edits the transforms Minecraft applies per context. Mine Plus
imports this block verbatim and emits it into the item model — what you author
is what the game shows. All vanilla contexts are supported:

`thirdperson_righthand`, `thirdperson_lefthand`, `firstperson_righthand`,
`firstperson_lefthand`, `head`, `gui`, `ground`, `fixed`.

Two ways to author:
- In Blockbench's **Display** tab (per-context sliders) — imported on export.
- Switch to **display mode** in Blockbench while an animation is playing in
  the Animate timeline, so the viewport renders the transforms exactly as the
  game would. Use it to pose a gun in the hand while its animations run.

Rules the core enforces (so you cannot break the client):
- rotation clamped to ±180°, translation to ±80 px, scale 0.01–100; NaN → 0.
- A zero scale is treated as "unset" (renders at 1, not flat).
- Blockbench-only contexts (`fixed` onwards are fine; experimental ones like
  `on_shelf` are dropped with a warning — the vanilla client would reject the
  whole model otherwise).
- A `.meta.json` `display` block wins over the bbmodel's own per context.

## 5. Export

Both directions work and are interchangeable:
- **Mineplus: Save as .mpmodel** — the Mineplus-native format (a bbmodel plus
  a Mineplus template stamp). Preferred: it round-trips display + animations
  losslessly and the plugin reopens it as a Mineplus model in one step.
- **File > Export > Blockbench model (.bbmodel)** — fully supported; the core
  importer reads `display` and `animations` from plain bbmodels too.

Old models: Tools > **Mineplus: Open & Migrate Old Model…** opens any old
`.bbmodel`/`.json` and re-stamps the format without touching cubes, bones,
textures, animations or the display block.

## 6. Install into the pack

1. Drop the exported file into the pack's model source folder (the folder your
   `pack.yml` / pack config points at; see `docs/pack-system.md`).
2. Register the item/block/mob in config or code
   (`docs/config-reference.md`, `docs/custom-mobs.md`, `examples/` has
   config-based, code-based and hybrid samples).
3. Rebuild the pack (`gradlew build` or your pack script). The bake sweeps
   stale frames automatically (BAKE_VERSION 7).

## 7. Verify in game

1. `/mineplus model …` (see `docs/developer-api.md`) to spawn/hold the item.
2. Check every context you authored: third person both hands, first person,
   GUI, dropped on ground, item frame, on head if used.
3. Play each animation via the API (`AnimationApi.playAnimation(instanceId,
   name, playback)`) — supports play/pause/resume/stop, priority, fade in/out,
   events, and state queries.
4. If a pose looks wrong in game but right in Blockbench's display mode,
   check the log: the
   compiler warns per-context about dropped non-vanilla contexts and the
   importer logs every clip it built.

## 8. Export checklist (the short version)

- [ ] Every animated group has a readable `snake_case` name.
- [ ] Display block authored for every context the item is seen in.
- [ ] Display mode looks right for each context.
- [ ] Validate Display passes; no duplicate cubes/groups.
- [ ] Exported as `.mpmodel` (or `.bbmodel`), textures embedded.
- [ ] Registered in pack config/code; pack rebuilt.
- [ ] In game: all contexts + all animations verified.

See also: `docs/display-slot-mapping.md`, `docs/pack-system.md`,
`docs/custom-mobs.md`, `docs/developer-api.md`.

## 9. Block models (the gate for a delivered block)

A model is not a custom block on its own: `blocks/<namespace>/<id>.json` is the
deliverable, and the model must pass this gate before the definition is
written. The plan and schema live in
[`design/2026-11-26-custom-block-system-design.md`](design/2026-11-26-custom-block-system-design.md).

- [ ] Model space is `free`, anchored to the block grid: feet on y=0, no cube
      below y=0, origin inside the anchor cell.
- [ ] Footprint stated in whole cells and matching the visible extent
      (a 2×2 wheel declares `width/height/depth`, not "about two blocks").
- [ ] Cubes are the collision source. No decorative cube intrudes into a
      walkway; every space that should be walkable is a real gap, not a dark
      filler cube.
- [ ] `fillBox` declared for every cell the block must leave open (water
      through a wheel ring, a doorway in a wall piece) and checked against the
      computed lattice.
- [ ] Clips named `snake_case`; autoplay/loop clips listed in
      `visuals.animations`.
- [ ] Textures embedded (or a sibling PNG matching the model stem).
- [ ] Element angles inside the 1.21.6 snap grid unless the target version
      matrix excludes those releases.
- [ ] Delivery report contains: pixel bounds, derived footprint, collision cell
      count, expected bare-hand break time, and an in-game screenshot of the
      debug lattice (`F3+G`).
- [ ] `blocks/*.json` written and validated with `/mineplus blocks validate`
      (phase 1 is live); a block shipped without a definition is rejected.
- [ ] Model and definition checked together:
      `node devtools/skills/mineplus-model-authoring/scripts/check-bbmodel.mjs <model>.bbmodel --target pack-block --definition <blocks/ns/id>.json`
      exits 0, or 1 with warnings and no errors (the example wheels exit 1 on the
      sunk-axle geometry warning). It proves `visuals.model`
      resolves, `occupancy` equals the measured footprint, every autoplay clip
      exists, `collision.mode` is known and `breaking.hardness` is sane.
      Autoplay clips start on placement and on chunk restore as of this build.
