# 🔁 Migration Guide — Unified Rendering Engine

> **Navigate:** [Docs Home](README.md) • [Configuration Reference](config-reference.md) • [Developer API](developer-api.md) • [Resource Pack System](pack-system.md) • [Changelog](../CHANGELOG.md)

This guide covers the **Unified Rendering Engine** update. Nothing is *removed*:
every legacy key, constructor, and API call keeps working. The changes are
additive (new unified vocabulary and telemetry) plus a small number of
**behavioral defaults** you may want to review.

---

## TL;DR

| Area | What changed | Action needed |
|---|---|---|
| Multiblock JSON | New unified `renderMode` key; `renderBackend`/`renderKind` still parse | Optional: switch to `renderMode` |
| `settings.mp.yml` | New `RENDERING.POLICY`, six new `TEXEL_BAKING` keys, `PACK.LIGHTING` | None — omitted keys use safe defaults |
| Texel baking | Uniform-area coalescing, adaptive budgeting, face-bake reuse (all default on) | Review if you tuned plate budgets by hand |
| Pack lighting | `PACK.LIGHTING: AUTO` is the default — non-emissive pack models are now lit by the world instead of forced full-bright | Review if you relied on the old always-glowing look |
| Developer API | New `RenderMode` / `RenderPlan` / `RenderPolicy` / `RenderRouter`, `PluginContext.renderRouter()`, `/mineplus render stats` | Optional: use the new vocabulary |

No configuration key was renamed or removed. No Java API was removed or changed
in a source-incompatible way.

---

## 1) Configuration keys

### Added — `RENDERING.POLICY`

```yaml
RENDERING:
  POLICY:
    ALLOW_DEGRADE_TO_VIRTUAL: true   # pack request falls back to virtual when unavailable
    LOG_ROUTING: false               # log each routing decision (needs ADDITIONAL_DEBUG_LOGS)
    TRACK_TELEMETRY: true            # counters for /mineplus render stats
```

Defaults reproduce the previous fallback behavior exactly (pack requests always
degraded to virtual when the pack subsystem was off). Set
`ALLOW_DEGRADE_TO_VIRTUAL: false` only if you want pack content to render
*nothing* rather than a virtual approximation when the pack is unavailable.

### Added — `TEXEL_BAKING` plate-count keys

```yaml
TEXEL_BAKING:
  UNIFORM_AREA_DETECTION: true
  UNIFORM_AREA_MIN_SIZE: 8
  UNIFORM_AREA_OKLAB_THRESHOLD: 0.05
  ADAPTIVE_BUDGETING: true
  BUDGET_FALLBACK_TO_SIMPLE_COLOR: true
  REUSE_SYMMETRIC_FACES: true
```

These reduce plate counts. They are all on by default because they either
preserve appearance (identical-color coalescing, face reuse, occlusion
pre-filtering) or degrade gracefully (a flat local tone for a face that already
exceeded its budget). To restore the exact pre-update baking output, set
`UNIFORM_AREA_DETECTION: false`, `ADAPTIVE_BUDGETING: false`,
`BUDGET_FALLBACK_TO_SIMPLE_COLOR: false`, and `REUSE_SYMMETRIC_FACES: false`.
`UNIFORM_AREA_MIN_SIZE` is the main knob: raise it to coalesce only very large
regions, lower it for more aggressive reduction.

### Added — `PACK.LIGHTING`

```yaml
PACK:
  LIGHTING: AUTO   # AUTO | NATURAL | EMISSIVE | FULLBRIGHT
```

**This is the one visible default change.** Previously every pack display was
forced to full brightness `(15, 15)`, so pack models glowed wherever they were.
`AUTO` (the new default) lights non-emissive models with natural world light and
only applies a brightness floor — the model's maximum `light_emission` — to
emissive models. If you want the old always-glowing look, set
`LIGHTING: FULLBRIGHT`. `PACK` settings apply after a restart.

---

## 2) Multiblock JSON

The `renderBackend` + `renderKind` pair is now expressed by one `renderMode`
value. Both forms are accepted; `renderMode` wins when present.

| Before | After |
|---|---|
| *(absent)* / `"renderBackend": "virtual"` | `"renderMode": "virtual"` |
| `"renderBackend": "pack"` | `"renderMode": "pack_item"` (or `"pack"`) |
| `"renderBackend": "pack", "renderKind": "block"` | `"renderMode": "pack_block"` (or `"block"`) |
| `"renderBackend": "virtual+pack"` | `"renderMode": "hybrid_item"` (or `"hybrid"`) |
| `"renderBackend": "virtual+pack", "renderKind": "block"` | `"renderMode": "hybrid_block"` |

```json
{
  "id": "alchemy_table",
  "levels": {
    "1": { "model": "models/alchemy-table.bbmodel", "renderMode": "pack_block" }
  }
}
```

Seed data in `examples/mineplus-fun` has been migrated to `renderMode`.

---

## 3) Developer API

**Nothing was removed.** The following were added on top of the existing
`RenderBackend` / `RenderKind` vocabulary, which remains the internal mechanism.

| Added | Purpose |
|---|---|
| `com.mineplus.infrastructure.render.RenderMode` | Unified mode enum; `RenderMode.of(backend, kind)` maps the legacy pair |
| `RenderPlan` | Declarative render intent (mode + optional policy + provenance) |
| `RenderPolicy` | Global fallback/logging/telemetry policy |
| `RenderRouter` | The routing choke point + telemetry (`route`, `snapshot`, `reset`) |
| `PluginContext.renderRouter()` | Access to the router from modules (never null) |
| `MultiBlockLevel.renderMode()` / `renderPlan(source)` | A level's mode and plan |
| `/mineplus render stats` / `render reset` | Routing telemetry |

`ModelRenderingManager` now routes through the `RenderRouter` instead of
resolving the backend inline; observable behavior is unchanged under the default
policy. Modules that only used `InfrastructureApi`, `BasicInfrastructureApi`,
`AnimationApi`, or `PackApi` need no changes.

### Updating a custom module (`mineplus-fun` derivatives)

1. Replace `renderBackend`/`renderKind` in your multiblock JSON with
   `renderMode` (see the table above). Not required — legacy keys still work.
2. Leave Java registration code as-is; `PackApi` and `InfrastructureApi` are
   source-compatible.
3. If you build a `PackSettings` programmatically, note the new trailing
   `PackLighting lighting` component (use `PackSettings.defaults().lighting()`
   or `PackLighting.AUTO`).
4. If you construct a `TexelBakingSettings` directly, note the six new trailing
   components — the simplest migration is to start from
   `TexelBakingSettings.defaults()` and override the fields you care about, as
   the bundled `devtools` baker does.
5. Rebuild against the new Core jar.

---

## 4) Behavior notes

- **Plate counts** for texel-baked models drop in most scenes (uniform
  coalescing, adaptive budgets, face reuse). `/mineplus model info <key>` now
  reports `simplifiedFallbacks` and `reusedFaceBakes` next to the budget verdict.
- **Pack lighting** defaults to natural world lighting for non-emissive models
  (see §1). This is the most noticeable change for players.
- **Occlusion tests** gain a per-cube bounding-box pre-filter; results are
  unchanged, only faster.
- **Routing telemetry** is on by default and costs a few atomic increments per
  render; disable with `RENDERING.POLICY.TRACK_TELEMETRY: false` if desired.

---

> ➡️ **Next:** the exact key-by-key reference is in the
> [Configuration Reference](config-reference.md); the render-mode API is in the
> [Developer API](developer-api.md#render-modes).
