package com.mineplus.config;

import com.mineplus.infrastructure.collision.CollisionSettings;
import com.mineplus.infrastructure.collision.mask.RasterMode;
import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualRenderingSettings;
import com.mineplus.infrastructure.virtual.animation.AnimationSettings;
import com.mineplus.infrastructure.virtual.display.DisplayTransportSettings;
import com.mineplus.infrastructure.virtual.rig.RigSettings;
import com.mineplus.infrastructure.virtual.texel.TexelBakingSettings;
import com.mineplus.infrastructure.world.WorldEditSettings;
import com.mineplus.infrastructure.combat.CombatSettings;
import com.mineplus.infrastructure.fx.unit.VisualUnitSettings;
import com.mineplus.infrastructure.input.InputSettings;
import com.mineplus.infrastructure.mob.MobBehaviorSettings;
import com.mineplus.infrastructure.mob.custom.CustomMobSettings;
import com.mineplus.infrastructure.perf.PerfSettings;
import com.mineplus.infrastructure.render.RenderPolicy;
import com.mineplus.pack.PackSettings;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

public class MineplusConfig {

    private final boolean additionalDebugLogs;
    private final VirtualRenderingSettings virtualRendering;
    private final AnimationSettings animation;
    private final TexelBakingSettings texelBaking;
    private final DisplayTransportSettings displayTransport;
    private final PackSettings pack;
    private final RenderPolicy renderPolicy;
    private final MobBehaviorSettings mobBehavior;
    private final CustomMobSettings customMobs;
    private final CombatSettings combat;
    private final PerfSettings performance;
    private final RigSettings rigs;
    private final WorldEditSettings worldEdits;
    private final InputSettings input;
    private final VisualUnitSettings visualUnits;
    private final int updateCheckResourceId;
    private CollisionSettings collision;

    public MineplusConfig() {
        this(false);
    }

    public MineplusConfig(boolean additionalDebugLogs) {
        this(additionalDebugLogs, VirtualRenderingSettings.defaults(),
                AnimationSettings.defaults(), TexelBakingSettings.defaults(),
                DisplayTransportSettings.defaults(), PackSettings.defaults(), 0);
    }

    public MineplusConfig(boolean additionalDebugLogs, VirtualRenderingSettings virtualRendering) {
        this(additionalDebugLogs, virtualRendering,
                AnimationSettings.defaults(), TexelBakingSettings.defaults(),
                0);
    }

    public MineplusConfig(
            boolean additionalDebugLogs,
            VirtualRenderingSettings virtualRendering,
            int updateCheckResourceId
    ) {
        this(additionalDebugLogs, virtualRendering,
                AnimationSettings.defaults(), TexelBakingSettings.defaults(),
                DisplayTransportSettings.defaults(), PackSettings.defaults(), updateCheckResourceId);
    }

    public MineplusConfig(
            boolean additionalDebugLogs,
            VirtualRenderingSettings virtualRendering,
            AnimationSettings animation,
            int updateCheckResourceId
    ) {
        this(additionalDebugLogs, virtualRendering, animation,
                TexelBakingSettings.defaults(), DisplayTransportSettings.defaults(),
                PackSettings.defaults(), updateCheckResourceId);
    }

    public MineplusConfig(
            boolean additionalDebugLogs,
            VirtualRenderingSettings virtualRendering,
            AnimationSettings animation,
            TexelBakingSettings texelBaking,
            int updateCheckResourceId
    ) {
        this(additionalDebugLogs, virtualRendering, animation, texelBaking,
                DisplayTransportSettings.defaults(), PackSettings.defaults(), updateCheckResourceId);
    }

    public MineplusConfig(
            boolean additionalDebugLogs,
            VirtualRenderingSettings virtualRendering,
            AnimationSettings animation,
            TexelBakingSettings texelBaking,
            DisplayTransportSettings displayTransport,
            int updateCheckResourceId
    ) {
        this(additionalDebugLogs, virtualRendering, animation, texelBaking, displayTransport,
                PackSettings.defaults(), updateCheckResourceId);
    }

