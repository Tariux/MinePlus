# Superseded by the Node importer.
#
# The hand-maintained frame list and the hand-smoothed display transforms this
# script used produced poses the source packs never had, which is exactly how a
# gun ended up with another gun's frame. `tools/import-western-frames.js`
# detects the frame set from the source file names, copies the pack's own
# `display` verbatim and authors the `fire` pose. This wrapper keeps old
# invocations working by delegating to it.
#
#   powershell -File tools/import-western-models.ps1 [gunpacks-root]

param(
    [string]$GunpacksRoot
)

$ErrorActionPreference = "Stop"

$importer = Join-Path $PSScriptRoot "import-western-frames.js"
if (-not (Test-Path -LiteralPath $importer)) {
    throw "Missing importer: $importer"
}

if ($GunpacksRoot) {
    & node $importer $GunpacksRoot
} else {
    & node $importer
}
exit $LASTEXITCODE
