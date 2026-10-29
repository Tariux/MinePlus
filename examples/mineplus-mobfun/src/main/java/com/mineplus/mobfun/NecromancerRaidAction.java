package com.mineplus.mobfun;

import com.mineplus.infrastructure.fx.ParticleFx;
import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import com.mineplus.infrastructure.mob.journey.JourneyRunner;
import com.mineplus.infrastructure.mob.pipeline.MobPipeline;
import com.mineplus.infrastructure.mob.pipeline.MobPipelineHandle;
import com.mineplus.infrastructure.mob.pipeline.stage.JourneyStage;
import com.mineplus.infrastructure.mob.pipeline.stage.LocateStructureStage;
import com.mineplus.util.DebugLogger;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Witch;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * A witch that has decided a village has to go: find one, march there, break
 * it.
 *
 * <p>The whole scenario is one {@link MobPipeline} definition shared by every
 * witch — locate a village (the Core anchors it to the bell), walk there with
 * the shared {@link JourneyRunner} (follow-range legs, stall detours, chunk
 * leases), then hand the mob to {@link NecromancerRaidStage} and let the raid
 * run. This action is only the starter: it declines while the witch is already
 * owned by a pipeline, sets the pipeline, and records a cooldown on the witch
 * when the scenario ends so a witch that found no village does not search the
 * world again every pass.
 *
 * <p>The witch keeps her ordinary {@code summon_minions} profile too; that is
 * what she does while she waits out the cooldown, and the pipeline replaces it
 * for the duration of the march and the raid.
 */
public final class NecromancerRaidAction implements BehaviorAction {

    private static final String ID = "necromancer_raid";
    private static final String VILLAGE_KEY = "village";
    /** Ticks before a witch that finished a raid looks for a new village. */
    private static final long RAID_COOLDOWN_TICKS = 6_000L;
    /** Ticks before a witch whose search failed tries again. */
    private static final long FAILED_COOLDOWN_TICKS = 1_200L;

    private final Plugin plugin;
    private final NamespacedKey cooldownKey;
    private final MobPipeline pipeline;

    public NecromancerRaidAction(Plugin plugin, ParticleFx fx) {
        this.plugin = plugin;
        this.cooldownKey = new NamespacedKey(plugin, "mobfun_raid_cooldown");
        this.pipeline = MobPipeline.builder("necromancer_village_raid")
                .stage(LocateStructureStage.village("locate_village", VILLAGE_KEY))
                .stage(new JourneyStage("march_to_village", VILLAGE_KEY, "village",
                        // A purposeful stride: faster than a villager's amble, with
                        // follow-range legs and enough patience for a long crossing.
                        new JourneyRunner.Options(1.05, 8.0, 8.0, 24, 40L, 60L, 2.0, 6, 0.0)))
                .stage(new NecromancerRaidStage(plugin, fx, VILLAGE_KEY))
                .onComplete(context -> coolDown(context.mob(), RAID_COOLDOWN_TICKS))
                .onFailure((context, reason) -> {
                    DebugLogger.debug("[MobFun] necromancer raid on %s failed: %s",
                            context.mob().getUniqueId(), reason);
                    coolDown(context.mob(), FAILED_COOLDOWN_TICKS);
                })
                .build();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean execute(BehaviorContext context) {
        if (!(context.entity() instanceof Witch witch) || witch.isDead() || !witch.isValid()) {
            return false;
        }
        if (context.isPipelined() || onCooldown(witch, context.world().getGameTime())) {
            return false;
        }
        MobPipelineHandle handle = context.startPipeline(pipeline);
        if (handle == null) {
            return false;
        }
        DebugLogger.debug("[MobFun] necromancer %s begins a village raid (pipeline %s)",
                witch.getUniqueId(), handle.pipelineId());
        return true;
    }

    private boolean onCooldown(Witch witch, long now) {
        Long until = witch.getPersistentDataContainer().get(cooldownKey, PersistentDataType.LONG);
        return until != null && now < until;
    }

    private void coolDown(LivingEntity entity, long ticks) {
        if (entity == null || !(entity instanceof Mob) || !entity.isValid() || entity.isDead()) {
            return;
        }
        long until = entity.getWorld() == null ? 0L : entity.getWorld().getGameTime() + ticks;
        entity.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG, until);
    }
}
