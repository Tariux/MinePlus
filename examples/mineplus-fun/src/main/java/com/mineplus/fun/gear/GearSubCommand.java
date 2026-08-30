package com.mineplus.fun.gear;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.virtual.VirtualBlockPlacementHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

public final class GearSubCommand implements SubCommand {

    private final PluginContext context;
    private final GearFeature feature;

    public GearSubCommand(PluginContext context, GearFeature feature) {
        this.context = context;
        this.feature = feature;
    }

    @Override
    public String name() {
        return "gear";
    }

    @Override
    public String description() {
        return "Place and inspect redstone-driven Gears.";
    }

    @Override
    public String usage() {
        return "/gear <place|remove|status>";
    }

    @Override
    public String permission() {
        return "mineplusfun.admin.gear";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use gear controls.");
            return true;
        }

        if (args.length < 1) {
            return false;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "place" -> {
                var placement = VirtualBlockPlacementHelper.getPlacementData(player, 6.0);
                if (placement == null) {
                    player.sendMessage(ChatColor.RED + "Look at a nearby block face to place the Gear.");
                    return true;
                }

                // The gear is rotationally symmetric (spins about Y), so the
                // snapped placement rotation is used as-is — no compensation.
                MultiBlockInstance created = context.infrastructureApi().createMultiBlock(
                        GearKeys.MACHINE_ID,
                        placement.location(),
                        player.getUniqueId(),
                        player.getUniqueId(),
                        placement.globalRotation()
                );
                if (created == null) {
                    player.sendMessage(ChatColor.RED + "Failed to create Gear at target location.");
                    return true;
                }
                if (!context.infrastructureApi().placeMultiBlock(created.id(), player)) {
                    player.sendMessage(ChatColor.RED + "Failed to place Gear at target location.");
                    return true;
                }
                player.sendMessage(ChatColor.GREEN + "Gear placed with id " + created.id()
                        + ChatColor.GRAY + " — power it with redstone to spin it.");
                return true;
            }
            case "remove" -> {
                MultiBlockInstance looked = context.moduleSupport().resolveLooked(player, 6, GearKeys.MACHINE_ID);
                if (looked == null) {
                    player.sendMessage(ChatColor.RED + "Look at a Gear to remove it.");
                    return true;
                }
                boolean removed = context.infrastructureApi().removeBlock(looked.id(), player, true);
                player.sendMessage(removed
                        ? ChatColor.GREEN + "Gear removed."
                        : ChatColor.RED + "Failed to remove Gear.");
                return true;
            }
            case "status" -> {
                player.sendMessage(ChatColor.GOLD + "Gears:");
                boolean any = false;
                for (MultiBlockInstance instance
                        : context.basicInfrastructureApi().getLoadedInstances()) {
                    if (!GearKeys.MACHINE_ID.equals(instance.typeId())) {
                        continue;
                    }
                    any = true;
                    player.sendMessage(ChatColor.YELLOW + "- " + instance.id()
                            + ChatColor.GRAY + " @" + instance.coordinate().x()
                            + "," + instance.coordinate().y()
                            + "," + instance.coordinate().z()
                            + ChatColor.WHITE + " " + feature.describe(instance));
                }
                if (!any) {
                    player.sendMessage(ChatColor.GRAY + "- none placed");
                }
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
            StringUtil.copyPartialMatches(args[0], List.of("place", "remove", "status"), completions);
            Collections.sort(completions);
            return completions;
        }

        return Collections.emptyList();
    }
}
