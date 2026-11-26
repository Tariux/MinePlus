package com.mineplus.machine;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.virtual.PlacementOptions;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * /machine — test bench for the rotation showcase: place each machine type
 * in front of you, inspect the solved network, remove what you placed.
 */
public final class MachineSubCommand implements SubCommand {

    private static final PlacementOptions PLACEMENT = PlacementOptions.DEFAULT
            .withYawSnap(45.0f)
            .withAirTargetCheck(true);

    private static final String[] TYPE_IDS = {
            "water_wheel", "water_wheel_weak", "water_wheel_strong",
            "rotation_shaft", "cogwheel", "cogwheel_vertical", "cogwheel_horizontal",
            "rotation_brake"};

    private final PluginContext context;
    private final MineplusMachinePlugin feature;

    public MachineSubCommand(PluginContext context, MineplusMachinePlugin feature) {
        this.context = context;
        this.feature = feature;
    }

    @Override
    public String name() {
        return "machine";
    }

    @Override
    public String description() {
        return "Place and inspect kinetic machines (water wheels, shafts, cogwheels, brake).";
    }

    @Override
    public String usage() {
        return "/machine <place <type>|status|remove>";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "place" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "Types: " + String.join(", ", TYPE_IDS));
                    return true;
                }
                return place(player, args[1].toLowerCase(Locale.ROOT));
            }
            case "status" -> {
                return status(player);
            }
            case "remove" -> {
                return removeLooked(player);
            }
            default -> {
                player.sendMessage(ChatColor.RED + "Usage: " + usage());
                return true;
            }
        }
    }

    private boolean place(Player player, String typeId) {
        boolean known = false;
        for (String candidate : TYPE_IDS) {
            if (candidate.equals(typeId)) {
                known = true;
                break;
            }
        }
        if (!known) {
            player.sendMessage(ChatColor.RED + "Unknown type '" + typeId
                    + "'. Types: " + String.join(", ", TYPE_IDS));
            return true;
        }
        var placement = context.dev().placementFor(player, 6.0, PLACEMENT);
        if (placement == null) {
            player.sendMessage(ChatColor.RED + "Look at a nearby block face to place the machine.");
            return true;
        }
        Location at = placement.location();
        var created = context.infrastructureApi().createMultiBlock(
                typeId, at, player.getUniqueId(), player.getUniqueId(), placement.globalRotation());
        if (created == null) {
            player.sendMessage(ChatColor.RED + "Failed to create " + typeId + ".");
            return true;
        }
        if (!context.infrastructureApi().placeMultiBlock(created.id(), player)) {
            player.sendMessage(ChatColor.RED + "Failed to place " + typeId + ".");
            return true;
        }
        player.sendMessage(ChatColor.GREEN + typeId + " placed (" + created.id() + ")"
                + ChatColor.GRAY + " — networks join by touching blocks; use /machine status while looking at it.");
        return true;
    }

    private boolean status(Player player) {
        MultiBlockInstance looked = context.moduleSupport().resolveLooked(player, 6, null);
        if (looked == null) {
            player.sendMessage(ChatColor.RED + "Look at a machine to inspect it.");
            return true;
        }
        player.sendMessage(ChatColor.AQUA + looked.typeId() + " " + ChatColor.GRAY
                + feature.describe(looked));
        return true;
    }

    private boolean removeLooked(Player player) {
        MultiBlockInstance looked = context.moduleSupport().resolveLooked(player, 6, null);
        if (looked == null) {
            player.sendMessage(ChatColor.RED + "Look at a machine to remove it.");
            return true;
        }
        boolean removed = context.infrastructureApi().removeBlock(looked.id(), player, true);
        player.sendMessage(removed ? ChatColor.GREEN + "Machine removed."
                : ChatColor.RED + "Failed to remove machine.");
        return true;
    }
}
