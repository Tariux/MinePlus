package com.mineplus.config;

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
