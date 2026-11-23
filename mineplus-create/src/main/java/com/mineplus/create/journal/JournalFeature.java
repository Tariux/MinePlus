package com.mineplus.create.journal;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.module.ModuleFeature;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Inventor's Journal: the module's top-level {@code /journal} command.
 *
 * <p>Research state is stored per player in the player's
 * {@link PersistentDataContainer} (comma-separated research ids) — persisted
 * by the server itself, no extra storage layer. Unlocking spends XP levels via
 * {@link Player#setLevel(int)}. When the Core exposes a GUI service the
 * command opens it; until then the command replies with the ladder state in
 * chat so the progression is playable end to end.</p>
 */
public final class JournalFeature extends ModuleFeature {

    private static final String PDC_KEY = "mineplus_create_research";

    private final ResearchLadder ladder = new ResearchLadder();
    private NamespacedKey researchKey;

    public JournalFeature(JavaPlugin plugin, PluginContext context) {
        super(plugin, context);
    }

    @Override
    public String id() {
        return "journal";
    }

    @Override
    protected void onEnable() {
        this.researchKey = new NamespacedKey(plugin, PDC_KEY);
        context.moduleSupport().registerCommand(plugin, "journal", new com.mineplus.infrastructure.command.SubCommand() {
            @Override
            public String name() {
                return "journal";
            }

            @Override
            public String description() {
                return "Open the Inventor's Journal (research ladder).";
            }

            @Override
            public String usage() {
                return "/journal [research <id>]";
            }

            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("The journal is for players.");
                    return true;
                }
                if (args.length >= 2 && args[0].equalsIgnoreCase("research")) {
                    research(player, args[1].toLowerCase());
                } else {
                    show(player);
                }
                return true;
            }
        });
    }

    private void show(Player player) {
        int tier = ResearchLadder.tierOf(unlockedIds(player));
        player.sendMessage("§6Inventor's Journal §7- tier " + Math.max(tier, 0));
        for (ResearchLadder.Research node : ladder.all()) {
            String state = isUnlocked(player, node.id) ? "§aunlocked"
                    : "§7cost " + node.xpCost + " levels - /journal research " + node.id;
            player.sendMessage(" §e" + node.title + " §8(T" + node.tier + ") " + state);
        }
    }

    private void research(Player player, String id) {
        ResearchLadder.Research node = ladder.unlock(id, unlockedIds(player), player.getLevel());
        if (node == null) {
            player.sendMessage("§cCannot research '" + id
                    + "' - check prerequisites and XP cost (/journal).");
            return;
        }
        player.setLevel(player.getLevel() - node.xpCost);
        var unlocked = unlockedIds(player);
        unlocked.add(node.id);
        player.getPersistentDataContainer().set(researchKey,
                PersistentDataType.STRING, String.join(",", unlocked));
        player.sendMessage("§aResearched: " + node.title + " (T" + node.tier + ")");
    }

    private boolean isUnlocked(Player player, String id) {
        return unlockedIds(player).contains(id);
    }

    private java.util.List<String> unlockedIds(Player player) {
        String raw = player.getPersistentDataContainer()
                .get(researchKey, PersistentDataType.STRING);
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String id : raw.split(",")) {
                ids.add(id.trim());
            }
        }
        return ids;
    }
}
