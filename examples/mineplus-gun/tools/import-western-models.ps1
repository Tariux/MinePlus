# Imports the author's Western gunpacks (vanilla resource-pack JSON models)
# into MineplusGun frame resources.
#
# Source packs (local authoring material, never committed):
#   C:\Users\Tariux\Desktop\gunpacks\western_1   (western pistol + rifle, bow overlay)
#   C:\Users\Tariux\Desktop\gunpacks\western_2   (lever rifle + coach shotgun)
#   C:\Users\Tariux\Desktop\gunpacks\western_3   (musketmod: flintlocks + blunderbuss)
#
# Output (module resources):
#   src/main/resources/defaults/frames/<weapon-id>/<frame>.json
#   src/main/resources/defaults/frames/textures/<weapon-specific>.png
#
# Cleanup applied to every model:
#   - texture references re-pointed into the mineplusgun namespace with a
#     per-weapon unique texture file (packs share names like "pistol.png")
#   - dangling "#missing" face textures mapped to the model's first texture
#   - Blockbench/vanilla-decoration keys stripped (format_version, credit,
#     texture_size, groups) and mod-item "overrides" dropped (the plugin
#     drives frame switching itself via custom_model_data)

$ErrorActionPreference = "Stop"

$moduleRoot = Split-Path -Parent $PSScriptRoot
$outRoot = Join-Path $moduleRoot "src\main\resources\defaults\frames"
$textureOutDir = Join-Path $outRoot "textures"

$pack1 = "C:\Users\Tariux\Desktop\gunpacks\western_1\assets\minecraft"
$pack2 = "C:\Users\Tariux\Desktop\gunpacks\western_2\assets\minecraft"
$pack3 = "C:\Users\Tariux\Desktop\gunpacks\western_3\assets\musketmod"

# Per-weapon frame mapping: logical frame -> source model stem.
# The arsenal is exactly the pack guns: western_1 pistol + rifle,
# western_2 lever rifle + shotgun, western_3 musketmod flintlocks.
$weapons = @(
    @{ id = "western_pistol";     texture = "western_pistol.png";
       base = "$pack1\models\item\pistol";   tex = "$pack1\textures\item\pistol.png";
       frames = @{ idle = "pistol_idle"; reload0 = "pistol_loading_0"; reload1 = "pistol_loading_1";
                   reload2 = "pistol_loading_2"; reload3 = "pistol_loading_3"; reload4 = "pistol_loading_4";
                   loaded = "pistol_primed" } },
    @{ id = "western_rifle";      texture = "western_rifle.png";
       base = "$pack1\models\item\rifle";    tex = "$pack1\textures\item\rifle.png";
       frames = @{ idle = "rifle_idle"; reload0 = "rifle_loading_0"; reload1 = "rifle_loading_1";
                   reload2 = "rifle_loading_2"; reload3 = "rifle_loading_3"; reload4 = "rifle_loading_4";
                   reload5 = "rifle_loading_5"; loaded = "rifle_primed" } },
    @{ id = "lever_rifle";        texture = "lever_rifle.png";
       base = "$pack2\models\item\rifle";    tex = "$pack2\textures\item\rifle.png";
       frames = @{ idle = "rifle_idle"; reload0 = "rifle_loading_0"; reload1 = "rifle_loading_1";
                   reload2 = "rifle_loading_2"; reload3 = "rifle_loading_3"; reload4 = "rifle_loading_4";
                   loaded = "rifle_primed" } },
    @{ id = "coach_shotgun";      texture = "coach_shotgun.png";
       base = "$pack2\models\item\shotgun";  tex = "$pack2\textures\item\shotgun.png";
       frames = @{ idle = "shotgun_idle"; reload0 = "shotgun_loading_0"; reload1 = "shotgun_loading_1";
                   reload2 = "shotgun_loading_2"; reload3 = "shotgun_loading_3"; reload4 = "shotgun_loading_4";
                   reload5 = "shotgun_loading_5"; reload6 = "shotgun_loading_6"; reload7 = "shotgun_loading_7";
                   loaded = "shotgun_primed" } },
    @{ id = "flintlock_pistol";   texture = "flintlock_pistol.png";
       base = "$pack3\models\item";           tex = "$pack3\textures\item\pistol.png";
       frames = @{ idle = "pistol"; loaded = "pistol_loaded" } },
    @{ id = "flintlock_musket";   texture = "flintlock_musket.png";
       base = "$pack3\models\item";           tex = "$pack3\textures\item\musket.png";
       frames = @{ idle = "musket"; loaded = "musket_loaded" } },
    @{ id = "scope_musket";       texture = "scope_musket.png";
       base = "$pack3\models\item";           tex = "$pack3\textures\item\musket_with_scope.png";
       frames = @{ idle = "musket_with_scope"; loaded = "musket_with_scope_loaded" } },
    @{ id = "bayonet_musket";     texture = "bayonet_musket.png";
       base = "$pack3\models\item";           tex = "$pack3\textures\item\musket_with_bayonet.png";
       frames = @{ idle = "musket_with_bayonet"; loaded = "musket_with_bayonet_loaded" } },
    @{ id = "blunderbuss";        texture = "blunderbuss.png";
       base = "$pack3\models\item";           tex = "$pack3\textures\item\blunderbuss.png";
       frames = @{ idle = "blunderbuss"; loaded = "blunderbuss_loaded" } }
)

