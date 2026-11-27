package com.mineplus.infrastructure.command.sub;

import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.infrastructure.core.block.BlockDefinition;
import com.mineplus.infrastructure.core.block.BlockDefinitionLoader;
import com.mineplus.infrastructure.core.block.BlockLoadReport;
import com.mineplus.infrastructure.core.block.BlockPlacementService;
import com.mineplus.infrastructure.core.block.BlockRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Inspects, validates and hands out custom block definitions. */
public final class BlockSubCommand implements SubCommand {

    private final MineplusPlugin plugin;
    private final BlockRegistry registry;
    private final BlockDefinitionLoader loader;
    private final BlockPlacementService placement;

    public BlockSubCommand(
            MineplusPlugin plugin,
            BlockRegistry registry,
            BlockDefinitionLoader loader,
            BlockPlacementService placement
    ) {
        this.plugin = plugin;
        this.registry = registry;
        this.loader = loader;
        this.placement = placement;
    }

    @Override
    public String name() {
        return "blocks";
    }

    @Override
    public String description() {
        return "Validate or inspect custom block definitions.";
    }

    @Override
    public String usage() {
        return "/mineplus blocks <validate|list|info|give|place|remove>";
    }

    @Override
    public String permission() {
        return "mineplus.admin.blocks";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        String action = args.length == 0 ? "validate" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "validate", "reload" -> validate(sender);
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "give" -> give(sender, args);
            case "place" -> place(sender, args);
            case "remove" -> remove(sender);
            default -> sender.sendMessage(ChatColor.RED + usage());
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only a player can receive a block item.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + usage());
            return;
        }
        BlockDefinition definition = registry.get(args[1]);
        if (definition == null) {
            sender.sendMessage(ChatColor.RED + "No block definition with id '" + args[1] + "'.");
            return;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException badAmount) {
                sender.sendMessage(ChatColor.RED + "Amount must be a whole number.");
                return;
            }
        }
        player.getInventory().addItem(placement.createItem(definition, amount));
        player.sendMessage(ChatColor.GREEN + "Gave " + amount + "x " + definition.displayName());
    }

    private void place(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only a player can place a block.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + usage());
            return;
        }
        BlockDefinition definition = registry.get(args[1]);
        if (definition == null) {
            sender.sendMessage(ChatColor.RED + "No block definition with id '" + args[1] + "'.");
            return;
        }
        BlockPlacementService.Outcome outcome = placement.place(player, definition);
        player.sendMessage((outcome.placed() ? ChatColor.GREEN : ChatColor.RED) + outcome.message());
    }

    private void remove(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only a player can remove a block.");
            return;
        }
        boolean removed = placement.removeLooking(player);
        player.sendMessage((removed ? ChatColor.GREEN : ChatColor.RED)
                + (removed ? "Block removed." : "No placed block within six blocks."));
    }

    private void validate(CommandSender sender) {
        BlockLoadReport report = loader.reload(plugin.getDataFolder());
        sender.sendMessage(ChatColor.GOLD + "Mineplus block definitions: "
                + ChatColor.WHITE + report.summary());
        int shown = 0;
        for (BlockLoadReport.Problem problem : report.problems()) {
            if (shown++ >= 25) {
                sender.sendMessage(ChatColor.GRAY + "... "
                        + (report.problems().size() - 25) + " more problem(s) in the log");
                break;
            }
            ChatColor colour = problem.severity() == BlockLoadReport.Severity.ERROR
                    ? ChatColor.RED : ChatColor.YELLOW;
            sender.sendMessage(colour + problem.severity().name().toLowerCase(Locale.ROOT)
                    + ": " + ChatColor.WHITE + problem.message());
        }
    }

    private void list(CommandSender sender) {
        if (registry.size() == 0) {
            sender.sendMessage(ChatColor.GRAY + "No block definitions loaded. Run "
                    + ChatColor.WHITE + "/mineplus blocks validate" + ChatColor.GRAY + ".");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Block definitions (" + registry.size() + ")");
        for (BlockDefinition definition : registry.all()) {
            sender.sendMessage(ChatColor.YELLOW + definition.id() + ChatColor.GRAY
                    + " -> " + definition.model()
                    + " " + definition.width() + "x" + definition.height() + "x" + definition.depth()
                    + ", collision " + definition.collision().name().toLowerCase(Locale.ROOT)
                    + ", " + definition.renderMode().key());
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + usage());
            return;
        }
        BlockDefinition definition = registry.get(args[1]);
        if (definition == null) {
            sender.sendMessage(ChatColor.RED + "No block definition with id '" + args[1] + "'.");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + definition.id()
                + ChatColor.WHITE + "  " + definition.displayName());
        sender.sendMessage(ChatColor.YELLOW + "Model: " + ChatColor.WHITE + definition.model()
                + ChatColor.GRAY + " (" + definition.renderMode().key() + ")");
        sender.sendMessage(ChatColor.YELLOW + "Footprint: " + ChatColor.WHITE
                + definition.width() + "x" + definition.height() + "x" + definition.depth()
                + ChatColor.GRAY + "  snap " + definition.rotationSnap() + "°");
        sender.sendMessage(ChatColor.YELLOW + "Collision: " + ChatColor.WHITE
                + definition.collision().name().toLowerCase(Locale.ROOT)
                + ChatColor.GRAY + " (" + definition.collisionMaterial() + ")");
        sender.sendMessage(ChatColor.YELLOW + "Breaking: " + ChatColor.WHITE
                + definition.breaking().hardness() + "s bare hand"
                + ChatColor.GRAY + ", tool " + definition.breaking().tool().tier()
                + " " + definition.breaking().tool().type()
                + ", " + definition.breaking().drops().size() + " drop(s)");
        if (!definition.autoplay().isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Autoplay: " + ChatColor.WHITE
                    + String.join(", ", definition.autoplay()));
        }
        if (definition.light() == 0 && definition.namespace().equals("mineplus")) {
            sender.sendMessage(ChatColor.GRAY + "Note-block carrier: no emitted light, "
                    + "bounded by the carrier slot pool.");
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("validate", "list", "info").stream()
                    .filter(option -> option.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args[0].equalsIgnoreCase("info")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> ids = new ArrayList<>();
            for (BlockDefinition definition : registry.all()) {
                if (definition.id().startsWith(prefix)) {
                    ids.add(definition.id());
                }
            }
            return ids;
        }
        return List.of();
    }
}