    public MineplusConfig(
            boolean additionalDebugLogs,
            VirtualRenderingSettings virtualRendering,
            AnimationSettings animation,
            TexelBakingSettings texelBaking,
            DisplayTransportSettings displayTransport,
            PackSettings pack,
            int updateCheckResourceId
    ) {
        this(additionalDebugLogs, virtualRendering, animation, texelBaking, displayTransport, pack,
                RenderPolicy.defaults(), updateCheckResourceId);
    }

    public MineplusConfig(
            boolean additionalDebugLogs,
            VirtualRenderingSettings virtualRendering,
            AnimationSettings animation,
            TexelBakingSettings texelBaking,
            DisplayTransportSettings displayTransport,
            PackSettings pack,
            RenderPolicy renderPolicy,
            int updateCheckResourceId
    ) {
        this(additionalDebugLogs, virtualRendering, animation, texelBaking, displayTransport, pack,
                renderPolicy, MobBehaviorSettings.defaults(), CustomMobSettings.defaults(),
                CombatSettings.defaults(), updateCheckResourceId);
    }

    /** Canonical constructor; new settings groups thread through here. */
    public MineplusConfig(
            boolean additionalDebugLogs,
            VirtualRenderingSettings virtualRendering,
            AnimationSettings animation,
            TexelBakingSettings texelBaking,
            DisplayTransportSettings displayTransport,
            PackSettings pack,
            RenderPolicy renderPolicy,
            MobBehaviorSettings mobBehavior,
            CustomMobSettings customMobs,
            CombatSettings combat,
            int updateCheckResourceId
    ) {
        this(additionalDebugLogs, virtualRendering, animation, texelBaking, displayTransport, pack,
                renderPolicy, mobBehavior, customMobs, combat, PerfSettings.defaults(), updateCheckResourceId);
    }

    /** Canonical constructor; new settings groups thread through here. */
    public MineplusConfig(
            boolean additionalDebugLogs,
            VirtualRenderingSettings virtualRendering,
            AnimationSettings animation,
            TexelBakingSettings texelBaking,
            DisplayTransportSettings displayTransport,
            PackSettings pack,
            RenderPolicy renderPolicy,
            MobBehaviorSettings mobBehavior,
            CustomMobSettings customMobs,
            CombatSettings combat,
            PerfSettings performance,
            int updateCheckResourceId
    ) {
        this(additionalDebugLogs, virtualRendering, animation, texelBaking, displayTransport, pack,
                renderPolicy, mobBehavior, customMobs, combat, performance, null, updateCheckResourceId);
    }

    /**
     * Canonical constructor; new settings groups thread through here.
     *
     * @param rigs bone-rig limits ({@code RIGS}); {@code null} = defaults
     */
    public MineplusConfig(
            boolean additionalDebugLogs,
            VirtualRenderingSettings virtualRendering,
            AnimationSettings animation,
            TexelBakingSettings texelBaking,
            DisplayTransportSettings displayTransport,
            PackSettings pack,
            RenderPolicy renderPolicy,
            MobBehaviorSettings mobBehavior,
            CustomMobSettings customMobs,
            CombatSettings combat,
            PerfSettings performance,
            RigSettings rigs,
            int updateCheckResourceId
    ) {
        this(additionalDebugLogs, virtualRendering, animation, texelBaking, displayTransport, pack,
                renderPolicy, mobBehavior, customMobs, combat, performance, rigs, null, updateCheckResourceId);
    }

    /**
     * Canonical constructor; new settings groups thread through here.
     *
     * @param rigs       bone-rig limits ({@code RIGS}); {@code null} = defaults
     * @param worldEdits deferred world-edit budget ({@code WORLD_EDITS});
     *                   {@code null} = defaults
     */
    public MineplusConfig(
            boolean additionalDebugLogs,
            VirtualRenderingSettings virtualRendering,
            AnimationSettings animation,
            TexelBakingSettings texelBaking,
            DisplayTransportSettings displayTransport,
            PackSettings pack,
            RenderPolicy renderPolicy,
            MobBehaviorSettings mobBehavior,
            CustomMobSettings customMobs,
            CombatSettings combat,
            PerfSettings performance,
            RigSettings rigs,
            WorldEditSettings worldEdits,
            int updateCheckResourceId
    ) {
        this(additionalDebugLogs, virtualRendering, animation, texelBaking, displayTransport, pack,
                renderPolicy, mobBehavior, customMobs, combat, performance, rigs, worldEdits, null,
                updateCheckResourceId);
    }

