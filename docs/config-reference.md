# Mineplus Config Reference

Mineplus is a **zero-content core**. Nothing is added to gameplay unless you provide JSON files or call the API.

Runtime folders (inside `plugins/Mineplus/`):
- `models/*.bbmodel`
- `multiblocks/*.json`
- `recipes/*.json`

Reload from game with `/mineplus reload`.

## 1) Multiblock Type JSON

Path: `plugins/Mineplus/multiblocks/<file>.json`

Top-level fields:
- `id` (required): unique machine type id.
- `name` (optional): display name, defaults to `id`.
- `gui` (optional): key used by `InfrastructureGuiManager`.
- `levels` (required): object where each key is an integer level.

Per-level fields:
- `model` (optional): relative or absolute path to `.bbmodel`.
- `speed` (optional, default `1.0`): crafting speed multiplier applied to running processes. `2.0` halves the time a recipe takes; a mid-process upgrade speeds up the running process immediately.
- `durability` (optional, default `1.0`): not yet consumed by the engine; reserved for future use.
- `upgradeCost` (optional): object of `itemKey -> amount`.
- `guiOptions` (optional): string map for custom GUI data.

Example:
```json
{
  "id": "crusher",
  "name": "Crusher",
  "gui": "crusher_gui",
  "levels": {
    "1": {
      "model": "models/crusher_lv1.bbmodel",
      "speed": 1.0,
      "durability": 100.0,
      "upgradeCost": {
        "core_plate": 8
      },
      "guiOptions": {
        "title": "Crusher I"
      }
    },
    "2": {
      "model": "models/crusher_lv2.bbmodel",
      "speed": 1.35,
      "durability": 160.0,
      "upgradeCost": {
        "core_plate": 16
      }
    }
  }
}
```

## 2) Machine Recipe JSON

Path: `plugins/Mineplus/recipes/<file>.json`

Loader supports either:
- a single recipe object, or
- `{ "recipes": [ ... ] }`

Recipe fields:
- `id` (required): unique recipe id.
- `machine` (required): multiblock type id.
- `level` (optional, default `1`): minimum machine level.
- `craftTimeTicks` (optional, default `20`): base duration in ticks when the recipe is run as a timed process (see `startProcess` in the developer API). The level's `speed` multiplier scales it. Processes pause in unloaded chunks and survive restarts.
- `input` (optional): object `key -> amount`.
- `output` (optional): object `key -> amount`.

Example:
```json
{
  "recipes": [
    {
      "id": "crusher_iron",
      "machine": "crusher",
      "level": 1,
      "craftTimeTicks": 100,
      "input": {
        "raw_iron": 1
      },
      "output": {
        "iron_dust": 2
      }
    }
  ]
}
```

## 3) Model Files (`.bbmodel`)

Path: `plugins/Mineplus/models/*.bbmodel`

Notes:
- Relative `model` paths are resolved from `plugins/Mineplus/`.
- Keep model file names stable; active instances rely on type/level model resolution.
- Use `/mineplus reload models` after edits.
- The importer applies cube rotation around each cube `origin` (pivot) and supports outliner group hierarchy transforms.
- `inflate` is supported and included in final cube dimensions.
- Face `uv` and face `rotation` are parsed for all six directions (`north`, `south`, `east`, `west`, `up`, `down`).
- Barrier occupancy is computed from transformed cube volumes (union per cube), not from one full model bounding box, so empty internal spaces stay free.

### Texture Engine Architecture

Mineplus renders models using Minecraft `BlockDisplay` entities. Each cube in the model becomes one `BlockDisplay` entity, which can only show a single Minecraft block material. This is the fundamental constraint of the system.

**How it works:**

1. The importer reads the `.bbmodel` `textures` array, extracting each texture's filename (from `path`, `relative_path`, or `name` fields, in that priority order).
2. For each cube, the importer reads the face `texture` reference — which is the **integer array index** into the `textures` array (standard Blockbench format).
3. The **primary texture** of a cube is chosen from the first resolved face texture, checked in order: north → south → east → west → up → down.
4. That primary texture name is mapped to a Minecraft `Material` via `TextureMaterialResolver`.
5. If the texture name doesn't match any known block, the fallback is `WHITE_CONCRETE`.

**Key limitation:** Each cube renders as exactly one Minecraft block material. Per-face texture mixing, UV crops/subsections, and UV rotation are parsed and stored but cannot be reproduced by the block-display renderer.

### Blockbench Designer Guidelines

Follow these rules when creating `.bbmodel` files for Mineplus:

1. **Use "Free Model" format** in Blockbench (not Java Block/Entity). Box UV or Per-face UV mode both work.

2. **Texture assignment is per-cube.** Assign the same Minecraft block texture to **all six faces** of each cube. The engine picks the first face with a valid texture and uses that one material for the entire cube.

