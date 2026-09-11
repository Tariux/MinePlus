package com.mineplus.fun.gunsmith;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.StringUtil;

/**
 * {@code /gunsmith <give|status>} — hand out the gun test rig and report
 * the overlay's state.
 *
 * <p>{@code give} hands a bow, crossbow and arrows stamped with the overlay's
 * legacy {@code custom_model_data} value: on 1.21.4+ clients the pack's
 * {@code items/*.json} definitions drive the presentation for every bow and
 * crossbow, while on legacy clients (1.21–1.21.3) the predicate overrides in
 * the shipped {@code models/item/bow.json} / {@code models/item/crossbow.json}
 * select the pistol/rifle frames for exactly these stamped items — unset
 * vanilla items stay vanilla. Drawing the bow plays the pistol frame
 * sequence and firing plays the gun sound; the crossbow does the rifle with
 * pull-charge frames. {@code status} reports the pack subsystem/artifact
 * state, the applied-pack caveat, and which vanilla items the overlay
 * affects.
 */
public final class GunsmithSubCommand implements SubCommand {

    private static final int ARROW_COUNT = 64;

    private final PluginContext context;

    public GunsmithSubCommand(PluginContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "gunsmith";
    }

    @Override
    public String description() {
        return "Gun pack field test: bow/crossbow gun overlay through raw pack assets.";
    }

    @Override
    public String usage() {
        return "/gunsmith <give|status>";
    }

    @Override
    public String permission() {
        return "mineplusfun.admin.gunsmith";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length < 1) {
            return false;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "give" -> {
                return giveRig(sender);
            }
            case "status" -> {
                return printStatus(sender);
            }
            default -> {
                return false;
            }
        }
    }

    private boolean giveRig(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can receive the test rig; use /gunsmith status.");
            return true;
        }
        player.getInventory().addItem(gunItem(Material.BOW)).values()
                .forEach(overflow -> player.getWorld().dropItemNaturally(player.getLocation(), overflow));
        player.getInventory().addItem(gunItem(Material.CROSSBOW)).values()
                .forEach(overflow -> player.getWorld().dropItemNaturally(player.getLocation(), overflow));
        player.getInventory().addItem(new ItemStack(Material.ARROW, ARROW_COUNT)).values()
                .forEach(overflow -> player.getWorld().dropItemNaturally(player.getLocation(), overflow));
        player.sendMessage(ChatColor.GREEN + "Gave you the gun test rig: pistol bow, rifle crossbow and "
                + ARROW_COUNT + " arrows.");
        player.sendMessage(ChatColor.GRAY + "With the pack applied, drawing the bow plays the pistol frame"
                + " sequence and firing plays the gun sound; the crossbow does the rifle with pull-charge"
                + " frames. On 1.21.4+ clients the overlay applies to the vanilla items; on older clients"
                + " (1.21 to 1.21.3) the rig's custom model data (" + GunsmithKeys.LEGACY_CUSTOM_MODEL_DATA
                + ") selects the gun models. Without the pack, everything stays perfectly vanilla.");
        return true;
    }

    /**
     * The test rig's bow/crossbow: stamped with the overlay's legacy
     * {@code custom_model_data} value so pre-1.21.4 clients (whose item
     * definitions do not exist) still resolve the gun models through the
     * shipped host-file predicate overrides.
     */
    private ItemStack gunItem(Material material) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(GunsmithKeys.LEGACY_CUSTOM_MODEL_DATA);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private boolean printStatus(CommandSender sender) {
        var pack = context.packApi();
        sender.sendMessage(ChatColor.GREEN + "=== Gunsmith (vanilla overlay pack test) ===");
        sender.sendMessage(ChatColor.GRAY + "Subsystem: "
                + (pack.isAvailable() ? ChatColor.GREEN + "ACTIVE" : ChatColor.YELLOW + "DISABLED"));
        var artifact = pack.currentArtifact();
        sender.sendMessage(ChatColor.GRAY + "Artifact: "
                + (artifact == null ? ChatColor.YELLOW + "none compiled yet"
                : ChatColor.WHITE + artifact.artifactName() + " (" + artifact.assetCount() + " assets)"));
        String url = pack.deliveryUrl(sender instanceof Player player ? player : null);
        sender.sendMessage(ChatColor.GRAY + "Download URL: "
                + (url == null ? ChatColor.YELLOW + "unavailable (check PACK delivery settings)"
                : ChatColor.WHITE + url));
        sender.sendMessage(ChatColor.GRAY + "Registered overlay assets: " + ChatColor.WHITE
                + GunsmithKeys.MANIFEST.size() + ChatColor.GRAY + " under namespace '"
                + GunsmithKeys.NAMESPACE + "'");
        sender.sendMessage(ChatColor.GRAY + "Affected vanilla content:");
        for (String affected : GunsmithKeys.AFFECTED_ITEMS) {
            sender.sendMessage(ChatColor.WHITE + "- " + affected);
        }
        sender.sendMessage(ChatColor.YELLOW + "Caveats: every effect requires the generated pack applied"
                + " (player state APPLIED). On 1.21.4+ clients the items/*.json definitions drive the"
                + " overlay; older clients (1.21 to 1.21.3) resolve the gun models through custom model"
                + " data " + GunsmithKeys.LEGACY_CUSTOM_MODEL_DATA + " (stamped by /gunsmith give) and the"
                + " shipped models/item/bow.json + models/item/crossbow.json host files. Sound overrides"
                + " (sounds.json + .ogg paths) apply on every client version.");
        if (sender instanceof Player player) {
            sender.sendMessage(ChatColor.GRAY + "Your pack state: " + ChatColor.WHITE
                    + pack.playerPackState(player.getUniqueId()));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0], List.of("give", "status"), completions);
            Collections.sort(completions);
            return completions;
        }
        return Collections.emptyList();
    }
}
