package com.mineplus.create.machines;

import com.mineplus.create.rotational.RotationalNetworkFeature;
import com.mineplus.create.rotational.RotationalNetworkManager;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import com.mineplus.infrastructure.core.recipes.MachineRecipe;
import com.mineplus.infrastructure.module.ModuleFeature;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * T0–T1 machines: hand crank, water wheel, windmill (sources) and millstone,
 * mechanical press (stress consumers). Resources ship in this module's jar and
 * are installed into the Core data folder via {@code moduleSupport()}; each
 * machine registers a hook that projects the instance into the pure-Java
 * {@link RotationalNetworkManager} as a node keyed by {@code "world;x;y;z"}.
 *
 * <p>Interactions: right-clicking a hand crank drives a 60-tick rotational
 * burst (the crank is burst-driven, not a standing source); right-clicking a
 * millstone or press with the recipe's input item consumes it from the hand and
 * starts the Core process; when the process completes the hook pays out the
 * recipe's outputs as named vanilla carrier items ({@link CreateItems}).</p>
 */
public final class MachinesFeature extends ModuleFeature {

    private static final long CRANK_BURST_TICKS = 60L;

    private final RotationalNetworkFeature rotational;
    private final com.mineplus.create.towns.TownsFeature towns;

    public MachinesFeature(JavaPlugin plugin, PluginContext context, RotationalNetworkFeature rotational,
                           com.mineplus.create.towns.TownsFeature towns) {
        super(plugin, context);
        this.rotational = rotational;
        this.towns = towns;
    }

    @Override
    public String id() {
        return "machines";
    }

    @Override
    protected void onEnable() {
        var support = context.moduleSupport();
        for (String model : new String[] {
                "mc-shaft", "mc-cogwheel", "mc-hand-crank", "mc-water-wheel", "mc-millstone", "mc-mechanical-press"}) {
            support.installDefault(plugin, "defaults/models/" + model + ".bbmodel", "models/" + model + ".bbmodel", true);
            support.installDefault(plugin, "defaults/models/" + model + ".meta.json", "models/" + model + ".meta.json", true);
        }
        installMachineType(MachineSpecs.HAND_CRANK);
        installMachineType(MachineSpecs.WATER_WHEEL);
        installMachineType(MachineSpecs.WINDMILL);
        installMachineType(MachineSpecs.MILLSTONE);
        installMachineType(MachineSpecs.MECHANICAL_PRESS);
        installMachineType(MachineSpecs.STEAM_ENGINE);
        installMachineType(MachineSpecs.DYNAMO);
        support.installDefault(plugin, "defaults/recipes/mc_machine_recipes.json", "recipes/mc_machine_recipes.json", false);
    }

    private void installMachineType(String typeId) {
        var support = context.moduleSupport();
        support.installDefault(plugin, "defaults/multiblocks/" + typeId + ".json",
                "multiblocks/" + typeId + ".json", false);
        registerMachineHook(typeId);
    }

    private void registerMachineHook(String typeId) {
        context.infrastructureApi().registerHook(typeId, new MultiBlockHook() {
            @Override
            public void onPlace(MultiBlockInstance instance, Player actor) {
                project(instance);
            }

            @Override
            public void onInteract(MultiBlockInstance instance, Player actor) {
                interact(instance, actor);
            }

            @Override
            public void onProcessComplete(MultiBlockInstance instance, MachineRecipe recipe) {
                payOutputs(instance, recipe);
            }

            @Override
            public void onBreak(MultiBlockInstance instance, Player actor) {
                rotational.networks().remove(key(instance));
            }

            @Override
            public void onRemove(MultiBlockInstance instance, Player actor) {
                rotational.networks().remove(key(instance));
            }
        });
    }

    private void interact(MultiBlockInstance instance, Player actor) {
        switch (instance.typeId()) {
            case MachineSpecs.HAND_CRANK -> crankBurst(instance, actor);
            case MachineSpecs.MILLSTONE, MachineSpecs.MECHANICAL_PRESS -> tryStartRecipe(instance, actor);
            default -> { }
        }
    }

