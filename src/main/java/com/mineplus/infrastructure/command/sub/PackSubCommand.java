package com.mineplus.infrastructure.command.sub;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.pack.PackApi;
import com.mineplus.pack.PlayerPackState;
import com.mineplus.pack.compile.PackArtifact;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

/**
 * {@code /mineplus pack} — pack subsystem administration: status (subsystem,
 * artifact, delivery, player states), recompile (explicit compile), push
 * (deliver the current pack to one player).
 */
public final class PackSubCommand implements SubCommand {

    private final PluginContext context;

    public PackSubCommand(PluginContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "pack";
    }

    @Override
    public String description() {
        return "Resource pack subsystem status and administration.";
    }

    @Override
    public String usage() {
        return "/mineplus pack <status|audit|verify|recompile|push <player>>";
    }

    @Override
    public String permission() {
        return "mineplus.admin.pack";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        PackApi api = context.packApi();

        switch (action) {
            case "status" -> {
                if (!api.isAvailable()) {
                    sender.sendMessage(ChatColor.YELLOW + "Pack subsystem: DISABLED"
                            + ChatColor.GRAY + " (set PACK.ENABLED: true in settings.mp.yml and restart).");
                    return true;
                }
                sender.sendMessage(ChatColor.GREEN + "Pack subsystem: ACTIVE");
                PackArtifact artifact = api.currentArtifact();
                if (artifact == null) {
                    sender.sendMessage(ChatColor.GRAY + " Artifact: none compiled yet ("
                            + ChatColor.YELLOW + "/mineplus pack recompile" + ChatColor.GRAY + ").");
                } else {
                    sender.sendMessage(ChatColor.GRAY + " Artifact: " + ChatColor.WHITE + artifact.artifactName()
                            + ChatColor.GRAY + " (" + artifact.assetCount() + " assets, "
                            + artifact.byteSize() + " bytes, pack_format " + artifact.packFormat()
                            + ", " + artifact.itemRepresentation() + ")");
                    String url = api.deliveryUrl(sender instanceof Player player ? player : null);
                    sender.sendMessage(ChatColor.GRAY + " Download URL: " + ChatColor.WHITE
                            + (url == null ? "unavailable (delivery mode, endpoint, or URL settings)"
                            : url));
                    if (url != null && artifact.sha1() != null && artifact.sha1().length() == 40) {
                        sender.sendMessage(ChatColor.GRAY + " SHA-1: " + ChatColor.WHITE + artifact.sha1());
                    }
                }
                sender.sendMessage(ChatColor.GRAY + " Registered assets: "
                        + context.packSystem().assetRegistry().size());
                var appearance = context.packSystem().appearance();
                sender.sendMessage(ChatColor.GRAY + " Appearance: " + ChatColor.WHITE
                        + context.packSystem().appearanceMode()
                        + ChatColor.GRAY + " (resolved " + appearance.representation()
                        + ", dispatch " + appearance.dispatch() + ")");
                sendValidationSummary(sender, context.packSystem().validationReport());
                sendPlayerStates(sender);
                return true;
            }
            case "audit" -> {
                if (!api.isAvailable()) {
                    sender.sendMessage(ChatColor.RED + "Pack subsystem is disabled.");
                    return true;
                }
                com.mineplus.pack.compile.ModelAssetValidator.Report report =
                        context.packSystem().validationReport();
                sender.sendMessage(ChatColor.GRAY + " Artifact under audit: " + ChatColor.WHITE
                        + (api.currentArtifact() == null ? "none compiled"
                        : api.currentArtifact().artifactName()));
                if (report.clean()) {
                    sender.sendMessage(ChatColor.GREEN + " Audit clean: " + report.checked()
                            + " asset(s) checked, 0 out-of-window elements, 0 unresolved references,"
                            + " 0 out-of-range UVs, 0 duplicate custom_model_data values.");
                    return true;
                }
                sender.sendMessage(ChatColor.RED + " Audit findings (" + report.warnings().size() + "):");
                sender.sendMessage(ChatColor.GRAY + "   out-of-window elements: " + report.outOfWindow());
                sender.sendMessage(ChatColor.GRAY + "   unresolved references: " + report.unresolvedRefs());
                sender.sendMessage(ChatColor.GRAY + "   UV outside 0..16: " + report.uvOutOfRange());
                sender.sendMessage(ChatColor.GRAY + "   duplicate custom_model_data: " + report.duplicateCommands());
                sender.sendMessage(ChatColor.GRAY + "   assets skipped by the compiler: " + report.skipped());
                int shown = Math.min(20, report.warnings().size());
                for (int index = 0; index < shown; index++) {
                    sender.sendMessage(ChatColor.YELLOW + "  - " + report.warnings().get(index));
                }
                if (report.warnings().size() > shown) {
                    sender.sendMessage(ChatColor.GRAY + "  ... and "
                            + (report.warnings().size() - shown) + " more (full list in the console logs).");
                }
                return true;
            }
            case "verify" -> {
                if (!api.isAvailable()) {
                    sender.sendMessage(ChatColor.RED + "Pack subsystem is disabled.");
                    return true;
                }
                sender.sendMessage(ChatColor.GRAY + "Verifying the served artifact through its own URL...");
                context.packSystem().verifyDelivery(check -> {
                    if (check.ok()) {
                        sender.sendMessage(ChatColor.GREEN + " Delivery verified: " + check.bytes()
                                + " bytes, " + check.entries() + " zip entries, SHA-1 matches.");
                        sender.sendMessage(ChatColor.GRAY + " The pack the server hands out is intact — a"
                                + " client that still shows vanilla is declining, has an older pack cached,"
                                + " or is behind a proxy that rewrites the URL.");
                    } else {
                        sender.sendMessage(ChatColor.RED + " Delivery problem: " + check.detail());
                    }
                });
                return true;
            }
            case "recompile" -> {
                if (!api.isAvailable()) {
                    sender.sendMessage(ChatColor.RED + "Pack subsystem is disabled.");
                    return true;
                }
                api.recompile().whenComplete((artifact, error) -> {
                    if (error != null) {
                        sender.sendMessage(ChatColor.RED + "Recompile failed: " + error.getMessage());
                        return;
                    }
                    if (artifact == null) {
                        sender.sendMessage(ChatColor.YELLOW + "Nothing to compile (no assets registered).");
                    } else {
                        sender.sendMessage(ChatColor.GREEN + "Compiled " + artifact.artifactName()
                                + " (" + artifact.assetCount() + " assets, " + artifact.compileMillis() + " ms).");
                    }
                });
                return true;
            }
            case "push" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: " + usage());
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player '" + args[1] + "' is not online.");
                    return true;
                }
                if (!api.isAvailable()) {
                    sender.sendMessage(ChatColor.RED + "Pack subsystem is disabled.");
                    return true;
                }
                if (api.deliverPack(target)) {
                    PlayerPackState state = api.playerPackState(target.getUniqueId());
                    sender.sendMessage(ChatColor.GREEN + "Pack pushed to " + target.getName()
                            + ChatColor.GRAY + " (state: " + state + ").");
                } else {
                    sender.sendMessage(ChatColor.RED + "Push failed: no artifact compiled or delivery disabled.");
                }
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
            StringUtil.copyPartialMatches(args[0],
                    List.of("status", "audit", "verify", "recompile", "push"), completions);
            Collections.sort(completions);
            return completions;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("push")) {
            List<String> completions = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            StringUtil.copyPartialMatches(args[1], names, completions);
            Collections.sort(completions);
            return completions;
        }
        return Collections.emptyList();
    }

    /** The one-line validation summary of the last compile (counts, then a sample). */
    private void sendValidationSummary(CommandSender sender,
                                       com.mineplus.pack.compile.ModelAssetValidator.Report report) {
        sender.sendMessage(ChatColor.GRAY + " Validation: " + ChatColor.WHITE
                + report.checked() + " checked, " + report.warnings().size() + " finding(s), "
                + report.skipped() + " skipped");
        if (report.outOfWindow() > 0 || report.unresolvedRefs() > 0 || report.uvOutOfRange() > 0
                || report.duplicateCommands() > 0) {
            sender.sendMessage(ChatColor.RED + "  defects: " + report.outOfWindow()
                    + " re-based element(s), " + report.unresolvedRefs() + " unresolved reference(s), "
                    + report.uvOutOfRange() + " out-of-range UV(s), " + report.duplicateCommands()
                    + " duplicate custom_model_data — see " + ChatColor.YELLOW + "/mineplus pack audit");
        }
        int shown = Math.min(5, report.warnings().size());
        for (int index = 0; index < shown; index++) {
            sender.sendMessage(ChatColor.YELLOW + "  - " + report.warnings().get(index));
        }
        if (report.warnings().size() > shown) {
            sender.sendMessage(ChatColor.GRAY + "  ... and "
                    + (report.warnings().size() - shown) + " more (see console logs).");
        }
    }

    /**
     * Per-player pack state: a mob or item that "does not render" is almost
     * always a player whose client never applied the pack, so the state of
     * every online player is printed rather than an aggregate count.
     */
    private void sendPlayerStates(CommandSender sender) {
        PackApi api = context.packApi();
        int applied = 0;
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        sender.sendMessage(ChatColor.GRAY + " Players: " + online.size() + " online");
        for (Player player : online) {
            PlayerPackState state = api.playerPackState(player.getUniqueId());
            if (state.hasPack()) {
                applied++;
            }
            ChatColor color = switch (state) {
                case APPLIED -> ChatColor.GREEN;
                case ACCEPTED, REQUESTED -> ChatColor.WHITE;
                case DECLINED -> ChatColor.YELLOW;
                case FAILED -> ChatColor.RED;
                case UNKNOWN -> ChatColor.GRAY;
            };
            String playerUrl = api.deliveryUrl(player);
            sender.sendMessage(ChatColor.GRAY + "  - " + player.getName() + ": " + color + state
                    + ChatColor.GRAY + " from " + ChatColor.WHITE
                    + (playerUrl == null ? "no URL resolved" : playerUrl));
        }
        if (applied < online.size()) {
            sender.sendMessage(ChatColor.GRAY + " Applied: " + applied + "/" + online.size()
                    + ChatColor.WHITE + " (/mineplus pack push <player> re-prompts one player).");
        }
    }
}
