package com.mineplus.fun.alchemy;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.virtual.VirtualBlockPlacementHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

/**
 * Player controls for the alchemy-table pack block: place via the ordinary
 * multiblock API (pack rendering is selected by the level definition), remove,
 * clear nearby, and inspect.
 */
public final class AlchemySubCommand implements SubCommand {

    private static final int CLEAR_RADIUS = 24;

    private final PluginContext context;

    public AlchemySubCommand(PluginContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "alchemy";
    }

    @Override
    public String description() {
        return "Place and inspect alchemy-table pack blocks.";
    }

    @Override
    public String usage() {
        return "/alchemy <place|remove|clear|status>";
    }

    @Override
    public String permission() {
        return "mineplusfun.admin.alchemy";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use alchemy controls.");
            return true;
        }
        if (args.length < 1) {
            return false;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "place" -> placeSingle(player);
            case "remove" -> removeLooked(player);
            case "clear" -> clearNearby(player);
            case "status" -> printStatus(player);
            default -> false;
        };
    }

    private boolean placeSingle(Player player) {
        var placement = VirtualBlockPlacementHelper.getPlacementData(player, 6.0);
        if (placement == null) {
            player.sendMessage(ChatColor.RED + "Look at a nearby block face to place the alchemy table.");
            return true;
        }
        MultiBlockInstance created = context.infrastructureApi().createMultiBlock(
                AlchemyKeys.MACHINE_ID,
                placement.location(),
                player.getUniqueId(),
                player.getUniqueId(),
                placement.globalRotation()
        );
        if (created == null) {
            player.sendMessage(ChatColor.RED + "Failed to create alchemy table at target location.");
            return true;
        }
        if (!context.infrastructureApi().placeMultiBlock(created.id(), player)) {
            player.sendMessage(ChatColor.RED + "Failed to place alchemy table at target location.");
            return true;
        }
        player.sendMessage(ChatColor.GREEN + "Alchemy Table placed with id " + created.id()
                + ChatColor.GRAY + " — rendered through the pack block axis.");
        return true;
    }

    private boolean removeLooked(Player player) {
        MultiBlockInstance looked = context.moduleSupport().resolveLooked(player, 6, AlchemyKeys.MACHINE_ID);
        if (looked == null) {
            player.sendMessage(ChatColor.RED + "Look at an Alchemy Table to remove it.");
            return true;
        }
        boolean removed = context.infrastructureApi().removeBlock(looked.id(), player, true);
        player.sendMessage(removed
                ? ChatColor.GREEN + "Alchemy table removed."
                : ChatColor.RED + "Failed to remove alchemy table.");
        return true;
    }

    private boolean clearNearby(Player player) {
        Location origin = player.getLocation();
        List<MultiBlockInstance> targets = new ArrayList<>();
        for (MultiBlockInstance instance : List.copyOf(
                context.basicInfrastructureApi().getLoadedInstances())) {
            if (!instance.typeId().equals(AlchemyKeys.MACHINE_ID)) {
                continue;
            }
            double dx = instance.coordinate().x() - origin.getX();
            double dy = instance.coordinate().y() - origin.getY();
            double dz = instance.coordinate().z() - origin.getZ();
            if (dx * dx + dy * dy + dz * dz > (double) CLEAR_RADIUS * CLEAR_RADIUS) {
                continue;
            }
            targets.add(instance);
        }
        if (targets.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "No alchemy tables within " + CLEAR_RADIUS + " blocks.");
            return true;
        }
        int removed = 0;
        for (MultiBlockInstance instance : targets) {
            if (context.infrastructureApi().removeBlock(instance.id(), player, true)) {
                removed++;
            }
        }
        player.sendMessage(ChatColor.GREEN + "Removed " + removed + "/" + targets.size()
                + " alchemy table(s) within " + CLEAR_RADIUS + " blocks.");
        return true;
    }

    private boolean printStatus(Player player) {
        player.sendMessage(ChatColor.GOLD + "Alchemy Tables:"
                + ChatColor.GRAY + " (pack block, model key '" + AlchemyKeys.MODEL_KEY + "')");
        boolean any = false;
        for (MultiBlockInstance instance : context.basicInfrastructureApi().getLoadedInstances()) {
            if (!instance.typeId().equals(AlchemyKeys.MACHINE_ID)) {
                continue;
            }
            any = true;
            player.sendMessage(ChatColor.GRAY + "- " + instance.id()
                    + ChatColor.DARK_GRAY + " @" + instance.coordinate().x()
                    + "," + instance.coordinate().y()
                    + "," + instance.coordinate().z());
        }
        if (!any) {
            player.sendMessage(ChatColor.GRAY + "- none placed");
        }
        player.sendMessage(ChatColor.GRAY + "Pack subsystem: "
                + (context.packApi().isAvailable() ? ChatColor.GREEN + "ACTIVE" : ChatColor.YELLOW + "DISABLED"));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0],
                    List.of("place", "remove", "clear", "status"), completions);
            Collections.sort(completions);
            return completions;
        }
        return Collections.emptyList();
    }
}
