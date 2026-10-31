package com.mineplus.mobfun;

import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.infrastructure.mob.BehaviorFireResult;
import com.mineplus.infrastructure.mob.MobBehaviorApi;
import com.mineplus.infrastructure.mob.custom.CustomMobApi;
import com.mineplus.infrastructure.mob.custom.CustomMobDefinition;
import com.mineplus.infrastructure.mob.custom.CustomMobModel;
import com.mineplus.pack.PackApi;
import com.mineplus.pack.compile.PackArtifact;
import java.io.File;
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
 *                                             and the custom mobs' presentation report
 * /mobfun &lt;id&gt; [on|off|toggle]              - enable/disable one showcase
 * /mobfun spawn &lt;id&gt; [force]                - spawn the showcase mob in front of you and
 *                                             force-fire its actions immediately
 * /mobfun fire &lt;id&gt; [force]                 - force-fire on the nearest mob of that type
 * /mobfun rig &lt;id&gt; [attack]                  - why this custom mob looks the way it does
 *                                             (rig, pose, isolation, pack), optionally playing
 *                                             its authored attack clip on the nearest one
 * /mobfun pack [push]                        - the resource-pack facts a missing texture turns
 *                                             on: artifact, representation, per-player state
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
    private final List<MobFunShowcaseEntry> showcases;
    private final Set<UUID> spawnedTestMobs = new HashSet<>();

    public MobFunSubCommand(MobFunPlugin plugin, List<MobFunShowcaseEntry> showcases) {
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
                + " raiding witches, pilgrim villagers, Evil Pig and Villager Soldier custom mobs)";
    }

    @Override
    public String usage() {
        return "/mobfun status | /mobfun <id> [on|off|toggle] | /mobfun spawn <id> [force]"
                + " | /mobfun fire <id> [force] | /mobfun rig <id> [attack] | /mobfun pack [push]"
                + " | /mobfun despawn";
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
            case "rig" -> {
                if (args.length >= 2) {
                    return reportRig(sender, args[1], args.length >= 3 && args[2].equalsIgnoreCase("attack"));
                }
                sender.sendMessage(ChatColor.RED + "Usage: /mobfun rig <" + customIdList() + "> [attack]");
                return true;
            }
            case "pack" -> {
                return reportPack(sender, args.length >= 2 && args[1].equalsIgnoreCase("push"));
            }
            default -> {
                return toggleShowcase(sender, args[0], args);
            }
        }
    }

    private boolean toggleShowcase(CommandSender sender, String idToken, String[] args) {
        MobFunShowcaseEntry showcase = findShowcase(idToken);
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
        MobFunShowcaseEntry showcase = findShowcase(idToken);
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
        LivingEntity entity = showcase.spawn(spawnLocation(player));
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
        MobFunShowcaseEntry showcase = findShowcase(idToken);
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

    /** A safe spot two blocks in front of the player, on the surface if needed. */
    private Location spawnLocation(Player player) {
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
        return target.getLocation().add(0.5, 0, 0.5);
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
        for (MobFunShowcaseEntry showcase : showcases) {
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
            sender.sendMessage(ChatColor.DARK_GREEN + "Custom mob presentation (rig / AI / pack):");
            sendPresentationStatus(sender);
        }
        sender.sendMessage(ChatColor.DARK_GREEN + "Test bench: " + ChatColor.WHITE
                + "/mobfun spawn <id> [force], /mobfun fire <id> [force], /mobfun rig <id> [attack],"
                + " /mobfun pack [push], /mobfun despawn");
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

    /**
     * {@code /mobfun rig <id> [attack]} — the presentation read-out for one
     * custom mob: what the definition asks for, what the Core could actually
     * present, and the live pose of each mob. "It looks like a plain villager"
     * has several very different causes (pack not delivered, no model asset,
     * rig cap, render toggle, isolation failure) and this separates them in one
     * command.
     */
    private boolean reportRig(CommandSender sender, String idToken, boolean attack) {
        MobFunShowcaseEntry showcase = findShowcase(idToken);
        if (showcase == null) {
            sender.sendMessage(ChatColor.RED + "Unknown showcase '" + idToken + "'.");
            return true;
        }
        if (!(showcase instanceof CustomMobShowcase custom)) {
            sender.sendMessage(ChatColor.RED + "'" + showcase.id() + "' is a behavior showcase"
                    + " (a profile on a vanilla mob); it has no rig. Custom mobs: " + customIdList() + ".");
            return true;
        }
        CustomMobDefinition definition = custom.definition();
        if (definition == null) {
            sender.sendMessage(ChatColor.RED + showcase.label() + " is not registered (it is OFF,"
                    + " its model failed to load, or CUSTOM_MOBS.ENABLED is false).");
            return true;
        }
        CustomMobApi api = custom.api();
        sender.sendMessage(ChatColor.DARK_GREEN + definition.id() + " — " + definition.displayName());

        CustomMobModel model = definition.model();
        sender.sendMessage(ChatColor.DARK_GREEN + "Definition: " + ChatColor.WHITE
                + definition.baseType().name().toLowerCase(Locale.ROOT) + " base, visual "
                + definition.visual() + ", hideBase=" + definition.hideBaseWhenRigged()
                + ", " + (model == null ? "no model" : model.parts().size() + " part(s), "
                + model.animations().size() + " clip(s)")
                + ", " + definition.attributes().overrideCount() + " attribute override(s)"
                + (definition.keepsVanillaGoals()
                ? " (vanilla goals of " + definition.goalDonor() + " kept)" : " (isolated)"));
        if (attack && sender instanceof Player player) {
            LivingEntity mob = nearestLiveMobs(player, api.liveMobs(definition.id()), 1).stream()
                    .findFirst().orElse(null);
            if (mob == null) {
                sender.sendMessage(ChatColor.YELLOW + "No live " + definition.path()
                        + " to swing with; /mobfun spawn " + showcase.id() + " first.");
            } else {
                boolean playing = api.playAttack(mob);
                sender.sendMessage((playing ? ChatColor.GREEN : ChatColor.RED)
                        + "Attack clip " + (playing ? "started on " : "refused for ")
                        + shortId(mob.getUniqueId()) + (playing ? "."
                        : " (no authored animation.attack, or no rig)."));
            }
            return true;
        }

        String failure = api.rigFailure(definition.id());
        sender.sendMessage(ChatColor.DARK_GREEN + "Rig: "
                + (failure == null ? ChatColor.WHITE : ChatColor.RED) + (failure == null
                ? api.rigStatus(definition.id())
                : "NOT presenting it, because " + failure));
        File overrides = overridesFile(definition.path());
        sender.sendMessage(ChatColor.DARK_GREEN + "Overrides: " + ChatColor.WHITE + (overrides == null
                ? "unknown (Core data folder unavailable)"
                : overrides.isFile() ? overrides.getName() + " (authored corrections apply)"
                : "none (authored defaults apply; create " + relative(overrides) + " to correct a rig)"));
        List<LivingEntity> live = api.liveMobs(definition.id());
        if (live.isEmpty()) {
            sender.sendMessage(ChatColor.DARK_GREEN + "Live mobs: " + ChatColor.WHITE + "none");
            return true;
        }
        sender.sendMessage(ChatColor.DARK_GREEN + "Live mobs (" + live.size() + "):");
        int shown = 0;
        for (LivingEntity mob : live) {
            if (shown++ >= STATUS_LINES) {
                sender.sendMessage(ChatColor.GRAY + "  ... " + (live.size() - shown + 1) + " more");
                break;
            }
            String pose = api.poseSnapshot(mob);
            String isolation = api.isolationLabel(mob);
            sender.sendMessage(ChatColor.WHITE + "  " + shortId(mob.getUniqueId())
                    + " at " + describe(mob.getLocation())
                    + " | AI: " + (isolation == null ? "never applied" : isolation)
                    + " | pose: " + (pose == null ? "no rig" : pose)
                    + (mob.isInvisible() ? " | base hidden" : " | base visible"));
        }
        return true;
    }

    /** {@code /mobfun pack [push]} — the artifact and delivery facts a texture depends on. */
    private boolean reportPack(CommandSender sender, boolean push) {
        PackApi pack = plugin.context() == null ? null : plugin.context().packApi();
        if (pack == null || !pack.isAvailable()) {
            sender.sendMessage(ChatColor.RED + "The resource pack subsystem is unavailable (PACK.ENABLED"
                    + " is false). Custom mob rigs and every pack item model fall back to vanilla.");
            return true;
        }
        if (push) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can receive a pack push.");
                return true;
            }
            boolean sent = pack.deliverPack(player);
            sender.sendMessage((sent ? ChatColor.GREEN : ChatColor.RED) + "Pack "
                    + (sent ? "pushed to your client — accept the prompt, then F3+T if it was already open."
                    : "could not be pushed (no deliverable URL; check PACK.DELIVERY)."));
            return true;
        }
        PackArtifact artifact = pack.currentArtifact();
        sender.sendMessage(ChatColor.DARK_GREEN + "Pack: " + ChatColor.WHITE + (artifact == null
                ? "nothing compiled yet"
                : artifact.artifactName() + ", " + artifact.assetCount() + " assets, "
                        + artifact.byteSize() + " bytes, pack_format " + artifact.packFormat()
                        + ", sha1 " + (artifact.sha1() == null ? "?" : artifact.sha1().substring(0, 10))));
        sender.sendMessage(ChatColor.DARK_GREEN + "Representation: " + ChatColor.WHITE
                + pack.appearanceMode() + " (item_model component: " + pack.usesModernItemModels()
                + ", generated display models: " + pack.supportsGeneratedModelDisplay() + ")");
        if (sender instanceof Player player) {
            String url = pack.deliveryUrl(player);
            sender.sendMessage(ChatColor.DARK_GREEN + "Your download URL: " + ChatColor.WHITE
                    + (url == null ? "none resolved" : url));
        }
        sender.sendMessage(ChatColor.DARK_GREEN + "Players: ");
        for (Player player : Bukkit.getOnlinePlayers()) {
            com.mineplus.pack.PlayerPackState state = pack.playerPackState(player.getUniqueId());
            sender.sendMessage(ChatColor.WHITE + "  " + player.getName() + ": "
                    + (state.hasPack() ? ChatColor.GREEN : ChatColor.RED) + state
                    + (state.hasPack() ? "" : ChatColor.GRAY + " (pack-dependent visuals show vanilla)"));
        }
        sender.sendMessage(ChatColor.GRAY + "A mob that renders as its vanilla base with a player who is not"
                + " APPLIED has an undelivered pack, not a broken model. /mineplus pack verify proves the"
                + " endpoint; /mobfun rig <id> proves the rig.");
        return true;
    }

    /** The custom-mob lines {@code /mobfun status} adds on top of the behavior report. */
    private void sendPresentationStatus(CommandSender sender) {
        boolean any = false;
        for (MobFunShowcaseEntry showcase : showcases) {
            if (showcase instanceof CustomMobShowcase custom && custom.definition() != null) {
                any = true;
                CustomMobApi api = custom.api();
                CustomMobDefinition definition = custom.definition();
                String failure = api.rigFailure(definition.id());
                int live = api.liveCount(definition.id());
                String isolation = null;
                for (LivingEntity mob : api.liveMobs(definition.id())) {
                    isolation = api.isolationLabel(mob);
                    break;
                }
                sender.sendMessage(ChatColor.WHITE + "  " + definition.path()
                        + ": " + definition.visual() + ", " + live + " live"
                        + (isolation == null ? "" : ", AI " + isolation)
                        + (failure == null ? "" : ", " + ChatColor.RED + "rig off: " + failure));
            }
        }
        if (!any) {
            sender.sendMessage(ChatColor.GRAY + "  no custom mob registered (CUSTOM_MOBS.ENABLED,"
                    + " or the module's custom showcases are off)");
        }
    }

    private static List<LivingEntity> nearestLiveMobs(Player player, List<LivingEntity> mobs, int limit) {
        List<LivingEntity> sorted = new ArrayList<>(mobs);
        Location from = player.getLocation();
        sorted.sort(java.util.Comparator.comparingDouble(mob -> mob.getLocation().distanceSquared(from)));
        return sorted.size() <= limit ? sorted : sorted.subList(0, limit);
    }

    private File overridesFile(String idPath) {
        org.bukkit.plugin.Plugin core = Bukkit.getPluginManager().getPlugin("Mineplus");
        if (core == null) {
            return null;
        }
        return new File(new File(core.getDataFolder(), "mobs"), idPath + ".model.json");
    }

    private String relative(File file) {
        return "plugins/Mineplus/mobs/" + file.getName();
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String describe(Location location) {
        return location.getWorld().getName() + " " + location.getBlockX() + "," + location.getBlockY()
                + "," + location.getBlockZ();
    }

    private MobFunShowcaseEntry findShowcase(String token) {
        for (MobFunShowcaseEntry showcase : showcases) {
            if (showcase.id().equalsIgnoreCase(token)) {
                return showcase;
            }
        }
        return null;
    }

    private String idList() {
        StringBuilder sb = new StringBuilder();
        for (MobFunShowcaseEntry showcase : showcases) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(showcase.id());
        }
        return sb.toString();
    }

    /** The showcase tokens that actually own a rig (for {@code /mobfun rig}). */
    private String customIdList() {
        StringBuilder sb = new StringBuilder();
        for (MobFunShowcaseEntry showcase : showcases) {
            if (!(showcase instanceof CustomMobShowcase)) {
                continue;
            }
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
            completions.add("rig");
            completions.add("pack");
            for (MobFunShowcaseEntry showcase : showcases) {
                completions.add(showcase.id());
            }
            return filter(completions, args[0]);
        }
        if (args[0].equalsIgnoreCase("rig") && args.length == 2) {
            for (MobFunShowcaseEntry showcase : showcases) {
                if (showcase instanceof CustomMobShowcase) {
                    completions.add(showcase.id());
                }
            }
            completions.add("attack");
            return filter(completions, args[1]);
        }
        if (args[0].equalsIgnoreCase("rig") && args.length == 3) {
            completions.add("attack");
            return filter(completions, args[2]);
        }
        if (args[0].equalsIgnoreCase("pack") && args.length == 2) {
            completions.add("push");
            return filter(completions, args[1]);
        }
        if ((args[0].equalsIgnoreCase("spawn") || args[0].equalsIgnoreCase("fire")) && args.length == 2) {
            for (MobFunShowcaseEntry showcase : showcases) {
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
