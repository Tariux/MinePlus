package com.mineplus.fun.packshowcase;

import com.mineplus.fun.ModuleFeature;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.definition.ItemCategory;
import com.mineplus.pack.PackApi;
import com.mineplus.pack.PlayerPackState;
import com.mineplus.pack.compile.PackArtifact;
import com.mineplus.pack.item.PackItemDefinition;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Phase 2 reference demonstration: <b>pack-based custom content</b> built
 * from the exact same authoring assets as the packless features.
 *
 * <p>The feature registers one custom item — {@code fun:strad_wine}, backed by
 * a vanilla glass bottle — whose model and texture are the Wine feature's
 * already-installed {@code strad-wine.bbmodel} and {@code strad_wine.png}.
 * Nothing new is authored: the pack pipeline consumes the existing Mineplus
 * model/texture systems, compiles them into the generated resource pack, and
 * the item renders at full client fidelity for pack players.
 *
 * <p>Coexistence contract, live: with the pack subsystem disabled (or the
 * player without the pack) the item still exists — identity, give command and
 * gameplay work — and renders as its vanilla backing item. The packless Wine
 * feature keeps rendering the same model through the texel pipeline
 * regardless; neither subsystem touches the other.
 */
public final class PackShowcaseFeature extends ModuleFeature {

    private static final String NAMESPACE = "fun";
    private static final String ITEM_ID = "strad_wine";
    private static final String MODEL_KEY = "strad-wine";

    private PackApi packApi;

    public PackShowcaseFeature(JavaPlugin plugin, PluginContext context) {
        super(plugin, context);
    }

    @Override
    public String id() {
        return "packshowcase";
    }

    @Override
    protected void onEnable() {
        packApi = context.packApi();
        // Registration order deliberately mirrors the module contract: the item
        // registers before the coordinated model reload, so its model/texture
        // assets attach during the reload-driven recompile — exactly how a real
        // module registers pack content.
        packApi.registerItem(PackItemDefinition.builder(
                        NAMESPACE, ITEM_ID,
                        org.bukkit.Material.GLASS_BOTTLE,
                        MODEL_KEY)
                .displayName("Strad Wine Bottle")
                .category(ItemCategory.UTILITY)
                .descriptionLines(List.of(
                        "The vinery's flagship, rendered at full",
                        "client fidelity through the generated pack."))
                .build());
    }

    @Override
    protected com.mineplus.infrastructure.command.SubCommand command() {
        return new PackShowcaseCommand();
    }

    private final class PackShowcaseCommand implements com.mineplus.infrastructure.command.SubCommand {

        @Override
        public String name() {
            return "packshowcase";
        }

        @Override
        public String description() {
            return "Pack-system showcase: obtain the pack-rendered wine bottle item.";
        }

        @Override
        public String usage() {
            return "/packshowcase [status]";
        }

        @Override
        public String permission() {
            return "mineplusfun.admin.packshowcase";
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
                sendStatus(sender);
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can receive the showcase item; use /packshowcase status.");
                return true;
            }
            ItemStack item = packApi.createItem(NAMESPACE, ITEM_ID);
            if (item == null) {
                sender.sendMessage(ChatColor.RED + "Showcase item is not registered.");
                return true;
            }
            player.getInventory().addItem(item).values()
                    .forEach(overflow -> player.getWorld().dropItemNaturally(player.getLocation(), overflow));
            player.sendMessage(ChatColor.GREEN + "Gave you the Strad Wine Bottle."
                    + ChatColor.GRAY + " With the pack applied it renders the full 3D model; without it, "
                    + "the vanilla glass bottle — identity and behavior are identical either way.");
            PlayerPackState state = packApi.playerPackState(player.getUniqueId());
            player.sendMessage(ChatColor.GRAY + "Your pack state: " + ChatColor.WHITE + state);
            return true;
        }

        private void sendStatus(CommandSender sender) {
            sender.sendMessage(ChatColor.GREEN + "=== MineplusFun Pack Showcase ===");
            sender.sendMessage(ChatColor.GRAY + "Subsystem: "
                    + (packApi.isAvailable() ? ChatColor.GREEN + "ACTIVE" : ChatColor.YELLOW + "DISABLED"));
            PackArtifact artifact = packApi.currentArtifact();
            sender.sendMessage(ChatColor.GRAY + "Artifact: "
                    + (artifact == null ? ChatColor.YELLOW + "none compiled yet"
                    : ChatColor.WHITE + artifact.artifactName() + " (" + artifact.assetCount() + " assets)"));
            sender.sendMessage(ChatColor.GRAY + "Item: " + ChatColor.WHITE + NAMESPACE + ":" + ITEM_ID
                    + ChatColor.GRAY + " (model key '" + MODEL_KEY + "', vanilla backing GLASS_BOTTLE)");
        }

        @Override
        public java.util.List<String> tabComplete(CommandSender sender, String[] args) {
            if (args.length == 1) {
                return List.of("status");
            }
            return List.of();
        }
    }
}
