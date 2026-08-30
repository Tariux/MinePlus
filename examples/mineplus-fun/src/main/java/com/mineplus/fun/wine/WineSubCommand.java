package com.mineplus.fun.wine;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.virtual.VirtualBlockPlacementHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.joml.Quaternionf;

/**
 * {@code /wine <place [variant]|flight|remove|clear|status>} — place, lay out,
 * and inspect texel-baked wine bottles.
 *
 * <p>Placement is rotation-aware through the Core's snapped placement rotation,
 * exactly like the Gear and Cannon features. {@code /wine flight} is the
 * showcase: it lays out one bottle of every {@link WineVariant} in a row on the
 * surface being looked at, perpendicular to the player's facing, each bottle
 * rotated toward the player — a winery tasting flight for comparing the texel
 * bakes of different sprites side by side. Verify individual bakes with
 * {@code /mineplus model info <key>-wine}: it reports the texel grid histogram,
 * palette usage, merged plate count, and the per-model budget verdict.
 */
public final class WineSubCommand implements SubCommand {

    private static final int FLIGHT_SPACING = 2;
    private static final int FLIGHT_CLEAR_RADIUS = 24;

    private final PluginContext context;

    public WineSubCommand(PluginContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "wine";
    }

    @Override
    public String description() {
        return "Place, compare, and inspect texel-baked wine bottles.";
    }

    @Override
    public String usage() {
        return "/wine <place [variant]|flight|remove|clear|status> (variants: "
                + WineVariant.keyList() + ")";
    }

    @Override
    public String permission() {
        return "mineplusfun.admin.wine";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use wine controls.");
            return true;
        }

