package com.mineplus.mobfun;

import com.mineplus.infrastructure.fx.ParticleFx;
import com.mineplus.infrastructure.fx.Sounds;
import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import com.mineplus.infrastructure.mob.navigation.GroundProbe;
import com.mineplus.infrastructure.mob.navigation.MobNavigator;
import com.mineplus.util.DebugLogger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.CaveSpider;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.SkeletonHorse;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Stray;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * The Necromancer Witch: she fields a small mixed horde against nearby
 * villagers, and she fights back when a golem comes for her.
 *
 * <p>How a raid plays out:
 * <ul>
 *   <li><b>Adaptive raid space</b> - before anything spawns, the area is
 *       measured: valid feet spots are searched outward over rings 1 to 3
 *       around the witch using the Core's ground rule ({@link
 *       GroundProbe#isCampGround} - open, dry, solid non-tree earth), sorted
 *       toward her prey and roomiest first, and each spot's open height is read
 *       so a 1-block jungle cave gap only ever receives a small mob. Dense
 *       canopy or broken terrain can starve a roll entirely instead of
 *       producing mobs stuck in leaves.</li>
 *   <li><b>Mixed, light horde</b> - one melee body, one flanker, one ranged
 *       shooter across her rolls (husk in deserts, strays in the cold,
 *       drowned in swamps, cave spiders flanking in jungles), none of which a
 *       golem can sweep with a single swing. Every fifth minion - when the
 *       ground allows - is a <b>skeleton horseman</b>: a tamed skeleton horse
 *       with an archer in the saddle, both charging the same victim.</li>
 *   <li><b>Spawned through the engine's budget</b> - every minion goes out
 *       through {@link BehaviorContext#trySpawn}, so summons are counted and
 *       capped by the same per-run limit as block edits and arrive with the
 *       reason the server's own cleanup needs to tell plugin mobs apart.</li>
 *   <li><b>One scan per roll</b> - prey, threat and her own surviving horde all
 *       come out of a single pass over the neighbourhood, and the same list
 *       re-targets the crew: three radius scans became one.</li>
 *   <li><b>Theirs to keep, confined to the raid</b> - a minion that follows its
 *       prey past {@link #LEASH_RADIUS} from the witch is walked home instead
 *       of allowed to drain into the world; a straggler's victim is somebody
 *       else's problem, and a horde that disperses is not a horde.</li>
 *   <li><b>Fights back, no spam</b> - when a golem (or a monster she did not
 *       summon) closes within 8 blocks, the witch switches to retaliation: she
 *       targets it and hurls potions (her vanilla AI does the throwing) while
 *       her crew keeps the distance. Summons themselves are gated by a
 *       per-witch PDC cooldown and a hard horde cap, so neither side can flood
 *       the field.</li>
 *   <li><b>Hide behind the horde</b> - after each summon, the witch takes a
 *       short speed surge and retreat hops away from the nearest real threat,
 *       ending up behind the screen she just raised. Her own minions are
 *       PDC-tagged and never count as threats.</li>
 * </ul>
 *
 * <p>No activity claim here, on purpose: this is the witch's only action, and
 * a claim re-runs the owner on every engine stagger, which would turn one
 * retaliation snarl into a per-storm packet of witch cackles. The PDC summon
 * cooldown paces the raids and the profile condition paces the retaliation.
 */
public final class SummonMinionHordeAction implements BehaviorAction {

    private static final int HORDE_CAP = 5;
    private static final int MINIONS_PER_SUMMON = 2;
    private static final int SPOT_SEARCH_RADIUS = 3;
    private static final long SUMMON_COOLDOWN_TICKS = 500L;
    private static final int HORSEMAN_EVERY = 5;
    private static final int THREAT_CLOSE_RADIUS = 8;
    private static final int RETREAT_HOPS = 4;
    private static final int RETREAT_HOP_DELAY_TICKS = 3;
    /** How far a minion may be dragged off before it is walked back to her. */
    private static final int LEASH_RADIUS = 24;
    /** The raid is read in one pass, so the pass has to reach past the leash. */
    private static final int SCAN_RADIUS = LEASH_RADIUS;
    private static final int SCAN_HEIGHT = 12;

    private final Plugin plugin;
    private final ParticleFx fx;
    private final NamespacedKey summonKey;
    private final NamespacedKey summonCooldownKey;
    private final NamespacedKey minionCounterKey;

    public SummonMinionHordeAction(Plugin plugin, ParticleFx fx) {
        this.plugin = plugin;
        this.fx = fx;
        this.summonKey = new NamespacedKey(plugin, "mobfun_summon");
        this.summonCooldownKey = new NamespacedKey(plugin, "mobfun_raid_cd");
        this.minionCounterKey = new NamespacedKey(plugin, "mobfun_minion_no");
    }

    @Override
    public String id() {
        return "summon_minions";
    }

    @Override
    public boolean execute(BehaviorContext context) {
        if (!(context.entity() instanceof Mob witch) || witch.isDead() || !witch.isValid()
                || !context.isChunkLoaded(context.footBlock())) {
            return false;
        }
        Raid raid = readRaid(witch);
        if (raid.victim() == null && raid.threat() == null) {
            DebugLogger.debug("[MobFun] necromancer %s stands down: no villager in sight and no"
                            + " golem/monster nearby (scanned=%d)", witch.getUniqueId(), raid.scanned());
            return false;
        }
        if (raid.threat() != null && raid.threatDistanceSquared() <= THREAT_CLOSE_RADIUS * THREAT_CLOSE_RADIUS) {
            DebugLogger.debug("[MobFun] necromancer %s retaliates against %s at %.1f blocks (no summons"
                    + " while the threat is this close)", witch.getUniqueId(), raid.threat().getType(),
                    Math.sqrt(raid.threatDistanceSquared()));
            retaliate(witch, raid.threat());
            retargetCrew(witch, raid.minions(), raid.threat());
            return true;
        }
        if (raid.victim() == null) {
            return false;
        }
        if (raid.minions().size() >= HORDE_CAP) {
            DebugLogger.debug("[MobFun] necromancer %s holds: horde cap %d reached (owned=%d)",
                    witch.getUniqueId(), HORDE_CAP, raid.minions().size());
            return false;
        }
        long now = context.world().getGameTime();
        Long cooldownUntil = witch.getPersistentDataContainer().get(summonCooldownKey, PersistentDataType.LONG);
        if (cooldownUntil != null && now < cooldownUntil) {
            DebugLogger.debug("[MobFun] necromancer %s holds: summon cooldown active (%d ticks left,"
                    + " prey found)", witch.getUniqueId(), cooldownUntil - now);
            retargetCrew(witch, raid.minions(), raid.victim());
            return false;
        }
        int summoned = summonScreen(context, witch, raid);
        if (summoned == 0) {
            DebugLogger.debug("[MobFun] necromancer %s found prey but no valid spawn spot in rings 1-%d",
                    witch.getUniqueId(), SPOT_SEARCH_RADIUS);
            return false;
        }
        DebugLogger.debug("[MobFun] necromancer %s summoned %d minion(s) against a %s at %.1f blocks"
                        + " (owned %d, cap %d)", witch.getUniqueId(), summoned, raid.victim().getType(),
                Math.sqrt(raid.victim().getLocation().distanceSquared(witch.getLocation())),
                raid.minions().size() + summoned, HORDE_CAP);
        witch.getPersistentDataContainer().set(summonCooldownKey, PersistentDataType.LONG,
                now + SUMMON_COOLDOWN_TICKS);
        Sounds.play(witch.getLocation().add(0, 1, 0), Sound.ENTITY_WITCH_CELEBRATE.name(), 1.0f, 1.2f);
        retargetCrew(witch, raid.minions(), raid.victim());
        startRetreat(witch, raid.threat() != null ? raid.threat().getLocation() : raid.victim().getLocation());
        return true;
    }

    /**
     * One pass over the neighbourhood: the witch's own surviving minions, her
     * prey, the closest real threat. The scan used to run twice per roll (once
     * to count the horde, once to re-target it); the list it produces now feeds
     * both.
     */
    private Raid readRaid(Mob witch) {
        List<Mob> minions = new ArrayList<>(HORDE_CAP);
        LivingEntity victim = null;
        LivingEntity threat = null;
        double threatDistance = Double.MAX_VALUE;
        int scanned = 0;
        for (Entity entity : witch.getNearbyEntities(SCAN_RADIUS, SCAN_HEIGHT, SCAN_RADIUS)) {
            scanned++;
            if (entity instanceof Mob mob && isOwnMinion(entity)) {
                minions.add(mob);
                continue;
            }
            double distance = entity.getLocation().distanceSquared(witch.getLocation());
            if (victim == null && entity instanceof Villager && witch.hasLineOfSight(entity)) {
                victim = (LivingEntity) entity;
            }
            boolean hostile = entity instanceof Monster || entity instanceof org.bukkit.entity.Golem;
            if (hostile && distance < threatDistance) {
                threat = (LivingEntity) entity;
                threatDistance = distance;
            }
        }
        return new Raid(minions, victim, threat, threatDistance, scanned);
    }

    /** What one scan knows about the raid in progress. */
    private record Raid(List<Mob> minions, LivingEntity victim, LivingEntity threat,
                        double threatDistanceSquared, int scanned) {
    }

    /**
     * The witch squares up and hurls potions at the golem herself (her vanilla
     * AI throws at her current target) while the horde holds.
     */
    private void retaliate(Mob witch, LivingEntity threat) {
        witch.setTarget(threat);
        Location center = witch.getLocation().add(0, 1, 0);
        Sounds.play(center, Sound.ENTITY_WITCH_THROW.name(), 1.0f, 1.0f);
        fx.burst(ParticleFx.Tier.AMBIENT, center, Particle.WITCH, 8, 0.3, 0.4, 0.3, 0.02);
        fx.burst(ParticleFx.Tier.AMBIENT, center, Particle.SMOKE, 5, 0.2, 0.3, 0.2, 0.01);
    }

    /** Spawns up to {@link #MINIONS_PER_SUMMON} minions between the witch and the victim. */
    private int summonScreen(BehaviorContext context, Mob witch, Raid raid) {
        Block foot = context.footBlock();
        Vector approach = raid.victim().getLocation().toVector()
                .subtract(witch.getLocation().toVector()).setY(0);
        approach = approach.lengthSquared() < 1.0E-4 ? new Vector(0, 0, 1) : approach.normalize();
        List<Block> spots = findSpots(foot, approach);
        List<Class<? extends Mob>> pool = poolFor(context.biome());
        boolean horsemanTurn = nextHorsemanTurn(witch);

        int summoned = 0;
        for (Block feet : spots) {
            if (summoned >= MINIONS_PER_SUMMON || raid.minions().size() + summoned >= HORDE_CAP) {
                break;
            }
            int clearance = clearanceOf(feet);
            Location spawnLocation = feet.getLocation().add(0.5, 0, 0.5);
            if (horsemanTurn && clearance >= 3
                    && raid.victim().getLocation().distance(witch.getLocation()) > 6) {
                if (spawnHorseman(context, spawnLocation, raid.victim()) != null) {
                    summoned++;
                    horsemanTurn = false;
                }
                continue;
            }
            Class<? extends Mob> type = pickType(pool, summoned, clearance);
            if (type == null) {
                continue;
            }
            Mob minion = spawnMinion(context, spawnLocation, type, raid.victim());
            if (minion != null) {
                summoned++;
            }
        }
        return summoned;
    }

    /**
     * One summon through the engine's spawn budget. The PDC tag is applied
     * inside the configurator, before the entity joins the world, so ownership
     * cannot be lost to a mob that despawned before a second write; the target
     * is set afterwards, because a mob with no world yet has no AI to aim.
     */
    private Mob spawnMinion(BehaviorContext context, Location at, Class<? extends Mob> type,
                            LivingEntity victim) {
        LivingEntity born = context.trySpawn(at, type, this::tagMinion);
        if (!(born instanceof Mob minion)) {
            return null;
        }
        minion.setTarget(victim);
        Location center = at.clone().add(0, 0.6, 0);
        Sounds.play(center, Sound.ENTITY_ZOMBIE_AMBIENT.name(), 0.8f, 0.7f);
        fx.burst(ParticleFx.Tier.AMBIENT, center, Particle.WITCH, 10, 0.3, 0.5, 0.3, 0.02);
        fx.burst(ParticleFx.Tier.AMBIENT, center, Particle.SMOKE, 6, 0.2, 0.4, 0.2, 0.01);
        return minion;
    }

    /**
     * Every {@link #HORSEMAN_EVERY}-th minion arrives mounted: a tamed skeleton
     * horse carrying a bow-wielding skeleton, both charging the same victim.
     * Requires a roomy spot - mounted archers need open ground. Both halves are
     * budgeted summons and both carry her mark, so a horseman counts as the two
     * mobs it is and never reads to her as a monster closing in.
     */
    private SkeletonHorse spawnHorseman(BehaviorContext context, Location at, LivingEntity victim) {
        LivingEntity born = context.trySpawn(at, SkeletonHorse.class, mount -> {
            tagMinion(mount);
            if (mount instanceof SkeletonHorse horse) {
                horse.setTamed(true);
                horse.setAdult();
            }
        });
        if (!(born instanceof SkeletonHorse mount)) {
            return null;
        }
        LivingEntity rider = context.trySpawn(at, Skeleton.class, this::tagMinion);
        if (rider instanceof Mob skeleton) {
            skeleton.setTarget(victim);
            mount.addPassenger(rider);
        }
        Location center = at.clone().add(0, 1, 0);
        Sounds.play(center, Sound.ENTITY_SKELETON_HORSE_AMBIENT.name(), 1.0f, 1.0f);
        fx.burst(ParticleFx.Tier.AMBIENT, center, Particle.WITCH, 10, 0.3, 0.5, 0.3, 0.02);
        return mount;
    }

    /**
     * Every {@link #HORSEMAN_EVERY}-th raid concludes with a mounted archer: a
     * per-witch PDC counter makes the cadence deterministic and persistent
     * across restarts.
     */
    private boolean nextHorsemanTurn(Mob witch) {
        int count = witch.getPersistentDataContainer()
                .getOrDefault(minionCounterKey, PersistentDataType.INTEGER, 0);
        count = count + 1;
        witch.getPersistentDataContainer().set(minionCounterKey, PersistentDataType.INTEGER, count);
        return count % HORSEMAN_EVERY == 0;
    }

    private void tagMinion(Entity minion) {
        minion.getPersistentDataContainer().set(summonKey, PersistentDataType.BYTE, (byte) 1);
    }

    private boolean isOwnMinion(Entity entity) {
        Byte tagged = entity.getPersistentDataContainer().get(summonKey, PersistentDataType.BYTE);
        return tagged != null && tagged == (byte) 1;
    }

    /**
     * Crew sync: the horde presses one victim together instead of chasing stale
     * targets - and a minion that has been dragged past the raid's edge is
     * walked home instead of lost to the world. Its assigned area is the witch;
     * its assigned goal is whoever she pointed it at.
     */
    private void retargetCrew(Mob witch, List<Mob> minions, LivingEntity victim) {
        for (Mob minion : minions) {
            if (minion.isDead() || !minion.isValid()) {
                continue;
            }
            if (minion.getLocation().distanceSquared(witch.getLocation())
                    > LEASH_RADIUS * LEASH_RADIUS) {
                minion.setTarget(null);
                MobNavigator.moveTo(minion, witch.getLocation(), MobNavigator.NORMAL_SPEED);
                continue;
            }
            if (victim != null && !victim.equals(minion.getTarget())) {
                minion.setTarget(victim);
            }
        }
    }

    /**
     * Valid spawn feet blocks in the rings around {@code foot}: the Core's
     * camp-ground rule (open, dry, solid non-leaf/non-log earth), ordered by
     * ring (near first), then toward {@code approach}, then roomiest first. The
     * height clearance of each spot is what makes dense jungle terrain work:
     * spots are measured, not assumed, and small mobs peel off into small
     * spaces.
     */
    private List<Block> findSpots(Block foot, Vector approach) {
        List<Block> found = new ArrayList<>();
        for (int ring = 1; ring <= SPOT_SEARCH_RADIUS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring || (dx == 0 && dz == 0)) {
                        continue;
                    }
                    Block feet = foot.getRelative(dx, 0, dz);
                    if (GroundProbe.isCampGround(feet)) {
                        found.add(feet);
                    }
                }
            }
        }
        found.sort(Comparator
                .comparingInt((Block block) -> Math.max(Math.abs(block.getX() - foot.getX()),
                        Math.abs(block.getZ() - foot.getZ())))
                .thenComparing((Block block) -> -orientationScore(block, foot, approach))
                .thenComparing((Block block) -> -clearanceOf(block)));
        return found;
    }

    private static double orientationScore(Block block, Block foot, Vector approach) {
        Vector offset = block.getLocation().toVector().subtract(foot.getLocation().toVector()).setY(0).normalize();
        return offset.dot(approach);
    }

    /** Open height above the spot: 3 means wide or mounted mobs fit. */
    private static int clearanceOf(Block feet) {
        if (!feet.getRelative(org.bukkit.block.BlockFace.UP).isPassable()) {
            return 1;
        }
        return feet.getRelative(org.bukkit.block.BlockFace.UP, 2).isPassable() ? 3 : 2;
    }

    /**
     * Biome-matched horde composition - a melee body, a flanker, an archer.
     * Desert sand summons husks, cold summons strays, swamps summons drowned,
     * jungles flank with cave spiders. Classes rather than entity types: the
     * engine's spawn budget is typed, and this is the mapping it wants.
     */
    private static List<Class<? extends Mob>> poolFor(org.bukkit.block.Biome biome) {
        String name = biome == null ? "" : biome.name();
        Class<? extends Mob> melee = Zombie.class;
        Class<? extends Mob> flanker = Spider.class;
        Class<? extends Mob> ranged = Skeleton.class;
        if (name.contains("DESERT") || name.contains("BADLANDS")) {
            melee = Husk.class;
        }
        if (name.contains("SWAMP") || name.contains("MANGROVE")) {
            melee = Drowned.class;
        }
        if (name.contains("SNOWY") || name.contains("FROZEN") || name.contains("TAIGA") || name.contains("GROVE")) {
            ranged = Stray.class;
        }
        if (name.contains("JUNGLE") || name.contains("BAMBOO")) {
            flanker = CaveSpider.class;
        }
        return List.of(melee, flanker, ranged);
    }

    /** Picks the pool entry at {@code index} that fits inside {@code clearance}. */
    private static Class<? extends Mob> pickType(List<Class<? extends Mob>> pool, int index, int clearance) {
        for (int offset = 0; offset < pool.size(); offset++) {
            Class<? extends Mob> candidate = pool.get((index + offset) % pool.size());
            if (minClearance(candidate) <= clearance) {
                return candidate;
            }
        }
        return null;
    }

    /** Spiders thread a one-block gap; anything upright needs a standing height. */
    private static int minClearance(Class<? extends Mob> type) {
        return Spider.class.isAssignableFrom(type) ? 1 : 2;
    }

    /**
     * The hide behind the raised screen: one bounded task does the speed surge
     * plus the few hops away from the threat center and retires itself, so a
     * retreat costs the scheduler one entry instead of one per hop.
     */
    private void startRetreat(Mob witch, Location threatCenter) {
        Vector fromThreat = witch.getLocation().toVector().subtract(threatCenter.toVector()).setY(0);
        Vector escape = fromThreat.lengthSquared() < 1.0E-4 ? new Vector(0, 0, 1) : fromThreat.normalize();
        witch.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 1, false, false, true));
        java.util.concurrent.atomic.AtomicInteger hopCount = new java.util.concurrent.atomic.AtomicInteger();
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int hop = hopCount.getAndIncrement();
            if (hop >= RETREAT_HOPS || witch.isDead() || !witch.isValid()) {
                task.cancel();
                return;
            }
            Vector push = escape.clone().multiply(0.42);
            witch.setVelocity(new Vector(push.getX(), 0.16, push.getZ()));
            fx.burst(ParticleFx.Tier.AMBIENT, witch.getLocation().add(0, 1, 0), Particle.SMOKE,
                    4, 0.2, 0.3, 0.2, 0.01);
        }, 6L, RETREAT_HOP_DELAY_TICKS);
    }
}