    /** Drives the crank: a short-lived source impulse that spins the network. */
    private void crankBurst(MultiBlockInstance instance, Player actor) {
        String key = key(instance);
        RotationalNetworkManager manager = rotational.networks();
        RotationalNetworkManager.Node node = manager.node(key);
        if (node == null) {
            project(instance);
            node = manager.node(key);
        }
        RotationalNetworkManager.Node crank = node;
        crank.sourceRpm = MachineSpecs.sourceRpm(MachineSpecs.HAND_CRANK);
        crank.sourceCapacity = MachineSpecs.sourceCapacity(MachineSpecs.HAND_CRANK);
        manager.recompute();
        BukkitTask existing = crankTasks.remove(key);
        if (existing != null) {
            existing.cancel();
        }
        crankTasks.put(key, plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            crankTasks.remove(key);
            RotationalNetworkManager.Node current = manager.node(key);
            if (current != null) {
                current.sourceRpm = 0f;
                current.sourceCapacity = 0f;
                manager.recompute();
            }
        }, CRANK_BURST_TICKS));
        actor.sendMessage("§7You crank the handle - the shaft starts turning (§e"
                + (CRANK_BURST_TICKS / 20) + "s§7).");
    }

    private void tryStartRecipe(MultiBlockInstance instance, Player actor) {
        ItemStack held = actor.getInventory().getItemInMainHand();
        List<MachineRecipe> recipes = context.infrastructureEngine().recipeManager()
                .getForMachine(instance.typeId(), 1);
        if (recipes == null || recipes.isEmpty()) {
            actor.sendMessage("§7The machine hums, but no recipes are loaded.");
            return;
        }
        for (MachineRecipe recipe : recipes) {
            boolean inputOk = recipe.input().isEmpty()
                    || recipe.input().entrySet().stream()
                            .allMatch(entry -> CreateItems.matches(entry.getKey(), held));
            if (!inputOk) {
                continue;
            }
            if (context.infrastructureApi().startProcess(instance.id(), recipe.id())) {
                if (!recipe.input().isEmpty()) {
                    held.setAmount(held.getAmount() - 1);
                }
                interactors.put(instance.id(), actor);
                actor.sendMessage("§aWorking: §e" + recipe.id() + " §7- right-click again when it finishes.");
                return;
            }
            actor.sendMessage("§cThe machine is already busy.");
            return;
        }
        actor.sendMessage("§cThe machine rejects that item. Inputs:");
        for (MachineRecipe recipe : recipes) {
            actor.sendMessage(" §7- §e" + recipe.id() + "§7: " + recipe.input().keySet());
        }
    }

    private void payOutputs(MultiBlockInstance instance, MachineRecipe recipe) {
        Player player = lastInteractor(instance);
        for (var entry : recipe.output().entrySet()) {
            ItemStack out = CreateItems.output(entry.getKey(), entry.getValue());
            if (out == null) {
                plugin.getLogger().warning("[machines] no carrier item for output " + entry.getKey());
                continue;
            }
            if (player != null) {
                towns.recordProduction(player.getUniqueId(), entry.getKey(), entry.getValue());
            }
            if (player != null && player.isOnline()) {
                player.getInventory().addItem(out).values()
                        .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
            } else {
                var block = instance.coordinate();
                var world = plugin.getServer().getWorld(block.worldName());
                if (world != null) {
                    world.dropItemNaturally(new org.bukkit.Location(world,
                            block.x() + 0.5, block.y() + 0.5, block.z() + 0.5), out);
                }
            }
        }
    }


    private Player lastInteractor(MultiBlockInstance instance) {
        return interactors.get(instance.id());
    }

    private final java.util.Map<String, BukkitTask> crankTasks = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Player> interactors = new java.util.HashMap<>();

    /** Adds (or refreshes) the network node for one machine instance. */
    private void project(MultiBlockInstance instance) {
        String typeId = instance.typeId();
        RotationalNetworkManager manager = rotational.networks();
        RotationalNetworkManager.Node node = manager.place(key(instance), nodeType(typeId));
        node.stress = MachineSpecs.stressOf(typeId);
        // The hand crank is burst-driven (onInteract), so it does not project a
        // standing source; every other source produces its spec RPM.
        if (MachineSpecs.isSource(typeId) && !MachineSpecs.HAND_CRANK.equals(typeId)) {
            node.sourceRpm = MachineSpecs.sourceRpm(typeId);
            node.sourceCapacity = MachineSpecs.sourceCapacity(typeId);
        }
        manager.recompute();
    }

    private static RotationalNetworkManager.NodeType nodeType(String typeId) {
        return switch (typeId) {
            case MachineSpecs.HAND_CRANK -> RotationalNetworkManager.NodeType.CRANK;
            case MachineSpecs.WATER_WHEEL -> RotationalNetworkManager.NodeType.WATER_WHEEL;
            case MachineSpecs.WINDMILL -> RotationalNetworkManager.NodeType.WINDMILL;
            case MachineSpecs.STEAM_ENGINE -> RotationalNetworkManager.NodeType.STEAM_ENGINE;
            default -> RotationalNetworkManager.NodeType.SHAFT;
        };
    }

    public static String key(MultiBlockInstance instance) {
        var coordinate = instance.coordinate();
        return coordinate.worldName() + ";" + coordinate.x() + ";"
                + coordinate.y() + ";" + coordinate.z();
    }
}
