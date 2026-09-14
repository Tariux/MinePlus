package com.mineplus.infrastructure.command.sub;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.infrastructure.render.RenderMode;
import com.mineplus.infrastructure.render.RenderPolicy;
import com.mineplus.infrastructure.render.RenderRouter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.util.StringUtil;

/**
 * {@code /mineplus render} — unified render engine telemetry: routing counters
 * per {@link RenderMode}, degraded/unavailable route counts, pack attach
 * failures, and the active {@link RenderPolicy}.
 */
public final class RenderSubCommand implements SubCommand {

    private final PluginContext context;

    public RenderSubCommand(PluginContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "render";
    }

    @Override
    public String description() {
        return "Unified render engine routing statistics and policy.";
    }

    @Override
    public String usage() {
        return "/mineplus render <stats|reset>";
    }

    @Override
    public String permission() {
        return "mineplus.admin.render";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        String action = args.length == 0 ? "stats" : args[0].toLowerCase(Locale.ROOT);
        RenderRouter router = context.renderRouter();

        switch (action) {
            case "stats" -> {
                RenderRouter.RenderStats stats = router.snapshot();
                sender.sendMessage(ChatColor.GOLD + "Render router");
                sender.sendMessage(ChatColor.YELLOW + " Routes: " + ChatColor.WHITE + stats.totalRoutes()
                        + ChatColor.GRAY + " (degraded " + stats.degradedRoutes()
                        + ", unavailable " + stats.unavailableRoutes()
                        + ", attach failures " + stats.attachFailures() + ")");
                for (RenderMode mode : RenderMode.values()) {
                    long count = stats.routesByMode().getOrDefault(mode, 0L);
                    sender.sendMessage(ChatColor.YELLOW + "  " + mode.key() + ": "
                            + ChatColor.WHITE + count
                            + ChatColor.GRAY + " -> " + mode.backend() + "/" + mode.kind());
                }
                RenderPolicy policy = router.policy();
                sender.sendMessage(ChatColor.YELLOW + " Policy: "
                        + ChatColor.WHITE + "degrade=" + policy.allowDegradeToVirtual()
                        + ChatColor.GRAY + ", logRouting=" + policy.logRouting()
                        + ", telemetry=" + policy.trackTelemetry());
                sender.sendMessage(ChatColor.YELLOW + " Pack renderer: " + ChatColor.WHITE
                        + (context.infrastructureEngine().renderingManager().packRendererActive() ? "active" : "inactive"));
                return true;
            }
            case "reset" -> {
                router.reset();
                sender.sendMessage(ChatColor.GREEN + "Render routing counters reset.");
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
            StringUtil.copyPartialMatches(args[0], List.of("stats", "reset"), completions);
            Collections.sort(completions);
            return completions;
        }
        return Collections.emptyList();
    }
}
