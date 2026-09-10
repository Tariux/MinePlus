# Resource Pack System — Phase 2 Roadmap & Kickoff Prompt

> Status: **planned — not implemented**. This document is the authoritative brief for the
> next major phase: an independent, resource pack–based rendering system (custom items,
> custom blocks, pack-driven animations) that ships alongside the packless virtual
> rendering engine as a peer feature, with zero structural interference between the two.

---

## 1. Scope & Goals

Mineplus today renders everything server-side with **zero client requirements**: virtual
block displays, texel surface baking, packet-streamed display entities. Phase 2 adds the
complementary axis — **client-assisted rendering via a generated resource pack**:

- Custom items with arbitrary textures (not palette-quantized).
- Custom item/block models authored in Blockbench, rendered at full client fidelity.
- Pack-driven animations (item-model predicate animations, armature-style rigs).
- A per-feature opt-in: every multiblock level, custom item, or module feature chooses
  its backend (packless virtual, pack-based, or both with degradation).

Explicit non-goals: no client mod requirement (pack is optional-by-feature, not
mandatory-by-plugin), no replacement of the virtual engine, no bundled hosting
infrastructure beyond a local byte-serving endpoint.

---

## 2. Coexistence Contract (the architectural invariant)

The two rendering methods must run side-by-side on the same server without structural
interference. The contract:

1. **Separate module, separate registries.** The pack system lives in its own package
   (`com.mineplus.pack`) and its own registries (`PackAssetRegistry`, `PackItemRegistry`).
   Virtual models stay keyed in `VirtualBlockManager`; pack assets are keyed by
   `namespace:path`. The two namespaces never collide and neither registry reads the
   other's internals.
2. **One choke point per backend.** Multiblock levels gain an optional
   `renderBackend: virtual | pack | virtual+pack` field (default `virtual`). Persistence
   stores the backend with the snapshot so restores route to the correct renderer.
   `ModelRenderingManager` delegates; it does not branch on internals.
3. **Feature-flag degradation.** A player who declined the pack sees the packless
   virtual fallback (or a vanilla-material approximation) — never a broken/missing
   render. Pack features must declare a `fallback` in their definition.
4. **No shared hot paths.** The pack system must not touch the display transport, the
   animation tick loop, the texel bake pool, or `VirtualBlockManager` state. Delivery
   (pack push) and generation (pack compile) run on their own bounded executors, never
   the texel bake pool and never the main thread.
5. **API symmetry.** `InfrastructureApi` consumers register pack content through a
   `PackApi` peer (same shape as `AnimationApi`), not through virtual-rendering calls.

---

## 3. Industry Analysis Targets

Before writing architecture, do a deep-dive on how the established plugins solved each
problem. Each target has a specific lesson to extract — do not copy their code, extract
their decisions.

| Plugin | Study for | Key questions |
|---|---|---|
| **ItemsAdder** | Namespace model, per-player packs, pack merging | How do multiple "resource packs" compose into one served pack? How is `contents/` folder structure mapped to `assets/<ns>/`? How are player-specific packs pushed and cached? |
| **Oraxen / Nexo** | Item generation pipeline, mechanics layering | How do JSON item definitions become pack models + Bukkit ItemStacks? CustomModelData vs 1.21.4+ `item_model` component migration strategy? How is hot-reload done without full client re-download? |
| **ModelEngine / BetterModel** | Pack-driven animations | How are bbmodel bone rigs converted to item-display armatures + pack models? How do they sync server-side puppet state with client interpolation? What are the entity-count budgets? |
| **MythicCrucible / EcoItems** | Architectural separation | How is "content definition" cleanly separated from "behavior attachment"? Where do their abstractions leak? |
| **FancyHolograms / DecentHolograms** | Per-player packet + pack interplay | How do they handle players with/without the pack in the same view? Forced vs optional pack application policies? |
| **Vanilla 1.21.4+** | The `item_model` / `items/` directory format | What does the new component-based item model system make easier, and what client versions must still be supported via CustomModelData? |

Analysis checklist per target: pack generation (zip streaming, incremental asset hashing,
cache invalidation), delivery (hash-based `setResourcePack` push, CDN vs self-host,
delta packs), conflict resolution (model overrides, atlas stitching, font glyph ranges),
versioning (pack_format bumps across MC versions), Folia compatibility, and failure
modes (player declines, timeout, corrupt download).