    /**
     * Canonical constructor; new settings groups thread through here.
     *
     * @param rigs       bone-rig limits ({@code RIGS}); {@code null} = defaults
     * @param worldEdits deferred world-edit budget ({@code WORLD_EDITS});
     *                   {@code null} = defaults
     * @param input      inbound input fast path ({@code INPUT}); {@code null} = defaults
     */
    public MineplusConfig(
            boolean additionalDebugLogs,
            VirtualRenderingSettings virtualRendering,
            AnimationSettings animation,
            TexelBakingSettings texelBaking,
            DisplayTransportSettings displayTransport,
            PackSettings pack,
            RenderPolicy renderPolicy,
            MobBehaviorSettings mobBehavior,
            CustomMobSettings customMobs,
            CombatSettings combat,
            PerfSettings performance,
            RigSettings rigs,
            WorldEditSettings worldEdits,
            InputSettings input,
            int updateCheckResourceId
    ) {
        this(additionalDebugLogs, virtualRendering, animation, texelBaking, displayTransport, pack,
                renderPolicy, mobBehavior, customMobs, combat, performance, rigs, worldEdits, input, null,
                updateCheckResourceId);
    }

    /**
     * Canonical constructor; new settings groups thread through here.
     *
     * @param rigs        bone-rig limits ({@code RIGS}); {@code null} = defaults
     * @param worldEdits  deferred world-edit budget ({@code WORLD_EDITS});
     *                    {@code null} = defaults
     * @param input       inbound input fast path ({@code INPUT}); {@code null} = defaults
     * @param visualUnits composed visual-unit budgets ({@code VISUAL_UNITS});
     *                    {@code null} = defaults
     */
    public MineplusConfig(
            boolean additionalDebugLogs,
            VirtualRenderingSettings virtualRendering,
            AnimationSettings animation,
            TexelBakingSettings texelBaking,
            DisplayTransportSettings displayTransport,
            PackSettings pack,
            RenderPolicy renderPolicy,
            MobBehaviorSettings mobBehavior,
            CustomMobSettings customMobs,
            CombatSettings combat,
            PerfSettings performance,
            RigSettings rigs,
            WorldEditSettings worldEdits,
            InputSettings input,
            VisualUnitSettings visualUnits,
            int updateCheckResourceId
    ) {
        this.additionalDebugLogs = additionalDebugLogs;
        this.virtualRendering = virtualRendering == null
                ? VirtualRenderingSettings.defaults()
                : virtualRendering;
        this.animation = animation == null ? AnimationSettings.defaults() : animation;
        this.texelBaking = texelBaking == null ? TexelBakingSettings.defaults() : texelBaking;
        this.displayTransport = displayTransport == null
                ? DisplayTransportSettings.defaults() : displayTransport;
        this.pack = pack == null ? PackSettings.defaults() : pack;
        this.renderPolicy = renderPolicy == null ? RenderPolicy.defaults() : renderPolicy;
        this.mobBehavior = mobBehavior == null ? MobBehaviorSettings.defaults() : mobBehavior;
        this.customMobs = customMobs == null ? CustomMobSettings.defaults() : customMobs;
        this.combat = combat == null ? CombatSettings.defaults() : combat;
        this.performance = performance == null ? PerfSettings.defaults() : performance;
        this.rigs = rigs == null ? RigSettings.defaults() : rigs;
        this.worldEdits = worldEdits == null ? WorldEditSettings.defaults() : worldEdits;
        this.input = input == null ? InputSettings.defaults() : input;
        this.visualUnits = visualUnits == null ? VisualUnitSettings.defaults() : visualUnits;
        this.updateCheckResourceId = Math.max(0, updateCheckResourceId);
        this.collision = CollisionSettings.defaults();
    }

