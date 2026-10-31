package com.mineplus.infrastructure;

import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.combat.Ballistics;
import com.mineplus.infrastructure.combat.CombatSettings;
import com.mineplus.infrastructure.combat.StanceService;
import com.mineplus.infrastructure.core.InfrastructureEngine;
import com.mineplus.infrastructure.core.api.AnimationApi;
import com.mineplus.infrastructure.core.api.BasicInfrastructureApi;
import com.mineplus.infrastructure.core.api.InfrastructureApi;
import com.mineplus.infrastructure.core.api.JsonInfrastructureApi;
import com.mineplus.infrastructure.fx.ParticleFx;
import com.mineplus.infrastructure.fx.unit.VisualUnitService;
import com.mineplus.infrastructure.hud.HudService;
import com.mineplus.infrastructure.input.HeldItemInput;
import com.mineplus.infrastructure.mob.MineplusMobBehaviorApi;
import com.mineplus.infrastructure.mob.MobBehaviorApi;
import com.mineplus.infrastructure.mob.MobBehaviorEngine;
import com.mineplus.infrastructure.mob.custom.CustomMobApi;
import com.mineplus.infrastructure.mob.custom.CustomMobManager;
import com.mineplus.infrastructure.mob.custom.CustomMobSettings;
import com.mineplus.infrastructure.module.ModuleSupport;
import com.mineplus.infrastructure.perf.PerfRegistry;
import com.mineplus.infrastructure.perf.PerfSettings;
import com.mineplus.infrastructure.registry.ItemMagazine;
import com.mineplus.infrastructure.registry.ItemRegistry;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.animation.AnimationSettings;
import com.mineplus.infrastructure.world.DeferredEditQueue;
import com.mineplus.infrastructure.world.WorldEditSettings;
import com.mineplus.pack.MineplusPackApi;
import com.mineplus.pack.PackApi;
import com.mineplus.pack.PackSettings;
import com.mineplus.pack.PackSystem;

public final class PluginContext {

    private final MineplusPlugin plugin;
    private final ItemRegistry itemRegistry;
    private final VirtualBlockManager virtualBlockManager;
    private final InfrastructureEngine infrastructureEngine;
    private final InfrastructureApi infrastructureApi;
    private final BasicInfrastructureApi basicInfrastructureApi;
    private final JsonInfrastructureApi jsonInfrastructureApi;
    private final AnimationApi animationApi;
    private final ModuleSupport moduleSupport;
    private final PackSystem packSystem;
    private final PackApi packApi;
    private final MobBehaviorApi mobBehaviorApi;
    private final CustomMobManager customMobs;
    private final ParticleFx particleFx;
    private final Ballistics ballistics;
    private final HeldItemInput heldItemInput;
    private final VisualUnitService visualUnits;
    private final HudService hud;
    private final StanceService stances;
    private final PerfRegistry perf;
    private final DeferredEditQueue deferredEdits;
    private final java.util.concurrent.ConcurrentHashMap<String, ItemMagazine> magazines =
            new java.util.concurrent.ConcurrentHashMap<>();

    private PluginContext(
            MineplusPlugin plugin,
            ItemRegistry itemRegistry,
            VirtualBlockManager virtualBlockManager,
            InfrastructureEngine infrastructureEngine,
            InfrastructureApi infrastructureApi,
            BasicInfrastructureApi basicInfrastructureApi,
            JsonInfrastructureApi jsonInfrastructureApi,
            AnimationApi animationApi,
            ModuleSupport moduleSupport,
            PackSystem packSystem,
            PackApi packApi,
            MobBehaviorApi mobBehaviorApi,
            CustomMobManager customMobs,
            ParticleFx particleFx,
            Ballistics ballistics,
            HeldItemInput heldItemInput,
            VisualUnitService visualUnits,
            HudService hud,
            StanceService stances,
            PerfRegistry perf,
            DeferredEditQueue deferredEdits
    ) {
        this.plugin = plugin;
        this.itemRegistry = itemRegistry;
        this.virtualBlockManager = virtualBlockManager;
        this.infrastructureEngine = infrastructureEngine;
        this.infrastructureApi = infrastructureApi;
        this.basicInfrastructureApi = basicInfrastructureApi;
        this.jsonInfrastructureApi = jsonInfrastructureApi;
        this.animationApi = animationApi;
        this.moduleSupport = moduleSupport;
        this.packSystem = packSystem;
        this.packApi = packApi;
        this.mobBehaviorApi = mobBehaviorApi;
        this.customMobs = customMobs;
        this.particleFx = particleFx;
        this.ballistics = ballistics;
        this.heldItemInput = heldItemInput;
        this.visualUnits = visualUnits;
        this.hud = hud;
        this.stances = stances;
        this.perf = perf;
        this.deferredEdits = deferredEdits;
    }

