package com.mineplus.mobfun;

import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.infrastructure.mob.action.EatPlantsAction;
import com.mineplus.infrastructure.mob.action.GrowPlantsAction;
import com.mineplus.infrastructure.mob.action.PlantSaplingAction;
import com.mineplus.infrastructure.mob.action.TorchPlaceAction;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MobFun — Mob behavior showcase module for the Mineplus Core.
 *
 * <p>Demonstrates the Core's mob behavior engine (the budgeted,
 * distance-gated re-implementation of the reference mod's per-tick AI event
 * system) with three vanilla mob types, each on a different mechanics axis:
 *
 * <ul>
 *   <li><b>Grazer Cow</b> — eats the plant it stands in (with baby-growth and
 *       healing side effects) and keeps grassy ground blooming
 *       ({@code eat_plants} + {@code grow_plants}).</li>
 *   <li><b>Reforesting Pig</b> — plants biome-appropriate saplings with
 *       spacing checks ({@code plant_sapling}).</li>
 *   <li><b>Lamplighter Villager</b> — places torches on dark ground
 *       ({@code place_torch}).</li>
 * </ul>
 *
 * <p>Bootstrap follows the module convention (see the MineplusFun reference
 * module): all showcases register first, then the module command is
 * registered. Teardown runs in reverse order. Each phase is
 * exception-isolated so one broken showcase never blocks the others.
 */
public final class MobFunPlugin extends JavaPlugin {

    private PluginContext context;
    private final List<MobFunShowcase> showcases = new ArrayList<>();

    @Override
    public void onEnable() {
        org.bukkit.plugin.Plugin core = Bukkit.getPluginManager().getPlugin("Mineplus");
        if (!(core instanceof MineplusPlugin mineplus)) {
            getLogger().severe("[MobFun] FATAL: Mineplus Core plugin was not found.");
            getLogger().severe("[MobFun] Install 'Mineplus.jar' (Core) into your plugins/ folder first, then restart.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.context = mineplus.getPluginContext();
        if (this.context == null) {
            getLogger().severe("[MobFun] Mineplus Core is not initialized; disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        showcases.add(new MobFunShowcase(this, "grazer", "Grazer Cow", EntityType.COW,
                context.mobBehaviorApi(), () -> com.mineplus.infrastructure.mob.MobProfile.builder(EntityType.COW)
                .action(new EatPlantsAction(), 45.0)
                .action(new GrowPlantsAction(), 300.0)
                .build()));
        showcases.add(new MobFunShowcase(this, "reforest", "Reforesting Pig", EntityType.PIG,
                context.mobBehaviorApi(), () -> com.mineplus.infrastructure.mob.MobProfile.builder(EntityType.PIG)
                .action(new PlantSaplingAction(), 450.0)
                .condition(BehaviorConditions.adult())
                .build()));
        showcases.add(new MobFunShowcase(this, "lamplighter", "Lamplighter Villager", EntityType.VILLAGER,
                context.mobBehaviorApi(), () -> com.mineplus.infrastructure.mob.MobProfile.builder(EntityType.VILLAGER)
                .action(new TorchPlaceAction(4), 40.0)
                .build()));

        for (MobFunShowcase showcase : showcases) {
            try {
                showcase.start();
            } catch (Exception exception) {
                getLogger().severe("Showcase '" + showcase.id() + "' failed to enable; isolating and continuing: "
                        + exception);
            }
        }

        try {
            context.moduleSupport().registerCommand(this, "mobfun", new MobFunSubCommand(this, showcases));
        } catch (Exception exception) {
            getLogger().severe("Command registration failed: " + exception);
        }

        getLogger().info("[MobFun] " + showcases.size()
                + " showcases (Grazer Cow, Reforesting Pig, Lamplighter Villager) enabled on top of Mineplus Core.");
    }

    @Override
    public void onDisable() {
        for (int i = showcases.size() - 1; i >= 0; i--) {
            try {
                showcases.get(i).stop();
            } catch (Exception exception) {
                getLogger().severe("Showcase '" + showcases.get(i).id() + "' failed to disable cleanly: " + exception);
            }
        }
        showcases.clear();
        this.context = null;
    }

    PluginContext context() {
        return context;
    }
}
