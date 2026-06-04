# Extension Workflows

## A) JSON-Only Servers (No Coding)

1. Add `.bbmodel` files into `plugins/Mineplus/models/`.
2. Add multiblock files into `plugins/Mineplus/multiblocks/`.
3. Add machine recipe files into `plugins/Mineplus/recipes/`.
4. Run `/mineplus reload all`.
5. Validate with `/mineplus status` and `/mineplus model list`.

Best for operators and datapack-like workflows.

## B) Basic Add-on Developers

Use `BasicInfrastructureApi` when you only need:
- place/remove machines,
- query by location/id,
- enumerate loaded types/instances.

Typical pattern:
1. Ensure target type exists (from JSON or advanced registration).
2. Call `createAndPlace(...)`.
3. Store returned instance id in your plugin state.
4. Call `removeAt(...)` when needed.

## C) Advanced Framework Developers

Use `InfrastructureApi` for full control:
1. Build `MultiBlockType` + `MultiBlockLevel` map in code.
2. Register behavior with `MultiBlockHook` and lifecycle listeners.
3. Register GUI bindings if needed.
4. Register recipes and linking channels.
5. Create/place/upgrade/remove instances through API methods only.

## Runtime Administration

Use the Mineplus admin CLI instead of debug commands:
- `/mineplus status`
- `/mineplus reload ...`
- `/mineplus model ...`

Debug model helpers:
- `/mineplus model models` shows all loaded model keys (from `plugins/Mineplus/models/**`).
- `/mineplus model debugspawn <modelKey>` places a model on the looked-at face.
- Put throwaway validation models in `plugins/Mineplus/models/debug/` to keep production model keys clean.

This keeps production operations clean and predictable.
