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
        return "/mineplus pack <status|recompile|push <player>>";
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
                }
                sender.sendMessage(ChatColor.GRAY + " Registered assets: "
                        + context.packSystem().assetRegistry().size());
                int applied = 0;
                int online = Bukkit.getOnlinePlayers().size();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (api.playerPackState(player.getUniqueId()).hasPack()) {
                        applied++;
                    }
                }
                sender.sendMessage(ChatColor.GRAY + " Players with pack: " + applied + "/" + online);
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
            StringUtil.copyPartialMatches(args[0], List.of("status", "recompile", "push"), completions);
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
}
