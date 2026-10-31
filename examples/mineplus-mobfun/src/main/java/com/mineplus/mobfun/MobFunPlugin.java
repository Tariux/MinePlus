package com.mineplus.mobfun;

import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.fx.ParticleFx;
import com.mineplus.infrastructure.mob.BehaviorContext;
import com.mineplus.infrastructure.mob.MobBehaviorApi;
import com.mineplus.infrastructure.mob.MobProfile;
import com.mineplus.infrastructure.mob.action.AttackNearestAction;
import com.mineplus.infrastructure.mob.action.EatPlantsAction;
import com.mineplus.infrastructure.mob.action.GrowPlantsAction;
import com.mineplus.infrastructure.mob.action.JourneyToStructureAction;
import com.mineplus.infrastructure.mob.action.PlantSaplingAction;
import com.mineplus.infrastructure.mob.action.TorchPlaceAction;
import com.mineplus.infrastructure.mob.custom.BedrockMobModel;
import com.mineplus.infrastructure.mob.custom.CustomMobAttributes;
import com.mineplus.infrastructure.mob.custom.CustomMobDefinition;
import com.mineplus.infrastructure.mob.custom.CustomMobModel;
import com.mineplus.infrastructure.mob.group.Formation;
import com.mineplus.infrastructure.mob.journey.JourneyRunner;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowman;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MobFun — Mob behavior showcase module for the Mineplus Core.
 *
 * <p>Demonstrates the Core's mob behavior engine (the budgeted, distance-gated,
 * arbitrated re-implementation of the reference mod's per-tick AI event system)
 * with eight vanilla mobs, each on a different mechanics axis:
 *
 * <ul>
 *   <li><b>Grazer Cow</b> / <b>Grazing Sheep</b> — eat the plant they stand
 *       in (with baby-growth and healing side effects) and keep grassy ground
 *       blooming ({@code eat_plants} + {@code grow_plants}).</li>
 *   <li><b>Reforesting Pig</b> — plants biome-appropriate saplings with
 *       spacing checks ({@code plant_sapling}, gated by the Core's
 *       {@code adult_only} condition).</li>
 *   <li><b>Lamplighter Villager</b> — places torches on dark ground
 *       ({@code place_torch}).</li>
 *   <li><b>Florist Bee</b> — plants wildflowers on grass ({@code
 *       plant_flower}); a module-side {@link com.mineplus.infrastructure.mob.BehaviorAction}
 *       implementation proving the action interface is fully open to modules.</li>
 *   <li><b>Golem Guardian Villager</b> — only when endangered (hostile
 *       nearby or low health), raises an iron-golem column <b>block by
 *       block</b> under one engine-granted {@code MobEditSession} and assembles
 *       it into a real golem that immediately fights the threat
 *       ({@code summon_iron_golem} + {@code inDanger} condition; per-builder
 *       cooldown and a one-golem cap keep raids spam-free).</li>
 *   <li><b>Frost Builder Villager</b> — the same guardian pattern with a snow
 *       golem ({@code summon_snow_golem}).</li>
 *   <li><b>Necromancer Witch</b> — a mob pipeline, not a single action: she
 *       finds the nearest village (the Core anchors it to its bell), marches
 *       there through the shared journey runner, then raids it — ringing the
 *       bell, keeping every surviving minion aimed at the nearest villager,
 *       fielding a biome-matched mixed horde through the engine's spawn budget
 *       and breaking off when the village is empty. Between raids she falls
 *       back to her ambient horde ({@code necromancer_raid} +
 *       {@code summon_minions}).</li>
 *   <li><b>Pilgrim Villager</b> — the Core's {@code journey_to_structure}
 *       scenario with this module's camp and celebration: the village is found
 *       through the engine's structure cache, the crew marches through the
 *       {@code JourneyRunner} (legs inside path range, stalls routed around)
 *       and holds cohesion, night halts the crew together while the leader
 *       raises one shared camp (campfire + a contiguous bed row, one bed per
 *       crew member), and arrival celebrates. Guardians keep their post: the
 *       profile is gated by {@code notBuilder}, and the journey's activity
 *       claim means no other villager showcase can pull a marching pilgrim
 *       somewhere else.</li>
 *   <li><b>Evil Pig</b> — the first <em>custom</em> mob, not a profile on a
 *       vanilla mob: a vanilla pig base with a Mineplus identity
 *       ({@code mobfun:evil_pig}), the vanilla pig model and a replacement
 *       texture, no vanilla AI at all, and one Core action
 *       ({@code attack_nearest}) that hunts the nearest player or villager.
 *       Registered through {@code CustomMobApi}; spawned through
 *       {@code /mobfun spawn evilpig}.</li>
 *   <li><b>Villager Soldier</b> — a fully authored custom mob: the Blockbench
 *       soldier model ({@code mobs/soldier_villager.bbmodel}, a 22-group rig
 *       with per-face UVs and its own {@code walk}/{@code idle} clips) on a
 *       villager base, armed with a spear and one Core action
 *       ({@code attack_nearest}) that hunts hostile mobs. It is the showcase
 *       for {@code BedrockMobModel}: the outliner becomes the render rig and
 *       the file's clips are played by the Core, so the legs swing while it
 *       walks and the whole rig breathes while it stands. Spawned through
 *       {@code /mobfun spawn soldier}.</li>
 *   <li><b>Woodland Trickster</b> — a second fully authored custom mob
 *       ({@code mobs/woodland_trickster.bbmodel}) on a villager base: the
 *       file's own bone tree (body, head, hood, cloth, arms, legs) with its
 *       authored rest rotations, and three clips — {@code animation.idle},
 *       {@code animation.walk} and {@code animation.attack}. Idle and walk
 *       blend by movement; the attack clip is played by the Core's rig whenever
 *       the mob lands a hit. It hunts players. Spawned through
 *       {@code /mobfun spawn trickster}.</li>
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
    private final List<MobFunShowcaseEntry> showcases = new ArrayList<>();
    /** Embedded Evil Pig texture; also the factory's stable source on re-enable. */
    private byte[] evilPigTexture;
    /** Imported Villager Soldier model, or {@code null} when its resources failed to load. */
    private CustomMobModel soldierModel;
    /** Imported Woodland Trickster model, or {@code null} when its resources failed to load. */
    private CustomMobModel tricksterModel;

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
        MobBehaviorApi mobApi = context.mobBehaviorApi();
        ParticleFx fx = context.particleFx();

        showcases.add(new MobFunShowcase(this, "grazer", "Grazer Cow", EntityType.COW,
                mobApi, () -> MobProfile.builder(EntityType.COW)
                .action(new EatPlantsAction(), 10.0)
                .action(new GrowPlantsAction(), 60.0)
                .build()));
        showcases.add(new MobFunShowcase(this, "sheep", "Grazing Sheep", EntityType.SHEEP,
                mobApi, () -> MobProfile.builder(EntityType.SHEEP)
                .action(new EatPlantsAction(), 10.0)
                .action(new GrowPlantsAction(), 60.0)
                .build()));
        showcases.add(new MobFunShowcase(this, "reforest", "Reforesting Pig", EntityType.PIG,
                mobApi, () -> MobProfile.builder(EntityType.PIG)
                .action(new PlantSaplingAction(), 45.0)
                .condition(BehaviorContext.Conditions.adultOnly())
                .build()));
        showcases.add(new MobFunShowcase(this, "lamplighter", "Lamplighter Villager", EntityType.VILLAGER,
                mobApi, () -> MobProfile.builder(EntityType.VILLAGER)
                .priority(BehaviorPriority.LAMPLIGHTER)
                .action(new TorchPlaceAction(4), 10.0)
                .build()));
        showcases.add(new MobFunShowcase(this, "florist", "Florist Bee", EntityType.BEE,
                mobApi, () -> MobProfile.builder(EntityType.BEE)
                .action(new PlantFlowerAction(fx), 45.0)
                .build()));
        showcases.add(new MobFunShowcase(this, "golem", "Golem Guardian Villager", EntityType.VILLAGER,
                mobApi, () -> MobProfile.builder(EntityType.VILLAGER)
                .priority(BehaviorPriority.GUARDIAN)
                .action(new GolemSummonAction(this, mobApi, fx, "summon_iron_golem",
                        "Summoned Iron Guardian", Material.IRON_BLOCK, EntityType.IRON_GOLEM,
                        IronGolem.class, Sound.BLOCK_METAL_PLACE, Sound.ENTITY_IRON_GOLEM_REPAIR), 30.0)
                .condition(BehaviorConditions.inDanger(8.0))
                .build()));
        showcases.add(new MobFunShowcase(this, "snowgolem", "Frost Builder Villager", EntityType.VILLAGER,
                mobApi, () -> MobProfile.builder(EntityType.VILLAGER)
                .priority(BehaviorPriority.GUARDIAN)
                .action(new GolemSummonAction(this, mobApi, fx, "summon_snow_golem",
                        "Summoned Snow Guardian", Material.SNOW_BLOCK, EntityType.SNOW_GOLEM,
                        Snowman.class, Sound.BLOCK_SNOW_PLACE, Sound.BLOCK_SNOW_BREAK), 30.0)
                .condition(BehaviorConditions.inDanger(8.0))
                .build()));
        showcases.add(new MobFunShowcase(this, "necromancer", "Necromancer Witch", EntityType.WITCH,
                mobApi, () -> MobProfile.builder(EntityType.WITCH)
                .priority(BehaviorPriority.NECROMANCER_RAID)
                .action(new NecromancerRaidAction(this, fx), 6.0)
                .action(new SummonMinionHordeAction(this, fx), 2.0)
                .build()));
        // One shared journey instance: its scenario state (crews, camps, the
        // not-found report) lives on the engine's services and in itself, so a
        // profile rebuild on toggle never strands a march halfway across a map.
        // The stride walks a little faster than a villager's ordinary amble —
        // a pilgrim with a destination is in a hurry, and the wasteland is
        // meant to be crossed, not loitered in.
        JourneyToStructureAction pilgrim = JourneyToStructureAction.builder()
                .id("village_journey")
                .structure("village")
                .crewName(PilgrimCamp.CREW)
                .searchRadiusChunks(64)
                .arrivalDistance(16)
                .cohesionRadius(12)
                .recruitRadius(24)
                .marchLeg(32)
                .gatherRadius(8)
                .detachRadius(64)
                // A camp is the end of a march, not a rest stop in the middle
                // of one: while the village is still far away the crew walks
                // through the night instead of doubling the crossing.
                .campDistance(96)
                .formation(Formation.WEDGE)
                // A pilgrim with a destination is in a hurry: a faster stride
                // and a shorter repath keep the walk continuous and the crew
                // closing on the village instead of ambling across the map.
                .stride(new JourneyRunner.Options(1.9, 6.0, 8.0, 32, 30L, 40L, 1.5, 6, 0.0))
                .callbacks(new PilgrimCamp(this, fx))
                .build();
        showcases.add(new MobFunShowcase(this, "pilgrim", "Pilgrim Villager", EntityType.VILLAGER,
                mobApi, () -> MobProfile.builder(EntityType.VILLAGER)
                .priority(BehaviorPriority.PILGRIM)
                .action(pilgrim, 2.0)
                .condition(BehaviorConditions.notBuilder(this))
                .build()));

        evilPigTexture = loadResource("mobs/evil_pig.png");
        if (evilPigTexture != null) {
            showcases.add(new CustomMobShowcase(this, context.customMobs(), "evilpig", "Evil Pig",
                    EntityType.PIG, this::evilPigDefinition));
        } else {
            getLogger().severe("Evil Pig NOT registered: resource mobs/evil_pig.png is missing from the jar.");
        }

        soldierModel = loadSoldierModel();
        if (soldierModel != null) {
            showcases.add(new CustomMobShowcase(this, context.customMobs(), "soldier", "Villager Soldier",
                    EntityType.VILLAGER, this::soldierDefinition));
        }

        tricksterModel = loadTricksterModel();
        if (tricksterModel != null) {
            showcases.add(new CustomMobShowcase(this, context.customMobs(), "trickster", "Woodland Trickster",
                    EntityType.VILLAGER, this::tricksterDefinition));
        }

        for (MobFunShowcaseEntry showcase : showcases) {
            try {
                showcase.start();
            } catch (Exception exception) {
                getLogger().severe("Showcase '" + showcase.id() + "' failed to enable; isolating and continuing: "
                        + exception);
            }
        }

        try {
            this.command = new MobFunSubCommand(this, showcases);
            context.moduleSupport().registerCommand(this, "mobfun", this.command);
        } catch (Exception exception) {
            getLogger().severe("Command registration failed: " + exception);
        }

        getLogger().info("[MobFun] " + showcases.size()
                + " showcases (Grazer Cow, Grazing Sheep, Reforesting Pig, Lamplighter Villager, Florist Bee,"
                + " Golem Guardian Villager, Frost Builder Villager, Necromancer Witch, Pilgrim Villager,"
                + " Evil Pig, Villager Soldier, Woodland Trickster) enabled on top of Mineplus Core.");
    }

    /**
     * The Evil Pig: the vanilla pig model with a replacement texture, its own
     * identity and exactly one behavior. Vanilla AI is cleared by the Core on
     * spawn, so every action this mob takes comes from {@code attack_nearest}.
     * A definition with no model would still work (the base pig stays visible);
     * the model is what makes it "evil".
     *
     * <p>The rig here is not decoration: a 1.21.1 client has no entity-model
     * override channel, so the only way a retextured pig can show its own
     * texture is as the per-part displays the Core builds from the vanilla pig
     * descriptor. {@code AUTO} asks for that rig and keeps the plain pig when the
     * pack cannot present it.</p>
     */
    private CustomMobDefinition evilPigDefinition() {
        CustomMobModel model = CustomMobModel.vanilla(EntityType.PIG, evilPigTexture);
        return CustomMobDefinition.builder("mobfun:evil_pig", EntityType.PIG, model)
                .displayName("Evil Pig")
                .showName(true)
                .visual(CustomMobDefinition.VisualMode.AUTO)
                .attributes(CustomMobAttributes.builder()
                        .maxHealth(12.0)
                        .movementSpeed(0.28)
                        .attackDamage(4.0)
                        .followRange(24.0)
                        .knockbackResistance(0.1)
                        .build())
                .behavior(profile -> profile
                        .priority(BehaviorPriority.EVIL_PIG)
                        .action(AttackNearestAction.builder()
                                .searchRadius(18.0)
                                .reach(2.5)
                                .speed(1.2)
                                .damage(4.0)
                                .cooldownTicks(20L)
                                .knockback(0.4)
                                .build(), 1.0))
                .build();
    }

    /**
     * Imports the Blockbench soldier with its own outliner and its own clips.
     *
     * <p>Every outliner group becomes a render bone (pivot, parent, geometry),
     * so the spear hand, the helmet and the knees articulate exactly as
     * authored; the file's {@code animation.walk} and {@code animation.idle}
     * clips drive them, blended by movement speed. Nothing is hand-mapped, so
     * re-exporting the model with more groups or other clips needs no code
     * change.</p>
     */
    private CustomMobModel loadSoldierModel() {
        byte[] modelJson = loadResource("mobs/soldier_villager.bbmodel");
        byte[] texture = loadResource("mobs/soldier_villager.png");
        if (modelJson == null || texture == null) {
            getLogger().severe("Villager Soldier NOT registered: mobs/soldier_villager.bbmodel and"
                    + " mobs/soldier_villager.png must both be present in the jar.");
            return null;
        }
        try {
            return BedrockMobModel.parse(modelJson)
                    .textureName("soldier_villager")
                    .partsFromOutliner()
                    .animationClips()
                    .build(texture);
        } catch (RuntimeException malformed) {
            getLogger().severe("Villager Soldier NOT registered: " + malformed.getMessage());
            return null;
        }
    }

    /**
     * The Villager Soldier: an imported model on a villager base with one job —
     * guard the village. Vanilla AI is cleared by the Core, so the spear only
     * moves through {@code attack_nearest}, aimed at hostile mobs rather than
     * players or villagers.
     *
     * <p>It moves like an iron golem: the attribute profile starts from
     * {@link EntityType#IRON_GOLEM}'s captured defaults (health, knockback,
     * scale, gravity and the rest of the golem's numbers), and only the fields
     * below deviate from that template. Health and armor are deliberately high:
     * the rig detaches on death (a displayed mob has no corpse), and a showcase
     * that a single shotgun blast erases cannot be inspected while it reacts to
     * damage.</p>
     *
     * <p>{@code visual(RIG)} is a demand, not a preference: if no part display
     * can be spawned, the Core says so in the log and in {@code /mobfun rig
     * soldier} instead of quietly leaving a villager standing where a soldier
     * should be.</p>
     */
    private CustomMobDefinition soldierDefinition() {
        return CustomMobDefinition.builder("mobfun:villager_soldier", EntityType.VILLAGER, soldierModel)
                .displayName("Villager Soldier")
                .visual(CustomMobDefinition.VisualMode.RIG)
                .attributes(CustomMobAttributes.builder()
                        .fromTemplate(EntityType.IRON_GOLEM)
                        .maxHealth(60.0)
                        .movementSpeed(0.3)
                        .attackDamage(6.0)
                        .followRange(28.0)
                        .armor(10.0)
                        .knockbackResistance(0.3)
                        .build())
                .behavior(profile -> profile
                        .priority(BehaviorPriority.SOLDIER)
                        .action(AttackNearestAction.builder()
                                .searchRadius(20.0)
                                .reach(3.4)
                                .speed(1.2)
                                .damage(6.0)
                                .cooldownTicks(26L)
                                .knockback(0.45)
                                .target(candidate -> candidate instanceof Enemy)
                                .build(), 1.0))
                .build();
    }

    /**
     * Imports the Woodland Trickster with its own outliner, rest rotations and
     * its three clips ({@code animation.idle}, {@code animation.walk},
     * {@code animation.attack}).
     */
    private CustomMobModel loadTricksterModel() {
        byte[] modelJson = loadResource("mobs/woodland_trickster.bbmodel");
        byte[] texture = loadResource("mobs/woodland_trickster.png");
        if (modelJson == null || texture == null) {
            getLogger().severe("Woodland Trickster NOT registered: mobs/woodland_trickster.bbmodel and"
                    + " mobs/woodland_trickster.png must both be present in the jar.");
            return null;
        }
        try {
            return BedrockMobModel.parse(modelJson)
                    .textureName("woodland_trickster")
                    .partsFromOutliner()
                    .animationClips()
                    .build(texture);
        } catch (RuntimeException malformed) {
            getLogger().severe("Woodland Trickster NOT registered: " + malformed.getMessage());
            return null;
        }
    }

    /**
     * The Woodland Trickster: a small hunched woodland creature on a villager
     * base that hunts players. Its rig is the file's own bone tree (hood over
     * head, cloth over the legs) and its clips drive the whole pose: idle
     * breathing while it stands, a leg/arm swing while it walks, and a one-shot
     * punch the Core plays when it swings — not only when it lands.
     *
     * <p>Its body numbers come from {@link EntityType#ZOMBIE}: the template is
     * captured once and supplies attack knockback, scale and gravity, while the
     * four fields below are this creature's own. Nothing here reaches a real
     * zombie — the profile is keyed to the custom id.</p>
     */
    private CustomMobDefinition tricksterDefinition() {
        return CustomMobDefinition.builder("mobfun:woodland_trickster", EntityType.VILLAGER, tricksterModel)
                .displayName("Woodland Trickster")
                .visual(CustomMobDefinition.VisualMode.RIG)
                .attributes(CustomMobAttributes.builder()
                        .fromTemplate(EntityType.ZOMBIE)
                        .maxHealth(30.0)
                        .movementSpeed(0.32)
                        .attackDamage(4.0)
                        .followRange(24.0)
                        .armor(2.0)
                        .knockbackResistance(0.1)
                        .build())
                .behavior(profile -> profile
                        .priority(BehaviorPriority.TRICKSTER)
                        .action(AttackNearestAction.builder()
                                .searchRadius(16.0)
                                .reach(2.6)
                                .speed(1.2)
                                .damage(4.0)
                                .cooldownTicks(24L)
                                .knockback(0.35)
                                .target(candidate -> candidate instanceof Player)
                                .build(), 1.0))
                .build();
    }

    private byte[] loadResource(String path) {
        try (InputStream stream = getResource(path)) {
            return stream == null ? null : stream.readAllBytes();
        } catch (IOException unreadable) {
            getLogger().severe("Could not read embedded resource '" + path + "': " + unreadable.getMessage());
            return null;
        }
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
