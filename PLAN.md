# Mineplus Unified Rendering Engine 2.0 - Implementation Plan

This plan outlines the steps to implement the Major Update: "Unified Rendering Engine 2.0" as described in the user's request. The focus is on code, logic, improvements, and new capabilities, particularly for texel baking optimization, resource pack rendering fixes, developer API improvements, and documentation updates.

## Table of Contents
- [Texel Baking Optimization](#texel-baking-optimization)
- [Resource Pack Rendering Fixes](#resource-pack-rendering-fixes)
- [Developer API Improvements](#developer-api-improvements)
- [Documentation Updates](#documentation-updates)

---

## Texel Baking Optimization

### Goals
- Reduce texel plate count for large uniform areas by replacing them with stretched vectors or single plates.
- Implement adaptive texel baking budgets based on face visibility and model complexity.
- Optimize the texel baking process to skip occluded faces and reuse results for symmetric faces.
- Ensure backward compatibility with existing texel baking settings.

### Steps
1. **Analyze Texture for Uniform Areas**
   - In `TexelSurfaceBaker.java`, add a pre-processing step to scan each face's texture for large regions of uniform color (using Oklab distance threshold).
   - For regions exceeding a minimum size (e.g., 8x8 texels), replace with a single stretched plate (or a vector-based representation) instead of multiple plates.
   - Introduce a new setting in `TexelBakingSettings` for uniform area detection threshold and minimum size.

2. **Adaptive Plate Budgeting**
   - Modify `TexelSurfacePlan` to calculate a dynamic plate budget per face based on:
     - Face size (in texels)
     - View distance (if available, from `VirtualBlockManager` or render distance settings)
     - Current TPS (if available, from a global stats provider)
   - If the required plates exceed the budget, fall back to a simpler representation (e.g., a single plate with the average color) for that face.
   - Log budget fallbacks via `DebugLogger` when `ADDITIONAL_DEBUG_LOGS` is enabled.

3. **Occlusion Culling Optimization**
   - Enhance `GeometryOccupancyCalculator` to cache occlusion results per model orientation and face.
   - Skip baking for faces that are fully occluded by neighboring cubes in the same multiblock structure (using the lattice occupancy).
   - Implement a quick reject test using the model's bounding box before detailed SAT rasterization.

4. **Symmetric Face Reuse**
   - Add a method to `TexelSurfaceBaker` to detect symmetric faces (by comparing texture UVs and geometry) within the same model.
   - Cache baked results for symmetric faces to avoid redundant computation.
   - Use a weak hash map keyed by face properties (UV transform, normal, texture source) to store and reuse `TexelBakeResult`.

5. **Update Configuration and Settings**
   - Add new settings under `TEXEL_BAKING` in `settings.mp.yml`:
     - `uniformAreaDetection: true`
     - `uniformAreaMinSize: 8`
     - `uniformAreaOklabThreshold: 0.05`
     - `adaptiveBudgeting: true`
     - `budgetFallbackToSimpleColor: true`
   - Ensure defaults maintain current behavior when new settings are omitted.

6. **Testing and Validation**
   - Create test cases for:
     - A model with a large uniform texture (should produce few plates).
     - A model with symmetric faces (should reuse bakes).
     - A model in a occluded position (should skip baking for hidden faces).
   - Verify that texel plate count reduces without visual degradation in uniform areas.
   - Ensure that the fallback to simple color does not cause sudden visual pops (consider cross-fading or hysteresis).

---

## Resource Pack Rendering Fixes

### Goals
- Fix rendering issues in pack mode for complex models (e.g., Alchemy table) ensuring correct lighting and model loading.
- Ensure that the pack backend uses the same virtual model data as the virtual backend for consistency.
- Fix the issue where pack models only show emissive light and lack natural lighting.

### Steps
1. **Unify Model Data Between Backends**
   - In `ModelImportCoordinator.java`, ensure that the `VirtualModel` instance created from a `.bbmodel` file is shared between the virtual and pack backends.
   - Modify `PackAssetRegistry` to use the same `VirtualModel` instance from the virtual importer instead of re-parsing or creating a duplicate.
   - This ensures that geometry, UVs, and material properties (like light emission) are identical.

2. **Fix Lighting in Pack Backend**
   - In `PackModelRenderer.java`, check how the model's material properties are applied to the `BlockDisplay` (carrier: note_block).
   - Ensure that the `note_block` carrier's state includes proper lighting properties (e.g., using the block's light emission from the model's `light_emission` field).
   - If the model specifies per-face light emission, map it to the note_block's relevant properties (note_block does not support per-face emission; we may need to approximate or use the average).
   - Alternatively, consider using a different carrier block that supports directional light emission (if available) or use the virtual display's emission for pack mode as well (but note: pack mode uses `BlockDisplay` for collision and rendering).

3. **Ensure Correct Model Loading for Complex Models**
   - Add validation in `BbModelImporter` to handle complex models with nested groups and ensure all bones are correctly processed.
   - Test with the Alchemy table model (`temp/alchemy-table.bbmodel`) to verify that all parts are present and correctly transformed.
   - If the model uses animations, ensure that the animation data is correctly parsed and available for the pack backend (if animation support is extended to pack blocks).

4. **Extension: Animation Support for Pack Blocks**
   - As part of the unified rendering engine, extend `ModelAnimationManager` to apply bone-delta transformations to `PackBlockRenderer` displays.
   - This requires accessing the `VirtualBone` bindings from the shared `VirtualModel` and applying the same animation logic used for virtual displays.
   - Update `PackBlockRenderer` to accept and apply transformation matrices from the animation system.

5. **Update Pack Block Definition**
   - In `PackBlockDefinition.java`, add a reference to the shared `VirtualModel` and ensure that the renderer (`PackBlockRenderer`) uses it for geometry and UV data.
   - Modify `PackBlockRenderer` to retrieve the model data from the definition instead of relying solely on the asset system.

6. **Testing and Validation**
   - Test the Alchemy table in pack mode:
     - Verify that the model loads completely without missing parts.
     - Check that natural lighting (from world light and sky light) affects the model (not just emissive).
     - Ensure that animations (if any) work correctly in pack mode.
   - Compare the visual output between virtual and pack modes for the same model to ensure consistency.

---

## Developer API Improvements

### Goals
- Make the developer API transparent, optimal, and up-to-date.
- Update all example code snippets in documentation and the `mineplus-fun` module.
- Remove deprecated API references and replace with recommended alternatives.
- Ensure API consistency and clear documentation for new rendering modes.

### Steps
1. **Review and Update Developer API Documentation**
   - Open `docs/developer-api.md` and review each section for accuracy.
   - Update the documentation to reflect the new `RenderMode` enum, `RenderPlan`, and `RenderPolicy` engine.
   - Add clear examples for:
     - Registering a multiblock with a custom render plan (per-bone/backend selection).
     - Using the `InfrastructureApi` to create/place multiblocks with specific render modes.
     - Accessing render telemetry via the new `/mineplus render stats` command.
   - Remove any references to deprecated methods or classes (e.g., old `renderBackend` usage without migration notes).

2. **Update Examples in `mineplus-fun`**
   - Navigate to `examples/mineplus-fun/src/main/java` and update all feature classes (Juicer, Cannon, Gear, Wine, Gunsmith, Cabinet, Alchemy, Pack Showcase) to use the latest API.
   - Specifically:
     - Update multiblock registration to use the new `InfrastructureApi.registerMultiBlock` method that accepts a `RenderPlan`.
     - For features like Cabinet and Alchemy (which are showcases for pack and texel), demonstrate the use of `HYBRID` or per-cube render plans.
     - Ensure that any GUI or item registration uses the updated `PackApi` methods.
   - Remove any workarounds that were previously needed due to API limitations.

3. **Deprecation Cleanup**
   - Identify deprecated classes/methods in the codebase (using `@Deprecated` annotations) and remove them if they are no longer needed, or update their Javadoc to point to the new API.
   - Focus on:
     - Old `renderBackend` and `renderKind` usage in multiblock JSON definitions (update to use `renderMode` and `renderPlan`).
     - Any direct use of `VirtualBlockManager` for spawning that bypasses the new `RenderRouter`.
   - Ensure that the removal or deprecation does not break existing modules (provide migration guides in documentation).

4. **API Consistency and Transparency**
   - Ensure that the `InfrastructureApi` is the single entry point for all infrastructure-related operations (multiblocks, recipes, GUIs, linking, etc.).
   - Update the `PluginContext` to expose the `InfrastructureApi` clearly.
   - Add Javadoc to all public methods in the API explaining their purpose, parameters, return values, and thread-safety.

5. **Testing**
   - Compile and run the `mineplus-fun` module to ensure all features work with the updated API.
   - Write simple integration tests (if not already present) that demonstrate the new API usage.

---

## Documentation Updates

### Goals
- Update `docs/config-reference.md`, `docs/pack-system.md`, and other documentation files to reflect the new rendering engine settings and remove outdated samples.
- Ensure all documentation is free of outdated examples and reflects the current codebase.

### Steps
1. **Update Configuration Reference**
   - Edit `docs/config-reference.md` to include:
     - New sections for `RENDERING.POLICY` (dynamic render policy settings).
     - Updated `TEXEL_BAKING` section with new settings for uniform area detection, adaptive budgeting, and symmetric face reuse.
     - Updated `VIRTUAL_RENDERING` section to note the deprecated `renderBackend` and `renderKind` keys and their mapping to the new `renderMode`.
     - Add examples for configuring per-cube render plans in multiblock JSON files.

2. **Update Pack System Documentation**
   - Edit `docs/pack-system.md` to reflect:
     - The unification of model data between virtual and pack backends.
     - Any changes to the pack block carrier or model loading process.
     - Notes on how animation support is now extended to pack blocks (if implemented).
     - Update the resource pack compilation process to use the shared `VirtualModel`.

3. **Remove Outdated Samples**
   - Search for all `.md` files in `docs/` and update or remove any code snippets that use old API calls.
   - Specifically, look for:
     - Old `renderBackend` usage in multiblock JSON examples.
     - Direct instantiation of managers that should be obtained via `PluginContext`.
     - Examples that show the old way of registering items or blocks without the new pack item definitions.
   - Replace with up-to-date examples that use the new API.

4. **Update Changelog and Migration Guide**
   - Ensure that `docs/changelog.md` includes an entry for the Major Update 3.0.0 with a summary of changes.
   - Create or update `docs/migration-guide.md` to help users migrate from version 2.x to 3.0, covering:
     - Changes in configuration keys (old to new mapping).
     - Steps to update multiblock JSON files.
     - Notes on deprecated API removal.
     - Instructions for updating custom modules (like `mineplus-fun` derivatives).

5. **Validate Documentation**
   - Use a tool like `markdownlint` to check for syntax issues.
   - Verify that all links and references are correct.
   - Ensure that the documentation builds correctly (if there is a documentation build process).

---

## Execution Notes

- This plan is designed to be followed sequentially, but some steps can be done in parallel (e.g., Texel Baking Optimization and Resource Pack Rendering Fixes).
- After implementing the plan, the user should build the project and run tests to verify correctness.
- The focus is on code changes; building, testing, and long checks are outside the scope of this plan but are implied as the next steps after following the plan.

---
*Plan generated for Mineplus Major Update: Unified Rendering Engine 2.0*