    public static PluginContext bootstrap(MineplusPlugin plugin, VirtualBlockManager virtualBlockManager) {
        return bootstrap(plugin, virtualBlockManager, AnimationSettings.defaults());
    }

    public static PluginContext bootstrap(
            MineplusPlugin plugin,
            VirtualBlockManager virtualBlockManager,
            AnimationSettings animationSettings
    ) {
        return bootstrap(plugin, virtualBlockManager, animationSettings, PackSettings.defaults());
    }

    /**
     * @param packSettings pack subsystem settings; when enabled, the subsystem
     *                     starts here (renderer injection happens before the
     *                     engine's restore pass in {@link #finalizeSetup()})
     */
    public static PluginContext bootstrap(
            MineplusPlugin plugin,
            VirtualBlockManager virtualBlockManager,
            AnimationSettings animationSettings,
            PackSettings packSettings
    ) {
        return bootstrap(plugin, virtualBlockManager, animationSettings, packSettings,
                CustomMobSettings.defaults());
    }

    /**
     * @param packSettings      pack subsystem settings; when enabled, the
     *                          subsystem starts here (renderer injection happens
     *                          before the engine's restore pass in
     *                          {@link #finalizeSetup()})
     * @param customMobSettings custom-mob subsystem limits ({@code CUSTOM_MOBS})
     */
    public static PluginContext bootstrap(
            MineplusPlugin plugin,
            VirtualBlockManager virtualBlockManager,
            AnimationSettings animationSettings,
            PackSettings packSettings,
            CustomMobSettings customMobSettings
    ) {
        return bootstrap(plugin, virtualBlockManager, animationSettings, packSettings,
                customMobSettings, new PerfRegistry(PerfSettings.defaults()));
    }

    /**
     * @param packSettings      pack subsystem settings; when enabled, the
     *                          subsystem starts here (renderer injection happens
     *                          before the engine's restore pass in
     *                          {@link #finalizeSetup()})
     * @param customMobSettings custom-mob subsystem limits ({@code CUSTOM_MOBS})
     * @param perf              performance registry owned by this context (also
     *                          handed to the display transport before bootstrap,
     *                          so both sides report to one instance)
     */
    public static PluginContext bootstrap(
            MineplusPlugin plugin,
            VirtualBlockManager virtualBlockManager,
            AnimationSettings animationSettings,
            PackSettings packSettings,
            CustomMobSettings customMobSettings,
            PerfRegistry perf
    ) {
        return bootstrap(plugin, virtualBlockManager, animationSettings, packSettings,
                customMobSettings, perf, null);
    }

    /**
     * @param packSettings      pack subsystem settings; when enabled, the
     *                          subsystem starts here (renderer injection happens
     *                          before the engine's restore pass in
     *                          {@link #finalizeSetup()})
     * @param customMobSettings custom-mob subsystem limits ({@code CUSTOM_MOBS})
     * @param perf              performance registry owned by this context (also
     *                          handed to the display transport before bootstrap,
     *                          so both sides report to one instance)
     * @param rigSettings       bone-rig limits ({@code RIGS}); {@code null} = defaults
     */
    public static PluginContext bootstrap(
            MineplusPlugin plugin,
            VirtualBlockManager virtualBlockManager,
            AnimationSettings animationSettings,
            PackSettings packSettings,
            CustomMobSettings customMobSettings,
            PerfRegistry perf,
            com.mineplus.infrastructure.virtual.rig.RigSettings rigSettings
    ) {
        return bootstrap(plugin, virtualBlockManager, animationSettings, packSettings,
                customMobSettings, perf, rigSettings, null);
    }

