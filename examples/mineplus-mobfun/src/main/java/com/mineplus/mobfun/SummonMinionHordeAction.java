package com.mineplus.mobfun;

import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import com.mineplus.util.DebugLogger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Golem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.SkeletonHorse;
import org.bukkit.entity.Villager;
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
 *       around the witch (passable, liquid-free, solid non-leaf/non-log
 *       ground), sorted toward her prey and roomiest first, and each spot's
 *       open height is read so a 1-block jungle cave gap only ever receives a
 *       small mob. Dense canopy or broken terrain can starve a roll entirely
 *       instead of producing mobs stuck in leaves.</li>
 *   <li><b>Mixed, light horde</b> - one melee body, one flanker, one ranged
 *       shooter across her rolls (husk in deserts, strays in the cold,
 *       drowned in swamps, cave spiders flanking in jungles), all of which a
 *       golem cannot sweep with a single swing. Every fifth minion - when the
 *       ground allows - is a <b>skeleton horseman</b>: a tamed skeleton horse
 *       with an archer in the saddle, both charging the same victim.</li>
 *   <li><b>Synced crew</b> - every summon re-targets the whole horde onto the
 *       same victim, so older minions and fresh arrivals fight as one pack.</li>
 *   <li><b>Fights back, no spam</b> - when a golem (or a monster she did not
 *       summon) closes within 8 blocks, the witch switches to retaliation:
 *       she targets it and hurls potions (her vanilla AI does the throwing)
 *       while her crew keeps the distance. Summons themselves are gated by a
 *       per-witch PDC cooldown and a hard horde cap, so neither side can
 *       flood the field.</li>
 *   <li><b>Hide behind the horde</b> - after each summon, the witch takes a
 *       short speed surge and retreat hops away from the nearest real threat,
 *       ending up behind the screen she just raised. Her own minions are
 *       PDC-tagged and never count as threats.</li>
 * </ul>
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

    private final Plugin plugin;
    private final NamespacedKey summonKey;
    private final NamespacedKey summonCooldownKey;
    private final NamespacedKey minionCounterKey;

    public SummonMinionHordeAction(Plugin plugin) {
        this.plugin = plugin;
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
        LivingEntity witch = context.entity();
        if (witch.isDead() || !witch.isValid() || !context.isChunkLoaded(context.footBlock())) {
            return false;
        }
        LivingEntity victim = null;
        LivingEntity threat = null;
        double threatDistance = Double.MAX_VALUE;
        int ownedMinions = 0;
        int nearbyCount = 0;
        for (Entity entity : witch.getNearbyEntities(16, 8, 16)) {
            nearbyCount++;
            if (isOwnMinion(entity)) {
                ownedMinions++;
                continue;
            }
            double distance = entity.getLocation().distanceSquared(witch.getLocation());
            if (victim == null && entity instanceof Villager && witch.hasLineOfSight(entity)) {
                victim = (LivingEntity) entity;
            }
            boolean hostile = entity instanceof Monster || entity instanceof Golem;
            if (hostile && distance < threatDistance) {
                threat = (LivingEntity) entity;
                threatDistance = distance;
            }
        }
        if (victim == null && threat == null) {
            DebugLogger.debug("[MobFun] necromancer %s stands down: no villager in sight and no"
                            + " golem/monster nearby (scanned=%d)", witch.getUniqueId(), nearbyCount);
            return false;
        }

        if (threat != null && threatDistance <= THREAT_CLOSE_RADIUS * THREAT_CLOSE_RADIUS) {
            DebugLogger.debug("[MobFun] necromancer %s retaliates against %s at %.1f blocks (no summons"
                    + " while the threat is this close)", witch.getUniqueId(), threat.getType(),
                    Math.sqrt(threatDistance));
            retaliate(context, witch, threat);
            return true;
        }
        if (victim == null) {
            return false;
        }
        if (ownedMinions >= HORDE_CAP) {
            DebugLogger.debug("[MobFun] necromancer %s holds: horde cap %d reached (owned=%d)",
                    witch.getUniqueId(), HORDE_CAP, ownedMinions);
            return false;
        }
        long now = context.world().getGameTime();
        Long cooldownUntil = witch.getPersistentDataContainer().get(summonCooldownKey, PersistentDataType.LONG);
        if (cooldownUntil != null && now < cooldownUntil) {
            DebugLogger.debug("[MobFun] necromancer %s holds: summon cooldown active (%d ticks left,"
                    + " prey found)", witch.getUniqueId(), cooldownUntil - now);
            return false;
        }
        int summoned = summonScreen(context, witch, victim, ownedMinions);
        if (summoned > 0) {
            DebugLogger.debug("[MobFun] necromancer %s summoned %d minion(s) against a %s at %.1f"
                    + " blocks (owned now %d)", witch.getUniqueId(), summoned, victim.getType(),
                    Math.sqrt(victim.getLocation().distanceSquared(witch.getLocation())),
                    ownedMinions + summoned);
            celebrate(context, witch);
            witch.getPersistentDataContainer().set(summonCooldownKey, PersistentDataType.LONG,
                    now + SUMMON_COOLDOWN_TICKS);
            syncCrew(witch, victim);
            scheduleRetreat(witch, threat != null ? threat.getLocation() : victim.getLocation());
            return true;
        }
        DebugLogger.debug("[MobFun] necromancer %s found prey but no valid spawn spot in rings 1-%d",
                witch.getUniqueId(), SPOT_SEARCH_RADIUS);
        return false;
    }

    /**
     * The witch squares up and hurls potions at the golem herself (her
     * vanilla AI throws at her current target) while the horde holds.
     */
    private void retaliate(BehaviorContext context, LivingEntity witch, LivingEntity threat) {
        if (witch instanceof Mob mob) {
            mob.setTarget(threat);
        }
        Location center = witch.getLocation().add(0, 1, 0);
        context.world().playSound(center, Sound.ENTITY_WITCH_THROW, 1.0f, 1.0f);
        context.world().spawnParticle(Particle.WITCH, center, 8, 0.3, 0.4, 0.3, 0.02);
        context.world().spawnParticle(Particle.SMOKE, center, 5, 0.2, 0.3, 0.2, 0.01);
    }

    /** Spawns up to {@link #MINIONS_PER_SUMMON} minions between the witch and the victim. */
    private int summonScreen(BehaviorContext context, LivingEntity witch, LivingEntity victim, int ownedMinions) {
        Block foot = context.footBlock();
        Vector approach = victim.getLocation().toVector().subtract(witch.getLocation().toVector()).setY(0);
        approach = approach.lengthSquared() < 1.0E-4 ? new Vector(0, 0, 1) : approach.normalize();
        List<Block> spots = findSpots(foot, approach);
        List<EntityType> pool = poolFor(context.biome());
        boolean horsemanTurn = nextHorsemanTurn(witch);

        int summoned = 0;
        for (Block feet : spots) {
            if (summoned >= MINIONS_PER_SUMMON || ownedMinions + summoned >= HORDE_CAP) {
                break;
            }
            int clearance = clearanceOf(feet);
            Location spawnLocation = feet.getLocation().add(0.5, 0, 0.5);
            if (horsemanTurn && clearance >= 2
                    && victim.getLocation().distance(witch.getLocation()) > 6) {
                if (spawnHorseman(context, spawnLocation, victim)) {
                    summoned++;
                    horsemanTurn = false;
                }
                continue;
            }
            EntityType type = pickType(pool, summoned, clearance);
            if (type == null) {
                continue;
            }
            Entity entity = context.world().spawnEntity(spawnLocation, type);
            if (!(entity instanceof Mob minion)) {
                continue;
            }
            tagMinion(minion);
            minion.setTarget(victim);
            celebrateSpawn(context, spawnLocation);
            summoned++;
        }
        return summoned;
    }

    /**
     * Every {@link #HORSEMAN_EVERY}-th minion arrives mounted: a tamed
     * skeleton horse carrying a bow-wielding skeleton, both charging the same
     * victim. Requires a roomy spot - mounted archers need open ground.
     */
    private boolean spawnHorseman(BehaviorContext context, Location spawnLocation, LivingEntity victim) {
        Entity horse = context.world().spawnEntity(spawnLocation, EntityType.SKELETON_HORSE);
        if (!(horse instanceof SkeletonHorse mount)) {
            return false;
        }
        mount.setTamed(true);
        mount.setAdult();
        Entity rider = context.world().spawnEntity(spawnLocation, EntityType.SKELETON);
        if (rider instanceof Mob skeleton) {
            skeleton.setTarget(victim);
        }
        tagMinion(mount);
        tagMinion(rider);
        mount.addPassenger(rider);
        celebrateSpawn(context, spawnLocation.clone().add(0, 1, 0));
        context.world().playSound(spawnLocation, Sound.ENTITY_SKELETON_HORSE_AMBIENT, 1.0f, 1.0f);
        return true;
    }

    /**
     * Every {@link #HORSEMAN_EVERY}-th raid concludes with a mounted
     * archer: a per-witch PDC counter makes the cadence deterministic and
     * persistent across restarts.
     */
    private boolean nextHorsemanTurn(LivingEntity witch) {
        int count = witch.getPersistentDataContainer()
                .getOrDefault(minionCounterKey, PersistentDataType.INTEGER, 0);
        count = count + 1;
        witch.getPersistentDataContainer().set(minionCounterKey, PersistentDataType.INTEGER, count);
        return count % HORSEMAN_EVERY == 0;
    }

    private void tagMinion(Entity minion) {
        minion.getPersistentDataContainer().set(summonKey, PersistentDataType.BYTE, (byte) 1);
    }

    /**
     * Crew sync: every minion the witch owns re-targets the same victim, so
     * the whole pack presses together instead of chasing stale targets.
     */
    private void syncCrew(LivingEntity witch, LivingEntity victim) {
        for (Entity entity : witch.getNearbyEntities(24, 12, 24)) {
            if (isOwnMinion(entity) && entity instanceof Mob minion && !minion.isDead()) {
                minion.setTarget(victim);
            }
        }
    }

    /**
     * Valid spawn feet blocks in the rings around {@code foot}: passable feet
     * block, solid non-leaf/non-log ground, no liquids, ordered by ring (near
     * first), then toward {@code approach}, then roomiest first. The height
     * clearance of each spot is what makes dense jungle terrain work: spots
     * are measured, not assumed, and small mobs peel off into small spaces.
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
                    if (isValidSpot(feet)) {
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

    private static boolean isValidSpot(Block feet) {
        if (!feet.isPassable() || feet.isLiquid()) {
            return false;
        }
        Block ground = feet.getRelative(BlockFace.DOWN);
        return ground.getType().isSolid()
                && !Tag.LEAVES.isTagged(ground.getType())
                && !Tag.LOGS.isTagged(ground.getType());
    }

    /** Open height above the spot: 3 means wide or mounted mobs fit. */
    private static int clearanceOf(Block feet) {
        if (!feet.getRelative(BlockFace.UP).isPassable()) {
            return 1;
        }
        return feet.getRelative(BlockFace.UP, 2).isPassable() ? 3 : 2;
    }

    /**
     * Biome-matched horde composition - a melee body, a flanker, an archer.
     * Desert sand summons husks, cold summons strays, swamps summons drowned,
     * jungles flank with cave spiders.
     */
    private static List<EntityType> poolFor(org.bukkit.block.Biome biome) {
        String name = biome == null ? "" : biome.name();
        EntityType melee = EntityType.ZOMBIE;
        EntityType flanker = EntityType.SPIDER;
        EntityType ranged = EntityType.SKELETON;
        if (name.contains("DESERT") || name.contains("BADLANDS")) {
            melee = EntityType.HUSK;
        }
        if (name.contains("SWAMP") || name.contains("MANGROVE")) {
            melee = EntityType.DROWNED;
        }
        if (name.contains("SNOWY") || name.contains("FROZEN") || name.contains("TAIGA") || name.contains("GROVE")) {
            ranged = EntityType.STRAY;
        }
        if (name.contains("JUNGLE") || name.contains("BAMBOO")) {
            flanker = EntityType.CAVE_SPIDER;
        }
        return List.of(melee, flanker, ranged);
    }

    /** Picks the pool entry at {@code index} that fits inside {@code clearance}. */
    private static EntityType pickType(List<EntityType> pool, int index, int clearance) {
        for (int offset = 0; offset < pool.size(); offset++) {
            EntityType candidate = pool.get((index + offset) % pool.size());
            if (minClearance(candidate) <= clearance) {
                return candidate;
            }
        }
        return null;
    }

    private static int minClearance(EntityType type) {
        return type == EntityType.CAVE_SPIDER ? 1 : 2;
    }

    private boolean isOwnMinion(Entity entity) {
        Byte tagged = entity.getPersistentDataContainer().get(summonKey, PersistentDataType.BYTE);
        return tagged != null && tagged == (byte) 1;
    }

    private int ownedMinionCount(LivingEntity witch) {
        int count = 0;
        for (Entity entity : witch.getNearbyEntities(24, 12, 24)) {
            if (isOwnMinion(entity)) {
                count++;
            }
        }
        return count;
    }

    private void celebrateSpawn(BehaviorContext context, Location center) {
        context.world().playSound(center, Sound.ENTITY_ZOMBIE_AMBIENT, 0.8f, 0.7f);
        context.world().spawnParticle(Particle.WITCH, center, 10, 0.3, 0.5, 0.3, 0.02);
        context.world().spawnParticle(Particle.SMOKE, center, 6, 0.2, 0.4, 0.2, 0.01);
    }

    private void celebrate(BehaviorContext context, LivingEntity witch) {
        context.world().playSound(witch.getLocation().add(0, 1, 0), Sound.ENTITY_WITCH_CELEBRATE, 1.0f, 1.2f);
    }

    /**
     * The hide behind the raised screen: a short speed surge plus a few hops
     * away from the threat center, pushing the witch behind her minions.
     */
    private void scheduleRetreat(LivingEntity witch, Location threatCenter) {
        Vector away = witch.getLocation().toVector().subtract(threatCenter.toVector()).setY(0);
        away = away.lengthSquared() < 1.0E-4 ? new Vector(0, 0, 1) : away.normalize();
        witch.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 1, false, false, true));
        for (int hop = 0; hop < RETREAT_HOPS; hop++) {
            Vector push = away.clone().multiply(0.42);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!witch.isDead() && witch.isValid()) {
                    witch.setVelocity(new Vector(push.getX(), 0.16, push.getZ()));
                    witch.getWorld().spawnParticle(Particle.SMOKE,
                            witch.getLocation().add(0, 1, 0), 4, 0.2, 0.3, 0.2, 0.01);
                }
            }, 6L + (long) hop * RETREAT_HOP_DELAY_TICKS);
        }
    }
}
