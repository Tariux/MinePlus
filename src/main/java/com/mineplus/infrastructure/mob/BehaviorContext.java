package com.mineplus.infrastructure.mob;

import com.mineplus.infrastructure.mob.navigation.MobNavigator;
import com.mineplus.infrastructure.mob.pipeline.MobPipeline;
import com.mineplus.infrastructure.mob.pipeline.MobPipelineHandle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Per-evaluation view of one entity. The engine creates a single reusable
 * instance per run and refreshes it before each action evaluation, so hot
 * paths allocate nothing — unlike per-evaluation snapshots. Every world write
 * funnels through {@link #trySetBlock} / {@link #tryBreak} which enforce the
 * run-scoped block edit budget.
 */
public final class BehaviorContext {

    private static final Set<Material> PLANTABLE_GROUND = EnumSet.of(Material.GRASS_BLOCK, Material.DIRT, Material.PODZOL, Material.COARSE_DIRT);
    private static final Set<Material> GRASSY_GROUND = EnumSet.of(Material.GRASS_BLOCK, Material.PODZOL, Material.MYCELIUM, Material.MOSS_BLOCK);

    private final MobBehaviorEngine engine;

    private LivingEntity entity;
    private World world;
    private Block footBlock;
    private Block groundBlock;
    private Random random;
    private BehaviorContext.EditRunGuard editGuard;
    /** The id of the action being evaluated — the namespace scenario state is keyed under. */
    private String actionId;
    /** The priority of the profile being evaluated; arbitrates competing claims. */
    private int profilePriority;

    BehaviorContext(MobBehaviorEngine engine) {
        this.engine = engine;
    }

    /** Binds the context to one entity evaluation. Package-private; engine-owned. */
    void refresh(LivingEntity entity, Random random, BehaviorContext.EditRunGuard editGuard) {
        this.entity = entity;
        this.world = entity.getWorld();
        this.footBlock = entity.getLocation().getBlock();
        this.groundBlock = footBlock.getRelative(BlockFace.DOWN);
        this.random = random;
        this.editGuard = editGuard;
        // Conditions are profile-level, so no action scope exists yet; leaving a
        // previous action's id in place would let one action read another's crew.
        this.actionId = null;
        this.profilePriority = 0;
    }

    /**
     * Names the action about to run, so scenario state the action stores (crews)
     * is scoped to it. The engine binds this immediately before each execution —
     * one entity evaluation runs several actions, and each owns its own state.
     */
    void bindAction(String actionId) {
        this.actionId = actionId;
    }

    /**
     * Binds the profile the action belongs to. The profile's priority is what
     * lets a scenario take an entity away from another one that claimed it
     * first, so the claim has to know who is asking.
     */
    void bindProfile(MobProfile profile) {
        this.profilePriority = profile == null ? 0 : profile.priority();
    }

    /** The priority of the profile being evaluated. */
    public int profilePriority() {
        return profilePriority;
    }

    /** The evaluated living entity. */
    public LivingEntity entity() {
        return entity;
    }

    /** The entity's world. */
    public World world() {
        return world;
    }

    /** Block at the entity's body position (air for an entity standing free). */
    public Block footBlock() {
        return footBlock;
    }

    /** Block directly below the entity's feet. */
    public Block groundBlock() {
        return groundBlock;
    }

    /** Biome at the entity's position. */
    public org.bukkit.block.Biome biome() {
        return footBlock.getBiome();
    }

    /** Shared per-run random source; callers must not retain it. */
    public Random random() {
        return random;
    }

    /** {@code true} when the block is air or otherwise replaceable (grass, flowers, snow layers). */
    public boolean isReplaceable(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        return type.isAir() || Tag.REPLACEABLE.isTagged(type);
    }

    /** {@code true} when the entity stands on grass-like ground (grass block, podzol, mycelium, moss). */
    public boolean onGrassyGround() {
        return GRASSY_GROUND.contains(groundBlock.getType());
    }

    /** {@code true} when the ground can host plants (grass block, dirt family). */
    public boolean onPlantableGround() {
        return PLANTABLE_GROUND.contains(groundBlock.getType()) || Tag.DIRT.isTagged(groundBlock.getType());
    }

    /** Block-light level at the entity's foot block (torches, lanterns, glowstone). */
    public int footBlockLight() {
        return footBlock.getLightFromBlocks();
    }

    /** {@code true} when this run can still afford {@code edits} more block writes. */
    public boolean hasEditBudget(int edits) {
        return editGuard != null && editGuard.remainingEdits() >= Math.max(1, edits);
    }

    /**
     * Places a block, respecting the engine's per-run edit budget. Physics
     * updates are suppressed so symbolic placements (torches, saplings) never
     * cascade into neighbor updates.
     *
     * @return {@code true} when the block changed
     */
    public boolean trySetBlock(Block target, BlockData data) {
        if (target == null || data == null) {
            return false;
        }
        if (editGuard == null || !editGuard.tryConsume(engine)) {
            return false;
        }
        target.setBlockData(data, false);
        engine.telemetry().blockEditDone();
        return true;
    }

    /**
     * Breaks a block (never dropping items unless asked) under the edit budget.
     *
     * @return {@code true} when the block changed
     */
    public boolean tryBreak(Block target, boolean dropItems) {
        if (target == null || target.getType().isAir()) {
            return false;
        }
        if (editGuard == null || !editGuard.tryConsume(engine)) {
            return false;
        }
        boolean removed = dropItems ? target.breakNaturally() : false;
        if (!dropItems) {
            target.setType(Material.AIR);
            removed = true;
        }
        if (removed) {
            engine.telemetry().blockEditDone();
        }
        return removed;
    }

    /**
     * Counts matching blocks in a small cube around the entity. Intended for
     * sparse-density checks (sapling spacing, flower clumps); the loop breaks
     * out as soon as {@code earlyExit} matches are seen.
     */
    public int countNearby(Predicate<Material> matcher, int radius, int verticalRadius, int earlyExit) {
        if (matcher == null || radius <= 0) {
            return 0;
        }
        int count = 0;
        int baseX = footBlock.getX();
        int baseY = footBlock.getY();
        int baseZ = footBlock.getZ();
        for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    if (matcher.test(world.getBlockAt(x, baseY + dy, z).getType())) {
                        count++;
                        if (count >= earlyExit) {
                            return count;
                        }
                    }
                }
            }
        }
        return count;
    }

    /** Counts block entities of one material in the given cube around the entity. */
    public int countNearby(Material material, int radius, int verticalRadius, int earlyExit) {
        return countNearby(material == null ? null : candidate -> candidate == material, radius, verticalRadius, earlyExit);
    }

    /**Whether the chunk holding the given block is loaded (safe-world-write precheck). */
    public boolean isChunkLoaded(Block block) {
        Chunk chunk = block == null ? null : block.getChunk();
        return chunk != null && chunk.isLoaded();
    }

    // ------------------------------------------------------------------
    // Activity claims (long-running scenario ownership)
    // ------------------------------------------------------------------

    /**
     * Claims exclusive behavior ownership of the entity for this activity
     * until the entity dies, the claim is released, or it is never renewed
     * again. While a claim is live the engine runs the claiming action on
     * every engine stagger (no odds gate) and skips every other action.
     */
    public boolean claimActivity(String activityId) {
        return claimActivity(activityId, null, 0.0, -1L);
    }

    /** Same as {@link #claimActivity(String)}, with a self-expiry in ticks. */
    public boolean claimActivity(String activityId, long durationTicks) {
        return claimActivity(activityId, null, 0.0, durationTicks);
    }

    /**
     * Claims the entity with an activity anchor: the area the scenario is
     * tied to (the waypoint of a journey, a camp site). Other actions can
     * read it through {@link #activityAnchor()} to limit themselves to the
     * assigned goal's area; negative {@code durationTicks} means until
     * released.
     *
     * <p>The claim carries the priority of the profile that took it, so a
     * scenario with a higher priority can take the entity over — a villager
     * under attack belongs to its guardian, not to the pilgrimage it was
     * walking.
     *
     * @return {@code true} when the claim was taken or renewed
     */
    public boolean claimActivity(String activityId, Location anchor, double anchorRadius, long durationTicks) {
        if (activityId == null || activityId.isBlank() || entity == null
                || entity.isDead() || !entity.isValid()) {
            return false;
        }
        engine.claimActivity(entity, activityId, anchor, anchorRadius, durationTicks, profilePriority);
        return true;
    }

    /** Ends this activity's claim; {@code true} when it was the owner. */
    public boolean releaseActivity(String activityId) {
        return engine.releaseActivity(entity, activityId);
    }

    /** {@code true} when this activity currently owns the entity. */
    public boolean ownsActivity(String activityId) {
        return activityId != null && activityId.equals(engine.activityIdOf(entity));
    }

    /** {@code true} when any activity holds the entity. */
    public boolean hasActivity() {
        return engine.activityIdOf(entity) != null;
    }

    /** The action id of the entity's current activity, or {@code null}. */
    public String activeActivityId() {
        return engine.activityIdOf(entity);
    }

    /**
     * The anchor a live activity assigned to this entity (the goal location
     * the mob is bound to), or {@code null}. Other actions use it to keep
     * their behavior inside the assigned goal's area.
     */
    public Location activityAnchor() {
        return engine.activityAnchorOf(entity);
    }

    /** The activity anchor radius, or {@code -1.0} when the entity is unclaimed. */
    public double activityAnchorRadius() {
        return engine.activityAnchorRadiusOf(entity);
    }

    /** Navigates the entity (a {@link Mob}) toward {@code target} through the Core navigator. */
    public boolean navigateTo(Location target, double speedModifier) {
        return entity instanceof Mob mob && MobNavigator.moveTo(mob, target, speedModifier);
    }

    /**
     * Navigates the entity toward {@code target} but no closer than
     * {@code stopDistance} (see {@link MobNavigator#moveToWithin}).
     */
    public boolean navigateToWithin(Location target, double stopDistance, double speedModifier) {
        return entity instanceof Mob mob && MobNavigator.moveToWithin(mob, target, stopDistance, speedModifier);
    }

    /** Stops the entity's current navigation path (back to its own AI). */
    public void stopNavigation() {
        if (entity instanceof Mob mob) {
            MobNavigator.stop(mob);
        }
    }

    /**
     * The engine's shared long-walk runner. Actions rarely need it directly —
     * {@link #advanceJourney} is the usual call.
     */
    public com.mineplus.infrastructure.mob.journey.JourneyRunner journeys() {
        return engine.journeys();
    }

    /**
     * Walks this entity one step of the way toward {@code destination}, however
     * far that destination is.
     *
     * <p>This is the Core's answer to "the mob refuses to move toward a distant
     * goal": a single vanilla path cannot reach hundreds of blocks, so the
     * journey is chopped into legs inside the mob's path range, each waypoint
     * normalised to standable ground, stalls detected by sampling progress and
     * routed around, and the whole scenario confined to {@code leashCenter} when
     * one is given. State lives in the engine, not in the entity's PDC.
     *
     * @param destination the goal, any distance away
     * @param options     stride, patience and confinement for this scenario
     * @param leashCenter the area the scenario belongs to, or {@code null}
     * @return what the step amounted to; {@link JourneyStatus#ARRIVED} means the
     *         goal is reached and the journey state is already gone
     */
    public com.mineplus.infrastructure.mob.journey.JourneyStatus advanceJourney(
            Location destination, com.mineplus.infrastructure.mob.journey.JourneyRunner.Options options,
            Location leashCenter) {
        if (!(entity instanceof Mob mob)) {
            return com.mineplus.infrastructure.mob.journey.JourneyStatus.NO_CAPACITY;
        }
        return engine.journeys().advance(mob, destination, options, leashCenter);
    }

    /** Same as {@link #advanceJourney} with no confinement. */
    public com.mineplus.infrastructure.mob.journey.JourneyStatus advanceJourney(
            Location destination, com.mineplus.infrastructure.mob.journey.JourneyRunner.Options options) {
        return advanceJourney(destination, options, confinementCenter());
    }

    /** Stops this entity's journey and forgets its walking state. */
    public void abandonJourney() {
        if (entity instanceof Mob mob) {
            engine.journeys().abandon(mob);
        }
    }

    /**
     * Attaches a multi-stage {@link MobPipeline} to this entity — the scenario
     * form of behavior: locate, march, act, with per-stage timeouts and
     * hand-off. While the pipeline runs the engine advances it every pass and
     * skips every ordinary action on this entity, so a raid is not interrupted
     * by grazing or torch-lighting.
     *
     * @return the pipeline handle, or {@code null} when this entity is not a
     *         live mob or a pipeline is already attached
     */
    public MobPipelineHandle startPipeline(MobPipeline pipeline) {
        if (!(entity instanceof Mob mob) || pipeline == null) {
            return null;
        }
        return engine.pipelines().assign(engine.plugin(), mob, pipeline);
    }

    /** {@code true} when a pipeline currently owns this entity. */
    public boolean isPipelined() {
        return entity != null && engine.pipelines().owns(entity);
    }

    /** {@code true} when this entity is mid-journey. */
    public boolean isJourneying() {
        return entity instanceof Mob mob && engine.journeys().isJourneying(mob);
    }

    /** The live journey's destination for this entity, or {@code null}. */
    public Location journeyDestination() {
        return entity instanceof Mob mob ? engine.journeys().destination(mob) : null;
    }

    /**
     * The area this entity's behavior is confined to: its activity anchor when
     * the claim carries one, otherwise {@code null} (unconstrained). A scenario
     * owns a place, and a mob that walks out of it belongs there, not to the
     * rest of the world.
     */
    public Location confinementCenter() {
        return engine.activityAnchorOf(entity);
    }

    /** The confinement radius, or {@code 0} when the entity is unconstrained. */
    public double confinementRadius() {
        return Math.max(0.0, engine.activityAnchorRadiusOf(entity));
    }

    /** {@code true} when the entity is inside its confined area (or has none). */
    public boolean withinConfinement() {
        double radius = confinementRadius();
        Location center = confinementCenter();
        return radius <= 0 || center == null || center.getWorld() != world
                || entity.getLocation().distance(center) <= radius;
    }

    /**
     * The nearest structure of a type, searched through the engine's cache.
     *
     * <p>{@code locateNearestStructure} is a world-generation query and belongs
     * on a cooldown, not on every evaluation: the Core escalates the radius,
     * memoises per region (so a whole crew pays for one search) and caches
     * "nothing here" as an answer. {@code null} means either nothing was found
     * within the ceiling or a probe is still cooling down — ask again later.
     *
     * @param structureKey   registry name, e.g. {@code village}
     * @param maxRadiusChunks how far the search may reach
     */
    public Location nearestStructure(String structureKey, int maxRadiusChunks) {
        return engine.structures().nearest(world, structureKey, entity.getLocation(), maxRadiusChunks);
    }

    /**
     * Spawns a living entity under the engine's per-run spawn budget — the guard
     * that keeps a summon-heavy showcase from flooding the tick.
     *
     * <p>The reason is {@link CreatureSpawnEvent.SpawnReason#CUSTOM}: a mob that
     * behavior summoned is the plugin's own, and a server cleanup that sweeps
     * custom spawns can find it without the mob being mistaken for one the world
     * generated. The configurator runs before the entity enters the world, so
     * tagging it (PDC, persistence) cannot leak a second entity-join packet.
     *
     * @return the entity, or {@code null} when the budget was spent or the world
     *         refused the spawn (an unsuitable location, a non-living class)
     */
    public LivingEntity trySpawn(Location spawnPoint, Class<? extends LivingEntity> type,
                                 java.util.function.Consumer<? super LivingEntity> configurator) {
        if (spawnPoint == null || type == null || world == null) {
            return null;
        }
        if (editGuard == null || !editGuard.tryConsumeSpawn(engine)) {
            return null;
        }
        java.util.function.Consumer<? super LivingEntity> configure =
                configurator == null ? unneeded -> { } : configurator;
        try {
            LivingEntity spawned = world.spawn(spawnPoint, type,
                    CreatureSpawnEvent.SpawnReason.CUSTOM, false, configure);
            engine.telemetry().entitySpawnDone();
            return spawned;
        } catch (Throwable refused) {
            return null;
        }
    }

    /** The id of the action currently being evaluated (also this scenario's namespace). */
    public String actionId() {
        return actionId;
    }

    // ------------------------------------------------------------------
    // Crews (mobs that move as one)
    // ------------------------------------------------------------------

    /**
     * Adds this entity to a named crew under {@code leader}, sharing {@code goal}.
     * Crew membership lives in the engine, not in the mob's PDC: it is runtime
     * state, and a restart should not resurrect a march nobody remembers.
     *
     * <p>A crew is local: a mob that is not in one joins the nearest crew of
     * that name within reach, or founds its own where it stands. Walking away
     * never costs a mob its crew.
     *
     * @param crew   the scenario's crew name, namespaced by this action id
     * @param leader the mob to follow, or {@code null} to join without naming one
     * @param goal   the place the crew is heading, or {@code null} to leave it
     */
    public boolean joinCrew(String crew, Mob leader, Location goal) {
        if (!(entity instanceof Mob member)) {
            return false;
        }
        return engine.crews().recruit(actionScope(), crew, member, leader, goal);
    }

    /**
     * Rallies nearby mobs of this entity's own type into the crew under it —
     * strays become a convoy in one call instead of a radius scan that also
     * recruits someone else's scenario.
     *
     * <p>A mob that is already crewed is left alone: recruiting it would pull a
     * mob that is walking a pilgrimage (or building a guardian) into this
     * crew, which is how two showcases end up fighting over the same
     * villager.
     *
     * @param radius how far the call reaches
     * @return how many mobs joined
     */
    public int rallyCrew(String crew, double radius, Location goal) {
        if (!(entity instanceof Mob leader)) {
            return 0;
        }
        List<Mob> nearby = new ArrayList<>();
        for (org.bukkit.entity.Entity candidate : leader.getNearbyEntities(radius, radius, radius)) {
            if (!(candidate instanceof Mob mob) || mob == leader || mob.getType() != leader.getType()
                    || mob.isDead() || !mob.isValid()) {
                continue;
            }
            if (engine.crews().crewOf(mob.getUniqueId()) != null) {
                continue; // already crewed: a mob in a group is never poached
            }
            String activity = engine.activityIdOf(mob);
            if (activity != null && !activity.equals(actionId)) {
                continue; // another scenario owns this mob right now
            }
            nearby.add(mob);
        }
        return engine.crews().recruitAll(actionScope(), crew, leader, nearby, goal);
    }

    /**
     * Joins the nearest existing crew of this name, without founding one.
     *
     * <p>{@code false} means there is no company within reach — the caller
     * then has to find a goal of its own rather than wait for one.
     */
    public boolean joinNearestCrew(String crew) {
        if (!(entity instanceof Mob member)) {
            return false;
        }
        return engine.crews().joinNearest(actionScope(), crew, member);
    }

    /**
     * The waypoint this member should walk toward to reach its crew's
     * formation point, or {@code null} when it is already close enough.
     *
     * <p>The waypoint is bounded by the mob's path range, so a member whose
     * crew is far ahead is handed a walk it can make instead of a target whose
     * path never resolves — the difference between a group that marches and a
     * group that stands still.
     *
     * @param cohesionRadius how close is close enough
     * @param maxReach       the mob's own path range, in blocks
     */
    public Location crewRejoin(String crew, double cohesionRadius, double maxReach) {
        if (!(entity instanceof Mob member)) {
            return null;
        }
        return engine.crews().rejoinWaypoint(actionScope(), crew, member, cohesionRadius, maxReach);
    }

    /** The crew's formation point — where the group is expected to be now. */
    public Location crewRally(String crew) {
        if (!(entity instanceof Mob member)) {
            return null;
        }
        return engine.crews().rally(actionScope(), crew, member);
    }

    /**
     * Moves the crew's formation point. The march advances it along the route
     * once the crew has caught up; at night it is pinned to the camp site.
     */
    public void setCrewRally(String crew, Location point) {
        if (entity instanceof Mob member) {
            engine.crews().setRally(actionScope(), crew, member, point);
        }
    }

    /**
     * How far the crew is spread around its formation point: the greatest
     * distance from it to any live member. Zero for a crew of one.
     *
     * <p>A member another scenario has taken over is not part of the
     * formation any more: a villager a guardian pulled out of the march is
     * not coming, and counting it would make the crew wait for it forever.
     */
    public double crewSpread(String crew) {
        Location rally = crewRally(crew);
        if (rally == null) {
            return 0.0;
        }
        if (!(entity instanceof Mob member)) {
            return 0.0;
        }
        return engine.crews().spread(actionScope(), crew, member, rally, this::stillInFormation);
    }

    /** {@code true} while this member still belongs to the scenario's formation. */
    private boolean stillInFormation(org.bukkit.entity.Mob member) {
        String claim = engine.activityIdOf(member);
        return claim == null || actionId == null || claim.equals(actionId);
    }

    /** The crew's live leader, or {@code null}; a dead leader is replaced automatically. */
    public Mob crewLeader(String crew) {
        if (!(entity instanceof Mob member)) {
            return null;
        }
        return engine.crews().leader(actionScope(), crew, member);
    }

    /** {@code true} when this entity leads its crew (or is free to found one). */
    public boolean isCrewLeader(String crew) {
        if (!(entity instanceof Mob member)) {
            return false;
        }
        return engine.crews().isLeader(actionScope(), crew, member);
    }

    /** The crew's shared goal, or {@code null}. */
    public Location crewGoal(String crew) {
        if (!(entity instanceof Mob member)) {
            return null;
        }
        return engine.crews().goal(actionScope(), crew, member);
    }

    /** Replaces the crew's shared goal (a freshly located structure). */
    public void setCrewGoal(String crew, Location goal) {
        if (entity instanceof Mob member) {
            engine.crews().setGoal(actionScope(), crew, member, goal);
        }
    }

    /**
     * The identity of this member's crew — two local crews of one scenario
     * share a name but not an identity, and per-crew state (a camp, a
     * patience counter) has to be keyed on the identity.
     */
    public String crewId(String crew) {
        if (!(entity instanceof Mob member)) {
            return null;
        }
        return engine.crews().crewKeyOf(member.getUniqueId());
    }

    /**
     * Sets how this member's crew arranges itself around its formation point:
     * a wedge behind the leader on the march, a pincer around a village, a ring
     * around a gate. Slots are offsets from the formation point, so the crew
     * still walks one route — only the shape changes.
     *
     * @return {@code true} when the member belonged to a crew to shape
     */
    public boolean setCrewFormation(String crew, com.mineplus.infrastructure.mob.group.Formation formation) {
        String key = squadKey();
        if (key == null) {
            return false;
        }
        engine.squads().setFormation(key, formation);
        return true;
    }

    /** The crew's formation ({@code NONE} when unset or the member is crewless). */
    public com.mineplus.infrastructure.mob.group.Formation crewFormation(String crew) {
        String key = squadKey();
        return key == null ? com.mineplus.infrastructure.mob.group.Formation.NONE
                : engine.squads().formation(key);
    }

    /** Gives this member a role in its squad (tank, flanker, sniper), or clears it with {@code null}. */
    public boolean setCrewRole(String crew, com.mineplus.infrastructure.mob.group.SquadRole role) {
        if (!(entity instanceof Mob member)) {
            return false;
        }
        String key = squadKey();
        if (key == null) {
            return false;
        }
        engine.squads().assignRole(key, member.getUniqueId(), role);
        return true;
    }

    /** This member's squad role, or {@code null} when it has none. */
    public com.mineplus.infrastructure.mob.group.SquadRole crewRole(String crew) {
        if (!(entity instanceof Mob member)) {
            return null;
        }
        String key = squadKey();
        return key == null ? null : engine.squads().role(key, member.getUniqueId());
    }

    private String squadKey() {
        return entity instanceof Mob member ? engine.crews().crewKeyOf(member.getUniqueId()) : null;
    }

    /** The live members of this member's crew. */
    public List<Mob> crewMembers(String crew) {
        if (!(entity instanceof Mob member)) {
            return List.of();
        }
        return engine.crews().members(actionScope(), crew, member);
    }

    /** Removes this entity from its crew (arrival, death, a scenario ending). */
    public void leaveCrew(String crew) {
        if (entity instanceof Mob member) {
            engine.crews().dismiss(actionScope(), crew, member);
        }
    }

    /** Drops this entity's whole crew. */
    public void disbandCrew(String crew) {
        if (entity instanceof Mob member) {
            engine.crews().disband(actionScope(), crew, member);
        }
    }

    /** Ends this activity for every live member before the crew is dropped. */
    public void stopCrew(String crew, String activityId) {
        for (Mob member : crewMembers(crew)) {
            engine.releaseActivity(member, activityId);
            engine.journeys().abandon(member);
            MobNavigator.stop(member);
        }
    }

    private String actionScope() {
        return actionId == null ? "unscoped" : actionId;
    }

    /**
     * Run-scoped budget guard. Created fresh by the engine per run; exhausted
     * budgets make {@link #trySetBlock}, {@link #tryBreak} and {@link #trySpawn}
     * no-ops until the next run, which is how the engine rate-limits world
     * changes and summons alike.
     */
    interface EditRunGuard {
        /** Attempts to consume one edit slot. */
        boolean tryConsume(MobBehaviorEngine engine);

        /** Attempts to consume one entity-spawn slot. */
        boolean tryConsumeSpawn(MobBehaviorEngine engine);

        /** Edit slots still available in this run. */
        int remainingEdits();
    }

    /** Condition helper factory predicates used with {@link MobProfile.Builder#condition(Predicate)}. */
    public static final class Conditions {

        private Conditions() {
        }

        /** Limits the profile to entities inside the given worlds. */
        public static Predicate<BehaviorContext> inWorld(Predicate<World> worldPredicate) {
            return context -> worldPredicate.test(context.world());
        }

        /** Requires the entity to be an adult baby-capable mob (no baby churn). */
        public static Predicate<BehaviorContext> adultOnly() {
            return context -> !(context.entity() instanceof org.bukkit.entity.Ageable ageable) || ageable.isAdult();
        }

        /** Requires block light at the foot position to be at most the given level. */
        public static Predicate<BehaviorContext> maxBlockLight(int level) {
            return context -> context.footBlockLight() <= level;
        }

        /**
         * Requires the entity to be at or below {@code fraction} of its own max
         * health — the "cornered" half of a guardian's trigger. Reads the mob's
         * own attribute so it works for a 10-health pig and a 20-health villager.
         */
        public static Predicate<BehaviorContext> belowHealth(double fraction) {
            double clamped = Math.max(0.0, Math.min(1.0, fraction));
            return context -> {
                LivingEntity self = context.entity();
                if (self.isDead() || !self.isValid()) {
                    return false;
                }
                org.bukkit.attribute.AttributeInstance maximum =
                        self.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                double ceiling = maximum == null ? 20.0 : maximum.getValue();
                return self.getHealth() <= ceiling * clamped;
            };
        }

        /**
         * Requires at least one live entity matching {@code matcher} within
         * {@code radius} — the sensing primitive a module composes a situation
         * out of (hostiles near, a golem closing in). Stops at the first match.
         */
        public static Predicate<BehaviorContext> nearbyEntity(double radius, Predicate<Entity> matcher) {
            return context -> matches(context, radius, matcher, false);
        }

        /** As {@link #nearbyEntity}, but the match also has to be in line of sight. */
        public static Predicate<BehaviorContext> visibleEntity(double radius, Predicate<Entity> matcher) {
            return context -> matches(context, radius, matcher, true);
        }

        private static boolean matches(BehaviorContext context, double radius, Predicate<Entity> matcher,
                                        boolean requiresSight) {
            if (matcher == null) {
                return false;
            }
            LivingEntity self = context.entity();
            if (self.isDead() || !self.isValid()) {
                return false;
            }
            double reach = Math.max(1.0, radius);
            for (Entity candidate : self.getNearbyEntities(reach, reach, reach)) {
                if (!(candidate instanceof LivingEntity living) || living.isDead() || !living.isValid()) {
                    // A corpse still in the entity list is not a reason to act:
                    // it kept a threat condition true for the tick it lingered.
                    continue;
                }
                if (matcher.test(living) && (!requiresSight || self.hasLineOfSight(living))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Small API shim so bundled actions can share sapling biome mapping. */
    public static final class Planting {

        private Planting() {
        }

        /**
         * Biome-appropriate sapling for a location, or {@code null}. Mirrors
         * the reference mod's approach (name matching over biome families) at
         * Bukkit API level: {@code Tag.SAPLINGS} members only.
         */
        public static Material saplingFor(org.bukkit.block.Biome biome) {
            if (biome == null) {
                return null;
            }
            String name = biome.name();
            if (name.contains("BIRCH")) {
                return Material.BIRCH_SAPLING;
            }
            if (name.contains("TAIGA") || name.contains("GROVE") || name.contains("SNOWY")) {
                return Material.SPRUCE_SAPLING;
            }
            if (name.contains("JUNGLE")) {
                return Material.JUNGLE_SAPLING;
            }
            if (name.contains("SAVANNA")) {
                return Material.ACACIA_SAPLING;
            }
            if (name.contains("CHERRY")) {
                return Material.CHERRY_SAPLING;
            }
            if (name.contains("DARK_FOREST")) {
                return Material.DARK_OAK_SAPLING;
            }
            if (name.contains("FOREST") || name.equals("PLAINS") || name.contains("MEADOW") || name.contains("FLOWER")) {
                return Material.OAK_SAPLING;
            }
            return null;
        }

        /** Saplings share the {@link Tag#SAPLINGS} tag; useful as a density-check matcher. */
        public static boolean isSapling(Material material) {
            return material != null && Tag.SAPLINGS.isTagged(material);
        }
    }

    /**
     * Convenience view for actions that need the blocks directly around the
     * entity without doing their own block math.
     */
    public List<Block> footNeighbors() {
        return List.of(
                footBlock.getRelative(BlockFace.NORTH),
                footBlock.getRelative(BlockFace.SOUTH),
                footBlock.getRelative(BlockFace.EAST),
                footBlock.getRelative(BlockFace.WEST)
        );
    }
}
