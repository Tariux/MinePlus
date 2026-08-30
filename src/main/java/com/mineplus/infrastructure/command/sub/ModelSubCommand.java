package com.mineplus.infrastructure.command.sub;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

public final class ModelSubCommand implements SubCommand {

    private final PluginContext context;

    public ModelSubCommand(PluginContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "model";
    }

    @Override
    public String description() {
        return "Inspect and administrate active model-backed multiblocks.";
    }

    @Override
    public String usage() {
        return "/mineplus model <list|inspect|info|remove|respawn|setlevel|models|debugspawn> ...";
    }

    @Override
    public String permission() {
        return "mineplus.admin.model";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }

        String action = args[0].toLowerCase(Locale.ROOT);

        switch (action) {
            case "list" -> {
                int limit = args.length > 1 ? parsePositiveInt(args[1], 10) : 10;
                listInstances(sender, limit);
                return true;
            }
            case "inspect" -> {
                MultiBlockInstance instance = resolveInstance(sender, args.length > 1 ? args[1] : "look");
                if (instance == null) {
                    sender.sendMessage(ChatColor.RED + "No active multiblock found for the provided target.");
                    return true;
                }
                describeInstance(sender, instance);
                return true;
            }
            case "remove" -> {
                MultiBlockInstance instance = resolveInstance(sender, args.length > 1 ? args[1] : "look");
                if (instance == null) {
                    sender.sendMessage(ChatColor.RED + "No active multiblock found for the provided target.");
                    return true;
                }
                Player actor = sender instanceof Player player ? player : null;
                boolean removed = context.infrastructureEngine().lifecycleManager().remove(instance.id(), actor, true);
                sender.sendMessage(removed
                        ? ChatColor.GREEN + "Removed instance " + instance.id()
                        : ChatColor.RED + "Failed to remove instance " + instance.id());
                return true;
            }
            case "respawn" -> {
                MultiBlockInstance instance = resolveInstance(sender, args.length > 1 ? args[1] : "look");
                if (instance == null) {
                    sender.sendMessage(ChatColor.RED + "No active multiblock found for the provided target.");
                    return true;
                }
                boolean reloaded = context.infrastructureEngine().lifecycleManager().reloadModel(instance.id());
                sender.sendMessage(reloaded
                        ? ChatColor.GREEN + "Respawned model for instance " + instance.id()
                        : ChatColor.RED + "Failed to respawn model for " + instance.id());
                return true;
            }
            case "setlevel" -> {
                if (args.length < 3) {
                    return false;
                }
                MultiBlockInstance instance = resolveInstance(sender, args[1]);
                if (instance == null) {
                    sender.sendMessage(ChatColor.RED + "No active multiblock found for the provided target.");
                    return true;
                }
                Integer level = parseLevel(args[2]);
                if (level == null) {
                    sender.sendMessage(ChatColor.RED + "Level must be a positive integer.");
                    return true;
                }

                MultiBlockType type = context.infrastructureEngine().registry().getType(instance.typeId());
                if (type == null || type.level(level) == null) {
                    sender.sendMessage(ChatColor.RED + "Invalid level for type " + instance.typeId());
                    return true;
                }

                boolean updated = context.infrastructureEngine().lifecycleManager().setLevel(instance.id(), level);
                sender.sendMessage(updated
                        ? ChatColor.GREEN + "Updated instance " + instance.id() + " to level " + level
                        : ChatColor.RED + "Failed to update level for " + instance.id());
                return true;
            }
            case "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /mineplus model info <modelKey>");
                    return true;
                }
                describeModel(sender, args[1].toLowerCase(Locale.ROOT));
                return true;
            }
            case "models" -> {
                List<String> modelNames = new ArrayList<>(context.virtualBlockManager().getAvailableModels());
                Collections.sort(modelNames);
                sender.sendMessage(ChatColor.GOLD + "Available loaded models: " + modelNames.size());
                if (modelNames.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "- none");
                } else {
                    sender.sendMessage(ChatColor.YELLOW + String.join(ChatColor.GRAY + ", " + ChatColor.YELLOW, modelNames));
                }
                return true;
            }
            case "debugspawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Only players can spawn debug models.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /mineplus model debugspawn <modelKey>");
                    return true;
                }
                String modelKey = args[1].toLowerCase(Locale.ROOT);
                var model = context.virtualBlockManager().getModel(modelKey);
                if (model == null) {
                    sender.sendMessage(ChatColor.RED + "Unknown model key: " + modelKey);
                    return true;
                }
                var placement = com.mineplus.infrastructure.virtual.VirtualBlockPlacementHelper.getPlacementData(player, 6.0);
                if (placement == null) {
                    sender.sendMessage(ChatColor.RED + "Look at a nearby block face to place the debug model.");
                    return true;
                }
                // Admin debug command: clear the target area so the model always places.
                context.virtualBlockManager().prepareSpawnArea(model, placement, true);
                UUID spawnedId = context.virtualBlockManager().spawnModel(model, placement);
                sender.sendMessage(ChatColor.GREEN + "Spawned debug model '" + modelKey + "' with id " + spawnedId);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(
                    args[0],
                    List.of("list", "inspect", "info", "remove", "respawn", "setlevel", "models", "debugspawn"),
                    completions
            );
            Collections.sort(completions);
            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(
                    args[1],
                    context.virtualBlockManager().getAvailableModels(),
                    completions
            );
            Collections.sort(completions);
            return completions;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("inspect")
                || args[0].equalsIgnoreCase("remove")
                || args[0].equalsIgnoreCase("respawn")
                || args[0].equalsIgnoreCase("setlevel"))) {
            List<String> candidates = new ArrayList<>();
            candidates.add("look");
            for (MultiBlockInstance instance : context.basicInfrastructureApi().getLoadedInstances()) {
                candidates.add(instance.id().toString());
            }
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(args[1], candidates, completions);
            Collections.sort(completions);
            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("debugspawn")) {
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(
                    args[1],
                    context.virtualBlockManager().getAvailableModels(),
                    completions
            );
            Collections.sort(completions);
            return completions;
        }

        return Collections.emptyList();
    }

    private void describeModel(CommandSender sender, String modelKey) {
        var manager = context.virtualBlockManager();
        var model = manager.getModel(modelKey);
        if (model == null) {
            sender.sendMessage(ChatColor.RED + "Unknown model key: " + modelKey);
            return;
        }

        var meta = manager.getModelMeta(modelKey);
        var settings = manager.settings();
        var collisionMode = meta.collisionMode() != null ? meta.collisionMode() : settings.collisionMode();
        var originMode = meta.originMode() != null ? meta.originMode() : settings.originMode();
        if (originMode == null || originMode == com.mineplus.infrastructure.virtual.ModelMeta.OriginMode.AUTO) {
            originMode = com.mineplus.infrastructure.virtual.ModelMeta.OriginMode.forModel(
                    model.modelFormat(), model.cubes());
        }

        sender.sendMessage(ChatColor.GOLD + "Model " + ChatColor.WHITE + modelKey);
        sender.sendMessage(ChatColor.YELLOW + "Cubes: " + ChatColor.WHITE + model.cubes().size()
                + ChatColor.GRAY + " | resolution " + model.resolution().width() + "x" + model.resolution().height()
                + " | format " + (model.modelFormat() == null ? "unknown" : model.modelFormat()));
        sender.sendMessage(ChatColor.YELLOW + "Modes: " + ChatColor.WHITE
                + "collision=" + collisionMode + ", origin=" + originMode);

        int uniformCubes = 0;
        int mixedCubes = 0;
        java.util.Set<String> textureNames = new java.util.TreeSet<>();
        for (var cube : model.cubes()) {
            java.util.Set<String> cubeTextures = new java.util.HashSet<>();
            if (cube.primaryTexture() != null) {
                cubeTextures.add(cube.primaryTexture());
            }
            for (var face : cube.faces().values()) {
                if (face.textureName() != null) {
                    cubeTextures.add(face.textureName());
                }
            }
            textureNames.addAll(cubeTextures);
            if (cubeTextures.size() > 1) {
                mixedCubes++;
            } else {
                uniformCubes++;
            }
        }
        int displayEstimate = uniformCubes + mixedCubes * (settings.perFaceRendering() ? 6 : 1);
        sender.sendMessage(ChatColor.YELLOW + "Display estimate: " + ChatColor.WHITE + displayEstimate
                + ChatColor.GRAY + " (uniform " + uniformCubes + ", mixed " + mixedCubes
                + (settings.perFaceRendering() ? "" : ", plates off") + ")");

        describeTexelBaking(sender, manager, modelKey);

        describeAnimations(sender, model, meta);

        int resolved = 0;
        int fallback = 0;
        for (String textureName : textureNames) {
            boolean hasPng = manager.hasTextureImage(modelKey, textureName);
            var resolution = com.mineplus.infrastructure.virtual.TextureMaterialResolver.resolveDetailed(textureName);
            String material = resolution.material().name();
            if (resolution.isFallback()) {
                fallback++;
                sender.sendMessage(ChatColor.YELLOW + "  " + textureName + " -> "
                        + ChatColor.RED + material + ChatColor.GRAY + " (fallback)"
                        + (hasPng ? ChatColor.AQUA + " [png]" : ChatColor.GRAY + " [no png]"));
            } else {
                resolved++;
                sender.sendMessage(ChatColor.YELLOW + "  " + textureName + " -> "
                        + ChatColor.GREEN + material + ChatColor.GRAY + " [" + resolution.tierName() + "]"
                        + (hasPng ? ChatColor.AQUA + " [png]" : ChatColor.GRAY + " [no png]"));
            }
        }
        sender.sendMessage(ChatColor.YELLOW + "Textures: " + ChatColor.WHITE + resolved + " resolved"
                + ChatColor.GRAY + ", " + fallback + " fallback");

        var cells = manager.occupancyCalculator().compute(
                model, null, null, collisionMode, settings.collisionEpsilon(), originMode);
        sender.sendMessage(ChatColor.YELLOW + "Occupancy cells: " + ChatColor.WHITE + cells.length / 3
                + ChatColor.GRAY + " (identity orientation, " + collisionMode + " mode)"
                + " | cache entries: " + manager.occupancyCalculator().cacheSize());
    }

    private void describeAnimations(
            CommandSender sender,
            com.mineplus.infrastructure.virtual.VirtualModel model,
            com.mineplus.infrastructure.virtual.ModelMeta meta
    ) {
        if (model.bones().isEmpty() && model.animations().isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Animations: " + ChatColor.GRAY + "none");
            return;
        }

        sender.sendMessage(ChatColor.YELLOW + "Bones: " + ChatColor.WHITE + model.bones().size()
                + ChatColor.GRAY + " -> " + model.bones().stream()
                        .map(com.mineplus.infrastructure.virtual.animation.VirtualBone::name)
                        .collect(java.util.stream.Collectors.joining(", ")));

        for (var clip : model.animations()) {
            StringBuilder bones = new StringBuilder();
            for (var entry : clip.animators().entrySet()) {
                if (!bones.isEmpty()) {
                    bones.append(", ");
                }
                bones.append(entry.getKey()).append(" [");
                var tracks = entry.getValue();
                boolean first = true;
                if (!tracks.rotation().isEmpty()) {
                    bones.append("rot:").append(tracks.rotation().size());
                    first = false;
                }
                if (!tracks.position().isEmpty()) {
                    if (!first) {
                        bones.append(' ');
                    }
                    bones.append("pos:").append(tracks.position().size());
                    first = false;
                }
                if (!tracks.scale().isEmpty()) {
                    if (!first) {
                        bones.append(' ');
                    }
                    bones.append("scl:").append(tracks.scale().size());
                }
                bones.append(']');
            }
            sender.sendMessage(ChatColor.YELLOW + "Animation '" + ChatColor.WHITE + clip.name()
                    + ChatColor.YELLOW + "': " + ChatColor.WHITE + clip.loop()
                    + ChatColor.GRAY + " len " + clip.length() + "s"
                    + ChatColor.YELLOW + " animators: " + ChatColor.WHITE + bones);
        }

        if (!meta.autoplay().isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Meta autoplay: " + ChatColor.WHITE
                    + String.join(", ", meta.autoplay()));
        }
    }

    private void describeTexelBaking(
            CommandSender sender,
            com.mineplus.infrastructure.virtual.VirtualBlockManager manager,
            String modelKey
    ) {
        var bake = manager.getTexelBake(modelKey);
        if (bake == null || !bake.enabled()) {
            sender.sendMessage(ChatColor.YELLOW + "Texel baking: " + ChatColor.GRAY + "OFF"
                    + (bake != null ? ChatColor.GRAY + " (mode=" + bake.mode() + ")" : ""));
            return;
        }

        sender.sendMessage(ChatColor.YELLOW + "Texel baking: " + ChatColor.GREEN + "ON"
                + ChatColor.GRAY + " (mode=" + bake.mode() + ", detail=" + bake.detail() + ")");
        sender.sendMessage(ChatColor.YELLOW + "  Faces baked: " + ChatColor.WHITE
                + bake.facesBaked() + "/" + bake.facesTotal()
                + ChatColor.GRAY + " (" + Math.max(0, bake.facesTotal() - bake.facesBaked())
                + " used TILE/CROP_HALF, had no PNG, or fell to budget)");

        if (!bake.gridHistogram().isEmpty()) {
            String grids = bake.gridHistogram().entrySet().stream()
                    .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                    .collect(java.util.stream.Collectors.joining(ChatColor.GRAY + ", " + ChatColor.WHITE));
            sender.sendMessage(ChatColor.YELLOW + "  Texel grids: " + ChatColor.WHITE + grids);
        }

        if (!bake.paletteUsage().isEmpty()) {
            String top = bake.paletteUsage().entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(8)
                    .map(entry -> com.mineplus.infrastructure.virtual.texel.TexelPalette
                            .materialName(entry.getKey()) + " " + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(ChatColor.GRAY + ", " + ChatColor.WHITE));
            sender.sendMessage(ChatColor.YELLOW + "  Palette usage: " + ChatColor.WHITE + "top: " + top);
        }

        sender.sendMessage(ChatColor.YELLOW + "  Merged plates: " + ChatColor.WHITE + bake.totalPlates()
                + ChatColor.GRAY + " (avg " + String.format(java.util.Locale.ROOT, "%.1f", bake.averagePlatesPerFace())
                + "/face, max " + bake.maxPlatesOnFace() + ")");

        var texelSettings = manager.texelSettings();
        boolean budgetOverridden = bake.effectiveMaxPlatesPerFace() != texelSettings.maxPlatesPerFace()
                || bake.effectiveMaxPlatesPerInstance() != texelSettings.maxPlatesPerInstance();
        sender.sendMessage(ChatColor.YELLOW + "  Budget: " + ChatColor.WHITE
                + bake.totalPlates() + "/" + bake.effectiveMaxPlatesPerInstance() + " per instance"
                + ChatColor.GRAY + " (" + bake.effectiveMaxPlatesPerFace() + "/face"
                + (budgetOverridden ? ", meta-overridden" : "") + ")"
                + (bake.budgetFallbackFaces() > 0
                        ? ChatColor.RED + " -> " + bake.budgetFallbackFaces() + " face(s) fell back"
                        + " (" + bake.faceBudgetFallbacks() + " per-face, "
                        + bake.instanceBudgetFallbacks() + " instance)"
                        : ChatColor.GREEN + " -> ok"));

        sender.sendMessage(ChatColor.YELLOW + "  Bake time: " + ChatColor.WHITE
                + String.format(java.util.Locale.ROOT, "%.1f", bake.bakeTimeNanos() / 1_000_000.0) + " ms");
    }

    private void listInstances(CommandSender sender, int limit) {
        List<MultiBlockInstance> instances = new ArrayList<>(context.basicInfrastructureApi().getLoadedInstances());
        sender.sendMessage(ChatColor.GOLD + "Active instances: " + instances.size());

        int shown = 0;
        for (MultiBlockInstance instance : instances) {
            sender.sendMessage(ChatColor.YELLOW + "- " + instance.id()
                    + ChatColor.GRAY + " [" + instance.typeId() + "]"
                    + " @ " + instance.coordinate().worldName() + ":"
                    + instance.coordinate().x() + ","
                    + instance.coordinate().y() + ","
                    + instance.coordinate().z());
            shown++;
            if (shown >= limit) {
                break;
            }
        }

        if (instances.size() > shown) {
            sender.sendMessage(ChatColor.GRAY + "... and " + (instances.size() - shown) + " more.");
        }
    }

    private void describeInstance(CommandSender sender, MultiBlockInstance instance) {
        sender.sendMessage(ChatColor.GOLD + "Instance " + instance.id());
        sender.sendMessage(ChatColor.YELLOW + "Type: " + ChatColor.WHITE + instance.typeId());
        sender.sendMessage(ChatColor.YELLOW + "Level: " + ChatColor.WHITE + instance.level());
        sender.sendMessage(ChatColor.YELLOW + "Rendered model id: "
                + ChatColor.WHITE + (instance.renderedModelId() == null ? "none" : instance.renderedModelId()));
        sender.sendMessage(ChatColor.YELLOW + "Location: " + ChatColor.WHITE
                + instance.coordinate().worldName() + " "
                + instance.coordinate().x() + " "
                + instance.coordinate().y() + " "
                + instance.coordinate().z());
    }

    private MultiBlockInstance resolveInstance(CommandSender sender, String rawTarget) {
        String target = rawTarget == null ? "look" : rawTarget.trim();

        if (target.equalsIgnoreCase("look")) {
            if (!(sender instanceof Player player)) {
                return null;
            }
            Block block = player.getTargetBlockExact(6);
            if (block == null) {
                return null;
            }
            Location location = block.getLocation();
            MultiBlockInstance byOrigin = context.basicInfrastructureApi().getAt(location);
            if (byOrigin != null) {
                return byOrigin;
            }
            UUID renderedModelId = context.virtualBlockManager().getInstanceIdAt(location);
            return context.infrastructureEngine().lifecycleManager().findByRenderedModelId(renderedModelId);
        }

        try {
            UUID id = UUID.fromString(target);
            return context.basicInfrastructureApi().get(id);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Integer parseLevel(String raw) {
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int parsePositiveInt(String raw, int fallback) {
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