        if (args.length < 1) {
            return false;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "place" -> {
                WineVariant variant = WineVariant.STRAD;
                if (args.length >= 2) {
                    variant = WineVariant.byKey(args[1]);
                    if (variant == null) {
                        player.sendMessage(ChatColor.RED + "Unknown wine '" + args[1]
                                + "'. Variants: " + WineVariant.keyList());
                        return true;
                    }
                }
                return placeSingle(player, variant);
            }
            case "flight" -> {
                return placeFlight(player);
            }
            case "remove" -> {
                return removeLooked(player);
            }
            case "clear" -> {
                return clearNearby(player);
            }
            case "status" -> {
                return printStatus(player);
            }
            default -> {
                return false;
            }
        }
    }

    private boolean placeSingle(Player player, WineVariant variant) {
        var placement = VirtualBlockPlacementHelper.getPlacementData(player, 6.0);
        if (placement == null) {
            player.sendMessage(ChatColor.RED + "Look at a nearby block face to place the Wine Bottle.");
            return true;
        }

        MultiBlockInstance created = context.infrastructureApi().createMultiBlock(
                variant.typeId(),
                placement.location(),
                player.getUniqueId(),
                player.getUniqueId(),
                placement.globalRotation()
        );
        if (created == null) {
            player.sendMessage(ChatColor.RED + "Failed to create Wine Bottle at target location.");
            return true;
        }
        if (!context.infrastructureApi().placeMultiBlock(created.id(), player)) {
            player.sendMessage(ChatColor.RED + "Failed to place Wine Bottle at target location.");
            return true;
        }
        player.sendMessage(ChatColor.GREEN + variant.displayName() + " placed with id " + created.id()
                + ChatColor.GRAY + " — pixel art reconstructed from vanilla palette blocks."
                + " Check /mineplus model info " + variant.key() + "-wine for the bake report.");
        return true;
    }

    /**
     * Lays out the tasting flight: one bottle per variant in a row on the looked-at
     * surface, perpendicular to the player's snapped facing, all rotated toward the
     * player. The row is centered on the placement target with
     * {@value #FLIGHT_SPACING}-block spacing so the barrier cells of neighbouring
     * bottles never touch. Occupied cells simply fail to place (the Core's
     * insufficient-space path) and are reported.
     */
    private boolean placeFlight(Player player) {
        var placement = VirtualBlockPlacementHelper.getPlacementData(player, 6.0);
        if (placement == null) {
            player.sendMessage(ChatColor.RED + "Look at a nearby surface to lay out the tasting flight.");
            return true;
        }
        if (placement.attachedFace() != BlockFace.UP) {
            player.sendMessage(ChatColor.RED + "Tasting flights are laid out on the ground or a table top"
                    + " — look at an upward-facing surface.");
            return true;
        }

        float snappedYaw = Math.round(player.getLocation().getYaw() / 90.0f) * 90.0f;
        boolean rowAlongX = Math.abs(snappedYaw % 180.0f) < 1.0f;

        WineVariant[] lineup = WineVariant.values();
        int placed = 0;
        List<String> skipped = new ArrayList<>();
        Location center = placement.location();
        for (int i = 0; i < lineup.length; i++) {
            int offset = (i - (lineup.length - 1) / 2) * FLIGHT_SPACING;
            Location spot = center.clone().add(rowAlongX ? offset : 0, 0, rowAlongX ? 0 : offset);
            MultiBlockInstance created = context.infrastructureApi().createMultiBlock(
                    lineup[i].typeId(),
                    spot,
                    player.getUniqueId(),
                    player.getUniqueId(),
                    new Quaternionf(placement.globalRotation())
            );
            if (created == null || !context.infrastructureApi().placeMultiBlock(created.id(), player)) {
                skipped.add(lineup[i].displayName());
                continue;
            }
            placed++;
        }

        player.sendMessage(ChatColor.GREEN + "Tasting flight laid out: " + placed + "/" + lineup.length
                + " bottle(s), each reconstructed from its own 16x16 sprite.");
        if (!skipped.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "Skipped (no space): " + String.join(", ", skipped));
        }
        player.sendMessage(ChatColor.GRAY + "Compare the bakes with /mineplus model info <"
                + WineVariant.keyList() + ">.");
        return true;
    }

    private boolean removeLooked(Player player) {
        MultiBlockInstance looked = null;
        for (WineVariant variant : WineVariant.values()) {
            looked = context.moduleSupport().resolveLooked(player, 6, variant.typeId());
            if (looked != null) {
                break;
            }
        }
        if (looked == null) {
            player.sendMessage(ChatColor.RED + "Look at a Wine Bottle to remove it.");
            return true;
        }
        WineVariant variant = WineVariant.byTypeId(looked.typeId());
        boolean removed = context.infrastructureApi().removeBlock(looked.id(), player, true);
        player.sendMessage(removed
                ? ChatColor.GREEN + (variant == null ? "Wine Bottle" : variant.displayName()) + " removed."
                : ChatColor.RED + "Failed to remove Wine Bottle.");
        return true;
    }

    /**
     * Removes every wine bottle within {@value #FLIGHT_CLEAR_RADIUS} blocks of the
     * player. The Core's location index is keyed by world-less coordinates, so the
     * distance filter is coordinate-only — acceptable for an admin showcase
     * command on servers that do not mirror wine displays across worlds.
     */
    private boolean clearNearby(Player player) {
        Location origin = player.getLocation();
        List<MultiBlockInstance> targets = new ArrayList<>();
        for (MultiBlockInstance instance : List.copyOf(
                context.basicInfrastructureApi().getLoadedInstances())) {
            if (WineVariant.byTypeId(instance.typeId()) == null) {
                continue;
            }
            double dx = instance.coordinate().x() - origin.getX();
            double dy = instance.coordinate().y() - origin.getY();
            double dz = instance.coordinate().z() - origin.getZ();
            if (dx * dx + dy * dy + dz * dz > (double) FLIGHT_CLEAR_RADIUS * FLIGHT_CLEAR_RADIUS) {
                continue;
            }
            targets.add(instance);
        }

        if (targets.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "No Wine Bottles within "
                    + FLIGHT_CLEAR_RADIUS + " blocks.");
            return true;
        }
        int removed = 0;
        for (MultiBlockInstance instance : targets) {
            if (context.infrastructureApi().removeBlock(instance.id(), player, true)) {
                removed++;
            }
        }
        player.sendMessage(ChatColor.GREEN + "Removed " + removed + "/" + targets.size()
                + " Wine Bottle(s) within " + FLIGHT_CLEAR_RADIUS + " blocks.");
        return true;
    }

    private boolean printStatus(Player player) {
        EnumMap<WineVariant, List<MultiBlockInstance>> byVariant = new EnumMap<>(WineVariant.class);
        for (MultiBlockInstance instance : context.basicInfrastructureApi().getLoadedInstances()) {
            WineVariant variant = WineVariant.byTypeId(instance.typeId());
            if (variant == null) {
                continue;
            }
            byVariant.computeIfAbsent(variant, ignored -> new ArrayList<>()).add(instance);
        }

        player.sendMessage(ChatColor.GOLD + "Wine Bottles:");
        boolean any = false;
        for (WineVariant variant : WineVariant.values()) {
            List<MultiBlockInstance> instances = byVariant.get(variant);
            if (instances == null || instances.isEmpty()) {
                continue;
            }
            any = true;
            player.sendMessage(ChatColor.YELLOW + variant.displayName() + ":");
            for (MultiBlockInstance instance : instances) {
                player.sendMessage(ChatColor.GRAY + "- " + instance.id()
                        + ChatColor.DARK_GRAY + " @" + instance.coordinate().x()
                        + "," + instance.coordinate().y()
                        + "," + instance.coordinate().z());
            }
        }
        if (!any) {
            player.sendMessage(ChatColor.GRAY + "- none placed");
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0],
                    List.of("place", "flight", "remove", "clear", "status"), completions);
            Collections.sort(completions);
            return completions;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("place")) {
            List<String> completions = new ArrayList<>();
            List<String> variants = new ArrayList<>();
            for (WineVariant variant : WineVariant.values()) {
                variants.add(variant.key());
            }
            StringUtil.copyPartialMatches(args[1], variants, completions);
            Collections.sort(completions);
            return completions;
        }

        return Collections.emptyList();
    }
}
