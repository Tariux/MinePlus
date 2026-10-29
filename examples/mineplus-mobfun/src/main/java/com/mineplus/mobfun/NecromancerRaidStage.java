package com.mineplus.mobfun;

import com.mineplus.infrastructure.fx.ParticleFx;
import com.mineplus.infrastructure.fx.Sounds;
import com.mineplus.infrastructure.mob.MobEditSession;
import com.mineplus.infrastructure.mob.navigation.GroundProbe;
import com.mineplus.infrastructure.mob.pipeline.PipelineContext;
import com.mineplus.infrastructure.mob.pipeline.PipelineStage;
import com.mineplus.infrastructure.mob.pipeline.StageResult;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Bell;
import org.bukkit.block.BlockState;
import org.bukkit.entity.CaveSpider;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Stray;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Witch;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The last stage of the necromancer raid: stand in the village and break it.
 *
 * <p>The marcher arrived; this stage is the raid. It keeps the witch aimed at
 * the nearest villager (or at a golem that gets too close), raises a mixed
 * horde through the engine's spawn budget, re-points the surviving minions at
 * the same victim, and rings the village bell on entry — the beacon the Core
 * used to find the centre is also the alarm the raid sounds.
 *
 * <p>The horde is capped and refilled on a game-time interval, and the stage
 * ends when the village has been empty for a while (nothing left to raid) or
 * after a hard ceiling (a witch that cannot finish the job must not own the
 * mob forever). Everything per-witch — the summon clock — lives on the
 * pipeline blackboard, because one stage instance is shared by every witch.
 */
public final class NecromancerRaidStage implements PipelineStage {

    private static final String ID = "raid_village";
    private static final String LAST_SUMMON_KEY = "raid_last_summon";
    private static final String BELL_RUNG_KEY = "raid_bell_rung";

    private static final double VILLAGE_RADIUS = 40.0;
    private static final int SCAN_HEIGHT = 16;
    private static final double THREAT_CLOSE_RADIUS = 12.0;
    private static final int HORDE_CAP = 8;
    private static final int MINIONS_PER_WAVE = 3;
    private static final long SUMMON_INTERVAL_TICKS = 80L;
    private static final long EMPTY_GRACE_TICKS = 1_200L;
    private static final long RAID_MAX_TICKS = 7_200L;

    private final Plugin plugin;
    private final ParticleFx fx;
    private final NamespacedKey minionKey;
    private final NamespacedKey minionOwnerKey;
    private final String villageKey;

