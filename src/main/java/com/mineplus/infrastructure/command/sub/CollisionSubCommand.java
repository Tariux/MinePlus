package com.mineplus.infrastructure.command.sub;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.collision.CollisionService;
import com.mineplus.infrastructure.collision.CollisionSpec;
import com.mineplus.infrastructure.collision.fit.FitResult;
import com.mineplus.infrastructure.collision.source.ModelResolver;
import com.mineplus.infrastructure.command.SubCommand;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

/**
 * {@code /mineplus collision ...} — atlas and fit tooling.
 *
 * <p>Everything here is read-mostly: {@code atlas rebuild} schedules the chunked
 * scan, {@code fit bake} writes the fit cache. No command changes a placed model.</p>
 */
public final class CollisionSubCommand implements SubCommand {

    private static final List<String> ROOTS = List.of("atlas", "fit");

    private final PluginContext context;

    public CollisionSubCommand(PluginContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "collision";
    }

    @Override
    public String description() {
        return "Shape atlas and hybrid fitter tools.";
    }

    @Override
    public String usage() {
        return "/mineplus collision <atlas stats|dump|rebuild | fit debug|report|bake>";
    }

    @Override
    public String permission() {
        return "mineplus.admin.collision";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        CollisionService service = context.collisionService();
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }
        String group = args[0].toLowerCase(Locale.ROOT);
        try {
            if ("atlas".equals(group)) {
                return atlas(sender, service, args);
            }
            if ("fit".equals(group)) {
                return fit(sender, service, args);
            }
        } catch (IllegalArgumentException badInput) {
            sender.sendMessage(ChatColor.RED + badInput.getMessage());
            return true;
        } catch (java.io.IOException diskError) {
            sender.sendMessage(ChatColor.RED + "collision: " + diskError.getMessage());
            return true;
        }
        usage(sender, label);
        return true;
    }

    private boolean atlas(CommandSender sender, CollisionService service, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "stats";
        switch (action) {
            case "stats" -> {
                for (String line : service.atlasStats()) {
                    sender.sendMessage(ChatColor.GRAY + line);
                }
                if (service.atlasBuilding()) {
                    sender.sendMessage(ChatColor.YELLOW + "a build is running in the background");
                }
            }
            case "dump" -> {
                int limit = args.length > 2 ? parseInt(args[2], 50) : 50;
                for (String line : service.atlasDump(limit)) {
                    sender.sendMessage(ChatColor.GRAY + line);
                }
            }
            case "rebuild" -> {
                if (service.atlasBuilder().isEmpty()) {
                    sender.sendMessage(ChatColor.RED
                            + "collision is disabled; enable COLLISION.ENABLED first");
                    return true;
                }
                service.requestAtlasBuild(context.plugin());
                sender.sendMessage(ChatColor.GREEN
                        + "shape atlas rebuild scheduled; watch the console for completion");
            }
            default -> sender.sendMessage(ChatColor.RED
                    + "unknown atlas action: " + action + " (use stats|dump|rebuild)");
        }
        return true;
    }

    private boolean fit(CommandSender sender, CollisionService service, String[] args)
            throws java.io.IOException {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "debug" -> {
                if (args.length < 5) {
                    sender.sendMessage(ChatColor.RED + "usage: /mineplus collision fit debug <x> <y> <z> [profile]");
                    return true;
                }
                int x = parseInt(args[2], 1);
                int y = parseInt(args[3], 1);
                int z = parseInt(args[4], 1);
                String profile = args.length > 5 ? args[5] : null;
                FitResult result = service.fitFill("debug:" + x + "x" + y + "x" + z,
                        x, y, z, profile);
                report(sender, result, service);
                render(sender, result);
            }
            case "report" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "usage: /mineplus collision fit report <model> [profile]");
                    return true;
                }
                String profile = args.length > 3 ? args[3] : null;
                FitResult result = service.fit(specFor(args[2], profile), resolver());
                report(sender, result, service);
            }
            case "bake" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "usage: /mineplus collision fit bake <model> [profile]");
                    return true;
                }
                String profile = args.length > 3 ? args[3] : null;
                CollisionSpec spec = specFor(args[2], profile);
                FitResult result = service.bake(spec, resolver());
                Path directory = context.plugin().getDataFolder().toPath()
                        .resolve(CollisionService.DIRECTORY).resolve("fits");
                Path target = directory.resolve(result.modelKey().replace(':', '_')
                        + "-" + result.profileName().toLowerCase(Locale.ROOT)
                        + "-" + (int) result.yaw() + ".json");
                CollisionService.writeLines(target, CollisionService.describeFit(result));
                sender.sendMessage(ChatColor.GREEN + "baked " + spec.modelKey() + " -> "
                        + target);
                report(sender, result, service);
            }
            default -> sender.sendMessage(ChatColor.RED
                    + "unknown fit action: " + action + " (use debug|report|bake)");
        }
        return true;
    }

    /**
     * Draws the plan in the world for one player: one dust cloud per cell,
     * coloured by the backend that will realise it, anchored at the block the
     * player is looking at (or their feet). Console senders get a line instead.
     */
    private void render(CommandSender sender, FitResult result) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage(ChatColor.GRAY
                    + "debug rendering needs a player; the plan was printed above");
            return;
        }
        org.bukkit.block.Block target = player.getTargetBlockExact(8);
        org.bukkit.Location origin = target == null ? player.getLocation() : target.getLocation();
        org.bukkit.World world = player.getWorld();
        org.bukkit.Particle dust = com.mineplus.infrastructure.fx.ParticleFx.particle("DUST",
                com.mineplus.infrastructure.fx.ParticleFx.particle("REDSTONE",
                        org.bukkit.Particle.SMOKE));
        int drawn = 0;
        for (com.mineplus.infrastructure.collision.fit.CellChoice cell : result.cells()) {
            org.bukkit.Location at = origin.clone().add(cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5);
            org.bukkit.Color color = switch (cell.backend()) {
                case REAL_BLOCK -> org.bukkit.Color.fromRGB(80, 220, 80);
                case GHOST_BLOCK -> org.bukkit.Color.fromRGB(80, 160, 255);
                case SHULKER -> org.bukkit.Color.fromRGB(255, 200, 60);
                case INTERACTION_ONLY -> org.bukkit.Color.fromRGB(255, 120, 220);
                case PASSABLE_OUTLINE -> org.bukkit.Color.fromRGB(170, 170, 170);
                case PLATE -> org.bukkit.Color.fromRGB(60, 220, 220);
                case NONE -> org.bukkit.Color.fromRGB(90, 40, 40);
            };
            world.spawnParticle(dust, at, 8, 0.35, 0.35, 0.35, 0,
                    new org.bukkit.Particle.DustOptions(color, 1.2f));
            drawn++;
        }
        player.sendMessage(ChatColor.GREEN + "drew " + drawn
                + " cells: green real block, blue ghost, yellow shulker, pink interaction,"
                + " grey outline, cyan plate, dark red none");
    }

    /** Builds a spec from the model file plus its meta {@code collision} block. */
    private CollisionSpec specFor(String modelKey, String profileOverride) throws java.io.IOException {
        File file = context.virtualBlockManager().getModelSourceFile(modelKey);
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("no installed model named '" + modelKey
                    + "' (see /mineplus models)");
        }
        String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return CollisionService.fromModel(modelKey, context.virtualBlockManager().getModelMeta(modelKey),
                json, profileOverride);
    }

    private ModelResolver resolver() {
        return name -> context.virtualBlockManager().getModelSourceFile(name) == null ? null
                : readQuietly(context.virtualBlockManager().getModelSourceFile(name));
    }

    private static String readQuietly(File file) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (java.io.IOException broken) {
            return null;
        }
    }

    private void report(CommandSender sender, FitResult result, CollisionService service) {
        for (String line : CollisionService.describeFit(result)) {
            sender.sendMessage(ChatColor.GRAY + line);
        }
        if (!service.atlas().isEmpty() && result.cells().isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW
                    + "no parts fit; try a coarser resolution or a larger profile budget");
        }
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + usage().replace("/mineplus", "/" + label));
        sender.sendMessage(ChatColor.GRAY + "  atlas stats|dump [limit]|rebuild");
        sender.sendMessage(ChatColor.GRAY + "  fit debug <x> <y> <z> [profile]");
        sender.sendMessage(ChatColor.GRAY + "  fit report <model> [profile]");
        sender.sendMessage(ChatColor.GRAY + "  fit bake <model> [profile]");
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("not a number: " + raw);
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length <= 1) {
            return prefix(args, ROOTS);
        }
        String group = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return prefix(args, "atlas".equals(group)
                    ? List.of("stats", "dump", "rebuild")
                    : List.of("debug", "report", "bake"));
        }
        if ("fit".equals(group) && args.length == 3 && !"debug".equalsIgnoreCase(args[1])) {
            out.addAll(context.virtualBlockManager().getAvailableModels());
            return prefix(args, out);
        }
        if ("fit".equals(group) && args.length == (("debug".equalsIgnoreCase(args[1])) ? 6 : 4)) {
            return prefix(args, List.of("DEFAULT", "FURNITURE", "WALKABLE", "THIN"));
        }
        return List.of();
    }

    private static List<String> prefix(String[] args, List<String> options) {
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(typed)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
