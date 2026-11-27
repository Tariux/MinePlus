#!/usr/bin/env bash
# End-to-end acceptance run for the custom-block system on a real Paper server.
#
#   devtools/live-test/run.sh [work-dir] [minecraft-version]
#
# Builds the plugin, downloads Paper, installs the example block definitions and
# models, starts the server headless, and drives a Mineflayer bot through:
#   give -> place -> barrier cells appear -> wrong tool refused -> axe breaks the
#   block -> state record removed -> restart -> block restored.
# Exit 0 means every assertion held. The work dir defaults to /tmp/mineplus-live
# and is reused, so a second run is fast.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${1:-/tmp/mineplus-live}"
MC="${2:-1.21.1}"
PAPER_BUILD="${PAPER_BUILD:-133}"
PAPER_SHA="39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9"
PAPER_URL="https://fill-data.papermc.io/v1/objects/$PAPER_SHA/paper-$MC-$PAPER_BUILD.jar"
PORT="${PORT:-25565}"
export PORT MC

say() { printf '\n== %s\n' "$*"; }

say "building the plugin"
(cd "$REPO" && ./gradlew build --offline -q)
JAR="$REPO/build/libs/$(ls "$REPO/build/libs" | grep -E '^mineplus-.*\.jar$' | head -1)"

mkdir -p "$WORK/plugins"
say "server in $WORK"
if [ ! -s "$WORK/paper.jar" ]; then
  curl -sSfL -o "$WORK/paper.jar" -H "User-Agent: mineplus-live-test/1.0" "$PAPER_URL"
fi
echo "$PAPER_SHA  $WORK/paper.jar" | sha256sum -c - >/dev/null
echo "eula=true" > "$WORK/eula.txt"
cat > "$WORK/server.properties" <<EOF
online-mode=false
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
max-players=4
view-distance=4
simulation-distance=4
server-port=$PORT
EOF

say "installing plugin and example content"
cp "$JAR" "$WORK/plugins/mineplus.jar"
EX="$REPO/examples/mineplus-machine/src/main/resources/defaults"
rm -rf "$WORK/plugins/Mineplus/blocks" "$WORK/plugins/Mineplus/models"
mkdir -p "$WORK/plugins/Mineplus"
cp -r "$EX/blocks" "$WORK/plugins/Mineplus/blocks"
cp -r "$EX/models" "$WORK/plugins/Mineplus/models"
rm -f "$WORK/plugins/Mineplus/blocks_state.json"
# barriers are real world blocks, so a reused world would carry stale ones
rm -rf "$WORK/world" "$WORK/world_nether" "$WORK/world_the_end"

say "installing the bot"
BOT_UUID=$(python3 - <<'EOF'
import hashlib, uuid
b = bytearray(hashlib.md5(b"OfflinePlayer:Tester").digest())
b[6] = (b[6] & 0x0F) | 0x30
b[8] = (b[8] & 0x3F) | 0x80
print(uuid.UUID(bytes=bytes(b)))
EOF
)
cat > "$WORK/ops.json" <<EOF
[{"uuid": "$BOT_UUID", "name": "Tester", "level": 4, "bypassesPlayerLimit": true}]
EOF
if [ ! -d "$WORK/node_modules/mineflayer" ]; then
  (cd "$WORK" && npm init -y >/dev/null 2>&1 && npm i mineflayer@4 --silent)
fi
cp "$REPO/devtools/live-test/bot-e2e.mjs" "$WORK/bot-e2e.mjs"

port_busy() {
  ss -ltn "sport = :$PORT" 2>/dev/null | grep -q LISTEN
}

start_server() {
  stop_server
  if port_busy; then
    echo "port $PORT is already in use; stop that server first" >&2
    return 1
  fi
  rm -f "$WORK/run.log"
  ( cd "$WORK" && exec java -Xms1G -Xmx2500M -jar paper.jar nogui > run.log 2>&1 ) &
  echo "$!" > "$WORK/server.pid"
  for _ in $(seq 1 150); do
    grep -q 'Done (' "$WORK/run.log" 2>/dev/null && return 0
    if ! kill -0 "$(cat "$WORK/server.pid")" 2>/dev/null; then
      echo "server exited during startup" >&2
      tail -20 "$WORK/run.log" >&2
      return 1
    fi
    sleep 2
  done
  echo "server did not reach Done" >&2
  tail -20 "$WORK/run.log" >&2
  return 1
}

stop_server() {
  [ -f "$WORK/server.pid" ] || return 0
  local pid
  pid=$(cat "$WORK/server.pid")
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 60); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    if kill -0 "$pid" 2>/dev/null; then
      echo "server $pid did not stop on SIGTERM; killing it" >&2
      kill -9 "$pid" 2>/dev/null || true
    fi
  fi
  rm -f "$WORK/server.pid"
}

trap stop_server EXIT

say "starting minecraft $MC"
start_server
grep -E 'Block definitions' "$WORK/run.log" | tail -1

say "phase 1: place, gate, break"
(cd "$WORK" && node bot-e2e.mjs phase1)

say "phase 2: restart and restore"
stop_server
start_server
grep -E 'Restored|Block definitions' "$WORK/run.log" | tail -2
(cd "$WORK" && node bot-e2e.mjs phase2)

say "server warnings from Mineplus"
grep -E '\[Mineplus\].*(WARN|ERROR)' "$WORK/run.log" | grep -v 'already exists' || echo "none"

say "PASS"
