# Create assets permission

This module implements gameplay inspired by the Create mod. **No Create
source code, textures or model assets are shipped with this plugin.**

If/when third-party assets are introduced, written permission from the Create
team (or a license explicitly permitting redistribution in this context) must
be saved in this directory as:

- `create-team-permission.md` — the verbatim permission text with the date and
  the responding team member, or a link to the archived thread.
- `create-license-copy.md` — the license text under which the assets ship.

Until those files exist, every model this module ships is original work (e.g.
produced by the devtools/create-harvest pipeline from vanilla block models or
drawn from scratch in Blockbench). The Model Harvester never reads the Create
jar at runtime and never copies Create assets into the build.
