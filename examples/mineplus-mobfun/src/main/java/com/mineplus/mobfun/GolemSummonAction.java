package com.mineplus.mobfun;

import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
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
 * head, each step a few ticks apart with its own placement sound - and after
 * a short final pause the column becomes a real golem (blocks are replaced by
 * the summoned entity, so the world is left clean). The new golem immediately
 * attacks the nearest monster around it, so it defends its summoner instead
 * of idling. Parameterized once for both guardians: iron (body {@link
 * Material#IRON_BLOCK}, spawn {@link EntityType#IRON_GOLEM}) and snow
 * ({@link Material#SNOW_BLOCK} / {@link EntityType#SNOW_GOLEM}).
 *
 * <p>Anti-spam: aside from the engine's odds gate, each builder tracks a
 * per-entity cooldown in its PDC (survives restarts) and at most one golem of
 * its type may exist nearby - raids escalate as a slow, watched duel instead
 * of a golem fountain. The builder also tags itself as a guardian, so the
 * pilgrim journey showcase knows not to march it off to a village.
 *
 * <p>Build-site selection is terrain-tolerant: candidate columns are scanned
 * over the eight neighbors (summoner's look direction first), each also tried
 * one block lower as a terrace fallback, and the ground under each candidate
 * must be solid and not leaves/logs - slopes, dense jungle floors and tree
 * rows do not strand the column in midair or inside the canopy. Profile
 * conditions decide when this may run (danger nearby). The first placement
 * runs inside the action (through the engine's edit budget); the staged
 * follow-ups run a few ticks later via the Bukkit scheduler - bounded, each
 * pre-verified, and canceled if the column changed in the meantime.
 */
public final class GolemSummonAction implements BehaviorAction {

    private static final int BUILD_STEP_DELAY_TICKS = 6;
    private static final int ASSEMBLY_DELAY_TICKS = 6;
    private static final int GOLEM_CAP = 1;
    private static final long COOLDOWN_TICKS = 900L;
    private static final int THREAT_TARGET_RADIUS = 10;

    private final Plugin plugin;
    private final NamespacedKey cooldownKey;
    private final NamespacedKey builderKey;
    private final String id;
    private final String golemName;
    private final Material bodyMaterial;
    private final EntityType golemType;
    private final Sound blockSound;
    private final Sound spawnSound;

    /** @param id        stable action id ({@code summon_iron_golem} / {@code summon_snow_golem})
     *  @param golemName custom name given to the summoned golem (crosshair-visible only) */
    public GolemSummonAction(Plugin plugin, String id, String golemName, Material bodyMaterial,
                             EntityType golemType, Sound blockSound, Sound spawnSound) {
        this.plugin = plugin;
        this.cooldownKey = new NamespacedKey(plugin, "mobfun_golem_cd");
        this.builderKey = new NamespacedKey(plugin, "mobfun_builder");
        this.id = id;
        this.golemName = golemName;
        this.bodyMaterial = bodyMaterial;
        this.golemType = golemType;
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
            Block torso = base.getRelative(BlockFace.UP);
            Block head = torso.getRelative(BlockFace.UP);
            BlockData bodyData = Bukkit.createBlockData(bodyMaterial);
            BlockData headData = Bukkit.createBlockData(Material.CARVED_PUMPKIN);
            // Stage 1 happens now (inside the engine's edit budget); the
            // staged steps and the assembly are bounded scheduled work.
            if (!context.trySetBlock(base, bodyData)) {
                return false;
            }
            playStep(world, base);
            builder.getPersistentDataContainer().set(builderKey, PersistentDataType.BYTE, (byte) 1);
            builder.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG,
                    now + COOLDOWN_TICKS);
            Bukkit.getScheduler().runTaskLater(plugin, () -> placeStep(world, torso, bodyData), BUILD_STEP_DELAY_TICKS);
            Bukkit.getScheduler().runTaskLater(plugin, () -> placeStep(world, head, headData),
                    BUILD_STEP_DELAY_TICKS * 2L);
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    assemble(world, base, torso, head, bodyData, headData),
                    BUILD_STEP_DELAY_TICKS * 2L + ASSEMBLY_DELAY_TICKS);
            return true;
        }
        return false;
    }

    /** Stage 2 and 3: place one more body block, verified still free. */
    private void placeStep(World world, Block target, BlockData data) {
        if (!world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4) || !target.isPassable()) {
            return;
        }
        target.setBlockData(data, false);
        playStep(world, target);
        world.spawnParticle(Particle.CLOUD, target.getLocation().add(0.5, 0.5, 0.5), 3, 0.15, 0.15, 0.15, 0.01);
    }

    /** Final stage: the finished column becomes the golem - and fights. */
    private void assemble(World world, Block base, Block torso, Block head, BlockData bodyData, BlockData headData) {
        Block[] column = {base, torso, head};
        BlockData[] materials = {bodyData, bodyData, headData};
        for (int i = 0; i < column.length; i++) {
            if (!world.isChunkLoaded(column[i].getX() >> 4, column[i].getZ() >> 4)
                    || column[i].getType() != materials[i].getMaterial()) {
                return;
            }
        }
        for (Block block : column) {
            block.setType(Material.AIR, false);
        }
        Location spawnLocation = base.getLocation().add(0.5, 0, 0.5);
        Entity golem = world.spawnEntity(spawnLocation, golemType);
        golem.setCustomName(golemName);
        golem.setCustomNameVisible(false);
        world.playSound(spawnLocation.clone().add(0, 1, 0), spawnSound, 1.0f, 1.0f);
        world.spawnParticle(Particle.WHITE_SMOKE, spawnLocation.clone().add(0, 1.5, 0), 10, 0.3, 0.4, 0.3, 0.02);
        if (golem instanceof Mob mob) {
            Mob threat = nearestMonster(golem);
            if (threat != null) {
                mob.setTarget(threat);
            }
        }
    }

    private void playStep(World world, Block block) {
        world.playSound(block.getLocation().add(0.5, 0.5, 0.5), blockSound, 0.9f, 1.0f);
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

    /** Ground must be real ground, never leaves or logs. */
    private static boolean isBuildable(Block base) {
        Block ground = base.getRelative(BlockFace.DOWN);
        Material groundType = ground.getType();
        return base.isPassable()
                && base.getRelative(BlockFace.UP).isPassable()
                && base.getRelative(BlockFace.UP, 2).isPassable()
                && groundType.isSolid()
                && !Tag.LEAVES.isTagged(groundType)
                && !Tag.LOGS.isTagged(groundType);
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