    /**
     * @param packSettings      pack subsystem settings; when enabled, the
     *                          subsystem starts here (renderer injection happens
     *                          before the engine's restore pass in
     *                          {@link #finalizeSetup()})
     * @param customMobSettings custom-mob subsystem limits ({@code CUSTOM_MOBS})
     * @param perf              performance registry owned by this context (also
     *                          handed to the display transport before bootstrap,
     *                          so both sides report to one instance)
     * @param rigSettings       bone-rig limits ({@code RIGS}); {@code null} = defaults
     * @param worldEditSettings deferred world-edit queue budget ({@code WORLD_EDITS});
     *                          {@code null} = defaults
     */
    public static PluginContext bootstrap(
            MineplusPlugin plugin,
            VirtualBlockManager virtualBlockManager,
            AnimationSettings animationSettings,
            PackSettings packSettings,
            CustomMobSettings customMobSettings,
            PerfRegistry perf,
            com.mineplus.infrastructure.virtual.rig.RigSettings rigSettings,
            WorldEditSettings worldEditSettings
    ) {
        PerfRegistry performance = perf == null ? new PerfRegistry(PerfSettings.defaults()) : perf;
        ItemRegistry itemRegistry = new ItemRegistry(plugin);
        InfrastructureEngine infrastructureEngine = new InfrastructureEngine(
                plugin, virtualBlockManager, itemRegistry, animationSettings, performance);

        PackSystem packSystem = null;
        if (packSettings != null && packSettings.enabled()) {
            // Start failures are isolated inside PackSystem; virtual rendering
            // is never affected.
            packSystem = PackSystem.start(plugin, virtualBlockManager, itemRegistry,
                    infrastructureEngine.renderingManager(), packSettings, performance, rigSettings);
        }
        PackApi packApi = packSystem != null && packSystem.isRunning()
                ? new MineplusPackApi(packSystem)
                : PackApi.disabled(itemRegistry);

        // Lazy-starting mob behavior engine: costs nothing until a module
        // registers its first behavior profile.
        MobBehaviorEngine mobBehaviorEngine = new MobBehaviorEngine(plugin);
        mobBehaviorEngine.setPerfRegistry(performance);
        MobBehaviorApi mobBehaviorApi = new MineplusMobBehaviorApi(mobBehaviorEngine);

        // Custom mobs: identity registry, spawn/restore lifecycle and the
        // per-part visual rig. Installed as the engine's identity resolver so a
        // custom id's profiles are the only ones its entities ever see.
        CustomMobManager customMobs = new CustomMobManager(
                plugin, packApi, mobBehaviorApi, customMobSettings, rigSettings);
        mobBehaviorEngine.setIdentityResolver(customMobs);
        customMobs.start();

        // Combat + input services. The ballistics task self-arms on the first
        // shot and retires when nothing is in flight; the held-item listener
        // only routes once a module installs a handler.
        ParticleFx particleFx = new ParticleFx();
        Ballistics ballistics = new Ballistics(plugin, particleFx);
        HeldItemInput heldItemInput = new HeldItemInput(plugin);
        HudService hud = new HudService();
        com.mineplus.infrastructure.virtual.display.DisplayTransport transport =
                virtualBlockManager.displayTransport();
        StanceService stances = new StanceService(plugin, virtualBlockManager,
                transport == null
                        ? com.mineplus.infrastructure.virtual.display.DisplayTransportSettings.defaults()
                        : transport.settings());

        // Deferred world edits: inert (no task, immediate application) until
        // WORLD_EDITS.ENABLED turns the budgeted drain on.
        DeferredEditQueue deferredEdits = new DeferredEditQueue(worldEditSettings);
        deferredEdits.setPerfRegistry(performance);

        // Composed visual units: inert until VISUAL_UNITS.ENABLED; the
        // ballistics engine routes block impacts through it when enabled.
        VisualUnitService visualUnits = new VisualUnitService(plugin, particleFx, virtualBlockManager);
        ballistics.setVisualUnits(visualUnits);

        return new PluginContext(
                plugin,
                itemRegistry,
                virtualBlockManager,
                infrastructureEngine,
                infrastructureEngine.api(),
                infrastructureEngine.basicApi(),
                infrastructureEngine.jsonApi(),
                infrastructureEngine.animationApi(),
                new ModuleSupport(plugin, infrastructureEngine.registry(), virtualBlockManager),
                packSystem,
                packApi,
                mobBehaviorApi,
                customMobs,
                particleFx,
                ballistics,
                heldItemInput,
                visualUnits,
                hud,
                stances,
                performance,
                deferredEdits
        );
    }

