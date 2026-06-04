package com.mineplus.infrastructure.command.sub;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

public final class StatusSubCommand implements SubCommand {

    private final PluginContext context;

    public StatusSubCommand(PluginContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String description() {
        return "Show runtime state for Mineplus core systems.";
    }

    @Override
    public String usage() {
        return "/mineplus status";
    }

    @Override
    public String permission() {
        return "mineplus.admin.status";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        sender.sendMessage(ChatColor.GOLD + "Mineplus Runtime Status");
        sender.sendMessage(ChatColor.YELLOW + "Loaded models: "
                + ChatColor.WHITE + context.infrastructureEngine().loadedModelKeys().size());
        sender.sendMessage(ChatColor.YELLOW + "Registered multiblock types: "
                + ChatColor.WHITE + context.basicInfrastructureApi().getTypeIds().size());
        sender.sendMessage(ChatColor.YELLOW + "Active multiblock instances: "
                + ChatColor.WHITE + context.basicInfrastructureApi().getLoadedInstances().size());
        return true;
    }
}
