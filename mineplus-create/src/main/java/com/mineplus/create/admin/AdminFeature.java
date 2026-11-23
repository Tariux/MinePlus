package com.mineplus.create.admin;

import com.mineplus.create.CreatePlugin;
import com.mineplus.create.machines.MachinesFeature;
import com.mineplus.create.rotational.RotationalNetworkManager;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.module.ModuleFeature;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * In-game checking helper: {@code /mcadmin place <type>} stages a machine
 * multiblock instance at the player's feet without building the physical
 * structure, and {@code /mcadmin status} dumps the rotational network state.
 * Admin permission only ({@code minepluscreate.admin}).
 */
public final class AdminFeature extends ModuleFeature {

    public AdminFeature(CreatePlugin plugin, PluginContext context) {
        super(plugin, context);
    }

    @Override
    public String id() {
        return "admin";
    }

    @Override
    protected void onEnable() {
        context.moduleSupport().registerCommand(plugin, "mcadmin", new SubCommand() {
            @Override
            public String name() {
                return "mcadmin";
            }

            @Override
            public String description() {
                return "Create module admin: stage machines and inspect networks.";
            }

            @Override
            public String usage() {
                return "/mcadmin place <mc_hand_crank|mc_water_wheel|mc_windmill|mc_millstone|mc_mechanical_press|mc_steam_engine|mc_dynamo> | status";
            }

            @Override
            public String permission() {
                return "minepluscreate.admin";
            }

            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("In-game only.");
                    return true;
                }
                if (args.length >= 1 && args[0].equalsIgnoreCase("status")) {
                    status(player);
                    return true;
                }
                if (args.length >= 2 && args[0].equalsIgnoreCase("place")) {
                    place(player, args[1].toLowerCase());
                    return true;
                }
                player.sendMessage(usage());
                return true;
            }
        });
    }

    private void place(Player player, String typeId) {
        MultiBlockInstance instance = context.infrastructureEngine().lifecycleManager()
                .create(typeId, player.getLocation(), player.getUniqueId(), player.getUniqueId(),
                        new org.joml.Quaternionf());
        if (instance == null) {
            player.sendMessage("§cCould not create §e" + typeId + "§c (unknown type or bad config).");
            return;
        }
        var lifecycle = context.infrastructureEngine().lifecycleManager();
        if (!lifecycle.place(instance.id(), player)) {
            lifecycle.remove(instance.id(), player, false);
            player.sendMessage("§cCould not place §e" + typeId + "§c. Check the server log.");
            return;
        }
        player.sendMessage("§aStaged §e" + typeId + " §aat your feet (instance §e" + instance.id() + "§a).");
    }

    private void status(Player player) {
        RotationalNetworkManager manager = ((CreatePlugin) plugin).rotational().networks();
        player.sendMessage("§6Rotational networks §7- " + manager.size() + " nodes");
        for (RotationalNetworkManager.Node node : manager.all()) {
            player.sendMessage(" §e" + node.key + " §7" + node.type
                    + " rpm=" + String.format("%.1f", node.actualRpm)
                    + " stress=" + String.format("%.1f", node.stress)
                    + " net=" + node.networkId + " sign=" + node.sign);
        }
    }
}