if (-not (Test-Path -LiteralPath $outRoot)) {
    New-Item -ItemType Directory -Path $outRoot | Out-Null
}
if (-not (Test-Path -LiteralPath $textureOutDir)) {
    New-Item -ItemType Directory -Path $textureOutDir | Out-Null
}

function Clean-Model([object]$model, [string]$namespace, [string]$textureName) {
    foreach ($key in @("format_version", "credit", "texture_size", "groups", "overrides")) {
        if ($model.PSObject.Properties[$key]) {
            $model.PSObject.Properties.Remove($key)
        }
    }

    $textureKeys = @()
    if ($model.textures) {
        $textureKeys = @($model.textures.PSObject.Properties.Name)
        foreach ($prop in $model.textures.PSObject.Properties) {
            $prop.Value = "${namespace}:item/${textureName}"
        }
    }

    if ($model.elements) {
        foreach ($element in $model.elements) {
            foreach ($faceProp in $element.faces.PSObject.Properties) {
                $face = $faceProp.Value
                if ($face -and $face.texture) {
                    if ($face.texture -eq "#missing" -or $face.texture -notin $textureKeys) {
                        $face.texture = "#" + $textureKeys[0]
                    }
                }
            }
        }
    }
    return $model
}

$imported = 0
foreach ($weapon in $weapons) {
    $weaponDir = Join-Path $outRoot $weapon.id
    if (-not (Test-Path -LiteralPath $weaponDir)) {
        New-Item -ItemType Directory -Path $weaponDir | Out-Null
    }

    $textureTarget = Join-Path $textureOutDir $weapon.texture
    Copy-Item -LiteralPath $weapon.tex -Destination $textureTarget -Force

    foreach ($frameName in $weapon.frames.Keys) {
        $stem = $weapon.frames[$frameName]
        $sourceFile = Join-Path $weapon.base ($stem + ".json")
        if (-not (Test-Path -LiteralPath $sourceFile)) {
            Write-Warning "Missing source model: $sourceFile"
            continue
        }
        $json = Get-Content -LiteralPath $sourceFile -Raw | ConvertFrom-Json
        $clean = Clean-Model -model $json -namespace "mineplusgun" -textureName ($weapon.texture -replace '\.png$', '')
        $target = Join-Path $weaponDir ($frameName + ".json")
        $clean | ConvertTo-Json -Depth 64 | Set-Content -LiteralPath $target -Encoding UTF8
        $imported++
    }
    Write-Host ("[{0}] {1} frame(s), texture {2}" -f $weapon.id, $weapon.frames.Count, $weapon.texture)
}
Write-Host "Imported $imported frame model(s) into $outRoot"