    public void finalizeSetup() {
        infrastructureEngine.initialize();
        perf.start(plugin);
        deferredEdits.start(plugin);
    }

    public MineplusPlugin plugin() {
        return plugin;
    }

    public ItemRegistry itemRegistry() {
        return itemRegistry;
    }

    public InfrastructureEngine infrastructureEngine() {
        return infrastructureEngine;
    }

    public VirtualBlockManager virtualBlockManager() {
        return virtualBlockManager;
    }

    public InfrastructureApi infrastructureApi() {
        return infrastructureApi;
    }

    public BasicInfrastructureApi basicInfrastructureApi() {
        return basicInfrastructureApi;
    }

    public JsonInfrastructureApi jsonInfrastructureApi() {
        return jsonInfrastructureApi;
    }

    /** Selector-based animation control (play/stop/pause/trigger/enable by clip or bone). */
    public AnimationApi animationApi() {
        return animationApi;
    }

    /**
     * The unified render engine's routing choke point: resolves a {@code RenderPlan}
     * to an effective backend/kind and exposes telemetry for
     * {@code /mineplus render stats}. Never null.
     */
    public com.mineplus.infrastructure.render.RenderRouter renderRouter() {
        return infrastructureEngine.renderingManager().renderRouter();
    }

    /**
     * Resource pack API — never null. When the subsystem is disabled this is
     * a safe fallback: item identity still registers, presentation falls back
     * to the vanilla backing material, nothing compiles or pushes.
     */
    public PackApi packApi() {
        return packApi;
    }

    /** The running pack subsystem, or null when disabled/unavailable. */
    public PackSystem packSystem() {
        return packSystem;
    }

    /** Module toolkit: resource installation, looked-at resolution, command registration. */
    public ModuleSupport moduleSupport() {
        return moduleSupport;
    }

    /**
     * Mob behavior API — never null. Lets dependent plugins register
     * per-entity-type behavior profiles (interval-gated world actions) that
     * the Core's budgeted, distance-gated engine executes. The engine is
     * lazy: it costs nothing until the first profile is registered.
     */
    public MobBehaviorApi mobBehaviorApi() {
        return mobBehaviorApi;
    }

    /**
     * Custom-mob API — never null. Lets modules register a vanilla-based mob
     * with its own identity, visual model and Core-driven behavior; the Core
     * owns spawning, chunk restore, vanilla-AI isolation and the visual rig.
     * Inert when {@code CUSTOM_MOBS.ENABLED} is false.
     */
    public CustomMobApi customMobs() {
        return customMobs;
    }

    /** The custom-mob manager behind {@link #customMobs()} (Core lifecycle access). */
    public CustomMobManager customMobManager() {
        return customMobs;
    }

    /** Subsystem diagnostics: rigged mob count. */
    public int riggedMobCount() {
        return customMobs.riggedCount();
    }

