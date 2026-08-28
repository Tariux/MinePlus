package com.mineplus.fun.juicer;

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
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.StringUtil;

public final class JuicerSubCommand implements SubCommand {

    private final PluginContext context;

    public JuicerSubCommand(PluginContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "juicer";
    }

    @Override
    public String description() {
        return "Place, upgrade, remove, and test the Juicer machine.";
    }

    @Override
    public String usage() {
        return "/juicer <place|remove|upgrade|give>";
    }

    @Override
    public String permission() {
        return "mineplusfun.admin.juicer";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use juicer controls.");
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
                    player.sendMessage(ChatColor.RED + "Look at a nearby block face to place the Juicer.");
                    return true;
                }

                MultiBlockInstance created = context.basicInfrastructureApi().createAndPlace(
                        JuicerKeys.MACHINE_ID,
                        placement.location(),
                        player.getUniqueId(),
                        player
                );
                if (created == null) {
                    player.sendMessage(ChatColor.RED + "Failed to place Juicer at target location.");
                    return true;
                }
                player.sendMessage(ChatColor.GREEN + "Juicer placed with id " + created.id());
                return true;
            }
            case "remove" -> {
                MultiBlockInstance looked = findLooked(player);
                if (looked == null) {
                    player.sendMessage(ChatColor.RED + "Look at a Juicer to remove it.");
                    return true;
                }
                boolean removed = context.infrastructureApi().removeBlock(looked.id(), player, true);
                player.sendMessage(removed
                        ? ChatColor.GREEN + "Juicer removed."
                        : ChatColor.RED + "Failed to remove Juicer.");
                return true;
            }
            case "upgrade" -> {
                MultiBlockInstance looked = findLooked(player);
                if (looked == null) {
                    player.sendMessage(ChatColor.RED + "Look at a Juicer to upgrade it.");
                    return true;
                }
                boolean upgraded = context.infrastructureApi().upgradeBlock(looked.id(), player);
                player.sendMessage(upgraded
                        ? ChatColor.GREEN + "Juicer upgraded."
                        : ChatColor.RED + "Upgrade failed. Check materials and level cap.");
                return true;
            }
            case "give" -> {
                String flavor = args.length > 1 ? args[1] : "carrot";
                String itemKey = flavor.equalsIgnoreCase("melon")
                        ? JuicerKeys.MELON_JUICE_ITEM
                        : JuicerKeys.CARROT_JUICE_ITEM;
                ItemStack item = context.itemRegistry().createItem(itemKey);
                if (item == null) {
                    player.sendMessage(ChatColor.RED + "Juice item is not registered.");
                    return true;
                }
                player.getInventory().addItem(item);
                player.sendMessage(ChatColor.GREEN + "Given " + itemKey + ".");
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
            StringUtil.copyPartialMatches(args[0], List.of("place", "remove", "upgrade", "give"), completions);
            Collections.sort(completions);
            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(args[1], List.of("carrot", "melon"), completions);
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
        if (byOrigin != null && byOrigin.typeId().equalsIgnoreCase(JuicerKeys.MACHINE_ID)) {
            return byOrigin;
        }

        UUID renderedModelId = context.virtualBlockManager().getInstanceIdAt(location);
        MultiBlockInstance byRender = context.infrastructureEngine().lifecycleManager().findByRenderedModelId(renderedModelId);
        if (byRender != null && byRender.typeId().equalsIgnoreCase(JuicerKeys.MACHINE_ID)) {
            return byRender;
        }
        return null;
    }
}
