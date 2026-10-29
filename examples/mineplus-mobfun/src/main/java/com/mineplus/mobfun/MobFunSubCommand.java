package com.mineplus.mobfun;

import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.infrastructure.mob.BehaviorFireResult;
import com.mineplus.infrastructure.mob.MobBehaviorApi;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * {@code /mobfun} — status, toggles, and a direct test bench for the behavior
 * showcases.
 *
 * <pre>
 * /mobfun status                            - engine settings + telemetry + showcase states,
 *                                             plus the engine's live journeys, crews and claims
 * /mobfun &lt;id&gt; [on|off|toggle]              - enable/disable one showcase
 * /mobfun spawn &lt;id&gt; [force]                - spawn the showcase mob in front of you and
 *                                             force-fire its actions immediately
 * /mobfun fire &lt;id&gt; [force]                 - force-fire on the nearest mob of that type
 * /mobfun despawn                           - remove test mobs spawned via /mobfun spawn
 * </pre>
 *
 * <p>Forcing bypasses the engine's odds gate only; by default profile
 * conditions still apply and the per-action report shows exactly why an
 * action did not fire (wrong ground, too bright, ...). Adding {@code force}
 * ignores conditions too.
 */
public final class MobFunSubCommand implements SubCommand {

    private static final double FIRE_SEARCH_RADIUS = 10.0;
    /** Status prints at most this many live journeys / crews (a village full of pilgrims is not a chat page). */
    private static final int STATUS_LINES = 12;

    private final MobFunPlugin plugin;
    private final List<MobFunShowcase> showcases;
    private final Set<UUID> spawnedTestMobs = new HashSet<>();

    public MobFunSubCommand(MobFunPlugin plugin, List<MobFunShowcase> showcases) {
        this.plugin = plugin;
        this.showcases = showcases;
    }

    @Override
    public String name() {
        return "mobfun";
    }

    @Override
    public String description() {
        return "MobFun behavior showcases (grazing cows/sheep, reforesting pigs, lamplighter villagers, florist bees,"
                + " raiding witches, pilgrim villagers)";
    }

    @Override
    public String usage() {
        return "/mobfun status | /mobfun <id> [on|off|toggle] | /mobfun spawn <id> [force]"
                + " | /mobfun fire <id> [force] | /mobfun despawn";
    }

