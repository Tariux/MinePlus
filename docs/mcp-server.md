# Mine Plus MCP server — parked

The experimental MCP server (`devtools/mcp/mineplus-mcp-server.js`) was removed
in November 2026 to keep all tooling effort on the Blockbench plugin
(`devtools/studio/generic-display-plugin/`).

What it did, for when this is revived:

- stdio JSON-RPC server, zero dependencies, Node >= 18
- detected whether a `.bbmodel` / `.mpmodel` file is a Mine Plus template
  (top-level `mineplus` metadata block)
- audited a model against the pack limits: cube count, bone count, element
  rotation 22.5° steps (pre-1.21.6 targets), texture resolution, animation list
- surfaced the authored `display` block as JSON

Design notes for the revival:

1. Keep it stdio and dependency-free so it can run from any MCP client config.
2. Reuse the same validation rules as `ModelGeometryGuard` (server side) and the
   plugin's Validate Model action (tool side) so all three agree.
3. Tools to expose: `check_template`, `audit_model`, `read_display`,
   `list_animations`.
