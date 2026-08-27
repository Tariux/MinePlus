package com.mineplus.infrastructure.command;

import com.mineplus.infrastructure.core.util.StringNormalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;

public final class CommandRouter implements CommandExecutor, TabCompleter {

    private final Map<String, SubCommand> commandLookup;
    private final Map<String, SubCommand> mainCommands;

    public CommandRouter() {
        this.commandLookup = new HashMap<>();
        this.mainCommands = new LinkedHashMap<>();
    }

    public void register(SubCommand subCommand) {
        String rootKey = normalize(subCommand.name());
        if (rootKey.isEmpty()) {
            throw new IllegalArgumentException("Subcommand name cannot be empty");
        }

        mainCommands.put(rootKey, subCommand);
        commandLookup.put(rootKey, subCommand);

        for (String alias : subCommand.aliases()) {
            String aliasKey = normalize(alias);
            if (!aliasKey.isEmpty()) {
                commandLookup.put(aliasKey, subCommand);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        SubCommand subCommand = commandLookup.get(normalize(args[0]));
        if (subCommand == null) {
            sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
            sendHelp(sender, label);
            return true;
        }

        String permission = subCommand.permission();
        if (!permission.isBlank() && !sender.hasPermission(permission)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }

        String[] childArgs = Arrays.copyOfRange(args, 1, args.length);
        boolean success = subCommand.execute(sender, label, childArgs);
        if (!success) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: " + subCommand.usage());
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            Collection<SubCommand> available = mainCommands.values();
            List<String> candidates = new ArrayList<>();
            for (SubCommand subCommand : available) {
                String permission = subCommand.permission();
                if (permission.isBlank() || sender.hasPermission(permission)) {
                    candidates.add(subCommand.name());
                }
            }

            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0], candidates, completions);
            Collections.sort(completions);
            return completions;
        }

        if (args.length > 1) {
            SubCommand subCommand = commandLookup.get(normalize(args[0]));
            if (subCommand != null) {
                String permission = subCommand.permission();
                if (permission.isBlank() || sender.hasPermission(permission)) {
                    String[] childArgs = Arrays.copyOfRange(args, 1, args.length);
                    return subCommand.tabComplete(sender, childArgs);
                }
            }
        }

        return Collections.emptyList();
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "Mineplus Admin Commands:");
        for (SubCommand subCommand : mainCommands.values()) {
            String permission = subCommand.permission();
            if (!permission.isBlank() && !sender.hasPermission(permission)) {
                continue;
            }
            sender.sendMessage(ChatColor.YELLOW + subCommand.usage() + ChatColor.GRAY + " - " + subCommand.description());
        }
    }

    private String normalize(String value) {
        return StringNormalizer.normalize(value);
    }
}
