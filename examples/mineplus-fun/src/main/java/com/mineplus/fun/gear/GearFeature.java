package com.mineplus.fun.gear;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Wires the Gear game feature into the Mineplus Core engine.
 *
 * <p>The gear model ({@code gear-1-1.bbmodel}) ships the {@code rotate_gear}
 * clip internally; the multiblock JSON deliberately declares <em>no</em>
 * {@code animations} autoplay — rotation is state-driven (adjacent redstone
 * power or an adjacent rotating gear), controlled through the Core's
 * {@code AnimationApi} by {@link GearGrid}.
 */
public final class GearFeature {

    private final JavaPlugin plugin;
    private final PluginContext context;
    private final GearGrid grid;
    private int periodicTaskId = -1;
    private boolean evaluationScheduled;

    public GearFeature(JavaPlugin plugin, PluginContext context) {
        this.plugin = plugin;
        this.context = context;
        this.grid = new GearGrid(context);
    }

    public void enable() {
        var support = context.moduleSupport();
        support.installDefault(plugin, "defaults/models/gear-1-1.bbmodel", "models/gear-1-1.bbmodel", true);
        support.installDefault(plugin, "defaults/multiblocks/gear.json", "multiblocks/gear.json", false);

        context.infrastructureApi().registerHook(GearKeys.MACHINE_ID, new GearHook(this::requestEvaluation));
        Bukkit.getPluginManager().registerEvents(
                new GearRedstoneListener(this::requestEvaluation), plugin);

        periodicTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                plugin, grid::evaluate, GearKeys.EVAL_INTERVAL_TICKS, GearKeys.EVAL_INTERVAL_TICKS);

        context.jsonInfrastructureApi().reloadAll();
    }

    public void disable() {
        if (periodicTaskId != -1) {
            Bukkit.getScheduler().cancelTask(periodicTaskId);
            periodicTaskId = -1;
        }
    }

    /** Debounced evaluation: collapses redstone-event storms into one next-tick pass. */
    private void requestEvaluation() {
        if (evaluationScheduled) {
            return;
        }
        evaluationScheduled = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            evaluationScheduled = false;
            grid.evaluate();
        });
    }

    /** Grid state for the {@code /gear status} diagnostic. */
    public String describe(MultiBlockInstance instance) {
        var state = context.animationApi().getAnimationState(instance.id(), GearKeys.ANIMATION_ROTATE);
        return state == null
                ? "idle"
                : "rotating (t=" + String.format("%.2f", state.time()) + "s / "
                        + state.length() + "s, " + state.loop() + ")";
    }
}
