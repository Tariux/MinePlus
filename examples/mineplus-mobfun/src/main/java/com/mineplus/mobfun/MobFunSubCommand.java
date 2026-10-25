package com.mineplus.mobfun;

import com.mineplus.infrastructure.command.SubCommand;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

/**
 * {@code /mobfun} — status and toggles for the three behavior showcases.
 *
 * <pre>
 * /mobfun status                          - engine settings + telemetry + showcase states
 * /mobfun &lt;grazer|reforest|lamplighter&gt; [on|off|toggle] - enable/disable one showcase
 * </pre>
 */
public final class MobFunSubCommand implements SubCommand {

    private final MobFunPlugin plugin;
    private final List<MobFunShowcase> showcases;

    public MobFunSubCommand(MobFunPlugin plugin, List<MobFunShowcase> showcases) {
        this.plugin = plugin;
        this.showcases = showcases;
    }

    @Override
    public String name() {
        return "mobfun";
    }

    @Override
    public String description() {
        return "MobFun behavior showcases (grazing cows, reforesting pigs, lamplighter villagers)";
    }

    @Override
    public String usage() {
        return "/mobfun status | /mobfun <grazer|reforest|lamplighter> [on|off|toggle]";
    }

    @Override
    public String permission() {
        return "mineplusmobfun.admin";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("status"))) {
            sendStatus(sender);
            return true;
        }

        MobFunShowcase showcase = findShowcase(args[0]);
        if (showcase == null) {
            sender.sendMessage(ChatColor.RED + "Unknown showcase '" + args[0]
                    + "'. Use grazer, reforest, or lamplighter.");
            return true;
        }

        String action = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "toggle";
        boolean target;
        switch (action) {
            case "on" -> target = true;
            case "off" -> target = false;
            case "toggle" -> target = !showcase.isEnabled();
            default -> {
                sender.sendMessage(ChatColor.RED + "Use on, off, or toggle.");
                return true;
            }
        }

        if (target) {
            showcase.enable();
            sender.sendMessage(ChatColor.GREEN + showcase.label() + " enabled.");
        } else {
            showcase.disable();
            sender.sendMessage(ChatColor.YELLOW + showcase.label() + " disabled.");
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_GREEN + "MobFun showcases");
        for (MobFunShowcase showcase : showcases) {
            sender.sendMessage(ChatColor.GREEN + showcase.statusLine());
        }
        if (plugin.context() != null) {
            sender.sendMessage(ChatColor.DARK_GREEN + "Core mob behavior settings: "
                    + ChatColor.WHITE + plugin.context().mobBehaviorApi().settings());
            sender.sendMessage(ChatColor.DARK_GREEN + "Engine telemetry:\n"
                    + ChatColor.WHITE + plugin.context().mobBehaviorApi().telemetrySummary());
        }
    }

    private MobFunShowcase findShowcase(String token) {
        for (MobFunShowcase showcase : showcases) {
            if (showcase.id().equalsIgnoreCase(token)) {
                return showcase;
            }
        }
        return null;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            completions.add("status");
            for (MobFunShowcase showcase : showcases) {
                completions.add(showcase.id());
            }
            return filter(completions, args[0]);
        }
        if (args.length == 2 && !"status".equalsIgnoreCase(args[0])) {
            completions.add("on");
            completions.add("off");
            completions.add("toggle");
            return filter(completions, args[1]);
        }
        return completions;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(java.util.Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(java.util.Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
