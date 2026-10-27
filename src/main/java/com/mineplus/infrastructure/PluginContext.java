package com.mineplus.infrastructure;

import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.combat.Ballistics;
import com.mineplus.infrastructure.combat.CombatSettings;
import com.mineplus.infrastructure.core.InfrastructureEngine;
import com.mineplus.infrastructure.core.api.AnimationApi;
import com.mineplus.infrastructure.core.api.BasicInfrastructureApi;
import com.mineplus.infrastructure.core.api.InfrastructureApi;
import com.mineplus.infrastructure.core.api.JsonInfrastructureApi;
import com.mineplus.infrastructure.fx.ParticleFx;
import com.mineplus.infrastructure.hud.HudService;
import com.mineplus.infrastructure.input.HeldItemInput;
import com.mineplus.infrastructure.mob.MineplusMobBehaviorApi;
import com.mineplus.infrastructure.mob.MobBehaviorApi;
import com.mineplus.infrastructure.mob.MobBehaviorEngine;
import com.mineplus.infrastructure.module.ModuleSupport;
import com.mineplus.infrastructure.registry.ItemMagazine;
import com.mineplus.infrastructure.registry.ItemRegistry;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.animation.AnimationSettings;
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
    private final ParticleFx particleFx;
    private final Ballistics ballistics;
    private final HeldItemInput heldItemInput;
    private final HudService hud;
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
            ParticleFx particleFx,
            Ballistics ballistics,
            HeldItemInput heldItemInput,
            HudService hud
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
        this.particleFx = particleFx;
        this.ballistics = ballistics;
        this.heldItemInput = heldItemInput;
        this.hud = hud;
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
        ItemRegistry itemRegistry = new ItemRegistry(plugin);
        InfrastructureEngine infrastructureEngine = new InfrastructureEngine(
                plugin, virtualBlockManager, itemRegistry, animationSettings);

        PackSystem packSystem = null;
        if (packSettings != null && packSettings.enabled()) {
            // Start failures are isolated inside PackSystem; virtual rendering
            // is never affected.
            packSystem = PackSystem.start(plugin, virtualBlockManager, itemRegistry,
                    infrastructureEngine.renderingManager(), packSettings);
        }
        PackApi packApi = packSystem != null && packSystem.isRunning()
                ? new MineplusPackApi(packSystem)
                : PackApi.disabled(itemRegistry);

        // Lazy-starting mob behavior engine: costs nothing until a module
        // registers its first behavior profile.
        MobBehaviorEngine mobBehaviorEngine = new MobBehaviorEngine(plugin);
        MobBehaviorApi mobBehaviorApi = new MineplusMobBehaviorApi(mobBehaviorEngine);

        // Combat + input services. The ballistics task self-arms on the first
        // shot and retires when nothing is in flight; the held-item listener
        // only routes once a module installs a handler.
        ParticleFx particleFx = new ParticleFx();
        Ballistics ballistics = new Ballistics(plugin, particleFx);
        HeldItemInput heldItemInput = new HeldItemInput(plugin);
        HudService hud = new HudService();

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
                particleFx,
                ballistics,
                heldItemInput,
                hud
        );
    }

    public void finalizeSetup() {
        infrastructureEngine.initialize();
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
     * The shared HUD bar (action bar / boss bar / off, per-player toggle).
     * One instance for the whole server: a module that kept its own would
     * fight over the action bar with every other module that shows one.
     */
    public HudService hud() {
        return hud;
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

    /** Releases the Core-owned runtime services. Idempotent; called on disable. */
    public void shutdownServices() {
        mobBehaviorApi.shutdown();
        ballistics.shutdown();
        heldItemInput.shutdown();
    }
}
