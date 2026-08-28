package com.mineplus.fun.cannon;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.virtual.VirtualBlockPlacementHelper;
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
import org.joml.Quaternionf;

public final class CannonSubCommand implements SubCommand {

    private final PluginContext context;

    public CannonSubCommand(PluginContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "cannon";
    }

    @Override
    public String description() {
        return "Place, upgrade, and remove the Cannon.";
    }

    @Override
    public String usage() {
        return "/cannon <place|remove|upgrade>";
    }

    @Override
    public String permission() {
        return "mineplusfun.admin.cannon";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use cannon controls.");
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
                    player.sendMessage(ChatColor.RED + "Look at a nearby block face to place the Cannon.");
                    return true;
                }

                // Aim the muzzle (model -X) away from the player: the placement helper
                // orients a model's front toward the player, and the cannon's muzzle sits
                // a quarter turn off that front, so pre-rotate by -90 degrees.
                Quaternionf rotation = new Quaternionf(placement.globalRotation())
                        .rotateY((float) (-Math.PI / 2.0));

                MultiBlockInstance created = context.infrastructureApi().createMultiBlock(
                        CannonKeys.MACHINE_ID,
                        placement.location(),
                        player.getUniqueId(),
                        player.getUniqueId(),
                        rotation
                );
                if (created == null) {
                    player.sendMessage(ChatColor.RED + "Failed to create Cannon at target location.");
                    return true;
                }
                if (!context.infrastructureApi().placeMultiBlock(created.id(), player)) {
                    player.sendMessage(ChatColor.RED + "Failed to place Cannon at target location.");
                    return true;
                }
                player.sendMessage(ChatColor.GREEN + "Cannon placed with id " + created.id());
                return true;
            }
            case "remove" -> {
                MultiBlockInstance looked = findLooked(player);
                if (looked == null) {
                    player.sendMessage(ChatColor.RED + "Look at a Cannon to remove it.");
                    return true;
                }
                boolean removed = context.infrastructureApi().removeBlock(looked.id(), player, true);
                player.sendMessage(removed
                        ? ChatColor.GREEN + "Cannon removed."
                        : ChatColor.RED + "Failed to remove Cannon.");
                return true;
            }
            case "upgrade" -> {
                MultiBlockInstance looked = findLooked(player);
                if (looked == null) {
                    player.sendMessage(ChatColor.RED + "Look at a Cannon to upgrade it.");
                    return true;
                }
                boolean upgraded = context.infrastructureApi().upgradeBlock(looked.id(), player);
                player.sendMessage(upgraded
                        ? ChatColor.GREEN + "Cannon upgraded."
                        : ChatColor.RED + "Upgrade failed. Check materials and level cap.");
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
            StringUtil.copyPartialMatches(args[0], List.of("place", "remove", "upgrade"), completions);
            Collections.sort(completions);
            return completions;
        }

        return Collections.emptyList();
    }

    private MultiBlockInstance findLooked(Player player) {
        Block block = player.getTargetBlockExact(6);
        if (block == null) {
            return null;
        }

        Location location = block.getLocation();
        MultiBlockInstance byOrigin = context.basicInfrastructureApi().getAt(location);
        if (byOrigin != null && byOrigin.typeId().equalsIgnoreCase(CannonKeys.MACHINE_ID)) {
            return byOrigin;
        }

        UUID renderedModelId = context.virtualBlockManager().getInstanceIdAt(location);
        MultiBlockInstance byRender = context.infrastructureEngine().lifecycleManager().findByRenderedModelId(renderedModelId);
        if (byRender != null && byRender.typeId().equalsIgnoreCase(CannonKeys.MACHINE_ID)) {
            return byRender;
        }
        return null;
    }
}
