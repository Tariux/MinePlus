package com.mineplus.fun.wine;

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

/**
 * {@code /wine <place|remove|status>} — place and inspect texel-baked wine bottles.
 *
 * <p>Placement is rotation-aware through the Core's snapped placement rotation,
 * exactly like the Gear and Cannon features. Verify the bake with
 * {@code /mineplus model info strad-wine}: it reports the texel grid histogram,
 * palette usage, merged plate count, and the per-model budget verdict.
 */
public final class WineSubCommand implements SubCommand {

    private final PluginContext context;

    public WineSubCommand(PluginContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "wine";
    }

    @Override
    public String description() {
        return "Place and inspect texel-baked Strad Wine Bottles.";
    }

    @Override
    public String usage() {
        return "/wine <place|remove|status>";
    }

    @Override
    public String permission() {
        return "mineplusfun.admin.wine";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use wine controls.");
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
                    player.sendMessage(ChatColor.RED + "Look at a nearby block face to place the Wine Bottle.");
                    return true;
                }

                MultiBlockInstance created = context.infrastructureApi().createMultiBlock(
                        WineKeys.MACHINE_ID,
                        placement.location(),
                        player.getUniqueId(),
                        player.getUniqueId(),
                        placement.globalRotation()
                );
                if (created == null) {
                    player.sendMessage(ChatColor.RED + "Failed to create Wine Bottle at target location.");
                    return true;
                }
                if (!context.infrastructureApi().placeMultiBlock(created.id(), player)) {
                    player.sendMessage(ChatColor.RED + "Failed to place Wine Bottle at target location.");
                    return true;
                }
                player.sendMessage(ChatColor.GREEN + "Wine Bottle placed with id " + created.id()
                        + ChatColor.GRAY + " — pixel art reconstructed from vanilla palette blocks."
                        + " Check /mineplus model info strad-wine for the bake report.");
                return true;
            }
            case "remove" -> {
                MultiBlockInstance looked = context.moduleSupport().resolveLooked(player, 6, WineKeys.MACHINE_ID);
                if (looked == null) {
                    player.sendMessage(ChatColor.RED + "Look at a Wine Bottle to remove it.");
                    return true;
                }
                boolean removed = context.infrastructureApi().removeBlock(looked.id(), player, true);
                player.sendMessage(removed
                        ? ChatColor.GREEN + "Wine Bottle removed."
                        : ChatColor.RED + "Failed to remove Wine Bottle.");
                return true;
            }
            case "status" -> {
                player.sendMessage(ChatColor.GOLD + "Wine Bottles:");
                boolean any = false;
                for (MultiBlockInstance instance
                        : context.basicInfrastructureApi().getLoadedInstances()) {
                    if (!WineKeys.MACHINE_ID.equals(instance.typeId())) {
                        continue;
                    }
                    any = true;
                    player.sendMessage(ChatColor.YELLOW + "- " + instance.id()
                            + ChatColor.GRAY + " @" + instance.coordinate().x()
                            + "," + instance.coordinate().y()
                            + "," + instance.coordinate().z());
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