    /**
     * Returns a copy of this configuration with the collision system settings
     * replaced. Config parsing fills this in after the other groups.
     */
    public MineplusConfig withCollision(CollisionSettings settings) {
        MineplusConfig copy = new MineplusConfig(additionalDebugLogs, virtualRendering, animation,
                texelBaking, displayTransport, pack, renderPolicy, mobBehavior, customMobs,
                combat, performance, rigs, worldEdits, input, visualUnits,
                updateCheckResourceId);
        copy.collision = settings == null ? CollisionSettings.defaults() : settings;
        return copy;
    }

    /** Shape-atlas / hybrid-fitter settings ({@code COLLISION}). */
    public CollisionSettings getCollision() {
        return collision;
    }

    public boolean isAdditionalDebugLogs() {
        return additionalDebugLogs;
    }

    public VirtualRenderingSettings getVirtualRendering() {
        return virtualRendering;
    }

    public AnimationSettings getAnimation() {
        return animation;
    }

    public TexelBakingSettings getTexelBaking() {
        return texelBaking;
    }

    public DisplayTransportSettings getDisplayTransport() {
        return displayTransport;
    }

    public PackSettings getPack() {
        return pack;
    }

    /** Unified render engine routing policy ({@code RENDERING.POLICY}). */
    public RenderPolicy getRenderPolicy() {
        return renderPolicy;
    }

    /** Mob behavior engine settings ({@code MOB_BEHAVIOR}). */
    public MobBehaviorSettings getMobBehavior() {
        return mobBehavior;
    }

    /** Custom-mob subsystem limits ({@code CUSTOM_MOBS}). */
    public CustomMobSettings getCustomMobs() {
        return customMobs;
    }

    /** Combat services settings ({@code BALLISTICS}, {@code PARTICLE_FX}). */
    public CombatSettings getCombat() {
        return combat;
    }

    /** Performance telemetry + adaptive valve settings ({@code PERFORMANCE}). */
    public PerfSettings getPerformance() {
        return performance;
    }

    /** Bone-rig limits ({@code RIGS}). */
    public RigSettings getRigs() {
        return rigs;
    }

    /** Deferred world-edit queue budget ({@code WORLD_EDITS}). */
    public WorldEditSettings getWorldEdits() {
        return worldEdits;
    }

    /** Inbound input fast-path settings ({@code INPUT}). */
    public InputSettings getInput() {
        return input;
    }

    /** Composed visual-unit budgets ({@code VISUAL_UNITS}). */
    public VisualUnitSettings getVisualUnits() {
        return visualUnits;
    }

    public int getUpdateCheckResourceId() {
        return updateCheckResourceId;
    }

