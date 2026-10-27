package com.mineplus.mobfun;

import com.mineplus.infrastructure.fx.ParticleFx;
import com.mineplus.infrastructure.fx.Sounds;
import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import com.mineplus.infrastructure.mob.MobBehaviorApi;
import com.mineplus.infrastructure.mob.MobEditSession;
import com.mineplus.infrastructure.mob.navigation.GroundProbe;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * The mob assembles a golem <b>block by block</b>: when endangered, it raises
 * the classic body column beside itself - base block, torso, carved pumpkin
 * head, each step a few ticks apart with its own placement sound - and after a
 * short final pause the column becomes a real golem (the blocks are cleared as
 * the entity takes their place, so the world is left clean). The new golem
 * immediately attacks the nearest monster around it instead of idling.
 * Parameterized once for both guardians: iron (body {@link
 * Material#IRON_BLOCK}, spawn {@link EntityType#IRON_GOLEM}) and snow
 * ({@link Material#SNOW_BLOCK} / {@link EntityType#SNOW_GOLEM}).
 *
 * <p>The build outlives the evaluation that starts it, and that is exactly what
 * {@link MobEditSession} exists for: the engine grants the column one session —
 * six block writes plus one summon, capped by the subsystem's per-run limits —
 * and every staged step writes through it. A module that scheduled its own
 * {@code setBlockData} calls would be editing the world outside every budget
 * the mob engine keeps.
 *
 * <p>Anti-spam: aside from the engine's odds gate, each builder tracks a
 * per-entity cooldown in its PDC (survives restarts) and at most one golem of
 * its type may exist nearby - raids escalate as a slow, watched duel instead
 * of a golem fountain. The builder also tags itself as a guardian, so the
 * pilgrim journey showcase knows not to march it off to a village.
 *
 * <p>Build-site selection is terrain-tolerant and reads the Core's ground
 * arithmetic ({@link GroundProbe}): candidate columns are scanned over the
 * eight neighbors (summoner's look direction first), each also tried one block
 * lower as a terrace fallback, and each candidate must be buildable open space
 * on solid non-tree earth with three clear cells above it - slopes, dense
 * jungle floors and tree rows do not strand the column in midair or inside the
 * canopy.
 * Profile conditions decide when this may run (danger nearby).
 */
public final class GolemSummonAction implements BehaviorAction {

    /** PDC key name shared with {@link BehaviorConditions#notBuilder(Plugin)}. */
    static final String BUILDER_TAG_KEY = "mobfun_builder";

    private static final int BUILD_STEP_DELAY_TICKS = 6;
    private static final int GOLEM_CAP = 1;
    private static final long COOLDOWN_TICKS = 900L;
    private static final int THREAT_TARGET_RADIUS = 10;
    /** base + torso + head placed, then the same three cleared to free the golem. */
    private static final int COLUMN_EDITS = 6;
    private static final int COLUMN_SPAWNS = 1;

    private final Plugin plugin;
    private final MobBehaviorApi api;
    private final ParticleFx fx;
    private final NamespacedKey cooldownKey;
    private final NamespacedKey builderKey;
    private final String id;
    private final String golemName;
    private final Material bodyMaterial;
    private final EntityType golemType;
    private final Class<? extends LivingEntity> golemClass;
    private final Sound blockSound;
    private final Sound spawnSound;

    /** @param id        stable action id ({@code summon_iron_golem} / {@code summon_snow_golem})
     *  @param golemName custom name given to the summoned golem (crosshair-visible only) */
    public GolemSummonAction(Plugin plugin, MobBehaviorApi api, ParticleFx fx,
                             String id, String golemName, Material bodyMaterial, EntityType golemType,
                             Class<? extends LivingEntity> golemClass, Sound blockSound, Sound spawnSound) {
        this.plugin = plugin;
        this.api = api;
        this.fx = fx;
        this.cooldownKey = new NamespacedKey(plugin, "mobfun_golem_cd");
        this.builderKey = new NamespacedKey(plugin, BUILDER_TAG_KEY);
        this.id = id;
        this.golemName = golemName;
        this.bodyMaterial = bodyMaterial;
        this.golemType = golemType;
        this.golemClass = golemClass;
        this.blockSound = blockSound;
        this.spawnSound = spawnSound;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean execute(BehaviorContext context) {
        LivingEntity builder = context.entity();
        if (builder.isDead() || !builder.isValid() || !context.isChunkLoaded(context.footBlock())) {
            return false;
        }
        World world = context.world();
        long now = world.getGameTime();
        Long cooldownUntil = builder.getPersistentDataContainer().get(cooldownKey, PersistentDataType.LONG);
        if (cooldownUntil != null && now < cooldownUntil) {
            return false;
        }
        if (countNearbyGolems(builder) >= GOLEM_CAP) {
            return false;
        }
        for (Block base : baseCandidates(context.footBlock(), builder.getLocation())) {
            if (!isBuildable(base)) {
                continue;
            }
            // One grant for the whole build: the engine caps it at its own per-run
            // limits, and every scheduled stage writes under it. The builder is
            // only committed once the first block is actually down, so a site the
            // budget refused does not burn the cooldown or brand the mob.
            if (!startColumn(world, api.openEditSession(COLUMN_EDITS, COLUMN_SPAWNS), base)) {
                continue;
            }
            builder.getPersistentDataContainer().set(builderKey, PersistentDataType.BYTE, (byte) 1);
            builder.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG,
                    now + COOLDOWN_TICKS);
            return true;
        }
        return false;
    }

    /**
     * Lays stage 1 immediately and hands the rest of the column to one
     * self-advancing task, so a build costs the scheduler one entry instead of
     * one per block.
     *
     * @return {@code false} when the very first block could not be placed
     */
    private boolean startColumn(World world, MobEditSession session, Block base) {
        Block torso = base.getRelative(BlockFace.UP);
        Block head = torso.getRelative(BlockFace.UP);
        BlockData bodyData = Bukkit.createBlockData(bodyMaterial);
        BlockData headData = Bukkit.createBlockData(Material.CARVED_PUMPKIN);
        Column column = new Column(world, session, new Block[]{base, torso, head},
                new BlockData[]{bodyData, bodyData, headData});
        if (!column.advance()) {
            return false;
        }
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!column.advance()) {
                task.cancel();
            }
        }, BUILD_STEP_DELAY_TICKS, BUILD_STEP_DELAY_TICKS);
        return true;
    }

    /**
     * The staged build itself: three placements, a pause (the finished column
     * is a sight, not a flicker), then the column is cleared and the golem
     * takes its place. Each stage re-verifies its block, so a player who broke
     * the column mid-build stops the build rather than completing it into air.
     */
    private final class Column {

        private final World world;
        private final MobEditSession session;
        private final Block[] column;
        private final BlockData[] materials;
        /** 0..2 place, 3 the pause after the last block, 4 clear and summon. */
        private int stage;

        private Column(World world, MobEditSession session, Block[] column, BlockData[] materials) {
            this.world = world;
            this.session = session;
            this.column = column;
            this.materials = materials;
        }

        /** @return {@code true} when the build goes on; {@code false} ends it. */
        boolean advance() {
            if (stage < column.length) {
                return place(stage++);
            }
            if (stage == column.length) {
                stage++;
                return true;
            }
            assemble();
            return false;
        }

        private boolean place(int index) {
            Block target = column[index];
            if (!world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4) || !target.isPassable()) {
                return false;
            }
            if (!session.setBlock(target, materials[index])) {
                return false; // the grant is spent: the column stays as built
            }
            Location at = target.getLocation().add(0.5, 0.5, 0.5);
            Sounds.play(at, blockSound.name(), 0.9f, 1.0f);
            fx.burst(ParticleFx.Tier.AMBIENT, at, Particle.CLOUD, 3, 0.15, 0.15, 0.15, 0.01);
            return true;
        }

        /** The finished column becomes the golem - and the golem fights. */
        private void assemble() {
            // Verified whole before anything is cleared: an interrupted build
            // leaves the column standing, never a half-cleared column and no
            // golem.
            for (Block block : column) {
                if (!world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                    return;
                }
            }
            for (int i = 0; i < column.length; i++) {
                if (column[i].getType() != materials[i].getMaterial() || !session.hasBudget()) {
                    return;
                }
            }
            for (Block block : column) {
                session.breakBlock(block, false);
            }
            Location spawnLocation = column[0].getLocation().add(0.5, 0, 0.5);
            LivingEntity golem = session.spawnMob(spawnLocation, golemClass,
                    born -> born.setCustomName(golemName));
            if (golem == null) {
                return;
            }
            Location center = spawnLocation.add(0, 1, 0);
            Sounds.play(center, spawnSound.name(), 1.0f, 1.0f);
            fx.burst(ParticleFx.Tier.CRITICAL, center.clone().add(0, 0.5, 0), Particle.WHITE_SMOKE,
                    10, 0.3, 0.4, 0.3, 0.02);
            if (golem instanceof Mob mob) {
                Mob threat = nearestMonster(golem);
                if (threat != null) {
                    mob.setTarget(threat);
                }
            }
        }
    }

    /** The golem's first victim: the nearest monster around it, if any. */
    private static Mob nearestMonster(Entity around) {
        Mob threat = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : around.getNearbyEntities(THREAT_TARGET_RADIUS, 6, THREAT_TARGET_RADIUS)) {
            if (!(entity instanceof Monster monster) || monster.isDead() || !monster.isValid()) {
                continue;
            }
            double distance = entity.getLocation().distanceSquared(around.getLocation());
            if (distance < bestDistance) {
                threat = monster;
                bestDistance = distance;
            }
        }
        return threat;
    }

    private int countNearbyGolems(LivingEntity builder) {
        int count = 0;
        for (Entity entity : builder.getNearbyEntities(12, 8, 12)) {
            if (entity.getType() == golemType) {
                count++;
            }
        }
        return count;
    }

    /**
     * Candidate base blocks for the column: the eight neighbors of the
     * builder's foot block (look direction and its diagonals first), each
     * also tried one block lower as a terrace fallback so gentle slopes work.
     */
    private List<Block> baseCandidates(Block foot, Location look) {
        Vector direction = look.getDirection().setY(0);
        BlockFace primary = direction.lengthSquared() < 1.0E-4 ? BlockFace.NORTH : dominantFace(direction);
        List<BlockFace> faces = new ArrayList<>(8);
        faces.add(primary);
        faces.add(diagonal(primary, true));
        faces.add(diagonal(primary, false));
        faces.add(lateral(primary, true));
        faces.add(lateral(primary, false));
        faces.add(diagonal(primary.getOppositeFace(), true));
        faces.add(diagonal(primary.getOppositeFace(), false));
        faces.add(primary.getOppositeFace());
        List<Block> candidates = new ArrayList<>(faces.size() * 2);
        for (BlockFace face : faces) {
            Block neighbor = foot.getRelative(face);
            candidates.add(neighbor);
            candidates.add(neighbor.getRelative(BlockFace.DOWN));
        }
        return candidates;
    }

    /**
     * A column needs buildable earth the Core recognises for placed blocks
     * (solid, never leaves or logs - a pumpkin on a canopy is a build that
     * silently never happens) with three clear cells on it: the base it stands
     * in, the torso, and the head.
     */
    private static boolean isBuildable(Block base) {
        return GroundProbe.isCampGround(base)
                && base.getRelative(BlockFace.UP).isPassable()
                && base.getRelative(BlockFace.UP, 2).isPassable();
    }

    private static BlockFace dominantFace(Vector direction) {
        if (Math.abs(direction.getX()) >= Math.abs(direction.getZ())) {
            return direction.getX() >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return direction.getZ() >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private static BlockFace lateral(BlockFace face, boolean clockwise) {
        return switch (face) {
            case NORTH -> clockwise ? BlockFace.EAST : BlockFace.WEST;
            case SOUTH -> clockwise ? BlockFace.WEST : BlockFace.EAST;
            case EAST -> clockwise ? BlockFace.SOUTH : BlockFace.NORTH;
            case WEST -> clockwise ? BlockFace.NORTH : BlockFace.SOUTH;
            default -> face;
        };
    }

    private static BlockFace diagonal(BlockFace face, boolean clockwise) {
        return switch (face) {
            case NORTH -> clockwise ? BlockFace.NORTH_EAST : BlockFace.NORTH_WEST;
            case SOUTH -> clockwise ? BlockFace.SOUTH_EAST : BlockFace.SOUTH_WEST;
            case EAST -> clockwise ? BlockFace.SOUTH_EAST : BlockFace.NORTH_EAST;
            case WEST -> clockwise ? BlockFace.SOUTH_WEST : BlockFace.NORTH_WEST;
            default -> face;
        };
    }
}
