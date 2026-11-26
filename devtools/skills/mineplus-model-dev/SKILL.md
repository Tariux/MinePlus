---
name: mineplus-model-dev
description: Work on MinePlus (Spigot/Paper plugin) and its models — build/test the Java plugin, author or edit Blockbench .bbmodel files, and use the custom blockbench-plugin so models stay compatible with MinePlus. Use when the task touches models, display settings, the resource-pack pipeline, or devtools.
---

# MinePlus model & plugin development

## What MinePlus is

Server-side Spigot/Paper plugin (`com.mineplus`, single jar, `build.gradle` at repo root) for custom items, blocks, multiblocks, GUIs and rendered models. Models come from Blockbench `.bbmodel` files and render through two axes: the resource pack (primary) and the virtual/texel axis (fallback).

## Dependencies (already committed; do not add others without asking)

- **Java 21** toolchain, **Gradle wrapper** (`./gradlew`, never a system gradle).
- `libs/*.jar` are **compileOnly Spigot stubs**: spigot-api-1.21.1, adventure 4.17.0 (+platform-api 4.3.0), gson 2.13.2, guava, joml 1.10.8, item-nbt-api 2.15.6, sqlite-jdbc 3.45.1.0, slf4j 2.0.13, HikariCP 5.1.0, fastutil 8.5.14.
- **Never** add `paper-api` to any classpath — Paper-only methods must stay behind the reflective seams (`util/ServerVersion`, `pack/PackFormat`, `util/SchedulerCompat`, `pack/ViaCompatProbe`, etc.). Target range is Minecraft 1.21.0 → 26.x.
- Blockbench plugin dev: plain **Node** (no npm deps), tests in `devtools/blockbench-plugin/test/plugin.test.js`.

## Custom Blockbench plugin (required for model compatibility)

- Location: `devtools/blockbench-plugin/mineplus_generic_display.js` (load via `web-index-plugin-loader.html` or install the file as a Blockbench plugin).
- It provides **Tools ▸ Copy Display Settings / Paste Display Settings** — snapshot every display slot (first/thirdperson, head, ground, gui, fixed) with rotation, translation, scale, pivots, mirror, using Blockbench's own `DisplaySlot.copy()` / `DisplaySlot.extend()` APIs. Use these instead of hand-editing display JSON.
- **Display settings are sacred:** MinePlus imports per-cube/per-group display overrides (`BbModelImporter` merges root < group < element) and emits them verbatim into the pack — nothing in the pipeline may override an authored pose (first-person included). Untouched contexts only fall back to `handheld` (`ModelJsonWriter`).
- Models must follow the anchor convention: pixel (0,0,0) = anchor block center at its base (full block spans [-8..8] xz, [0..16] y); origin mode `CENTER` default, `GRID` for multi-block.

## Build & verify

- Full check: `./gradlew check` (runs unit tests + all `testRun*` plain-Java runners, e.g. `testRunCustomMobPipelineTest`).
- Fast compile check: `./gradlew compileJava compileTestJava`.
- Single runner: `./gradlew testRunCustomMobPipelineTest`.
- Blockbench plugin: `node devtools/blockbench-plugin/test/plugin.test.js` (must exit 0).
- Report a check as passing only after running it in this task and quoting the result.

## Key docs

`docs/model-authoring-guide.md`, `docs/display-slot-mapping.md`, `docs/pack-system.md`, `docs/custom-mobs.md`, `docs/version-compatibility.md`.

## Starter prompt (paste at the top of a new task)

> You are working in the MinePlus repo (Spigot/Paper plugin, Java 21, Gradle wrapper; deps are committed `libs/*.jar` Spigot stubs — never add paper-api or new libs). Models are Blockbench `.bbmodel` files rendered through the resource-pack/virtual pipeline; my custom Blockbench plugin at `devtools/blockbench-plugin/mineplus_generic_display.js` (Copy/Paste Display Settings tools) defines how display settings are authored, and they must reach the game unchanged — never override authored display poses in the importer or pack writer. Follow the skill `.agents/skills/mineplus-model-dev/SKILL.md`. Task: <describe your task>. Before done, run `./gradlew check` and `node devtools/blockbench-plugin/test/plugin.test.js` and quote the real results.