    /**
     * Parses the {@code COLLISION} section. Missing keys fall back to the
     * documented defaults; old {@code VIRTUAL_RENDERING} collision keys are read
     * as a last resort and reported so the operator can migrate.
     */
    public static CollisionSettings parseCollision(FileConfiguration yaml,
            CollisionSettings fallback) {
        CollisionSettings defaults = fallback == null ? CollisionSettings.defaults() : fallback;
        var section = yaml.getConfigurationSection("COLLISION");
        List<String> deprecated = new ArrayList<>();
        boolean enabled = defaults.enabled();
        if (section != null) {
            enabled = section.getBoolean("ENABLED", enabled);
        }

        String profile = defaults.defaultProfile();
        List<String> extras = new ArrayList<>(defaults.extraMaterials());
        List<String> invisible = new ArrayList<>(defaults.invisibleStates());
        java.util.Set<String> excluded = new java.util.LinkedHashSet<>(defaults.excludedMaterials());
        int resolution = defaults.resolution();
        RasterMode raster = defaults.rasterMode();
        int maxBlocks = defaults.maxBlocks();
        int maxEntities = defaults.maxEntities();
        boolean ghosts = defaults.allowGhostBlocks();
        boolean shulkers = defaults.allowShulkerEntities();
        boolean visible = defaults.allowVisibleBlocks();
        boolean interactions = defaults.allowInteraction();
        boolean passable = defaults.allowPassableOutline();
        boolean plates = defaults.allowPlates();
        boolean minecarts = defaults.minecartPlates();
        double shulkerScale = defaults.shulkerScale();
        int partsPerTick = defaults.partsPerTick();
        boolean cache = defaults.cacheFits();
        boolean validate = defaults.validateAtStartup();

        if (section != null) {
            resolution = section.getInt("RESOLUTION", resolution);
            raster = RasterMode.fromKey(section.getString("RASTER_MODE"), raster);
            profile = section.getString("PROFILE", profile);
            maxBlocks = section.getInt("MAX_BLOCKS", maxBlocks);
            maxEntities = section.getInt("MAX_ENTITIES", maxEntities);
            var backends = section.getConfigurationSection("BACKENDS");
            if (backends != null) {
                ghosts = backends.getBoolean("GHOST_BLOCK", ghosts);
                shulkers = backends.getBoolean("SHULKER_BOX_ENTITY", shulkers);
                interactions = backends.getBoolean("INTERACTION_ONLY", interactions);
                passable = backends.getBoolean("PASSABLE_OUTLINE", passable);
                plates = backends.getBoolean("PLATE", plates);
                minecarts = backends.getBoolean("PLATE_USE_MINECART", minecarts);
            }
            visible = section.getBoolean("ALLOW_VISIBLE_STATES", visible);
            shulkerScale = section.getDouble("SHULKER_SCALE", shulkerScale);
            partsPerTick = section.getInt("PARTS_PER_TICK", partsPerTick);
            var atlas = section.getConfigurationSection("ATLAS");
            if (atlas != null) {
                cache = atlas.getBoolean("CACHE", cache);
                extras.addAll(atlas.getStringList("EXTRA_MATERIALS"));
                invisible.addAll(atlas.getStringList("INVISIBLE_STATES"));
                excluded.addAll(atlas.getStringList("EXCLUDED_MATERIALS"));
            }
            validate = section.getBoolean("VALIDATE_AT_STARTUP", validate);
        } else {
            var legacy = yaml.getConfigurationSection("VIRTUAL_RENDERING");
            if (legacy != null) {
                if (legacy.contains("COLLISION_MODE")) {
                    deprecated.add("VIRTUAL_RENDERING.COLLISION_MODE");
                    String mode = legacy.getString("COLLISION_MODE", "SURFACE");
                    if ("NONE".equalsIgnoreCase(mode)) {
                        enabled = false;
                    }
                }
                if (legacy.contains("COLLISION_EPSILON")) {
                    deprecated.add("VIRTUAL_RENDERING.COLLISION_EPSILON");
                    double epsilon = legacy.getDouble("COLLISION_EPSILON", 0.0625);
                    if (epsilon > 0) {
                        resolution = Math.max(1, Math.min(32, (int) Math.round(1.0 / epsilon)));
                    }
                }
                if (legacy.contains("COLLISION_NON_AIR_POLICY")) {
                    deprecated.add("VIRTUAL_RENDERING.COLLISION_NON_AIR_POLICY");
                }
            }
        }
        return new CollisionSettings(enabled, resolution, raster, profile, maxBlocks, maxEntities,
                ghosts, shulkers, visible, interactions, passable, plates, minecarts, shulkerScale,
                partsPerTick, cache, validate, extras, invisible, excluded, deprecated);
    }