3. **Name your texture files after the Minecraft block texture.** The engine extracts the filename from the texture entry and maps it to a `Material`.
   - Example: use `bookshelf.png`, `purpur_pillar.png`, `concrete_brown.png`.
   - The `.png` extension is stripped automatically.
   - Paths like `C:\...\blocks\bookshelf.png` or `minecraft-textures/blocks/bookshelf.png` work — only the final filename matters.

4. **Use actual Minecraft block textures** extracted from the game's resource pack. Place them anywhere on disk and import them into Blockbench. The path/filename metadata is what Mineplus reads.

5. **If you need different textures on different parts** of your model, split them into separate cubes. Each cube = one material.

6. **Avoid the generic "texture" name.** A texture file named just `texture` or `texture.png` won't resolve to any block material and will fall back to `WHITE_CONCRETE`.

7. **Model geometry** (position, scale, rotation, pivot, inflate, outliner groups) is all fully supported. Only the texture → material mapping has restrictions.

### Allowed Texture Names

The following texture filenames are recognized and mapped to Minecraft block materials. Use these exact names (without `.png`) for your texture files in Blockbench.

Any Minecraft block material name also works directly (e.g., `oak_planks`, `diamond_block`). The list below covers **aliases and multi-face textures** that need special handling.

#### Stone & Variants
`stone`, `granite`, `polished_granite`, `diorite`, `polished_diorite`, `andesite`, `polished_andesite`, `deepslate`, `cobbled_deepslate`, `polished_deepslate`, `calcite`, `tuff`, `dripstone_block`, `cobblestone`, `mossy_cobblestone`, `smooth_stone`

#### Dirt & Soil
`dirt`, `coarse_dirt`, `rooted_dirt`, `grass_block_top`, `grass_block_side`, `grass_block_side_overlay`, `podzol_top`, `podzol_side`, `mud`, `muddy_mangrove_roots_top`, `muddy_mangrove_roots_side`, `packed_mud`, `mycelium_top`, `mycelium_side`, `soul_sand`, `soul_soil`

#### Sand & Gravel
`sand`, `red_sand`, `gravel`, `clay`

#### Sandstone
`sandstone`, `sandstone_top`, `sandstone_bottom`, `chiseled_sandstone`, `cut_sandstone`, `red_sandstone`, `red_sandstone_top`, `red_sandstone_bottom`, `chiseled_red_sandstone`, `cut_red_sandstone`

#### Wood (all wood types: oak, spruce, birch, jungle, acacia, dark_oak, mangrove, cherry, bamboo, crimson, warped)
`{wood}_planks`, `{wood}_log`, `{wood}_log_top`, `stripped_{wood}_log`, `stripped_{wood}_log_top`, `{wood}_stem`, `{wood}_stem_top`

#### Ores
`coal_ore`, `iron_ore`, `gold_ore`, `diamond_ore`, `emerald_ore`, `lapis_ore`, `redstone_ore`, `copper_ore`, `nether_gold_ore`, `nether_quartz_ore`, `deepslate_coal_ore`, `deepslate_iron_ore`, `deepslate_gold_ore`, `deepslate_diamond_ore`, `deepslate_emerald_ore`, `deepslate_lapis_ore`, `deepslate_redstone_ore`, `deepslate_copper_ore`, `ancient_debris_top`, `ancient_debris_side`

#### Metal & Mineral Blocks
`gold_block`, `iron_block`, `diamond_block`, `netherite_block`, `emerald_block`, `lapis_block`, `redstone_block`, `coal_block`, `copper_block`, `raw_gold_block`, `raw_iron_block`, `raw_copper_block`, `exposed_copper`, `weathered_copper`, `oxidized_copper`, `cut_copper`, `exposed_cut_copper`, `weathered_cut_copper`, `oxidized_cut_copper`, `amethyst_block`

#### Colored Blocks (all 16 colors: white, orange, magenta, light_blue, yellow, lime, pink, gray, light_gray, cyan, purple, blue, brown, green, red, black)
`{color}_wool`, `{color}_concrete`, `concrete_{color}` (alias), `{color}_concrete_powder`, `concrete_powder_{color}` (alias), `{color}_terracotta`, `{color}_glazed_terracotta`, `{color}_stained_glass`, `terracotta`

#### Bricks & Stone Bricks
`bricks`, `stone_bricks`, `mossy_stone_bricks`, `cracked_stone_bricks`, `chiseled_stone_bricks`, `mud_bricks`

#### Nether
`netherrack`, `nether_bricks`, `red_nether_bricks`, `chiseled_nether_bricks`, `cracked_nether_bricks`, `basalt_top`, `basalt_side`, `polished_basalt_top`, `polished_basalt_side`, `smooth_basalt`, `blackstone`, `blackstone_top`, `polished_blackstone`, `chiseled_polished_blackstone`, `polished_blackstone_bricks`, `cracked_polished_blackstone_bricks`, `gilded_blackstone`, `warped_nylium_top`, `warped_nylium_side`, `crimson_nylium_top`, `crimson_nylium_side`, `warped_wart_block`, `nether_wart_block`, `shroomlight`, `glowstone`

