# Resource Pack System (Phase 2)

Mineplus' second rendering axis: rich custom content — custom items, custom
block/item models, textures — through a **generated resource pack**, with **no
client mod required** and **zero impact on vanilla content**. It is a peer of
the packless [virtual rendering engine](config-reference.md#settingsmpyml-rendering-engine);
both run side by side, and neither touches the other's internals.

```
Vanilla client + Paper + Mineplus + (optional) generated pack  →  custom content
```

---

## How it fits the architecture

| Layer | Responsibility |
|---|---|
| `com.mineplus.infrastructure.render.RenderMode` | the unified vocabulary; one value names subsystem + primitive (`virtual`, `pack_item`, `pack_block`, `hybrid_item`, `hybrid_block`) |
| `RenderBackend` / `RenderKind` | the underlying two axes (`RenderBackend` = subsystem, `RenderKind` = primitive); `RenderMode.of(...)` maps a legacy pair onto a mode |
| `com.mineplus.infrastructure.render.RenderRouter` | the routing choke point: applies subsystem availability + `RenderPolicy` and records telemetry |
| `com.mineplus.pack.PackLighting` | how pack display entities are lit (`AUTO` / `NATURAL` / `EMISSIVE` / `FULLBRIGHT`) |
| `com.mineplus.pack.PackApi` | the developer surface — a peer of `InfrastructureApi`/`AnimationApi`, obtained via `PluginContext.packApi()` |
| `PackAssetRegistry` | namespaced assets (`namespace:path`) with owner tracking and conflict detection |
| `PackCompiler` / `PackCache` | deterministic, dependency-aware compilation into hash-named `packs/mp-<hash>.zip` artifacts |
| `PackDeliveryService` | LOCAL byte endpoint / STATIC_URL / DISABLED; per-player state tracking |
| `PackItemFactory` | `ContentDefinition → ItemStack` (modern `item_model` component, legacy `CustomModelData` fallback) |
| `PackModelRenderer` | pack item-axis world rendering (ItemDisplay entities) |
| `PackBlockRenderer` | pack block-axis world rendering (one BlockDisplay per instance, carrying an allocated carrier state) |
| `BlockStateWriter` | generates the carrier `blockstates/<carrier>.json` host files (vanilla states preserved, allocated states redirected) |
| `ModelRenderingManager` | the **backend choke point** — the only place a render decision becomes a backend call |

**Coexistence contract** (enforced structurally):

- The pack subsystem never touches the virtual display transport, texel bake
  pool, `VirtualBlockManager` internals, or the animation tick loop.
- Pack world objects reuse the virtual engine's collision lattice through its
  public collision-only spawn API — barriers, break handling, occupancy and
  restore dedupe behave identically for both backends.
- Backend selection lives in multiblock level definitions, as a single
  `"renderMode"` (`virtual` | `pack_item` | `pack_block` | `hybrid_item` |
  `hybrid_block`, default `virtual`). The legacy pair
  (`"renderBackend": "virtual" | "pack" | "virtual+pack"` +
  `"renderKind": "model" | "block"`) still parses and maps onto the same modes.
  The `RenderRouter` is the single place a level's mode becomes an effective
  backend/kind, applying the global `RENDERING.POLICY` fallback.

## Backend semantics

| Backend | Collision | Visual (pack player) | Visual (packless player) |
|---|---|---|---|
| `virtual` (default) | virtual engine | virtual render (texel/block displays) | virtual render — identical |
| `pack` + `model` | virtual engine (collision-only) | pack item model (full fidelity) | backing vanilla item |
| `pack` + `block` | virtual engine (collision-only) | pack block model (one BlockDisplay) | the carrier block (a note block) |
| `virtual+pack` | virtual engine | virtual render **+** pack display | virtual render |

Fallbacks, in one place (`RenderRouter`, honoring `RENDERING.POLICY`): when the
pack subsystem is disabled, pack/hybrid modes degrade to `virtual`
(`ALLOW_DEGRADE_TO_VIRTUAL: true`, the default). When a model has no registered
pack item/block, or a pack display fails to attach, the pack render falls back to
a full virtual render. A player without the pack always sees a valid mesh through
`hybrid`, or the carrier block through a pack mode.

### Lighting

Pack visuals are `Display` entities that inherit world light unless a
`Brightness` override is set. `PACK.LIGHTING` (default `AUTO`) decides:

- `AUTO` — non-emissive models get **natural world lighting**; a model with any
  `light_emission` gets block light = the model's maximum emission and sky 15,
  so glowing parts still glow.
- `NATURAL` — never override (models are dark in unlit caves).
- `EMISSIVE` — always apply the model's emission (virtual-engine parity).
- `FULLBRIGHT` — always `(15, 15)`, the legacy fully-lit look.

A `BlockDisplay`/`ItemDisplay` carries one brightness for the whole model, so
per-face emission cannot be represented — the model's maximum is used, the same
approximation the one-display pack axis requires.

## Content model

```
PackItemDefinition (namespace:id, backing material, model key, display name)
        ↓ PackApi.registerItem
ItemRegistry identity (PDC — the same recognition every Mineplus item uses)
        + auto-registered ModelAsset + TextureAssets (from the model + its PNGs)
        ↓ compile
assets/<ns>/models/item/<id>.json   ← serialized from the imported VirtualModel
assets/<ns>/textures/item/<name>.png ← the same PNGs the texel baker reads (atlas-stitched)
assets/<ns>/items/<id>.json         ← modern item model definition (1.21.4+)
        ↓ deliver
Player applies pack → full-fidelity custom item
```

**Vanilla preservation (hard rule):** a vanilla Diamond Sword is a vanilla
Diamond Sword. Only items carrying Mineplus PDC identity resolve to custom
representation. The modern `item_model` component is strictly additive; the
legacy `CustomModelData` path only redirects items carrying the item's stable
predicate value (never unset vanilla items — but note it *does* regenerate the
vanilla item model host file, the classic pre-1.21.4 cross-plugin conflict
surface; prefer modern servers).

## Block rendering (pack blocks)

Items have the additive `item_model` component; blocks have no per-entity model
dispatch — a `BlockDisplay` renders whatever the client's `blockstates` file
says its block state is. Pack blocks solve this with a **carrier state**:

```
PackBlockDefinition (namespace:id, model key, geometry key, carrier pool)
        ↓ PackApi.registerBlock
carrier slot allocation (one vanilla state per block)
        + auto-registered BlockModelAsset + TextureAssets (same model + PNGs)
        ↓ compile
assets/<ns>/models/block/<id>.json          ← block-space element model from the VirtualModel
assets/<ns>/textures/block/<name>.png       ← the same PNGs the texel baker reads (atlas-stitched)
assets/minecraft/blockstates/<carrier>.json ← host file: every vanilla state preserved,
                                              the allocated state(s) redirected to the block model
        ↓ deliver + place
multiblock lifecycle (create/place/remove, barriers, persistence)
        → one BlockDisplay carrying the allocated carrier state
```

The shipped carrier is **note_block** (`minecraft:note_block`). Every note-block
state renders the same `minecraft:block/note_block` model and the block uses the
client's `MODEL` render type, so a `BlockDisplay` actually draws the override.
The carrier's complete vanilla state table is enumerated at runtime from the
server's own block data (exact property names/values per version), allocated
states are drawn only from the **rare mob-head instruments** (zombie, skeleton,
creeper, dragon, wither_skeleton, piglin, custom_head at `powered=false`), and
every other state stays vanilla. A naturally placed note block therefore almost
never lands on an allocated state — and if it does, or if a player has not
applied the pack, it renders a normal note block rather than nothing.

**Atlas rule:** a model's texture reference must resolve to a sprite the client
actually stitches. The generated models reference `ns:block/<name>` (blocks) and
`ns:item/<name>` (items), and the matching PNGs are written under
`assets/<ns>/textures/block/` and `assets/<ns>/textures/item/`. The vanilla
`blocks` atlas only scans those two directories, so a model pointing at
`textures/<name>` directly resolves to a missing sprite — the purple/black
checkerboard.

**Carrier contract:** a carrier must render with the client's `MODEL` render
type. Blocks whose render type is `INVISIBLE` — light, structure_void, barrier —
draw nothing from a `BlockDisplay` no matter what their blockstate file says, so
they can never carry a pack block. Adding a carrier means providing its
complete vanilla state table and the subset it is willing to allocate
(`PackBlockCarrier`).

Pack blocks are placed and collision-owned by the **ordinary multiblock
lifecycle** — `createMultiBlock` / `placeMultiBlock` / `removeBlock`, barrier
collision, break handling and persistence are identical to every other
machine. The pack subsystem only declares the rendering; it does not touch
texel baking, the virtual display transport, or the multiblock engine's
internals. If a pack block can't attach its display, the collision-only owner
is released and the level falls back to a full virtual render.

## Configuration (`settings.mp.yml`)

```yaml
PACK:
  ENABLED: true            # master switch; false = subsystem fully inert
  DELIVERY:
    MODE: LOCAL            # LOCAL (default) | STATIC_URL | DISABLED
    LOCAL_HOST: 0.0.0.0
    LOCAL_PORT: 8163       # busy ports auto-probe upward
    PUBLIC_URL: ''         # blank = per-player URL resolution (recommended)
    STATIC_URL: ''         # externally hosted artifact URL; {hash} placeholder
    PROMPT_MESSAGE: ''
    PROMPT_DELAY_TICKS: 60
  CACHE:
    MAX_ARTIFACTS: 8        # mp-<hash>.zip files kept before pruning
  PACK_FORMAT_OVERRIDE: 0   # pin pack_format when the version table lags
  LIGHTING: AUTO            # AUTO | NATURAL | EMISSIVE | FULLBRIGHT (applies after restart)
```

These are the **optimal defaults**: the subsystem is enabled with LOCAL
delivery and works without any URL setup. The download URL is resolved per
player — loopback (`http://127.0.0.1:<port>`) for clients on the server
machine (single-player/LAN testing), then the operator-configured server IP,
then the hostname the player used to connect (Paper's handshake host), then
the machine's LAN address — so remote dedicated servers work as long as the
port is reachable. `PUBLIC_URL` is the override for reverse-proxied or CDN
fronted setups; `STATIC_URL` defers to fully external hosting.

When the configured port is busy, the endpoint binds the next free port
automatically (up to ten consecutive ports are probed); the served URL always
uses the port actually bound, and `/mineplus pack status` shows it. A URL is
never handed to clients for an endpoint that is not listening — that failure
mode surfaces as a client-side "failed to apply resource pack" with nothing
to download.

Players who joined before the first compile completes (or who already applied
a previous artifact when a new one compiles) are pushed the pack
automatically; players who declined are never re-prompted by this path. With
`ENABLED: false` the plugin behaves byte-identically to a pre-pack build: no
compilation, no endpoint, no prompts — and `PackApi` degrades to a safe
fallback where item identity still registers (gameplay intact, vanilla
visuals).

## Developer usage

```java
// Inside a module feature (see mineplus-fun's PackShowcaseFeature):
PackApi pack = context.packApi();

pack.registerItem(PackItemDefinition.builder(
                "fun",                      // namespace
                "strad_wine",               // item id -> fun:strad_wine
                Material.GLASS_BOTTLE,      // backing vanilla item
                "strad-wine")               // registered virtual model key
        .displayName("Strad Wine Bottle")
        .category(ItemCategory.UTILITY)
        .build());

ItemStack item = pack.createItem("fun", "strad_wine");
PlayerPackState state = pack.playerPackState(player.getUniqueId());
```

Registration is order-insensitive: the item registers immediately; its
model/texture assets attach during the coordinated reload-driven recompile
(items registered before their models load are the normal module flow).

Pack blocks use the same pattern and the same assets. A multiblock pack block has
**two keys**: the render-time key the engine derives (`<typeId>_lvl_<level>`)
and the geometry key the model file loads under (its stem in `models/`). The
latter is what the assets attach to at reload; the former only exists after the
first render.

```java
// see mineplus-fun's AlchemyFeature
context.packApi().registerBlock(PackBlockDefinition.builder(
                "fun",                      // namespace
                "alchemy_table",            // block id -> fun:alchemy_table
                "alchemy_table_lvl_1")      // derived multiblock model key (render lookup)
        .geometryModelKey("alchemy-table")  // models/alchemy-table.bbmodel stem (asset attach)
        .displayName("Alchemy Table")
        .carrier(PackBlockCarrier.NOTE_BLOCK) // uniform model + MODEL render type
        .build());
```

Multiblock levels select their render mode in JSON:

```json
"levels": {
  "1": {
    "model": "models/strad-wine.bbmodel",
    "renderMode": "pack_item"
  },
  "2": {
    "model": "models/alchemy-table.bbmodel",
    "renderMode": "pack_block"
  }
}
```

The legacy `"renderBackend": "pack"` + `"renderKind": "block"` pair is still
accepted and maps to `pack_block`; new content should use `renderMode`.

## Compilation & delivery model

- **Deterministic**: fixed entry order, timestamps and compression; identical
  registrations produce byte-identical artifacts. Artifact identity = SHA-256
  over the ordered asset graph **plus** the compile context (pack format,
  item representation). `pack.mcmeta` also declares a generous
  `supported_formats` range (detected format → latest known), so a client
  whose format drifted above the detected value accepts the pack instead of
  rejecting it after download.
- **Incremental**: per-asset content hashes memoize serialization (unchanged
  assets never re-serialize); a graph-hash cache hit skips compilation and
  client re-downloads entirely. Model texture references and registered
  texture entries share one canonical path normalization (last-segment, the
  same semantics as the Core texture resolver), so a model and its textures
  can never disagree inside the artifact.
- **Failure isolation**: a failing asset is skipped with a warning; a failed
  compile keeps the previous artifact; a pack subsystem failure never affects
  virtual rendering.
- **Delivery**: compilation and delivery are separate concerns. LOCAL mode
  serves artifact bytes from a two-thread JDK HTTP endpoint (streamed, never
  in-memory) with per-player URL resolution and automatic port probing;
  STATIC_URL defers to operator hosting; DISABLED expects manual
  distribution. Player states (`UNKNOWN → REQUESTED → ACCEPTED/DECLINED/FAILED
  → APPLIED`) drive the fallback contract. Failed pushes keep every overload
  that still carries the SHA-1 integrity hash; `/mineplus pack status` prints
  the resolved URL and SHA-1 for client-side verification.
- **Scheduling**: pack work runs on its own bounded executors, never the main
  thread, the texel bake pool, or virtual rendering workers. Player-scoped
  pushes are Folia-aware (`PackScheduling`).

## Administration

```
/mineplus pack status            # subsystem, artifact, asset + player state summary
/mineplus pack recompile         # explicit recompile (async, reports the artifact)
/mineplus pack push <player>     # deliver the current pack to one player
npm run pack                     # (repository) build the manual gun-overlay pack zip
```

`npm run pack` assembles `examples/mineplus-fun`'s `defaults/pack/gun/` tree
into `dist/mineplus-resource-pack.zip` — a ready-to-use, deterministic pack
for hand distribution when the LOCAL endpoint is unreachable (firewalled
hosts, proxies). `--format <n>` pins `pack_format` (default 34 = 1.21.1;
the `supported_formats` ceiling keeps newer clients accepting it). Drop the
zip into a client's `resourcepacks` folder or host it and point
`resource-pack=` at it.

## Honest limitations (current boundaries)

- **Rotated cubes**: vanilla element models support axis-aligned boxes with at
  most one ±45° rotation; cubes carrying non-trivial rotations serialize as
  their AABB (warned per model). Axis-aligned geometry — machines, furniture,
  items — is exact.
- **Wrapping UV windows** are clamped to the 0..16 UV window (warned).
- **Pack block carrier capacity**: the shipped `note_block` carrier allocates
  from the rare mob-head instruments (7 × 25 = 175 slots). Additional carriers
  can be added to `PackBlockCarrier` with their own complete vanilla state
  table and allocatable subset; a carrier must render with the `MODEL` render
  type and reproduce vanilla faithfully for every unallocated state.
- **Packless fallback for blocks**: a `pack` + `block` level renders the
  allocated carrier state to players without the pack — a normal note block
  (visible). Use `virtual+pack` when a mixed population must see the exact
  minecraft-visible mesh rather than the carrier approximation.
- **Pack lighting is per-model**: a display entity carries one `Brightness`, so
  per-face/per-cube `light_emission` cannot be represented in pack modes — the
  model's maximum emission is used (the virtual engine keeps per-cube emission
  because it emits per-cube displays). `PACK.LIGHTING: AUTO` (default) still
  gives non-emissive models natural world lighting; see [Lighting](#lighting).
- **Legacy `CustomModelData` mode** regenerates vanilla item model host files
  (predicate-gated; the era-typical conflict surface). Modern servers
  (1.21.4+) use the strictly additive `item_model` component. Block host files
  are representation-independent — a `BlockDisplay` has no per-entity model
  dispatch on any client version — but they necessarily own the carrier's
  blockstate file and must reproduce every vanilla state (which the generator
  does).
- **Pack-format table** lags new Minecraft releases; the emitted
  `supported_formats` range keeps newer clients accepting the pack anyway, and
  `PACK_FORMAT_OVERRIDE` pins the exact `pack_format` when needed.
- **Vanilla overlay raw assets** (the Gunsmith tree) re-skin the overlaid
  items' *looks only*. Modern clients (1.21.4+) resolve the overlay through
  the tree's `items/*.json` definitions; older clients (1.21–1.21.3)
  resolve the same pistol/rifle models through `custom_model_data`
  predicate overrides in the shipped vanilla-replica
  `models/item/bow.json` / `models/item/crossbow.json` host files (value 1,
  stamped on the test rig by `/gunsmith give` — unset vanilla items stay
  vanilla on every version). No vanilla sound or projectile texture is
  overridden: the tree's `sounds.json` registers only the additive
  `minecraft:gun.fire` event, played explicitly by the Gunsmith firing
  runtime (a hitscan — no arrow is ever launched).
- **Pack-driven animation**: pack world objects reuse the existing animation
  architecture's pose pipeline where bindings apply, but client-side
  predicate/item-model animation (ModelEngine-style rigs) is future work — the
  `AnimationApi` selector surface is the integration point.
- **Custom mobs / armor runtime**: the definitional layer (identity, model,
  slot semantics via `PackItemDefinition.equipmentSlot`) is in place; mob
  hosting and armor-layer textures are extension points, not shipped runtime.