    @Override
    public String permission() {
        return "mineplusmobfun.admin";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> {
                if (args.length >= 2) {
                    return spawn(sender, args[1], hasForceFlag(args));
                }
                sender.sendMessage(ChatColor.RED + "Usage: /mobfun spawn <" + idList() + "> [force]");
                return true;
            }
            case "fire" -> {
                if (args.length >= 2) {
                    return fire(sender, args[1], hasForceFlag(args));
                }
                sender.sendMessage(ChatColor.RED + "Usage: /mobfun fire <" + idList() + "> [force]");
                return true;
            }
            case "despawn" -> {
                return despawn(sender);
            }
            default -> {
                return toggleShowcase(sender, args[0], args);
            }
        }
    }

    private boolean toggleShowcase(CommandSender sender, String idToken, String[] args) {
        MobFunShowcase showcase = findShowcase(idToken);
        if (showcase == null) {
            sender.sendMessage(ChatColor.RED + "Unknown showcase '" + idToken + "'. Use " + idList()
                    + ", or the commands: spawn, fire, despawn.");
            return true;
        }

        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "toggle";
        boolean target;
        switch (action) {
            case "on" -> target = true;
            case "off" -> target = false;
            case "toggle" -> target = !showcase.isEnabled();
            default -> {
                sender.sendMessage(ChatColor.RED + "Use on, off, or toggle.");
                return true;
            }
        }

        if (target) {
            showcase.enable();
            sender.sendMessage(ChatColor.GREEN + showcase.label() + " enabled.");
        } else {
            showcase.disable();
            sender.sendMessage(ChatColor.YELLOW + showcase.label() + " disabled.");
        }
        return true;
    }

    /** {@code /mobfun spawn <id> [force]}: spawn in front of the player, force-fire, report. */
    private boolean spawn(CommandSender sender, String idToken, boolean ignoreConditions) {
        MobFunShowcase showcase = findShowcase(idToken);
        if (showcase == null) {
            sender.sendMessage(ChatColor.RED + "Unknown showcase '" + idToken + "'. Use " + idList() + ".");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can spawn test mobs.");
            return true;
        }
        if (!showcase.isEnabled()) {
            showcase.enable();
            sender.sendMessage(ChatColor.YELLOW + showcase.label() + " was off; auto-enabled for this test.");
        }
        LivingEntity entity = spawnInFront(player, showcase.entityType());
        if (entity == null) {
            sender.sendMessage(ChatColor.RED + "Failed to spawn a " + showcase.entityType().name()
                    .toLowerCase(Locale.ROOT) + ".");
            return true;
        }
        entity.setCustomName(ChatColor.AQUA + showcase.label() + ChatColor.GRAY + " (test)");
        entity.setCustomNameVisible(true);
        entity.setPersistent(true);
        spawnedTestMobs.add(entity.getUniqueId());

        sender.sendMessage(ChatColor.GREEN + "Spawned " + showcase.label() + " in front of you"
                + (ignoreConditions ? " (forcing actions, conditions ignored):" : " (forcing actions):"));
        reportFire(sender, entity, ignoreConditions);
        return true;
    }

    /** {@code /mobfun fire <id> [force]}: force-fire on the nearest matching mob. */
    private boolean fire(CommandSender sender, String idToken, boolean ignoreConditions) {
        MobFunShowcase showcase = findShowcase(idToken);
        if (showcase == null) {
            sender.sendMessage(ChatColor.RED + "Unknown showcase '" + idToken + "'. Use " + idList() + ".");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can target mobs.");
            return true;
        }
        if (!showcase.isEnabled()) {
            showcase.enable();
            sender.sendMessage(ChatColor.YELLOW + showcase.label() + " was off; auto-enabled for this test.");
        }
        LivingEntity target = nearestMob(player, showcase.entityType());
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "No " + showcase.entityType().name().toLowerCase(Locale.ROOT)
                    + " within " + (int) FIRE_SEARCH_RADIUS + " blocks. Use /mobfun spawn " + showcase.id() + ".");
            return true;
        }
        sender.sendMessage(ChatColor.GREEN + "Forcing " + showcase.label() + " actions on the nearest mob:");
        reportFire(sender, target, ignoreConditions);
        return true;
    }

    /** {@code /mobfun despawn}: remove every test mob this command spawned. */
    private boolean despawn(CommandSender sender) {
        int removed = 0;
        for (Iterator<UUID> iterator = spawnedTestMobs.iterator(); iterator.hasNext(); ) {
            Entity entity = Bukkit.getEntity(iterator.next());
            if (entity != null && entity.isValid()) {
                entity.remove();
                removed++;
            }
            iterator.remove();
        }
        sender.sendMessage(ChatColor.YELLOW + "Removed " + removed + " MobFun test mob(s).");
        return true;
    }

    /** Removes all tracked test mobs (also called on plugin disable). */
    public void purgeTestMobs() {
        for (UUID id : spawnedTestMobs) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
        }
        spawnedTestMobs.clear();
    }

    /** Forces one firing pass on the entity and prints a colored per-action report. */
    private void reportFire(CommandSender sender, LivingEntity entity, boolean ignoreConditions) {
        BehaviorFireResult result = plugin.context().mobBehaviorApi().forceFire(plugin, entity, ignoreConditions);
        if (result.attempts().isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No actions registered for " + entity.getType()
                    + " (showcase failed to enable?).");
            return;
        }
        boolean allBlocked = true;
        for (BehaviorFireResult.Attempt attempt : result.attempts()) {
            ChatColor color;
            String detail;
            switch (attempt.status()) {
                case FIRED -> {
                    color = ChatColor.GREEN;
                    detail = "";
                    allBlocked = false;
                }
                case DECLINED -> {
                    color = ChatColor.YELLOW;
                    detail = ChatColor.GRAY + " (context check failed: wrong ground/light/lighting spot?)";
                    allBlocked = false;
                }
                case CONDITION_FAILED -> {
                    color = ChatColor.RED;
                    detail = ChatColor.GRAY + " (blocked by profile conditions)";
                }
                default -> {
                    color = ChatColor.GRAY;
                    detail = "";
                }
            }
            sender.sendMessage("  " + color + attempt.status() + ChatColor.WHITE + " " + attempt.actionId() + detail);
        }
        if (allBlocked) {
            sender.sendMessage(ChatColor.GRAY + "Everything was blocked by profile conditions — move the mob to a"
                    + " matching spot (e.g. villager in the dark, cow in tall grass), or rerun with 'force'.");
        }
    }

    private LivingEntity spawnInFront(Player player, EntityType type) {
        World world = player.getWorld();
        Location base = player.getLocation();
        Vector direction = base.getDirection().setY(0);
        direction = direction.lengthSquared() < 1.0E-4 ? new Vector(0, 0, 1) : direction.normalize();
        Block target = base.clone().add(direction.multiply(2.0)).getBlock();
        if (!target.isPassable() || !target.getRelative(org.bukkit.block.BlockFace.UP).isPassable()) {
            target = world.getHighestBlockAt(target.getX(), target.getZ());
            if (!target.isPassable()) {
                target = target.getRelative(org.bukkit.block.BlockFace.UP);
            }
        }
        Entity spawned = world.spawnEntity(target.getLocation().add(0.5, 0, 0.5), type);
        return spawned instanceof LivingEntity living ? living : null;
    }

    private LivingEntity nearestMob(Player player, EntityType type) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(),
                FIRE_SEARCH_RADIUS, FIRE_SEARCH_RADIUS, FIRE_SEARCH_RADIUS)) {
            if (entity.getType() != type || !(entity instanceof LivingEntity living)
                    || living.isDead() || !living.isValid()) {
                continue;
            }
            double distance = entity.getLocation().distanceSquared(player.getLocation());
            if (distance < bestDistance) {
                best = living;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean hasForceFlag(String[] args) {
        for (int i = 2; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("force")) {
                return true;
            }
        }
        return false;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_GREEN + "MobFun showcases");
        for (MobFunShowcase showcase : showcases) {
            sender.sendMessage(ChatColor.GREEN + showcase.statusLine());
        }
        if (plugin.context() != null) {
            MobBehaviorApi mobApi = plugin.context().mobBehaviorApi();
            sender.sendMessage(ChatColor.DARK_GREEN + "Core mob behavior settings: "
                    + ChatColor.WHITE + mobApi.settings());
            sender.sendMessage(ChatColor.DARK_GREEN + "Engine telemetry:\n"
                    + ChatColor.WHITE + mobApi.telemetrySummary());
            sender.sendMessage(ChatColor.DARK_GREEN + "Pilgrim journeys (engine state):");
            sendLines(sender, mobApi.journeySnapshot(STATUS_LINES),
                    "  none active (the Core searches for a village for villagers outside one, on its"
                            + " structure-cache cooldown)");
            sender.sendMessage(ChatColor.DARK_GREEN + "Crews (engine state):");
            sendLines(sender, mobApi.crewSnapshot(STATUS_LINES), "  none marching");
            sender.sendMessage(ChatColor.DARK_GREEN + "Squads (formations and roles):");
            sendLines(sender, mobApi.squads().snapshot(STATUS_LINES), "  none shaped (crews walk plain formation points)");
            sender.sendMessage(ChatColor.DARK_GREEN + "Pipelines (engine state):");
            sendLines(sender, mobApi.pipelineSnapshot(STATUS_LINES),
                    "  none running (a witch starts one when she finds a village to raid)");
            sender.sendMessage(ChatColor.DARK_GREEN + "Activity claims: " + ChatColor.WHITE
                    + claimedMobs());
        }
        sender.sendMessage(ChatColor.DARK_GREEN + "Test bench: " + ChatColor.WHITE
                + "/mobfun spawn <id> [force], /mobfun fire <id> [force], /mobfun despawn");
    }

    private void sendLines(CommandSender sender, List<String> lines, String whenEmpty) {
        if (lines.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + whenEmpty);
            return;
        }
        for (String line : lines) {
            sender.sendMessage(ChatColor.WHITE + line);
        }
    }

    /**
     * Which test mobs the engine currently owns for a long-running scenario —
     * the read-out that shows arbitration working (a marching pilgrim is
     * claimed, so no other villager showcase can pull it away mid-journey).
     */
    private String claimedMobs() {
        MobBehaviorApi mobApi = plugin.context().mobBehaviorApi();
        int claimed = 0;
        for (UUID id : spawnedTestMobs) {
            if (Bukkit.getEntity(id) instanceof LivingEntity entity
                    && mobApi.activeActivity(entity) != null) {
                claimed++;
            }
        }
        return claimed + " of " + spawnedTestMobs.size() + " test mob(s)";
    }

    private MobFunShowcase findShowcase(String token) {
        for (MobFunShowcase showcase : showcases) {
            if (showcase.id().equalsIgnoreCase(token)) {
                return showcase;
            }
        }
        return null;
    }

    private String idList() {
        StringBuilder sb = new StringBuilder();
        for (MobFunShowcase showcase : showcases) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(showcase.id());
        }
        return sb.toString();
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            completions.add("status");
            completions.add("spawn");
            completions.add("fire");
            completions.add("despawn");
            for (MobFunShowcase showcase : showcases) {
                completions.add(showcase.id());
            }
            return filter(completions, args[0]);
        }
        if ((args[0].equalsIgnoreCase("spawn") || args[0].equalsIgnoreCase("fire")) && args.length == 2) {
            for (MobFunShowcase showcase : showcases) {
                completions.add(showcase.id());
            }
            return filter(completions, args[1]);
        }
        if ((args[0].equalsIgnoreCase("spawn") || args[0].equalsIgnoreCase("fire")) && args.length == 3) {
            completions.add("force");
            return filter(completions, args[2]);
        }
        if (args.length == 2 && !"status".equalsIgnoreCase(args[0])) {
            completions.add("on");
            completions.add("off");
            completions.add("toggle");
            return filter(completions, args[1]);
        }
        return completions;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
