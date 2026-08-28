package com.mineplus.infrastructure.command.sub;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.util.StringUtil;

public final class ReloadSubCommand implements SubCommand {

    private final PluginContext context;

    public ReloadSubCommand(PluginContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String description() {
        return "Reload JSON configs and virtual model definitions.";
    }

    @Override
    public String usage() {
        return "/mineplus reload [all|models|multiblocks|recipes]";
    }

    @Override
    public String permission() {
        return "mineplus.admin.reload";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        String scope = args.length == 0 ? "all" : args[0].toLowerCase(Locale.ROOT);

        switch (scope) {
            case "all" -> {
                context.plugin().refreshVirtualRenderingSettings();
                context.jsonInfrastructureApi().reloadAll();
                sender.sendMessage(ChatColor.GREEN + "Reloaded models, multiblocks, and recipes.");
                return true;
            }
            case "models" -> {
                context.plugin().refreshVirtualRenderingSettings();
                context.jsonInfrastructureApi().reloadModelDefinitions();
                sender.sendMessage(ChatColor.GREEN + "Reloaded model definitions and refreshed active renders.");
                return true;
            }
            case "multiblocks" -> {
                context.jsonInfrastructureApi().reloadMultiBlocks();
                sender.sendMessage(ChatColor.GREEN + "Reloaded multiblock type definitions.");
                return true;
            }
            case "recipes" -> {
                context.jsonInfrastructureApi().reloadRecipes();
                sender.sendMessage(ChatColor.GREEN + "Reloaded machine recipes.");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }

        List<String> completions = new ArrayList<>();
        StringUtil.copyPartialMatches(
                args[0],
                List.of("all", "models", "multiblocks", "recipes"),
                completions
        );
        Collections.sort(completions);
        return completions;
    }
}
