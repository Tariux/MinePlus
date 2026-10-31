package com.mineplus.infrastructure.command.sub;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.infrastructure.perf.PerfRegistry;
import com.mineplus.infrastructure.perf.PerfSnapshot;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

/**
 * {@code /mineplus perf} — performance telemetry table, counter reset and a JSON
 * snapshot dump under {@code plugins/Mineplus/perf/}. Works with every other
 * subsystem disabled: it only reads the {@link PerfRegistry}.
 */
public final class PerfCommand implements SubCommand {

    private final PluginContext context;

    public PerfCommand(PluginContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "perf";
    }

    @Override
    public String description() {
        return "Performance telemetry, adaptive valve state and JSON dumps.";
    }

    @Override
    public String usage() {
        return "/mineplus perf [reset|dump]";
    }

    @Override
    public String permission() {
        return "mineplus.admin.perf";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        PerfRegistry perf = context.perf();
        String action = args.length == 0 ? "show" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "show", "stats" -> {
                printTable(sender, perf);
                return true;
            }
            case "reset" -> {
                perf.reset();
                sender.sendMessage(ChatColor.GREEN + "Performance counters and adaptive valve reset.");
                return true;
            }
            case "dump" -> {
                dump(sender, perf);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void printTable(CommandSender sender, PerfRegistry perf) {
        PerfSnapshot snapshot = perf.snapshot();
        sender.sendMessage(ChatColor.GOLD + "Performance telemetry"
                + ChatColor.GRAY + " (telemetry " + (snapshot.telemetry() ? "on" : "off")
                + ", adaptive " + (snapshot.adaptive() ? "on" : "off")
                + ", pose budget " + snapshot.poseBudgetMs() + " ms"
                + ", interval x" + snapshot.tickIntervalMultiplier() + ")");
        sender.sendMessage(ChatColor.YELLOW + " Counters");
        for (var entry : snapshot.counters().entrySet()) {
            sender.sendMessage(ChatColor.GRAY + "  " + entry.getKey() + ": "
                    + ChatColor.WHITE + entry.getValue());
        }
        sender.sendMessage(ChatColor.YELLOW + " Gauges");
        for (var entry : snapshot.gauges().entrySet()) {
            sender.sendMessage(ChatColor.GRAY + "  " + entry.getKey() + ": "
                    + ChatColor.WHITE + entry.getValue());
        }
        if (!snapshot.averages().isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + " Recent ticks (ring average)");
            for (var entry : snapshot.averages().entrySet()) {
                sender.sendMessage(ChatColor.GRAY + "  " + entry.getKey() + ": "
                        + ChatColor.WHITE + String.format(Locale.ROOT, "%.3f", entry.getValue()));
            }
        } else {
            sender.sendMessage(ChatColor.GRAY + " No closed telemetry ticks yet.");
        }
    }

    private void dump(CommandSender sender, PerfRegistry perf) {
        JavaPlugin plugin = context.plugin();
        PerfSnapshot snapshot = perf.snapshot();
        sender.sendMessage(ChatColor.YELLOW + "Writing performance snapshot...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            File directory = new File(plugin.getDataFolder(), "perf");
            String name = "perf-" + Instant.now().toString().replace(':', '-') + ".json";
            File target = new File(directory, name);
            String message;
            try {
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IOException("could not create " + directory.getAbsolutePath());
                }
                Files.writeString(target.toPath(), snapshot.toJson());
                message = ChatColor.GREEN + "Snapshot written to " + target.getAbsolutePath();
            } catch (IOException exception) {
                message = ChatColor.RED + "Snapshot failed: " + exception.getMessage();
            }
            final String result = message;
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(result));
        });
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0], List.of("show", "reset", "dump"), completions);
            Collections.sort(completions);
            return completions;
        }
        return Collections.emptyList();
    }
}