    public static VirtualRenderingSettings parseVirtualRendering(
            FileConfiguration yaml, VirtualRenderingSettings fallback) {
        if (!yaml.isConfigurationSection("VIRTUAL_RENDERING")) {
            return fallback;
        }
        var section = yaml.getConfigurationSection("VIRTUAL_RENDERING");
        VirtualRenderingSettings defaults = VirtualRenderingSettings.defaults();
        return new VirtualRenderingSettings(
                ModelMeta.CollisionMode.fromKey(section.getString("COLLISION_MODE"), defaults.collisionMode()),
                (float) section.getDouble("COLLISION_EPSILON", defaults.collisionEpsilon()),
                VirtualRenderingSettings.NonAirPolicy.fromKey(
                        section.getString("COLLISION_NON_AIR_POLICY"), defaults.collisionNonAirPolicy()),
                section.getBoolean("ROTATION_SNAP", defaults.rotationSnap()),
                (float) section.getDouble("ROTATION_SNAP_THRESHOLD_DEGREES",
                        defaults.rotationSnapThresholdDegrees()),
                section.getBoolean("PER_FACE_RENDERING", defaults.perFaceRendering()),
                ModelMeta.OriginMode.fromKey(section.getString("ORIGIN_MODE"), defaults.originMode())
        );
    }

    public static AnimationSettings parseAnimation(FileConfiguration yaml, AnimationSettings fallback) {
        if (!yaml.isConfigurationSection("ANIMATION")) {
            return fallback;
        }
        var section = yaml.getConfigurationSection("ANIMATION");
        AnimationSettings defaults = AnimationSettings.defaults();
        return new AnimationSettings(
                section.getBoolean("ENABLED", defaults.enabled()),
                section.getInt("TICK_INTERVAL_TICKS", defaults.tickIntervalTicks()),
                section.getInt("INTERPOLATION_TICKS", defaults.interpolationTicks()),
                section.getBoolean("AUTOPLAY", defaults.autoplay()),
                com.mineplus.infrastructure.virtual.animation.PosePipeline.fromKey(
                        section.getString("PIPELINE"), defaults.pipeline()),
                section.getInt("MAX_APPLY_PER_TICK", defaults.maxApplyPerTick()),
                section.getBoolean("AUTO_STATE", defaults.autoState()),
                com.mineplus.infrastructure.virtual.animation.TransitionMode.fromKey(
                        section.getString("TRANSITION"), defaults.transition()),
                section.getInt("TRANSITION_TICKS", defaults.transitionTicks())
        );
    }

    public static TexelBakingSettings parseTexelBaking(FileConfiguration yaml, TexelBakingSettings fallback) {
        return TexelBakingSettings.parse(yaml, fallback);
    }

    public static DisplayTransportSettings parseDisplayTransport(
            FileConfiguration yaml, DisplayTransportSettings fallback) {
        return DisplayTransportSettings.parse(yaml, fallback);
    }

    public static PackSettings parsePack(FileConfiguration yaml, PackSettings fallback) {
        return PackSettings.parse(yaml, fallback);
    }

    public static RenderPolicy parseRenderPolicy(FileConfiguration yaml, RenderPolicy fallback) {
        return RenderPolicy.parse(yaml, fallback);
    }

    public static MobBehaviorSettings parseMobBehavior(FileConfiguration yaml, MobBehaviorSettings fallback) {
        return MobBehaviorSettings.parse(yaml, fallback);
    }

    public static CustomMobSettings parseCustomMobs(FileConfiguration yaml, CustomMobSettings fallback) {
        return CustomMobSettings.parse(yaml, fallback);
    }

    public static CombatSettings parseCombat(FileConfiguration yaml, CombatSettings fallback) {
        return CombatSettings.parse(yaml, fallback);
    }

    public static PerfSettings parsePerformance(FileConfiguration yaml, PerfSettings fallback) {
        return PerfSettings.parse(yaml, fallback);
    }

    public static RigSettings parseRigs(FileConfiguration yaml, RigSettings fallback) {
        return RigSettings.parse(yaml, fallback);
    }

    public static WorldEditSettings parseWorldEdits(FileConfiguration yaml, WorldEditSettings fallback) {
        return WorldEditSettings.parse(yaml, fallback);
    }

    public static InputSettings parseInput(FileConfiguration yaml, InputSettings fallback) {
        return InputSettings.parse(yaml, fallback);
    }

    public static VisualUnitSettings parseVisualUnits(FileConfiguration yaml, VisualUnitSettings fallback) {
        return VisualUnitSettings.parse(yaml, fallback);
    }
}
