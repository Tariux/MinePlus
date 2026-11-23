package com.mineplus.create.towns;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.module.ModuleFeature;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Phase 3 RP layer: towns and the contracts board. Members share contract
 * progress (millstone/press outputs count as proof of work) and their town
 * tier grants a co-op stress bonus to networks they crank or drive
 * ({@link TownRegistry#stressCapacityBonus(int)}).
 *
 * <p>Commands: {@code /town create|join|info|leave} and
 * {@code /contracts [complete <id>]}.</p>
 */
public final class TownsFeature extends ModuleFeature {

    private final TownRegistry towns = new TownRegistry();
    private final ContractBoard contracts = new ContractBoard();

    public TownsFeature(JavaPlugin plugin, PluginContext context) {
        super(plugin, context);
    }

    @Override
    public String id() {
        return "towns";
    }

    @Override
    protected void onEnable() {
        publishDefaults();
        context.moduleSupport().registerCommand(plugin, "town", new com.mineplus.infrastructure.command.SubCommand() {
            @Override
            public String name() {
                return "town";
            }

            @Override
            public String description() {
                return "Found a town, join one, or see your town's progress.";
            }

            @Override
            public String usage() {
                return "/town create <name> | join <name> | info | leave";
            }

            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Towns are for players.");
                    return true;
                }
                handleTown(player, args);
                return true;
            }
        });
        context.moduleSupport().registerCommand(plugin, "contracts", new com.mineplus.infrastructure.command.SubCommand() {
            @Override
            public String name() {
                return "contracts";
            }

            @Override
            public String description() {
                return "The town contracts board - shared work, shared rewards.";
            }

            @Override
            public String usage() {
                return "/contracts [complete <id>]";
            }

            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Contracts are for players.");
                    return true;
                }
                handleContracts(player, args);
                return true;
            }
        });
    }

    private void publishDefaults() {
        contracts.publish("flour_delivery", "Flour Delivery", 16, 2, 1);
        contracts.publish("sheet_order", "Sheet Metal Order", 24, 3, 2);
        contracts.publish("great_press_run", "The Great Press Run", 40, 5, 3);
    }

    private void handleTown(Player player, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("create")) {
            String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            TownRegistry.Town town = towns.create(name, player.getUniqueId());
            player.sendMessage(town == null
                    ? "§cName taken, or you already belong to a town."
                    : "§aFounded §e" + town.name + "§a. Members: §e" + Bukkit.getOfflinePlayer(player.getUniqueId()).getName());
            return;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("join")) {
            player.sendMessage(towns.join(args[1], player.getUniqueId())
                    ? "§aWelcome to §e" + args[1] + "§a."
                    : "§cNo such town, or you are already in one.");
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("leave")) {
            player.sendMessage("§7Leaving towns is a mayor's job for now - ask an admin.");
            return;
        }
        TownRegistry.Town town = towns.townOf(player.getUniqueId());
        if (town == null) {
            player.sendMessage("§7You are not in a town. §e/town create <name> §7or §e/town join <name>");
            return;
        }
        player.sendMessage("§6" + town.name + " §7- tier §e" + town.tier()
                + " §7(§e" + town.completedContracts + "§7 contracts done), members: §e" + town.members.size());
        player.sendMessage(" §7Co-op stress bonus: §e+" + TownRegistry.stressCapacityBonus(town.tier()) + " SU");
        player.sendMessage(" §7Contracts: §e/contracts");
    }

    private void handleContracts(Player player, String[] args) {
        TownRegistry.Town town = towns.townOf(player.getUniqueId());
        if (town == null) {
            player.sendMessage("§cJoin a town first: §e/town join <name>");
            return;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("complete")) {
            ContractBoard.Contract contract = contracts.contract(args[1]);
            if (contract == null) {
                player.sendMessage("§cNo such contract.");
                return;
            }
            if (towns.progressOf(town, contract.id) < contract.requiredUnits) {
                player.sendMessage("§cNot enough shared progress (§e"
                        + towns.progressOf(town, contract.id) + "§c/§e" + contract.requiredUnits + "§c).");
                return;
            }
            towns.completeContract(town, contract.id, contract.requiredUnits);
            for (UUID member : town.members) {
                Player online = Bukkit.getPlayer(member);
                if (online != null) {
                    online.giveExpLevels(contract.rewardLevels);
                    online.sendMessage("§6Contract complete: §e" + contract.title
                            + " §6- §e+" + contract.rewardLevels + " levels §6for everyone.");
                }
            }
            return;
        }
        player.sendMessage("§6Contracts board §7- " + town.name + " (tier " + town.tier() + ")");
        for (ContractBoard.Contract contract : contracts.availableFor(town.tier())) {
            player.sendMessage(" §e" + contract.id + " §8- " + contract.title + " §7"
                    + towns.progressOf(town, contract.id) + "/" + contract.requiredUnits
                    + " §8(reward +" + contract.rewardLevels + " levels) §7/contracts complete " + contract.id);
        }
        for (ContractBoard.Contract contract : contracts.all()) {
            if (town.tier() < contract.minTownTier) {
                player.sendMessage(" §8" + contract.id + " - needs town tier " + contract.minTownTier);
            }
        }
    }

    public void recordProduction(UUID playerId, String itemId, int amount) {
        TownRegistry.Town town = towns.townOf(playerId);
        if (town == null || amount <= 0) {
            return;
        }
        java.util.List<String> contractIds = switch (itemId) {
            case "mc_wheat_flour" -> java.util.List.of("flour_delivery");
            case "mc_iron_sheet", "mc_gold_sheet" -> java.util.List.of("sheet_order", "great_press_run");
            default -> java.util.List.of();
        };
        for (String contractId : contractIds) {
            ContractBoard.Contract contract = contracts.contract(contractId);
            if (contract != null && town.tier() >= contract.minTownTier) {
                towns.addProgress(town, contract.id, amount);
            }
        }
    }

    public TownRegistry towns() {
        return towns;
    }

    public ContractBoard contracts() {
        return contracts;
    }
}
