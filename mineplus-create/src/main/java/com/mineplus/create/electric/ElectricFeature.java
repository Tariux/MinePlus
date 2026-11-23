package com.mineplus.create.electric;

import com.mineplus.create.machines.MachineSpecs;
import com.mineplus.create.machines.MachinesFeature;
import com.mineplus.create.rotational.RotationalNetworkFeature;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import com.mineplus.infrastructure.core.recipes.MachineRecipe;
import com.mineplus.infrastructure.module.ModuleFeature;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * T3 electric tier: a dynamo attached to a spinning network converts |RPM| to
 * FE (per-position buffers in {@link DynamoBufferBank}); gadgets and machines
 * draw from the buffer. Buffer state is session-local (regenerated while the
 * network spins), matching the dynamo fiction.
 *
 * <p>Interactions: right-clicking a dynamo reports its buffer and the current
 * generation rate from the network's actual RPM.</p>
 */
public final class ElectricFeature extends ModuleFeature {

    private final DynamoBufferBank bank = new DynamoBufferBank();
    private final RotationalNetworkFeature rotational;

    public ElectricFeature(JavaPlugin plugin, PluginContext context, RotationalNetworkFeature rotational) {
        super(plugin, context);
        this.rotational = rotational;
    }

    @Override
    public String id() {
        return "electric";
    }

    @Override
    protected void onEnable() {
        context.infrastructureApi().registerHook(MachineSpecs.DYNAMO, new MultiBlockHook() {
            @Override
            public void onPlace(MultiBlockInstance instance, Player actor) {
                String key = MachinesFeature.key(instance);
                bank.register(key);
                var node = rotational.networks().place(key,
                        com.mineplus.create.rotational.RotationalNetworkManager.NodeType.SHAFT);
                node.stress = MachineSpecs.stressOf(MachineSpecs.DYNAMO);
                rotational.networks().recompute();
            }

            @Override
            public void onInteract(MultiBlockInstance instance, Player actor) {
                String key = MachinesFeature.key(instance);
                float rpm = rotational.networks().node(key) == null
                        ? 0f : rotational.networks().node(key).actualRpm;
                int perTick = MachineSpecs.dynamoFePerTick(rpm);
                actor.sendMessage("§7Dynamo buffer: §e" + bank.buffer(key) + "§7/§e"
                        + MachineSpecs.DYNAMO_BUFFER_FE + " FE§7 (generating §e"
                        + perTick + " FE/t§7 at §e" + String.format("%.1f", rpm) + " RPM§7).");
            }

            @Override
            public void onBreak(MultiBlockInstance instance, Player actor) {
                bank.unregister(MachinesFeature.key(instance));
                rotational.networks().remove(MachinesFeature.key(instance));
            }

            @Override
            public void onRemove(MultiBlockInstance instance, Player actor) {
                bank.unregister(MachinesFeature.key(instance));
                rotational.networks().remove(MachinesFeature.key(instance));
            }

            @Override
            public void onProcessComplete(MultiBlockInstance instance, MachineRecipe recipe) {
                // not a crafting machine
            }
        });
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::generateTick, 1L, 1L);
    }

    private org.bukkit.scheduler.BukkitTask task;

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public DynamoBufferBank bank() {
        return bank;
    }

    /** One generation step: feeds every registered dynamo from its network RPM. */
    public void generateTick() {
        bank.generateTick(key -> {
            var node = rotational.networks().node(key);
            return node == null || node.chunkUnloaded ? 0d : node.actualRpm;
        });
    }
}
