package com.mineplus.gun.command;

import com.mineplus.gun.GunServices;
import com.mineplus.gun.MineplusGunPlugin;
import com.mineplus.gun.shop.ShopGui;
import com.mineplus.gun.weapon.WeaponDefinition;
import com.mineplus.infrastructure.command.SubCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The shared {@code /mpgun …} / {@code /mineplus gun …} command tree. The same
 * instance shape is registered twice with different labels; per-action
 * permissions are enforced here (the router enforces only the top-level one).
 */
public final class GunSubCommand implements SubCommand {

    private final String label;
    private final MineplusGunPlugin plugin;
    private final GunServices services;
    private final Runnable reloadAction;

    public GunSubCommand(String label, MineplusGunPlugin plugin, GunServices services, Runnable reloadAction) {
        this.label = label;
        this.plugin = plugin;
        this.services = services;
        this.reloadAction = reloadAction;
    }

    @Override
    public String name() {
        return label;
    }

    @Override
    public String description() {
        return "MineplusGun combat administration and shop";
    }

    @Override
    public String usage() {
        return "/" + label + " <shop|give|hud|reload|stats|debug>";
    }

    @Override
    public String permission() {
        // No blanket gate: each action checks its own permission so /mpgun and
        // /mineplus gun behave identically for every player.
        return "";
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "shop" -> shop(sender);
            case "give" -> give(sender, args);
            case "hud" -> hud(sender);
            case "reload" -> reload(sender);
            case "stats" -> stats(sender);
            case "debug" -> debug(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "MineplusGun:");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " shop" + ChatColor.GRAY + " - open the weapon shop");
        if (sender.hasPermission("mineplusgun.admin")) {
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " give <weapon> [player]");
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " reload");
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " stats");
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " debug <hitbox|trace>");
        }
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " hud" + ChatColor.GRAY + " - toggle the ammo HUD");
    }

    private void shop(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can open the shop.");
            return;
        }
        if (!services.shopService().useAllowed(player)) {
            player.sendMessage(ChatColor.RED + "You do not have permission to use the shop.");
            return;
        }
        ShopGui.open(plugin, services, player, 0);
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mineplusgun.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " give <weapon> [player]");
            return;
        }
        WeaponDefinition weapon = services.registry().get(args[1]);
        if (weapon == null) {
            sender.sendMessage(ChatColor.RED + "Unknown weapon '" + args[1] + "'.");
            return;
        }
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            target = null;
        }
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Target player not found.");
            return;
        }
        ItemStack stack = services.itemFactory().create(weapon);
        target.getInventory().addItem(stack);
        sender.sendMessage(ChatColor.GREEN + "Gave " + weapon.displayName() + ChatColor.GREEN + " to "
                + target.getName() + ".");
    }

    private void hud(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players have a HUD.");
            return;
        }
        if (!player.hasPermission("mineplusgun.hud")) {
            player.sendMessage(ChatColor.RED + "You do not have permission.");
            return;
        }
        boolean enabled = services.ammoHud().toggle(player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "Ammo HUD " + (enabled ? "enabled" : "disabled") + ".");
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("mineplusgun.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return;
        }
        try {
            reloadAction.run();
            sender.sendMessage(ChatColor.GREEN + "MineplusGun configuration reloaded ("
                    + services.registry().size() + " weapons).");
        } catch (Exception exception) {
            sender.sendMessage(ChatColor.RED + "Reload failed: " + exception.getMessage());
        }
    }

    private void stats(CommandSender sender) {
        if (!sender.hasPermission("mineplusgun.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "MineplusGun stats:");
        sender.sendMessage(ChatColor.YELLOW + "Weapons: " + ChatColor.WHITE + services.registry().size());
        if (services.runtime() != null) {
            sender.sendMessage(ChatColor.YELLOW + "Shooters: " + ChatColor.WHITE + services.runtime().activeSessions()
                    + ChatColor.YELLOW + "  Bullets: " + ChatColor.WHITE + services.runtime().activeBullets()
                    + ChatColor.YELLOW + "  Grenades: " + ChatColor.WHITE + services.runtime().activeGrenades());
            sender.sendMessage(ChatColor.YELLOW + "Shots: " + ChatColor.WHITE + services.runtime().totalShots()
                    + ChatColor.YELLOW + "  Hits: " + ChatColor.WHITE + services.runtime().totalHits()
                    + ChatColor.YELLOW + "  Instant traces: " + ChatColor.WHITE
                    + services.runtime().instantTraces());
        }
    }

    private void debug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mineplusgun.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can run ballistics diagnostics.");
            return;
        }
        if (services.runtime() == null) {
            sender.sendMessage(ChatColor.RED + "Combat runtime is not running.");
            return;
        }
        String mode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "trace";
        player.sendMessage(ChatColor.GOLD + "Ballistics debug (" + mode + "):");
        player.sendMessage(ChatColor.YELLOW + "Active bullets: " + ChatColor.WHITE
                + services.runtime().activeBullets() + ChatColor.YELLOW + "  Instant traces: " + ChatColor.WHITE
                + services.runtime().instantTraces());
        WeaponDefinition held = services.runtime().resolve(player.getInventory().getItemInMainHand());
        if (held == null) {
            player.sendMessage(ChatColor.GRAY + "No weapon in hand.");
            return;
        }
        player.sendMessage(ChatColor.YELLOW + held.id() + " velocity=" + held.velocity()
                + " gravity=" + held.gravity() + " drag=" + held.drag()
                + " penetration=" + held.penetration() + " spread=" + held.spreadDegrees());
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            completions.add("shop");
            completions.add("hud");
            if (sender.hasPermission("mineplusgun.admin")) {
                completions.add("give");
                completions.add("reload");
                completions.add("stats");
                completions.add("debug");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (WeaponDefinition weapon : services.registry().all()) {
                completions.add(weapon.id());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                completions.add(player.getName());
            }
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        completions.removeIf(value -> !value.toLowerCase(Locale.ROOT).startsWith(last));
        return completions;
    }
}
