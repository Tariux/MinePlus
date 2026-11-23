package com.mineplus.create.steam;

import com.mineplus.create.machines.MachineSpecs;
import com.mineplus.create.machines.MachinesFeature;
import com.mineplus.create.rotational.RotationalNetworkFeature;
import com.mineplus.create.rotational.RotationalNetworkManager;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import com.mineplus.infrastructure.core.recipes.MachineRecipe;
import com.mineplus.infrastructure.module.ModuleFeature;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * T2 steam tier: the boiler burns coal (pure-Java burn math in
 * {@link MachineSpecs}); while burn ticks remain, its steam engine projects
 * into the rotational network as a 48 RPM / 64 SU source.
 *
 * <p>Interactions: right-clicking a placed steam engine with coal refuels it
 * (1 coal = {@value MachineSpecs#COAL_BURN_TICKS} ticks, tracked per engine); a
 * repeating task decrements burn each tick and drives or stops the engine's
 * network source accordingly. Burn state is session-local, matching the
 * "boiler needs refuelling after a server reboot" fiction.</p>
 */
public final class SteamFeature extends ModuleFeature {

    private final RotationalNetworkFeature rotational;
    private final Map<String, Integer> burnTicks = new HashMap<>();
    private BukkitTask burnTask;

    public SteamFeature(JavaPlugin plugin, PluginContext context, RotationalNetworkFeature rotational) {
        super(plugin, context);
        this.rotational = rotational;
    }

    @Override
    public String id() {
        return "steam";
    }

    @Override
    protected void onEnable() {
        context.infrastructureApi().registerHook(MachineSpecs.STEAM_ENGINE, new MultiBlockHook() {
            @Override
            public void onPlace(MultiBlockInstance instance, Player actor) {
                // Placed cold: it only joins the network once refuelled.
            }

            @Override
            public void onInteract(MultiBlockInstance instance, Player actor) {
                refuelInteract(instance, actor);
            }

            @Override
            public void onBreak(MultiBlockInstance instance, Player actor) {
                burnTicks.remove(MachinesFeature.key(instance));
                stopEngine(MachinesFeature.key(instance));
            }

            @Override
            public void onRemove(MultiBlockInstance instance, Player actor) {
                burnTicks.remove(MachinesFeature.key(instance));
                stopEngine(MachinesFeature.key(instance));
            }

            @Override
            public void onProcessComplete(MultiBlockInstance instance, MachineRecipe recipe) {
                // not a crafting machine
            }
        });
        burnTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickBurn, 1L, 1L);
    }

    @Override
    protected void onDisable() {
        if (burnTask != null) {
            burnTask.cancel();
            burnTask = null;
        }
        burnTicks.clear();
    }

    private void refuelInteract(MultiBlockInstance instance, Player actor) {
        ItemStack held = actor.getInventory().getItemInMainHand();
        if (held.getType() != Material.COAL && held.getType() != Material.CHARCOAL) {
            int remaining = burnTicks.getOrDefault(MachinesFeature.key(instance), 0);
            actor.sendMessage(remaining > 0
                    ? "§7Boiler is burning: §e" + (remaining / 20) + "s §7left."
                    : "§7Boiler is cold. Right-click with §ecoal§7 to refuel.");
            return;
        }
        String key = MachinesFeature.key(instance);
        int before = burnTicks.getOrDefault(key, 0);
        burnTicks.put(key, refuel(before, 1));
        held.setAmount(held.getAmount() - 1);
        projectEngine(key);
        actor.sendMessage("§aBoiler refuelled - §e" + (burnTicks.get(key) / 20)
                + "s §aof steam. The flywheel spins up.");
    }

    private void tickBurn() {
        if (burnTicks.isEmpty()) {
            return;
        }
        RotationalNetworkManager manager = rotational.networks();
        for (Map.Entry<String, Integer> entry : burnTicks.entrySet()) {
            int remaining = Math.max(0, entry.getValue() - 1);
            entry.setValue(remaining);
            if (remaining == 0) {
                stopEngine(entry.getKey());
            }
        }
        burnTicks.values().removeIf(ticks -> ticks == 0);
        manager.recompute();
    }

    /** Burn ticks after refuelling with coal (exposed for tests and listeners). */
    public int refuel(int currentBurnTicks, int coalItems) {
        return MachineSpecs.burnTicksAfter(currentBurnTicks, coalItems);
    }

    /** Remaining burn ticks for one engine key. */
    public int burnTicks(String key) {
        return burnTicks.getOrDefault(key, 0);
    }

    /**
     * Projects a fueled steam engine as a rotational source.
     *
     * @param key network key {@code "world;x;y;z"}
     */
    public void projectEngine(String key) {
        RotationalNetworkManager manager = rotational.networks();
        RotationalNetworkManager.Node node = manager.place(key, RotationalNetworkManager.NodeType.STEAM_ENGINE);
        node.sourceRpm = MachineSpecs.STEAM_ENGINE_RPM;
        node.sourceCapacity = MachineSpecs.STEAM_ENGINE_CAPACITY;
        manager.recompute();
    }

    private void stopEngine(String key) {
        RotationalNetworkManager manager = rotational.networks();
        RotationalNetworkManager.Node node = manager.node(key);
        if (node != null) {
            node.sourceRpm = 0f;
            node.sourceCapacity = 0f;
        }
        manager.recompute();
    }
}
