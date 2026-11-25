package com.mineplus.fun.tea;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.VirtualBlockPlacementHelper;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.pack.PackApi;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /tea <give|spawn|play|list|debug|stop|remove>} — hands out the tea
 * cup item and drives the tea cup's four baked clips. {@code debug} plays
 * every clip in sequence so all animations can be checked in one go.
 *
 * <p>Placed cups render through the pack axis ({@link TeaAnimations#place}):
 * collision-only virtual spawn plus a pack {@code ItemDisplay}, so the cup
 * shows the resource-pack texture instead of the texel bake, and its clips
 * play through the frame animator.
 */
public final class TeaSubCommand implements SubCommand {

    private static final long DEBUG_STEP_DELAY_TICKS = 40L;

    private final PluginContext context;
    private final PackApi packApi;
    private final TeaAnimations animations;
    private final Map<UUID, UUID> spawnedByPlayer = new HashMap<>();

    public TeaSubCommand(PluginContext context, PackApi packApi, TeaAnimations animations) {
        this.context = context;
        this.packApi = packApi;
        this.animations = animations;
    }

    @Override
    public String name() {
        return "tea";
    }

    @Override
    public String description() {
        return "Tea cup item and animation showcase.";
    }

    @Override
    public String usage() {
        return "/tea <give|spawn|play|list|debug|stop|remove> [animation]";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /tea.");
            return true;
        }
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        switch (action) {
            case "give" -> {
                // Pack item (fun:tea_cup): the generated resource pack renders
                // the full 3D tea model instead of the vanilla potion texture.
                ItemStack item = packApi.createItem(TeaKeys.NAMESPACE, TeaKeys.TEA_ITEM);
                if (item == null) {
                    sender.sendMessage(ChatColor.RED + "Tea item is not registered.");
                    return true;
                }
                player.getInventory().addItem(item);
                player.sendMessage(ChatColor.GREEN + "You received a cup of tea. Right click to drink!");
                return true;
            }
            case "spawn" -> {
                return spawnCup(player);
            }
            case "list" -> {
                player.sendMessage(ChatColor.GOLD + "Tea animations:");
                for (String animation : TeaKeys.ANIMATIONS) {
                    player.sendMessage(ChatColor.YELLOW + " - " + animation);
                }
                return true;
            }
            case "play" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /tea play <animation>");
                    return true;
                }
                UUID cupId = spawnedByPlayer.get(player.getUniqueId());
                if (cupId == null) {
                    player.sendMessage(ChatColor.RED + "No tea cup spawned. Use /tea spawn first.");
                    return true;
                }
                String animation = args[1].toLowerCase(Locale.ROOT);
                if (!TeaKeys.ANIMATIONS.contains(animation)) {
                    player.sendMessage(ChatColor.RED + "Unknown animation '" + animation + "'. Try /tea list.");
                    return true;
                }
                boolean played = animations.playOnCup(cupId, animation);
                player.sendMessage(played
                        ? ChatColor.GREEN + "Playing " + animation
                        : ChatColor.RED + "Failed to play " + animation);
                return true;
            }
            case "debug" -> {
                return playDebugSequence(player);
            }
            case "stop" -> {
                UUID cupId = spawnedByPlayer.get(player.getUniqueId());
                if (cupId == null) {
                    player.sendMessage(ChatColor.RED + "No tea cup spawned.");
                    return true;
                }
                animations.stopCup(cupId);
                player.sendMessage(ChatColor.GREEN + "Stopped all tea animations.");
                return true;
            }
            case "remove" -> {
                UUID cupId = spawnedByPlayer.remove(player.getUniqueId());
                if (cupId == null) {
                    player.sendMessage(ChatColor.RED + "No tea cup spawned.");
                    return true;
                }
                animations.remove(cupId);
                player.sendMessage(ChatColor.GREEN + "Removed your tea cup.");
                return true;
            }
            default -> {
                player.sendMessage(ChatColor.GOLD + usage());
                player.sendMessage(ChatColor.GRAY + "Animations: " + String.join(", ", TeaKeys.ANIMATIONS));
                return true;
            }
        }
    }

    private boolean spawnCup(Player player) {
        VirtualBlockManager blocks = context.virtualBlockManager();
        VirtualModel model = blocks.getModel(TeaKeys.MODEL_KEY);
        if (model == null) {
            player.sendMessage(ChatColor.RED + "Tea model not loaded. Is models/tea.bbmodel installed?");
            return true;
        }
        var placement = VirtualBlockPlacementHelper.getPlacementData(player, 6.0);
        if (placement == null) {
            player.sendMessage(ChatColor.RED + "Look at a nearby block face to place the tea cup.");
            return true;
        }
        // Remove the previous cup first so /tea spawn stays one-per-player.
        UUID previous = spawnedByPlayer.remove(player.getUniqueId());
        if (previous != null) {
            animations.remove(previous);
        }
        blocks.prepareSpawnArea(model, placement, true);
        UUID cupId = animations.place(model, placement);
        if (cupId == null) {
            player.sendMessage(ChatColor.RED + "Failed to spawn the tea cup here.");
            return true;
        }
        spawnedByPlayer.put(player.getUniqueId(), cupId);
        player.sendMessage(ChatColor.GREEN + "Spawned tea cup. Try /tea play steam_idle");
        return true;
    }

    private boolean playDebugSequence(Player player) {
        UUID cupId = spawnedByPlayer.get(player.getUniqueId());
        if (cupId == null) {
            player.sendMessage(ChatColor.RED + "No tea cup spawned. Use /tea spawn first.");
            return true;
        }
        player.sendMessage(ChatColor.GOLD + "Tea debug: playing " + TeaKeys.ANIMATIONS.size() + " animations in sequence.");
        for (int i = 0; i < TeaKeys.ANIMATIONS.size(); i++) {
            String animation = TeaKeys.ANIMATIONS.get(i);
            context.plugin().getServer().getScheduler().runTaskLater(
                    context.plugin(),
                    () -> {
                        boolean played = animations.playOnCup(cupId, animation);
                        player.sendMessage(ChatColor.YELLOW + "[tea debug] " + animation
                                + (played ? "" : ChatColor.RED + " (failed)"));
                    },
                    i * DEBUG_STEP_DELAY_TICKS);
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>(List.of("give", "spawn", "play", "list", "debug", "stop", "remove"));
            completions.removeIf(action -> !action.startsWith(args[0].toLowerCase(Locale.ROOT)));
            return completions;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("play")) {
            List<String> completions = new ArrayList<>(TeaKeys.ANIMATIONS);
            completions.removeIf(animation -> !animation.startsWith(args[1].toLowerCase(Locale.ROOT)));
            return completions;
        }
        return List.of();
    }
}
