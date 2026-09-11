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
| `com.mineplus.infrastructure.render.RenderBackend` | the one shared backend vocabulary: `VIRTUAL`, `PACK`, `VIRTUAL_PLUS_PACK` |
| `com.mineplus.pack.PackApi` | the developer surface — a peer of `InfrastructureApi`/`AnimationApi`, obtained via `PluginContext.packApi()` |
| `PackAssetRegistry` | namespaced assets (`namespace:path`) with owner tracking and conflict detection |
| `PackCompiler` / `PackCache` | deterministic, dependency-aware compilation into hash-named `packs/mp-<hash>.zip` artifacts |
| `PackDeliveryService` | LOCAL byte endpoint / STATIC_URL / DISABLED; per-player state tracking |
| `PackItemFactory` | `ContentDefinition → ItemStack` (modern `item_model` component, legacy `CustomModelData` fallback) |
| `PackModelRenderer` | pack-axis world rendering (ItemDisplay entities) |
| `ModelRenderingManager` | the **backend choke point** — the only place a render decision becomes a backend call |

**Coexistence contract** (enforced structurally):

- The pack subsystem never touches the virtual display transport, texel bake
  pool, `VirtualBlockManager` internals, or the animation tick loop.
- Pack world objects reuse the virtual engine's collision lattice through its
  public collision-only spawn API — barriers, break handling, occupancy and
  restore dedupe behave identically for both backends.
- Backend selection lives in multiblock level definitions
  (`"renderBackend": "virtual" | "pack" | "virtual+pack"`), default `virtual`.

## Backend semantics

| Backend | Collision | Visual (pack player) | Visual (packless player) |
|---|---|---|---|
| `virtual` (default) | virtual engine | virtual render (texel/block displays) | virtual render — identical |
| `pack` | virtual engine (collision-only) | pack item model (full fidelity) | backing vanilla item |
| `virtual+pack` | virtual engine | virtual render **+** pack display | virtual render |

Fallbacks, in one place (`MultiBlockLevel.effectiveBackend`): when the pack
subsystem is disabled, `pack` and `virtual+pack` degrade to `virtual`. When a
model has no registered pack item, the pack render falls back to a full
virtual render. A player without the pack always sees *something* valid.

## Content model

```
PackItemDefinition (namespace:id, backing material, model key, display name)
        ↓ PackApi.registerItem
ItemRegistry identity (PDC — the same recognition every Mineplus item uses)
        + auto-registered ModelAsset + TextureAssets (from the model + its PNGs)
        ↓ compile
assets/<ns>/models/item/<id>.json   ← serialized from the imported VirtualModel
assets/<ns>/textures/<name>.png     ← the same PNGs the texel baker reads
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

Multiblock levels select their backend in JSON:

```json
"levels": {
  "1": {
    "model": "models/strad-wine.bbmodel",
    "renderBackend": "pack"
  }
}
```

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
- **Legacy `CustomModelData` mode** regenerates vanilla item model host files
  (predicate-gated; the era-typical conflict surface). Modern servers
  (1.21.4+) use the strictly additive `item_model` component.
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