---

## 4. Architecture Blueprint

```
com.mineplus.pack
├── PackAssetRegistry        // namespaced assets: models, textures, item definitions
├── PackCompiler             // async, incremental: assets -> zip, content-hash named
├── PackDeliveryService      // host abstraction: local byte endpoint | static URL | off
├── PackItemFactory          // Bukkit ItemStacks referencing pack models
├── PackModelRenderer        // spawns display entities whose blockstate points at pack models
├── PackApi                  // the InfrastructureApi-peer registration surface
└── PackBackendSelector      // implements the renderBackend contract with virtual fallback
```

Principles:

- **Compile off-main, always.** Pack compilation is a build step (zip + hash), scheduled
  on a dedicated executor; the main thread only ever flips an atomic "current pack"
  reference.
- **Hash-named artifacts.** `<plugin>/packs/mp-<shorthash>.zip`; clients cache by URL,
  so unchanged content never re-downloads. Prune artifacts older than N revisions.
- **Incremental rebuilds.** Asset-level content hashing means adding one item recompiles
  only the changed zip region — study ItemsAdder's approach, improve on it.
- **Delivery is pluggable and optional.** Self-host via a bounded local HTTP endpoint
  (config: port, bind, public URL override), or a static URL, or delivery disabled
  (server admin distributes the pack manually).
- **Declarations over code.** JSON feature definitions declare pack assets the same way
  multiblocks declare models today — the Core stays feature-free.

---

## 5. Milestones

| # | Milestone | Exit criteria |
|---|---|---|
| M0 | Industry analysis spike | Written comparison of the §3 targets against this blueprint; decisions log |
| M1 | Asset registry + compiler | A hand-authored asset set compiles to a valid, hash-named pack offline |
| M2 | Delivery + item factory | A player receives the pack via hash push; `/mpgive` yields a working custom item |
| M3 | Custom blocks via displays | A multiblock level with `renderBackend: pack` renders through pack models, with virtual fallback for pack-less players |
| M4 | Pack animations | bbmodel rigs animate via pack item models; parity API with `AnimationApi` selectors |
| M5 | Productionization | Incremental rebuilds, artifact pruning, Folia support, metrics, docs, module example |

---

## 6. Phase Kickoff Prompt

Use this verbatim to start the implementation phase:

> You are building Phase 2 of Mineplus (github.com/Tariux/MinePlus): an independent
> resource pack system (`com.mineplus.pack`) for custom items, custom blocks, and
> pack-driven animations, coexisting with the existing packless virtual rendering
> engine under the coexistence contract in `docs/resource-pack-roadmap.md` §2.
>
> **Step 1 — Analysis.** Study how ItemsAdder, Oraxen/Nexo, ModelEngine/BetterModel,
> MythicCrucible, and FancyHolograms each solve: namespace/pack composition, item
> generation (CustomModelData vs 1.21.4+ `item_model`), pack delivery & caching,
> per-player application, and animation rigs. Produce a decisions log mapping each of
> their choices to our blueprint (§4), with explicit adopt/adapt/reject rationale.
> Respect their licenses: extract decisions, never code or assets.
>
> **Step 2 — Core.** Implement M1–M2: `PackAssetRegistry` (namespaced, immutable),
> `PackCompiler` (async, incremental, content-hash artifacts), `PackDeliveryService`
> (local byte endpoint / static URL / off), `PackItemFactory`. No main-thread IO, no
> shared state with the virtual engine, dedicated executors.
>
> **Step 3 — Integration.** Implement M3–M4 behind the `renderBackend` field and
> `PackApi`, with mandatory packless fallbacks declared per feature. Persistence must
> record the backend per instance. Add a `mineplus-fun`-style reference module that
> exercises both backends on the same feature.
>
> **Constraints.** Core stays feature-free; pack code never touches the display
> transport, texel bake pool, or `VirtualBlockManager` internals; Folia-compatible
> scheduling; bStats opt-out respected; docs (`docs/`) updated in the same PR; the
> plugin must remain fully functional with the pack system disabled in config.
