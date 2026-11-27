package com.mineplus.machine;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.network.rotation.RotationNetworkService;
import com.mineplus.infrastructure.module.ModuleFeature;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Machine — Create-style mechanical power showcase for Mineplus Core.
 *
 * <p>Everything kinetic is config: each {@code defaults/multiblocks/*.json}
 * declares its behaviour through {@code rotation.*} guiOptions
 * ({@code kind}, {@code su}, {@code stress}, {@code speed}, {@code cogwheel}),
 * and the Core's {@link RotationNetworkService} picks those up at type
 * registration. This plugin only wires the render side: each solved member
 * gets its model's {@code spin} (wheels, shafts, gears) or {@code swing}
 * (lever) clip replayed at the network's live speed, with sign preserved so
 * meshed cogwheels visibly counter-rotate. Interacting with a brake lever
 * toggles it between engaged and released.
 */
public final class MineplusMachinePlugin extends ModuleFeature {

    private static final String SPIN_CLIP = "spin";
    private static final String SWING_CLIP = "swing";
    private static final String BRAKE_TYPE_ID = "rotation_brake";

    private final Map<UUID, Float> lastSpeeds = new HashMap<>();
    private final Map<UUID, Boolean> braked = new HashMap<>();
    private MachineItemListener machineItems;
    private WaterFlowTask waterFlow;

    public MineplusMachinePlugin(JavaPlugin plugin, com.mineplus.infrastructure.PluginContext context) {
        super(plugin, context);
    }

    @Override
    public String id() {
        return "machine";
    }

    @Override
    protected void onEnable() {
        var support = context.moduleSupport();
        installModel("water_wheel");
        installModel("water_wheel_weak");
        installModel("water_wheel_strong");
        installModel("rotation_shaft", "shaft");
        installModel("cogwheel", "gear");
        installModel("cogwheel_vertical", "gear_vertical");
        installModel("cogwheel_horizontal", "gear_horizontal");
        installModel("rotation_brake", "lever_handle");
        for (String typeId : new String[]{
                "water_wheel", "water_wheel_weak", "water_wheel_strong",
                "rotation_shaft", "cogwheel", "cogwheel_vertical", "cogwheel_horizontal",
                "rotation_brake"}) {
            support.installDefault(plugin, "defaults/multiblocks/" + typeId + ".json",
                    "multiblocks/" + typeId + ".json", false);
        }

        // The block layer (blocks/mineplus/*.json) is what /mineplus blocks
        // list|give|place read. The Core loads it during its own enable, before
        // this module runs, so install ours and re-run the load; the same
        // registry backs the placement service, item factory and commands.
        for (String id : new String[]{"shaft", "water_wheel_weak", "water_wheel_strong"}) {
            support.installDefault(plugin, "defaults/blocks/mineplus/" + id + ".json",
                    "blocks/mineplus/" + id + ".json", true);
        }
        support.reloadBlocks();

        context.rotationNetworks().setStateSink((instance, state) -> render(instance, state));
        Bukkit.getPluginManager().registerEvents(new BrakeListener(this), plugin);
        machineItems = new MachineItemListener(this);
        Bukkit.getPluginManager().registerEvents(machineItems, plugin);
        waterFlow = new WaterFlowTask(this);
        Bukkit.getPluginManager().registerEvents(waterFlow, plugin);
        // Continuous reconcile: wheels must follow water even when no block
        // event reaches us (chunk load, neighbouring schematics, missed events).
        Bukkit.getScheduler().runTaskTimer(plugin, waterFlow::rescanAll, 40L, 40L);
        // A freshly placed wheel starts dry. Its profile carries static SU, so
        // zero that output and count the water around it in the same place
        // event instead of leaving it spinning until the first reconcile.
        context.infrastructureEngine().hookBus().registerLifecycleListener(event -> {
            MultiBlockInstance instance = event.instance();
            if (instance == null || !instance.typeId().startsWith("water_wheel")) {
                return;
            }
            context.rotationNetworks().setSourceSu(instance, 0);
            waterFlow.rescan(instance);
        }, com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleEventType.PLACE);
        preloadModels();
        registerPackItems();
    }

    /**
     * Registers every machine level's model as a resourcepack item so the
     * router picks {@code PACK_ITEM} instead of degrading to the texel/virtual
     * path. The bbmodel-embedded textures flow through the ordinary
     * {@code ensureModelAssets} pipeline ({@code registerModelTextures} reads
     * them from the model source file), so no separate texture files are
     * needed. The model key must match
     * {@code ModelRenderingManager.buildModelKey}: {@code <type>_lvl_<level>}.
     */
    private void registerPackItems() {
        var pack = context.packApi();
        if (pack == null || !pack.isAvailable()) {
            return;
        }
        for (String typeId : MACHINE_TYPE_IDS) {
            var type = typeById(typeId);
            if (type == null) {
                continue;
            }
            for (int level = type.minLevel(); level <= type.maxLevel(); level++) {
                String modelKey = packItemKey(typeId, level);
                var definition = new com.mineplus.pack.item.PackItemDefinition(
                        NAMESPACE, modelKey, org.bukkit.Material.CLOCK, modelKey,
                        "minecraft:item/clock", java.util.List.of(),
                        type.displayName(),
                        com.mineplus.infrastructure.definition.ItemCategory.MACHINE_COMPONENT,
                        null, null, java.util.List.of());
                pack.registerItem(definition);
            }
        }
    }

    @Override
    protected com.mineplus.infrastructure.command.SubCommand command() {
        return new MachineSubCommand(context, this);
    }

    /**
     * Parses the models this module just installed, before their pack items
     * are registered. Models otherwise load lazily on first render, which
     * left the item assets without geometry (a clock in hand) and made the
     * first block render fall back to texel.
     */
    private void preloadModels() {
        var virtual = context.virtualBlockManager();
        var support = context.moduleSupport();
        // The block layer resolves its model by name (visuals.model), not by
        // pack key, so those models have to be in the manager under their own
        // names or placement refuses with "Model ... is not loaded".
        for (String blockModel : new String[]{"shaft", "water_wheel_weak", "water_wheel_strong"}) {
            virtual.preloadModel(blockModel, support.dataFile("models/" + blockModel + ".bbmodel"));
        }
        for (String typeId : MACHINE_TYPE_IDS) {
            var type = typeById(typeId);
            if (type == null) {
                continue;
            }
            for (int level = type.minLevel(); level <= type.maxLevel(); level++) {
                var definition = type.level(level);
                if (definition == null || definition.modelPath().isBlank()) {
                    continue;
                }
                virtual.preloadModel(packItemKey(typeId, level),
                        support.dataFile(definition.modelPath()));
            }
        }
    }

    private void installModel(String typeId) {
        installModel(typeId, typeId);
    }

    private void installModel(String typeId, String modelName) {
        context.moduleSupport().installDefault(plugin,
                "defaults/models/" + modelName + ".bbmodel",
                "models/" + modelName + ".bbmodel", true);
        // Ships the per-model filling space (water wheels: only the center
        // axle cell becomes solid, the ring over the river stays open). The
        // meta file is optional — models without one must not warn.
        if (plugin.getResource("defaults/models/" + modelName + ".bbmodel.meta.json") != null) {
            context.moduleSupport().installDefault(plugin,
                    "defaults/models/" + modelName + ".bbmodel.meta.json",
                    "models/" + modelName + ".bbmodel.meta.json", true);
        }
    }

    private void render(MultiBlockInstance instance, RotationNetworkService.MemberState state) {
        float previous = lastSpeeds.getOrDefault(instance.id(), Float.NaN);
        if (previous == state.speed() && !Float.isNaN(previous)) {
            return;
        }
        lastSpeeds.put(instance.id(), state.speed());
        var animation = context.animationApi();
        if (state.speed() == 0f) {
            animation.stopAnimation(instance.id(), SPIN_CLIP);
            if (waterFlow != null && instance.typeId().startsWith("water_wheel")) {
                waterFlow.rescan(instance);
            }
            return;
        }
        // The clips are authored so 1x playback means the model's own loop
        // (8 s for wheels/shafts/gears): replaying at |speed|/45 maps the
        // solved deg/tick onto that loop, negative speeds reverse it.
        float rate = Math.abs(state.speed()) / 45f;
        if (state.direction() < 0) {
            rate = -rate;
        }
        animation.playAnimation(instance.id(), SPIN_CLIP,
                com.mineplus.infrastructure.virtual.animation.AnimationPlayback.speed(rate));
    }

    /** Handles brake lever interaction; returns true when consumed. */
    boolean toggleBrake(MultiBlockInstance instance) {
        if (!BRAKE_TYPE_ID.equals(instance.typeId())) {
            return false;
        }
        boolean engage = !Boolean.TRUE.equals(braked.get(instance.id()));
        braked.put(instance.id(), engage);
        context.rotationNetworks().setBraked(instance, engage);
        var animation = context.animationApi();
        if (engage) {
            animation.playAnimation(instance.id(), SWING_CLIP);
        } else {
            animation.stopAnimation(instance.id(), SWING_CLIP);
        }
        return true;
    }

    /** Diagnostic for {@code /machine status}. */
    public String describe(MultiBlockInstance instance) {
        Float speed = lastSpeeds.get(instance.id());
        boolean brakedNow = Boolean.TRUE.equals(braked.get(instance.id()));
        return (brakedNow ? "braked" : "free")
                + (speed == null ? ", never solved" : String.format(", speed %.2f deg/tick", speed));
    }

    /** Resolves the machine the player is looking at (listener + command). */
    MultiBlockInstance resolveLookedAt(org.bukkit.entity.Player player) {
        return context.moduleSupport().resolveLooked(player, 6.0, null);
    }

    /** Exposes the rotation service to package helpers (WaterFlowTask). */
    com.mineplus.infrastructure.core.network.rotation.RotationNetworkService rotationNetworks() {
        return context.rotationNetworks();
    }

    /** The plugin bootstrap (for namespaced keys). */
    JavaPlugin plugin() {
        return plugin;
    }

    /** The core plugin context (package helpers). */
    com.mineplus.infrastructure.PluginContext context() {
        return context;
    }

    /** Module item listener (give/shop wiring). */
    MachineItemListener itemListener() {
        return machineItems;
    }

    /** All machine type ids this module ships, in shop order. */
    static final String[] MACHINE_TYPE_IDS = {
            "water_wheel", "water_wheel_weak", "water_wheel_strong",
            "rotation_shaft", "cogwheel", "cogwheel_vertical", "cogwheel_horizontal",
            "rotation_brake"};

    /** The pack namespace every machine item and raw asset lives under. */
    static final String NAMESPACE = "mineplusmachine";

    /**
     * Pack item/model id for one machine level. Must match
     * {@code ModelRenderingManager.buildModelKey}: {@code <type>_lvl_<level>}.
     */
    static String packItemKey(String typeId, int level) {
        return (typeId + "_lvl_" + level).toLowerCase(java.util.Locale.ROOT);
    }

    /** Resolves a registered machine type by id, or null. */
    com.mineplus.infrastructure.core.multiblock.MultiBlockType typeById(String typeId) {
        return context.infrastructureApi() == null ? null
                : context.infrastructureApi().getType(typeId);
    }

    /** All registered machine types (shop order not guaranteed; see {@link #MACHINE_TYPE_IDS}). */
    java.util.Collection<com.mineplus.infrastructure.core.multiblock.MultiBlockType> machineTypes() {
        return context.infrastructureApi() == null
                ? java.util.List.of()
                : context.infrastructureApi().getTypes();
    }
}
