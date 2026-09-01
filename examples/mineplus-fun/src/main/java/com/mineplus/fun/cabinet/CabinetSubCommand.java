package com.mineplus.fun.cabinet;

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

public final class CabinetSubCommand implements SubCommand {

    private static final int CLEAR_RADIUS = 24;

    private final PluginContext context;

    public CabinetSubCommand(PluginContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "cabinet";
    }

    @Override
    public String description() {
        return "Place and inspect acacia cabinets.";
    }

    @Override
    public String usage() {
        return "/cabinet <place|remove|clear|status>";
    }

    @Override
    public String permission() {
        return "mineplusfun.admin.cabinet";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use cabinet controls.");
            return true;
        }

        if (args.length < 1) {
            return false;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "place" -> {
                return placeSingle(player);
            }
            case "remove" -> {
                return removeLooked(player);
            }
            case "clear" -> {
                return clearNearby(player);
            }
            case "status" -> {
                return printStatus(player);
            }
            default -> {
                return false;
            }
        }
    }

    private boolean placeSingle(Player player) {
        var placement = VirtualBlockPlacementHelper.getPlacementData(player, 6.0);
        if (placement == null) {
            player.sendMessage(ChatColor.RED + "Look at a nearby block face to place the cabinet.");
            return true;
        }

        MultiBlockInstance created = context.infrastructureApi().createMultiBlock(
                CabinetKeys.MACHINE_ID,
                placement.location(),
                player.getUniqueId(),
                player.getUniqueId(),
                placement.globalRotation()
        );
        if (created == null) {
            player.sendMessage(ChatColor.RED + "Failed to create cabinet at target location.");
            return true;
        }
        if (!context.infrastructureApi().placeMultiBlock(created.id(), player)) {
            player.sendMessage(ChatColor.RED + "Failed to place cabinet at target location.");
            return true;
        }
        player.sendMessage(ChatColor.GREEN + "Acacia Cabinet placed with id " + created.id()
                + ChatColor.GRAY + " — right-click to open its storage.");
        return true;
    }

    private boolean removeLooked(Player player) {
        MultiBlockInstance looked = context.moduleSupport().resolveLooked(player, 6, CabinetKeys.MACHINE_ID);
        if (looked == null) {
            player.sendMessage(ChatColor.RED + "Look at a Cabinet to remove it.");
            return true;
        }
        boolean removed = context.infrastructureApi().removeBlock(looked.id(), player, true);
        player.sendMessage(removed
                ? ChatColor.GREEN + "Cabinet removed."
                : ChatColor.RED + "Failed to remove cabinet.");
        return true;
    }

    private boolean clearNearby(Player player) {
        Location origin = player.getLocation();
        List<MultiBlockInstance> targets = new ArrayList<>();
        for (MultiBlockInstance instance : List.copyOf(
                context.basicInfrastructureApi().getLoadedInstances())) {
            if (!instance.typeId().equals(CabinetKeys.MACHINE_ID)) {
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
            player.sendMessage(ChatColor.GRAY + "No cabinets within " + CLEAR_RADIUS + " blocks.");
            return true;
        }
        int removed = 0;
        for (MultiBlockInstance instance : targets) {
            if (context.infrastructureApi().removeBlock(instance.id(), player, true)) {
                removed++;
            }
        }
        player.sendMessage(ChatColor.GREEN + "Removed " + removed + "/" + targets.size()
                + " cabinet(s) within " + CLEAR_RADIUS + " blocks.");
        return true;
    }

    private boolean printStatus(Player player) {
        player.sendMessage(ChatColor.GOLD + "Cabinets:");
        boolean any = false;
        for (MultiBlockInstance instance : context.basicInfrastructureApi().getLoadedInstances()) {
            if (!instance.typeId().equals(CabinetKeys.MACHINE_ID)) {
                continue;
            }
            any = true;
            String state = instance.level() >= CabinetKeys.LEVEL_OPEN
                    ? ChatColor.YELLOW + "open" : ChatColor.GREEN + "closed";
            player.sendMessage(ChatColor.GRAY + "- " + instance.id()
                    + ChatColor.DARK_GRAY + " @" + instance.coordinate().x()
                    + "," + instance.coordinate().y()
                    + "," + instance.coordinate().z()
                    + ChatColor.GRAY + " " + state
                    + " " + CabinetStore.countItems(instance) + " slot(s) used");
        }
        if (!any) {
            player.sendMessage(ChatColor.GRAY + "- none placed");
        }
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