    public NecromancerRaidStage(Plugin plugin, ParticleFx fx, String villageKey) {
        this.plugin = plugin;
        this.fx = fx;
        this.villageKey = villageKey == null || villageKey.isBlank() ? "village" : villageKey;
        this.minionKey = new NamespacedKey(plugin, "mobfun_summon");
        this.minionOwnerKey = new NamespacedKey(plugin, "mobfun_summon_owner");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long timeoutTicks() {
        return RAID_MAX_TICKS + 600L;
    }

    /** Rings the village bell once, as the raid's opening alarm. */
    @Override
    public void onEnter(PipelineContext context) {
        if (!(context.mob() instanceof Witch witch)) {
            return;
        }
        ringBell(context, witch);
        Sounds.play(witch.getLocation().add(0, 1, 0), Sound.ENTITY_WITCH_CELEBRATE.name(), 1.0f, 0.8f);
    }

    @Override
    public StageResult tick(PipelineContext context) {
        if (!(context.mob() instanceof Witch witch) || witch.isDead() || !witch.isValid()
                || witch.getWorld() == null) {
            context.fail("necromancer is gone");
            return StageResult.FAILED;
        }
        long elapsed = context.stageGameTicks();
        if (elapsed >= RAID_MAX_TICKS) {
            return StageResult.COMPLETE; // raid spent, hand the mob back
        }

        Scan scan = scan(witch);
        LivingEntity focus = scan.threat() != null ? scan.threat() : scan.victim();
        if (focus != null) {
            witch.setTarget(focus);
        }
        retargetMinions(witch, scan.minions(), focus);

        if (scan.victim() == null && elapsed >= EMPTY_GRACE_TICKS) {
            return StageResult.COMPLETE; // nothing left in the village
        }

        long lastSummon = context.get(LAST_SUMMON_KEY) instanceof Long value ? value : Long.MIN_VALUE;
        boolean summonDue = elapsed - lastSummon >= SUMMON_INTERVAL_TICKS
                || lastSummon == Long.MIN_VALUE;
        if (focus != null && summonDue && scan.minions().size() < HORDE_CAP) {
            int summoned = summonWave(context, witch, focus, scan.minions().size());
            if (summoned > 0) {
                context.put(LAST_SUMMON_KEY, elapsed);
            }
        }
        return StageResult.RUNNING;
    }

    /** One neighbourhood pass: own horde, nearest villager, nearest golem. */
    private Scan scan(Witch witch) {
        List<Mob> minions = new ArrayList<>(HORDE_CAP);
        LivingEntity victim = null;
        double victimDistance = Double.MAX_VALUE;
        LivingEntity threat = null;
        double threatDistance = Double.MAX_VALUE;
        for (Entity entity : witch.getNearbyEntities(VILLAGE_RADIUS, SCAN_HEIGHT, VILLAGE_RADIUS)) {
            if (isOwnMinion(witch, entity)) {
                if (entity instanceof Mob minion && !minion.isDead() && minion.isValid()) {
                    minions.add(minion);
                }
                continue;
            }
            double distance = entity.getLocation().distanceSquared(witch.getLocation());
            if (entity instanceof Villager && distance < victimDistance) {
                victim = (LivingEntity) entity;
                victimDistance = distance;
            }
            if (entity instanceof org.bukkit.entity.Golem && distance < threatDistance) {
                threat = (LivingEntity) entity;
                threatDistance = distance;
            }
        }
        if (threat != null && threatDistance > THREAT_CLOSE_RADIUS * THREAT_CLOSE_RADIUS) {
            threat = null; // a golem across the village is not this stage's problem yet
        }
        return new Scan(minions, victim, threat);
    }

    private record Scan(List<Mob> minions, LivingEntity victim, LivingEntity threat) {
    }

    /** One summon wave through a bounded edit session (spawn budget only). */
    private int summonWave(PipelineContext context, Witch witch, LivingEntity focus, int living) {
        MobEditSession session = context.openEditSession(0, MINIONS_PER_WAVE);
        if (session == null || !session.hasSpawnBudget()) {
            return 0;
        }
        List<Class<? extends Mob>> pool = poolFor(context.world().getBiome(witch.getLocation()));
        int summoned = 0;
        for (int attempt = 0; attempt < MINIONS_PER_WAVE * 2 && summoned < MINIONS_PER_WAVE
                && living + summoned < HORDE_CAP; attempt++) {
            Location spot = summonSpot(witch);
            if (spot == null) {
                continue;
            }
            Class<? extends Mob> type = pool.get((living + summoned + attempt) % pool.size());
            LivingEntity born = session.spawnMob(spot, type, minion -> tag(minion, witch));
            if (!(born instanceof Mob minion)) {
                continue;
            }
            minion.setTarget(focus);
            summoned++;
            Location center = spot.clone().add(0, 0.6, 0);
            Sounds.play(center, Sound.ENTITY_ZOMBIE_AMBIENT.name(), 0.8f, 0.7f);
            fx.burst(ParticleFx.Tier.AMBIENT, center, Particle.WITCH, 10, 0.3, 0.5, 0.3, 0.02);
            fx.burst(ParticleFx.Tier.AMBIENT, center, Particle.SMOKE, 6, 0.2, 0.4, 0.2, 0.01);
        }
        return summoned;
    }

    /** A standable, dry spot a few blocks off the witch, or {@code null}. */
    private static Location summonSpot(Witch witch) {
        Location base = witch.getLocation();
        for (int ring = 1; ring <= 4; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    org.bukkit.block.Block block = base.getWorld()
                            .getBlockAt(base.getBlockX() + dx, base.getBlockY(), base.getBlockZ() + dz);
                    if (GroundProbe.isCampGround(block)) {
                        return block.getLocation().add(0.5, 0, 0.5);
                    }
                }
            }
        }
        return GroundProbe.standableNear(base, 4, 4);
    }

    private void retargetMinions(Witch witch, List<Mob> minions, LivingEntity focus) {
        for (Mob minion : minions) {
            if (minion.isDead() || !minion.isValid()) {
                continue;
            }
            if (focus != null && !focus.equals(minion.getTarget())) {
                minion.setTarget(focus);
            } else if (focus == null && minion.getTarget() instanceof Monster) {
                minion.setTarget(null);
            }
        }
    }

    /** Rings the nearest bell around the remembered village centre. */
    private void ringBell(PipelineContext context, Witch witch) {
        if (Boolean.TRUE.equals(context.get(BELL_RUNG_KEY))) {
            return;
        }
        context.put(BELL_RUNG_KEY, Boolean.TRUE);
        Location centre = context.location(villageKey);
        if (centre == null) {
            centre = witch.getLocation();
        }
        World world = centre.getWorld();
        if (world == null) {
            return;
        }
        int baseChunkX = centre.getBlockX() >> 4;
        int baseChunkZ = centre.getBlockZ() >> 4;
        Bell nearest = null;
        double best = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (!world.isChunkLoaded(baseChunkX + dx, baseChunkZ + dz)) {
                    continue;
                }
                Chunk chunk = world.getChunkAt(baseChunkX + dx, baseChunkZ + dz);
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof Bell bell) {
                        double distance = centre.distanceSquared(bell.getLocation());
                        if (distance < best) {
                            best = distance;
                            nearest = bell;
                        }
                    }
                }
            }
        }
        if (nearest == null) {
            return;
        }
        try {
            nearest.ring(witch);
        } catch (Throwable ignored) {
            // A bell that refuses the ring (occupant limits) is scenery.
        }
    }

    private void tag(Entity minion, Witch witch) {
        minion.getPersistentDataContainer().set(minionKey, PersistentDataType.BYTE, (byte) 1);
        minion.getPersistentDataContainer().set(minionOwnerKey, PersistentDataType.STRING,
                witch.getUniqueId().toString());
    }

    private boolean isOwnMinion(Witch witch, Entity entity) {
        Byte tag = entity.getPersistentDataContainer().get(minionKey, PersistentDataType.BYTE);
        if (tag == null || tag != (byte) 1) {
            return false;
        }
        String owner = entity.getPersistentDataContainer().get(minionOwnerKey, PersistentDataType.STRING);
        return owner != null && owner.equals(witch.getUniqueId().toString());
    }

    /** Biome-matched raiding party: melee, flanker, ranged. */
    private static List<Class<? extends Mob>> poolFor(org.bukkit.block.Biome biome) {
        String name = biome == null ? "" : biome.name();
        Class<? extends Mob> melee = Zombie.class;
        Class<? extends Mob> flanker = org.bukkit.entity.Spider.class;
        Class<? extends Mob> ranged = Skeleton.class;
        if (name.contains("DESERT") || name.contains("BADLANDS")) {
            melee = Husk.class;
        }
        if (name.contains("SWAMP") || name.contains("MANGROVE")) {
            melee = Drowned.class;
        }
        if (name.contains("SNOWY") || name.contains("FROZEN") || name.contains("TAIGA")
                || name.contains("GROVE")) {
            ranged = Stray.class;
        }
        if (name.contains("JUNGLE") || name.contains("BAMBOO")) {
            flanker = CaveSpider.class;
        }
        return List.of(melee, flanker, ranged);
    }
}
