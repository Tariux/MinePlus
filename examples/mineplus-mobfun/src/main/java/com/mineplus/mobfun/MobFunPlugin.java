package com.mineplus.mobfun;

import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.mob.action.EatPlantsAction;
import com.mineplus.infrastructure.mob.action.GrowPlantsAction;
import com.mineplus.infrastructure.mob.action.PlantSaplingAction;
import com.mineplus.infrastructure.mob.action.TorchPlaceAction;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MobFun — Mob behavior showcase module for the Mineplus Core.
 *
 * <p>Demonstrates the Core's mob behavior engine (the budgeted,
 * distance-gated re-implementation of the reference mod's per-tick AI event
 * system) with eight vanilla mobs, each on a different mechanics axis:
 *
 * <ul>
 *   <li><b>Grazer Cow</b> / <b>Grazing Sheep</b> — eat the plant they stand
 *       in (with baby-growth and healing side effects) and keep grassy ground
 *       blooming ({@code eat_plants} + {@code grow_plants}).</li>
 *   <li><b>Reforesting Pig</b> — plants biome-appropriate saplings with
 *       spacing checks ({@code plant_sapling}).</li>
 *   <li><b>Lamplighter Villager</b> — places torches on dark ground
 *       ({@code place_torch}).</li>
 *   <li><b>Florist Bee</b> — plants wildflowers on grass ({@code
 *       plant_flower}); a module-side {@link BehaviorAction} implementation
 *       proving the action interface is fully open to modules.</li>
 *   <li><b>Golem Guardian Villager</b> — only when endangered (hostile
 *       nearby or low health), raises an iron-golem column <b>block by
 *       block</b> that assembles into a real golem which immediately fights
 *       the threat ({@code summon_iron_golem} + {@code inDanger} condition;
 *       per-builder cooldown and a one-golem cap keep raids spam-free).</li>
 *   <li><b>Frost Builder Villager</b> — the same guardian pattern with a
 *       snow golem ({@code summon_snow_golem}).</li>
 *   <li><b>Necromancer Witch</b> — when prey (a villager in sight) or a
 *       predator (a golem within 10 blocks) is around, she fields a small,
 *       space-adaptive mixed horde (husks/strays/drowned/cave spiders per
 *       biome, occasional skeleton horseman) that hunts as one synced crew,
 *       hurls potions at close golems, and retreats behind her own minions
 *       ({@code summon_minions} + {@code threatOrPrey} condition).</li>
 *   <li><b>Pilgrim Villager</b> — villagers in the wastelands locate the
 *       nearest village and journey there in a synced crew; at night the
 *       leader raises one shared camp (campfire + a row of beds, one per
 *       crew member) and the crew holds until dawn; they celebrate on
 *       arrival ({@code village_journey}); /mobfun status lists each
 *       pilgrim's journey state.</li>
 * </ul>
 *
 * <p>Rates are tuned for demonstration (fires are observable within seconds
 * to minutes, not the reference mod's natural half-hour pacing); modules
 * wanting natural pacing simply pass larger interval seconds. The situational
 * showcases gate themselves through profile conditions, so their short
 * intervals only ever fire in the intended situation (danger / villager in
 * sight).
 *
 * <p>Bootstrap follows the module convention (see the MineplusFun reference
 * module): all showcases register first, then the module command is
 * registered. Teardown runs in reverse order. Each phase is
 * exception-isolated so one broken showcase never blocks the others. Test
 * mobs spawned via {@code /mobfun spawn} are tracked and removed on disable.
 */
public final class MobFunPlugin extends JavaPlugin {

    private PluginContext context;
    private MobFunSubCommand command;
    private final List<MobFunShowcase> showcases = new ArrayList<>();
    private JourneyAction journeyAction;

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
                .action(new EatPlantsAction(), 10.0)
                .action(new GrowPlantsAction(), 60.0)
                .build()));
        showcases.add(new MobFunShowcase(this, "sheep", "Grazing Sheep", EntityType.SHEEP,
                context.mobBehaviorApi(), () -> com.mineplus.infrastructure.mob.MobProfile.builder(EntityType.SHEEP)
                .action(new EatPlantsAction(), 10.0)
                .action(new GrowPlantsAction(), 60.0)
                .build()));
        showcases.add(new MobFunShowcase(this, "reforest", "Reforesting Pig", EntityType.PIG,
                context.mobBehaviorApi(), () -> com.mineplus.infrastructure.mob.MobProfile.builder(EntityType.PIG)
                .action(new PlantSaplingAction(), 45.0)
                .condition(BehaviorConditions.adult())
                .build()));
        showcases.add(new MobFunShowcase(this, "lamplighter", "Lamplighter Villager", EntityType.VILLAGER,
                context.mobBehaviorApi(), () -> com.mineplus.infrastructure.mob.MobProfile.builder(EntityType.VILLAGER)
                .action(new TorchPlaceAction(4), 10.0)
                .build()));
        showcases.add(new MobFunShowcase(this, "florist", "Florist Bee", EntityType.BEE,
                context.mobBehaviorApi(), () -> com.mineplus.infrastructure.mob.MobProfile.builder(EntityType.BEE)
                .action(new PlantFlowerAction(), 45.0)
                .build()));
        showcases.add(new MobFunShowcase(this, "golem", "Golem Guardian Villager", EntityType.VILLAGER,
                context.mobBehaviorApi(), () -> com.mineplus.infrastructure.mob.MobProfile.builder(EntityType.VILLAGER)
                .action(new GolemSummonAction(this, "summon_iron_golem", "Summoned Iron Guardian",
                        Material.IRON_BLOCK, EntityType.IRON_GOLEM,
                        Sound.BLOCK_METAL_PLACE, Sound.ENTITY_IRON_GOLEM_REPAIR), 30.0)
                .condition(BehaviorConditions.inDanger(8.0))
                .build()));
        showcases.add(new MobFunShowcase(this, "snowgolem", "Frost Builder Villager", EntityType.VILLAGER,
                context.mobBehaviorApi(), () -> com.mineplus.infrastructure.mob.MobProfile.builder(EntityType.VILLAGER)
                .action(new GolemSummonAction(this, "summon_snow_golem", "Summoned Snow Guardian",
                        Material.SNOW_BLOCK, EntityType.SNOW_GOLEM,
                        Sound.BLOCK_SNOW_PLACE, Sound.BLOCK_SNOW_BREAK), 30.0)
                .condition(BehaviorConditions.inDanger(8.0))
                .build()));
        showcases.add(new MobFunShowcase(this, "necromancer", "Necromancer Witch", EntityType.WITCH,
                context.mobBehaviorApi(), () -> com.mineplus.infrastructure.mob.MobProfile.builder(EntityType.WITCH)
                .action(new SummonMinionHordeAction(this), 12.0)
                .condition(BehaviorConditions.threatOrPrey(12.0, 10.0))
                .build()));
        // One shared JourneyAction instance: the /mobfun status command reads
        // active journey states from it, while profile rebuilds (toggles)
        // keep reusing it - its per-entity state lives on the entities' PDC.
        this.journeyAction = new JourneyAction(this);
        showcases.add(new MobFunShowcase(this, "pilgrim", "Pilgrim Villager", EntityType.VILLAGER,
                context.mobBehaviorApi(), () -> com.mineplus.infrastructure.mob.MobProfile.builder(EntityType.VILLAGER)
                .action(journeyAction, 2.0)
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
            this.command = new MobFunSubCommand(this, showcases, journeyAction);
            context.moduleSupport().registerCommand(this, "mobfun", this.command);
        } catch (Exception exception) {
            getLogger().severe("Command registration failed: " + exception);
        }

        getLogger().info("[MobFun] " + showcases.size()
                + " showcases (Grazer Cow, Grazing Sheep, Reforesting Pig, Lamplighter Villager, Florist Bee,"
                + " Golem Guardian Villager, Frost Builder Villager, Necromancer Witch, Pilgrim Villager)"
                + " enabled on top of Mineplus Core.");
    }

    @Override
    public void onDisable() {
        if (command != null) {
            try {
                command.purgeTestMobs();
            } catch (Exception exception) {
                getLogger().severe("Test-mob cleanup failed: " + exception);
            }
            command = null;
        }
        for (int i = showcases.size() - 1; i >= 0; i--) {
            try {
                showcases.get(i).stop();
            } catch (Exception exception) {
                getLogger().severe("Showcase '" + showcases.get(i).id() + "' failed to disable cleanly: "
                        + exception);
            }
        }
        showcases.clear();
        this.context = null;
    }

    PluginContext context() {
        return context;
    }
}
