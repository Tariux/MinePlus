# Live acceptance test (custom blocks)

`run.sh` proves the custom-block system on a real Paper server instead of a mock:
it builds the plugin, downloads Paper, installs the example block definitions and
models, starts the server headless, then drives a Mineflayer bot through the
player-facing flow.

```bash
devtools/live-test/run.sh [work-dir] [minecraft-version]
```

Defaults: work dir `/tmp/mineplus-live`, Minecraft `1.21.1`, Paper build `133`
(pinned by SHA-256). Set `PAPER_BUILD`/`PAPER_SHA` or pass a version to test
another target. The work dir is reused, so the second run skips the download.

## What it asserts

Phase 1, one server run:

1. `/mineplus blocks give` delivers a block item.
2. `/mineplus blocks place` spawns the model and creates barrier cells.
3. The placement is written to `blocks_state.json`.
4. A bare-hand dig is refused with the "Needs a axe." hint and changes nothing.
5. A wooden axe breaks the block: every barrier cell goes, the state record is
   removed, and the block item is dropped back.
6. A block is placed again and left for phase 2.

Phase 2, after a restart:

7. The plugin logs `Restored N placed block(s)` and the barrier cells are back
   when the chunk loads.

## Notes

- Phase 2 restarts the server on the same port. `run.sh` sends SIGTERM to the
  pid in `$WORK/server.pid`, waits up to 60 s for the JVM to exit (SIGKILL after
  that) and then refuses to start while the port still answers, so a lingering
  JVM cannot fail the restart on `world/session.lock`.
- On a sandbox whose egress blocks `*.mojang.com`, Paper logs an auth warning
  and may take extra time before `Done`; `start_server` waits up to 5 minutes.
- Barrier cells are unbreakable client-side, so `bot.dig` cannot reach them;
  `bot-e2e.mjs` sends `block_dig` packets directly, which is what a real client
  does while holding left-click.
- The barrier lattice comes from the **model geometry** and is capped by the
  model's `fillBox` (`.bbmodel.meta.json`), not by `occupancy` in the block JSON.
  `occupancy` is the authored claim that `check-bbmodel.mjs --definition` checks
  against the geometry.
- The bot sees the block item as `paper` with a custom-data tag; the resource
  pack is what turns it into a model on a real client, and pack delivery is out
  of scope here.