    /**
     * Shared particle budget for gameplay feedback. Modules emit through this
     * instance so one tick-wide budget covers every effect on the server
     * instead of each plugin metering itself.
     */
    public ParticleFx particleFx() {
        return particleFx;
    }

    /**
     * The Core's projectile engine — simulated rounds, no projectile entities.
     * Never null; it self-arms on the first shot.
     */
    public Ballistics ballistics() {
        return ballistics;
    }

    /**
     * Held-item input routing (press/release/swap/melee arbitration) for
     * item-driven modules. Never null; it stays inert until a module installs
     * a {@link HeldItemInput.HeldItemHandler}.
     */
    public HeldItemInput heldItemInput() {
        return heldItemInput;
    }

    /**
     * Composed visual units (one lifetime/budget per impact). Never null; inert
     * while {@code VISUAL_UNITS.ENABLED} is false.
     */
    public VisualUnitService visualUnits() {
        return visualUnits;
    }

    /**
     * The shared HUD bar (action bar / boss bar / off, per-player toggle).
     * One instance for the whole server: a module that kept its own would
     * fight over the action bar with every other module that shows one.
     */
    public HudService hud() {
        return hud;
    }

    /**
     * Observer-only stance channel: shows one player a different equipment view
     * of an entity without touching the target's inventory. Never null; inert
     * when the packet transport is off or {@code STANCES.ENABLED} is false.
     */
    public StanceService stances() {
        return stances;
    }

    /**
     * Performance telemetry and the adaptive valve. Never null; recorders are
     * no-ops when {@code PERFORMANCE.TELEMETRY} is disabled.
     */
    public PerfRegistry perf() {
        return perf;
    }

    /**
     * Deferred, budgeted world-edit queue ({@code WORLD_EDITS}). Never null;
     * inert by default, where {@code enqueue} applies edits immediately
     * (main thread only). Modules submit detached {@code BlockEdit}s instead
     * of writing a large structure in one tick.
     */
    public DeferredEditQueue deferredEdits() {
        return deferredEdits;
    }

    /**
     * A deferred magazine for one module's ammo-style slot: in-memory counts,
     * PDC written back only on switch/quit/death. Instances are cached per
     * (owner, slot name), so two callers asking for the same slot share one
     * key and stay consistent.
     */
    public ItemMagazine magazine(org.bukkit.plugin.Plugin owner, String slotName) {
        if (owner == null || slotName == null || slotName.isBlank()) {
            throw new IllegalArgumentException("owner and slotName are required");
        }
        String cacheKey = owner.getName() + ":" + slotName.toLowerCase(java.util.Locale.ROOT);
        return magazines.computeIfAbsent(cacheKey,
                key -> new ItemMagazine(owner, slotName));
    }

    /** Applies the combat settings slice (called on enable and reload). */
    public void applyCombatSettings(CombatSettings settings) {
        ballistics.applySettings(settings == null ? CombatSettings.defaults() : settings);
    }

    /**
     * Applies the inbound input settings slice (called on enable and reload).
     * {@code INPUT.FAST_PATH: false} leaves the event path untouched.
     */
    public void applyInputSettings(com.mineplus.infrastructure.input.InputSettings settings) {
        heldItemInput.updateSettings(settings);
    }

    /**
     * Applies the composed visual-unit settings slice (called on enable and
     * reload). Disabling releases every live unit including pooled decals.
     */
    public void applyVisualUnitSettings(
            com.mineplus.infrastructure.fx.unit.VisualUnitSettings settings) {
        visualUnits.updateSettings(settings);
    }

    /** Releases the Core-owned runtime services. Idempotent; called on disable. */
    public void shutdownServices() {
        perf.shutdown();
        stances.shutdown();
        deferredEdits.shutdown();
        customMobs.shutdown();
        mobBehaviorApi.shutdown();
        ballistics.shutdown();
        visualUnits.shutdown();
        heldItemInput.shutdown();
    }
}