#### End
`end_stone`, `end_stone_bricks`, `purpur_block`, `purpur_pillar`, `purpur_pillar_top`

#### Prismarine & Ocean
`prismarine`, `prismarine_bricks`, `dark_prismarine`, `sea_lantern`

#### Quartz
`quartz_block_top`, `quartz_block_bottom`, `quartz_block_side`, `quartz_pillar`, `quartz_pillar_top`, `chiseled_quartz_block`, `chiseled_quartz_block_top`, `smooth_quartz`, `quartz_bricks`

#### Ice & Snow
`ice`, `packed_ice`, `blue_ice`, `snow`, `snow_block`

#### Organic & Farm
`hay_block_top`, `hay_block_side`, `melon_top`, `melon_side`, `pumpkin_top`, `pumpkin_side`, `carved_pumpkin`, `jack_o_lantern`, `bone_block_top`, `bone_block_side`, `dried_kelp_top`, `dried_kelp_side`, `dried_kelp_block`, `honeycomb_block`, `honey_block_top`, `honey_block_side`, `honey_block_bottom`, `slime_block`, `moss_block`

#### Functional Blocks
`bookshelf`, `obsidian`, `crying_obsidian`, `bedrock`, `sponge`, `wet_sponge`, `glass`, `spawner`, `dragon_egg`

#### Crafting & Utility
`furnace_front`, `furnace_front_on`, `furnace_side`, `furnace_top`, `blast_furnace_front`, `blast_furnace_front_on`, `blast_furnace_side`, `blast_furnace_top`, `smoker_front`, `smoker_front_on`, `smoker_side`, `smoker_top`, `smoker_bottom`, `crafting_table_front`, `crafting_table_side`, `crafting_table_top`, `smithing_table_front`, `smithing_table_side`, `smithing_table_top`, `smithing_table_bottom`, `fletching_table_front`, `fletching_table_top`, `cartography_table_top`, `cartography_table_side1`, `cartography_table_side2`, `cartography_table_side3`, `loom_front`, `loom_side`, `loom_top`, `loom_bottom`, `barrel_top`, `barrel_top_open`, `barrel_side`, `barrel_bottom`, `enchanting_table_top`, `enchanting_table_side`, `enchanting_table_bottom`, `end_portal_frame_top`, `end_portal_frame_side`

#### Redstone
`dispenser_front`, `dispenser_front_vertical`, `dropper_front`, `dropper_front_vertical`, `observer_front`, `observer_back`, `observer_back_on`, `observer_side`, `observer_top`, `piston_top`, `piston_side`, `piston_bottom`, `piston_inner`, `piston_top_sticky`, `tnt_top`, `tnt_side`, `tnt_bottom`, `target_top`, `target_side`, `redstone_lamp`, `redstone_lamp_on`, `note_block`, `jukebox_top`, `jukebox_side`

#### Mushroom
`mushroom_block_inside`, `mushroom_stem`, `red_mushroom_block`, `brown_mushroom_block`

#### Sculk
`sculk`, `sculk_catalyst_top`, `sculk_catalyst_side`, `sculk_catalyst_bottom`

#### Froglight
`ochre_froglight_top`, `ochre_froglight_side`, `verdant_froglight_top`, `verdant_froglight_side`, `pearlescent_froglight_top`, `pearlescent_froglight_side`

#### Misc
`lodestone_top`, `lodestone_side`, `respawn_anchor_top`, `respawn_anchor_side0`

#### Direct Material Names
Any valid Minecraft block material name also works directly (e.g., `oak_planks`, `white_concrete`, `diamond_block`). The name is matched case-insensitively against the Bukkit `Material` enum. If it is a valid block material, it will be used.

### Internal Debug Models

- Use `plugins/Mineplus/models/debug/` for raw importer/transform debug models.
- The loader scans `plugins/Mineplus/models/` recursively, so nested folders are supported.
- Model keys include folder path (example: `models/debug/test_rotation.bbmodel` -> key `debug/test_rotation`).
- Use `/mineplus model models` to list loaded keys and `/mineplus model debugspawn <modelKey>` to spawn one quickly.

## 4) Admin Command Reference

- `/mineplus status`
- `/mineplus reload [all|models|multiblocks|recipes]`
- `/mineplus model list [limit]`
- `/mineplus model inspect [look|uuid]`
- `/mineplus model remove [look|uuid]`
- `/mineplus model respawn [look|uuid]`
- `/mineplus model setlevel <look|uuid> <level>`
