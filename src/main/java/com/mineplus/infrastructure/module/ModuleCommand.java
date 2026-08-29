package com.mineplus.infrastructure.module;

import com.mineplus.infrastructure.command.SubCommand;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.util.StringUtil;

/**
 * Bukkit {@link Command} adapter around the Core's {@link SubCommand}
 * interface, used by {@link ModuleSupport#registerCommand} for dynamic
 * module command registration. Enforces the subcommand's permission and
 * forwards tab completion.
 */
final class ModuleCommand extends Command {

    private final SubCommand subCommand;

    ModuleCommand(String label, SubCommand subCommand) {
        super(label, subCommand.description(), "/" + subCommand.usage(), new ArrayList<>());
        this.subCommand = subCommand;
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        String permission = subCommand.permission();
        if (permission != null && !permission.isBlank() && !sender.hasPermission(permission)) {
            sender.sendMessage("I'm sorry, but you do not have permission to perform this command. "
                    + "Please contact the server administrators if you believe that this is a mistake.");
            return true;
        }

        boolean handled = subCommand.execute(sender, commandLabel, args);
        if (!handled) {
            sender.sendMessage("Usage: " + subCommand.usage());
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        String permission = subCommand.permission();
        if (permission != null && !permission.isBlank() && !sender.hasPermission(permission)) {
            return List.of();
        }
        List<String> completions = new ArrayList<>(subCommand.tabComplete(sender, args));
        if (args.length > 0) {
            List<String> matches = new ArrayList<>();
            StringUtil.copyPartialMatches(args[args.length - 1], completions, matches);
            return matches;
        }
        return completions;
    }
}
