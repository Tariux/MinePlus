package com.mineplus.infrastructure.core.block;

import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/** Runs definition event actions for a player. */
public final class BlockActionRunner {

    private BlockActionRunner() {
    }

    public static void run(Player player, BlockDefinition definition, List<BlockAction> actions) {
        if (player == null || actions == null || actions.isEmpty()) {
            return;
        }
        for (BlockAction action : actions) {
            switch (action.kind()) {
                case MESSAGE -> player.sendMessage(ChatColor.translateAlternateColorCodes(
                        '&', action.value()
                                .replace("%player%", player.getName())
                                .replace("%block%", definition.displayName())));
                case SOUND -> {
                    try {
                        player.playSound(player.getLocation(), action.value(), 1.0f, 1.0f);
                    } catch (RuntimeException unknownSound) {
                        // A bad sound key never fails the action list.
                    }
                }
                case COMMAND -> {
                    String command = action.value()
                            .replace("%player%", player.getName())
                            .replace("%block%", definition.id());
                    player.performCommand(command.startsWith("/") ? command.substring(1) : command);
                }
                default -> {
                }
            }
        }
    }
}
