# ADR 0009: Minigame/research persistence in SQLite, and Redis pub/sub over plain sockets

Date: 2026-11-25
Status: Accepted (implemented; see docs/mega-plan/07-roadmap.md Phases C4/D2/D3)

## Context

The mega plan calls for durable research progress (C4) and minigame instance snapshots (D3), plus a cross-server message bus (D2) with Redis pub/sub as the transport. Constraints of this codebase:

- Tests run headless with no Bukkit mock; persistence must be exercisable in-process.
- There is no Redis client jar in `libs/`, and adding a heavyweight dependency for one pub/sub call is against the project's declared-jar discipline.
- Some deployments (single server, no MySQL/datasource) still need research progress to survive restarts.

## Decision

1. **Persistence goes through the SQLite driver already declared in `build.gradle`** (`sqlite-jdbc-3.45.1.0.jar`), in `src/main/java/com/mineplus/infrastructure/persistence/sqlite/`:
   - `SqliteResearchRepository` — one row per owner, saves replace all nodes in one transaction, loads are tolerant of malformed rows.
   - `SqliteMinigameInstanceRepository` — one row per instance, stores a snapshot of state/teams/scores from `InstanceManager`, same transactional replace-all shape.
   Both share the same connection discipline as the existing multiblock persistence. They are keyed by owner/instance id, so in-memory state and disk can be reconciled at startup (`ResearchTree.restore`).
   - Alternative rejected: MySQL-only via HikariCP — excludes the common single-server install; HikariCP stays available for deployments that have a datasource.
   - Alternative rejected: YAML files per owner — unbounded file counts and no transactional replace.

2. **Datasource-less setups get the same seam through `fileFallback(...)`** — the repositories are behind a small interface, so a JSON/file-backed implementation can be swapped in without touching callers. Tests exercise both paths.

3. **Redis transport speaks RESP pub/sub over plain sockets** (`core/network/RedisTransport.java`) — SUBSCRIBE/PUBLISH frames written and read by hand, no client jar. It implements the same `Transport` seam as the in-memory default (`MessageBus`), with reconnect and backoff; message ids plus echo suppression give dedupe in the bus, so delivery is at-least-once.
   - Alternative rejected: adding jedis/lettuce — a second connection pool and its netty stack for one command pair; if a client jar is ever added, only `RedisTransport` is replaced.
   - Alternative rejected: shipping only the in-memory transport — D2's cross-server requirement (queue, parties, cross-server commands) cannot be met without real pub/sub.

4. **Multi-server-on-one-host setups get `FileSpoolTransport`** (`core/network/FileSpoolTransport.java`) — atomic temp-then-move message files per peer, watched outboxes, malformed-message tolerance, same at-least-once contract. Same seam, so a deployment picks a transport by constructing the bus with one class.

## Consequences

- Research progress and minigame state survive restarts on every install, with or without a datasource.
- Cross-server messaging works without any new dependency; upgrading to a managed Redis client later touches exactly one class per transport seam.
- SQLite is single-writer: suitable for the per-server ownership model here, not for a shared cluster database — cluster setups use a datasource-backed implementation of the same interface.
