package com.mineplus.config;

import com.mineplus.infrastructure.virtual.VirtualRenderingSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ConfigManager {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final File configFile;
    private MineplusConfig config;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configFile = new File(plugin.getDataFolder(), "settings.mp.yml");
    }

    public void loadConfig() {
        if (!configFile.exists()) {
            saveDefaultConfig();
        }

        YamlConfiguration yamlConfig = loadValidatedConfig();

        try {
            boolean additionalDebugLogs = yamlConfig.getBoolean("ADDITIONAL_DEBUG_LOGS",
                    yamlConfig.getBoolean("additionalDebugLogs", false));

            this.config = new MineplusConfig(
                    additionalDebugLogs,
                    MineplusConfig.parseVirtualRendering(yamlConfig, VirtualRenderingSettings.defaults()),
                    MineplusConfig.parseAnimation(yamlConfig, com.mineplus.infrastructure.virtual.animation.AnimationSettings.defaults()),
                    MineplusConfig.parseTexelBaking(yamlConfig, com.mineplus.infrastructure.virtual.texel.TexelBakingSettings.defaults()),
                    MineplusConfig.parseDisplayTransport(yamlConfig, com.mineplus.infrastructure.virtual.display.DisplayTransportSettings.defaults()),
                    MineplusConfig.parsePack(yamlConfig, com.mineplus.pack.PackSettings.defaults()),
                    MineplusConfig.parseRenderPolicy(yamlConfig, com.mineplus.infrastructure.render.RenderPolicy.defaults()),
                    MineplusConfig.parseMobBehavior(yamlConfig, com.mineplus.infrastructure.mob.MobBehaviorSettings.defaults()),
                    MineplusConfig.parseCustomMobs(yamlConfig, com.mineplus.infrastructure.mob.custom.CustomMobSettings.defaults()),
                    MineplusConfig.parseCombat(yamlConfig, com.mineplus.infrastructure.combat.CombatSettings.defaults()),
                    MineplusConfig.parsePerformance(yamlConfig, com.mineplus.infrastructure.perf.PerfSettings.defaults()),
                    MineplusConfig.parseRigs(yamlConfig, com.mineplus.infrastructure.virtual.rig.RigSettings.defaults()),
                    MineplusConfig.parseWorldEdits(yamlConfig, com.mineplus.infrastructure.world.WorldEditSettings.defaults()),
                    MineplusConfig.parseInput(yamlConfig, com.mineplus.infrastructure.input.InputSettings.defaults()),
                    MineplusConfig.parseVisualUnits(yamlConfig, com.mineplus.infrastructure.fx.unit.VisualUnitSettings.defaults()),
                    yamlConfig.getInt("UPDATE_CHECKER.RESOURCE_ID", 0)
            );
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Could not load configuration from settings.mp.yml, using defaults.", e);
            this.config = new MineplusConfig();
        }
    }

    /**
     * Loads settings.mp.yml, backing up and regenerating it if the file is not
     * valid YAML (a hand edit gone wrong, or a file written by an older
     * template). The regenerated template always parses, so the plugin
     * self-heals instead of silently running on defaults forever.
     */
    private YamlConfiguration loadValidatedConfig() {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(configFile);
            return yaml;
        } catch (Exception broken) {
            File backup = new File(configFile.getParentFile(),
                    "settings.mp.yml.broken-" + System.currentTimeMillis());
            try {
                Files.move(configFile.toPath(), backup.toPath());
                logger.severe("settings.mp.yml is not valid YAML (" + broken.getMessage()
                        + "). Moved the broken file to " + backup.getName()
                        + " and regenerating the default template.");
            } catch (IOException moveFailed) {
                logger.log(Level.SEVERE, "settings.mp.yml is not valid YAML and could not be backed up ("
                        + moveFailed.getMessage() + "); overwriting with the default template.", broken);
            }
            saveDefaultConfig();
            try {
                yaml.load(configFile);
            } catch (Exception freshTemplateBroken) {
                // The shipped template is fixed; this is unreachable unless the disk is failing.
                logger.log(Level.SEVERE, "Freshly generated settings.mp.yml failed to parse.", freshTemplateBroken);
            }
            return yaml;
        }
    }

    public void saveDefaultConfig() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        if (!configFile.exists()) {
            String defaultConfigContent = """
                    # ==========================================
                    # Mineplus Configuration File (settings.mp.yml)
                    # ==========================================

                    # Toggle additional detailed debug logs across multiblock lifecycle,
                    # rendering pipeline, persistence transactions, and linking events.
                    # Default: false
                    ADDITIONAL_DEBUG_LOGS: false

                    # Update checker: compares the installed version against the
                    # SpigotMC resource page. Set to your resource id after publishing;
                    # 0 disables the check entirely.
                    UPDATE_CHECKER:
                      RESOURCE_ID: 0

                    # Unified render engine routing. Content declares a render plan
                    # (a renderMode, or the legacy renderBackend/renderKind pair);
                    # this policy decides how the router behaves when a pack
                    # request cannot be served.
                    RENDERING:
                      POLICY:
                        # When a pack/hybrid level cannot be served (pack subsystem
                        # unavailable, or the pack display fails to attach), fall
                        # back to the virtual engine. false leaves it unrendered.
                        ALLOW_DEGRADE_TO_VIRTUAL: true
                        # Log each routing decision (downgrades, unavailable routes).
                        # Requires ADDITIONAL_DEBUG_LOGS: true.
                        LOG_ROUTING: false
                        # Maintain the route counters shown by /mineplus render stats.
                        TRACK_TELEMETRY: true

                    # Virtual rendering engine (bbmodel -> BlockDisplay pipeline).
                    # Per-model overrides live in models/<key>.meta.json.
                    VIRTUAL_RENDERING:
                      # Collision proxy occupancy grid: GEOMETRY | SURFACE | AABB
                      # (AABB is a compatibility alias resolved through the
                      # same per-cube geometry rasterization as GEOMETRY.)
                      COLLISION_MODE: GEOMETRY
                      # Cell shrink epsilon for geometry contact tests.
                      COLLISION_EPSILON: 0.001
                      # Behavior when a collision cell is not air: SKIP | STRICT
                      COLLISION_NON_AIR_POLICY: SKIP
                      # Snap placement rotations to the 24 grid orientations.
                      ROTATION_SNAP: true
                      # Max deviation from the nearest grid orientation before a warning is logged (degrees).
                      ROTATION_SNAP_THRESHOLD_DEGREES: 5
                      # Emit per-face material plates for mixed-material cubes.
                      PER_FACE_RENDERING: true
                      # Anchor convention: AUTO (detect from model format and extent) | CENTER | GRID
                      ORIGIN_MODE: AUTO

                    # Animation engine (bbmodel animations -> BlockDisplay transforms).
                    # The server pushes target transforms every TICK_INTERVAL_TICKS and the
                    # vanilla client interpolates between them, so motion renders at the
                    # client's own frame rate.
                    ANIMATION:
                      # Master switch for the animation runtime.
                      ENABLED: true
                      # Server ticks between transform pushes (1 = every tick, the smoothest
                      # a purely server-side renderer can update).
                      TICK_INTERVAL_TICKS: 1
                      # Client interpolation window in ticks; 0 = match TICK_INTERVAL_TICKS.
                      INTERPOLATION_TICKS: 1
                      # Auto-start animations declared in multiblock levels ("animations")
                      # or model meta files ("autoplay").
                      AUTOPLAY: true
                      # TICK: evaluate and apply every pose on the server tick.
                      # ASYNC_COMPUTE: evaluate poses on the single Mineplus-Pose
                      # thread, apply on the main thread under MAX_APPLY_PER_TICK.
                      # ASYNC_SEND: like ASYNC_COMPUTE, plus one shared flush pass.
                      PIPELINE: ASYNC_COMPUTE
                      # Display pose writes allowed per tick in async pipelines;
                      # the remainder carries over to the next tick.
                      MAX_APPLY_PER_TICK: 512
                      # Derive idle/walk/swim/airborne/attack clips from the model's
                      # owning entity (attack > airborne > swim > walk > idle).
                      # Off by default; explicit AnimationApi calls always win.
                      AUTO_STATE: false
                      # IMMEDIATE: cut to the new state. AFTER_CURRENT: let the
                      # current automatic clip finish its pass first.
                      TRANSITION: AFTER_CURRENT
                      # Crossfade length in ticks when a state starts or ends.
                      TRANSITION_TICKS: 5

                    # Texel surface baking: reconstructs a face's texture pixel-by-pixel
                    # out of flat vanilla palette blocks (concretes, powders, terracottas).
                    # Requires the texture PNG to sit next to the model file (or in the
                    # models folder root) — placing a PNG next to an existing model is the
                    # opt-in gesture. Per-model overrides live in models/<key>.meta.json
                    # ("texelMode": AUTO | ON | OFF, "texelDetail": FACE | SUPERSAMPLE_2X2
                    # | SUPERSAMPLE_4X4).
                    TEXEL_BAKING:
                      # Global enable (false = rendering pipeline identical to before).
                      ENABLED: true
                      # AUTO: only faces that would otherwise use the FULL strategy and have
                      # a resolvable PNG get baked (zero change for existing content).
                      # ON: bake every face with a resolvable PNG. OFF: never bake.
                      MODE: AUTO
                      # Sampling per texel: FACE = one center sample; SUPERSAMPLE_2X2/_4X4
                      # area-average the texel's texture footprint for close-up models.
                      DETAIL: FACE
                      # Merged-plate ceiling per face; above it the face falls back to the
                      # single-material plate (surfaced by /mineplus model info).
                      MAX_PLATES_PER_FACE: 96
                      # Whole-instance plate budget; faces overflow in emission order.
                      MAX_PLATES_PER_INSTANCE: 150
                      # Hard grid edge cap per face (max texels per axis pre-merge).
                      MAX_GRID_EDGE: 64
                      # Collapse large flat color regions into one stretched plate
                      # instead of many 1x1 plates. Regions of near-identical
                      # stretchable colors merge within UNIFORM_AREA_OKLAB_THRESHOLD;
                      # regions of exactly-equal grained colors merge exactly.
                      UNIFORM_AREA_DETECTION: true
                      # Minimum region area (in texels) to coalesce.
                      UNIFORM_AREA_MIN_SIZE: 8
                      # Perceptual (Oklab) tolerance for coalescing stretchable regions.
                      UNIFORM_AREA_OKLAB_THRESHOLD: 0.05
                      # Scale the per-face plate ceiling with face texel area, so
                      # large faces keep proportional detail and small faces stay
                      # cheap. The hard MAX_PLATES_PER_FACE ceiling still applies.
                      ADAPTIVE_BUDGETING: true
                      # An over-budget face degrades to a single dominant-color
                      # plate instead of dropping to the legacy per-face render.
                      # Faces with genuine cutout holes keep the legacy fallback.
                      BUDGET_FALLBACK_TO_SIMPLE_COLOR: true
                      # Memoize identical (texture + UV window + orientation) face
                      # sampling passes within one model bake.
                      REUSE_SYMMETRIC_FACES: true

                    # Display transport: packet-based streaming of the render pipeline's
                    # displays. Instead of spawning real entities (vanilla tracking), every
                    # display is a pooled never-spawned entity sent per viewer: dirty-state
                    # suppression, one bundle packet per player per tick, distance LOD
                    # (FULL = animated stream, STATIC = frozen visuals, beyond = removed).
                    # When disabled or unsupported, the legacy spawned-entity path runs
                    # exactly as before.
                    DISPLAY_TRANSPORT:
                      # Master switch (false = legacy world-spawn entities).
                      ENABLED: false
                      # <= FULL_RANGE: every update packet. <= STATIC_RANGE: static visual. Beyond: hidden.
                      LOD:
                        FULL_RANGE: 20
                        STATIC_RANGE: 48
                        # Ticks between LOD recomputations for moved players.
                        CHECK_INTERVAL_TICKS: 5
                      # Pooled idle displays kept per world before real client removal.
                      POOL:
                        MAX_IDLE_PER_WORLD: 512
                      # Packets per bundle; the client rejects bundles above 4096.
                      NETWORK:
                        BUNDLE_LIMIT: 4000
                        # Weight ceiling per bundle after per-packet accounting
                        # (metadata/equipment lists count their entries). Keeps
                        # a few heavy packets from collapsing a bundle.
                        BUNDLE_WEIGHT_LIMIT: 4096
                        # Milliseconds per tick spent flushing player queues.
                        # Unflushed players carry over to the next tick in order;
                        # 0 flushes everything at once.
                        FLUSH_BUDGET_MS: 2
                      # Per-observer render state: LOD tier plus the visibility,
                      # brightness, equipment, stance and animation override slots
                      # modules can set without duplicating the model.
                      VIEWER_STATE:
                        ENABLED: true
                      # Observer-only equipment stance channel: one player can see
                      # an entity in a different stance without the target's real
                      # inventory changing. Disabled = no listeners, no packets.
                      STANCES:
                        ENABLED: false
                        # Re-send overrides after an inventory close (the client
                        # resets its equipment view then).
                        RESEND_ON_INVENTORY_CLOSE: true

                    # Resource pack system (Phase 2): compiles registered custom
                    # content (custom items, models, textures) into a generated,
                    # content-hash-named resource pack served to players. Fully
                    # additive: vanilla items/blocks/entities without Mineplus
                    # identity never change. When ENABLED is false the whole
                    # subsystem is inert and the virtual rendering engine alone
                    # renders everything exactly as before.
                    PACK:
                      # Master switch (false = nothing compiled, served, or pushed).
                      # Default ON: the pack axis works out of the box.
                      ENABLED: true
                      DELIVERY:
                        # DISABLED: operators distribute the artifact manually.
                        # LOCAL (default): the plugin serves the artifact over a
                        #   small built-in HTTP endpoint. The download URL is
                        #   resolved per player — loopback for clients on the
                        #   server machine, the server IP / connect hostname /
                        #   LAN address otherwise — so single-player LAN testing
                        #   and remote dedicated servers both work without extra
                        #   setup. Set PUBLIC_URL when a reverse proxy or CDN
                        #   fronts the endpoint.
                        # STATIC_URL: the artifact is hosted externally.
                        MODE: LOCAL
                        # Bind address of the built-in endpoint.
                        LOCAL_HOST: 0.0.0.0
                        # Bind port; when busy, the next free port is probed
                        # automatically (the served URL always uses the bound one).
                        LOCAL_PORT: 8163
                        # Blank = per-player resolution (recommended). Set to the
                        # externally reachable prefix (e.g. http://packs.example.com)
                        # when a reverse proxy / external IP fronts LOCAL.
                        PUBLIC_URL: ''
                        # Used by STATIC_URL mode; may contain {hash}.
                        STATIC_URL: ''
                        # Optional prompt text shown with the pack request.
                        PROMPT_MESSAGE: ''
                        # Ticks after join before the automatic prompt. Players
                        # already online when a new artifact compiles are pushed
                        # automatically — no rejoin needed.
                        PROMPT_DELAY_TICKS: 60
                      CACHE:
                        # Generated artifacts (mp-<hash>.zip) kept before pruning.
                        MAX_ARTIFACTS: 8
                      # Non-zero pins pack.mcmeta's pack_format when the
                      # built-in version table lags a new Minecraft release.
                      # The pack also declares a generous supported_formats
                      # range, so newer clients accept it by default.
                      PACK_FORMAT_OVERRIDE: 0
                      # How item definitions present state:
                      #   AUTO (default): item_model on 1.21.4+, legacy
                      #     custom_model_data predicates before it.
                      #   ITEM_MODEL: item_model definitions where available.
                      #   RANGE_DISPATCH: custom_model_data floats inside the
                      #     item definition (works on every modern server).
                      #   SELECT: custom_model_data strings + minecraft:select;
                      #     degrades to RANGE_DISPATCH when the server cannot
                      #     write the string channel.
                      #   LEGACY: custom_model_data predicates on every version.
                      APPEARANCE_MODE: AUTO
                      # Validate models and textures before writing the
                      # artifact (bone/cube/animation budgets, texture size,
                      # unresolved textures, duplicate ids, entry collisions).
                      # Warnings only; the pack still builds.
                      VALIDATE_ASSETS: true
                      # How pack display entities are lit:
                      #   AUTO (default): non-emissive models use natural world
                      #     light; emissive models get block light = their max
                      #     light_emission and sky 15.
                      #   NATURAL: never override (models are dark in caves).
                      #   EMISSIVE: always block light = model max emission, sky 15.
                      #   FULLBRIGHT: always (15, 15) — the legacy fully-lit look.
                      LIGHTING: AUTO

                    # Combat services: the Core's simulated projectile engine
                    # and the particle budgets that make gameplay legible. Both
                    # are shared by modules (guns, throwables, machines firing
                    # rounds) so no module writes its own ballistic loop.
                    BALLISTICS:
                      # Master switch; false makes every shot a no-op that the
                      # firing module handles (never an exception).
                      ENABLED: true
                      # Rounds simulated concurrently. Over the cap a shot
                      # degrades to one instant trace over its full range: same
                      # hit, same damage, no simulation backlog.
                      MAX_ACTIVE_ROUNDS: 128
                      # Draw the dashed tracer along each round's path.
                      TRACERS: true
                      # Server ticks between simulation steps (1 = every tick;
                      # the finest a server-side renderer can update).
                      INTERVAL_TICKS: 1

                    PARTICLE_FX:
                      # Confirmation effects (muzzle flash, impact) budget per
                      # tick. This channel is never starved by decoration.
                      CRITICAL_PER_TICK: 96
                      # Decorative effects (tracers, smoke, rings) budget per
                      # tick — the channel that degrades under load.
                      AMBIENT_PER_TICK: 128
                      # Ceiling a single emission request may spend.
                      MAX_PER_EMIT: 32
                      # How far a particle packet reaches, in blocks. The
                      # vanilla broadcast reaches every player in the world;
                      # this keeps a shot's cost proportional to who can
                      # actually see it.
                      RADIUS: 48

                    # Performance telemetry and the adaptive degradation valve.
                    # Telemetry costs one scheduled tick close plus LongAdder
                    # increments on hot paths; it powers /mineplus perf.
                    PERFORMANCE:
                      # Collect counters, per-tick ring rows and /mineplus perf
                      # output. false makes every recorder a no-op branch.
                      TELEMETRY: true
                      # Degrade the animation/network cadence when the pose
                      # slice stays over budget. Never touches gameplay, damage,
                      # hit registration or persistence.
                      ADAPTIVE: true
                      # Pose apply slice budget per tick, in milliseconds.
                      POSE_BUDGET_MS: 3

                    # Deferred world edits: bulk block changes drain across
                    # ticks under a fixed budget instead of spiking one tick.
                    WORLD_EDITS:
                      # Master switch; false applies each enqueue immediately
                      # (the pre-queue behavior) and schedules no task.
                      ENABLED: false
                      # Wall-clock budget of one drain pass, in milliseconds
                      # (0 = no time bound).
                      MAX_MS_PER_TICK: 2
                      # Hard ceiling of blocks applied by one drain pass.
                      MAX_BLOCKS_PER_TICK: 256

                    # Inbound input: optionally read held-item gestures at
                    # network arrival instead of waiting for the next tick's
                    # Bukkit event. Off by default; the event path is unchanged
                    # while disabled and is the fallback whenever the fast path
                    # cannot be installed.
                    INPUT:
                      # Install one Netty handler (named mineplus_input) per
                      # player and drain its samples at tick start. Any failure
                      # logs once and permanently falls back to events.
                      FAST_PATH: false

                    # Composed visual units: a hit's flash, dust, surface decal
                    # and sound advance as one unit under one budget instead of
                    # independent effects. Off by default; when off the service
                    # has no task and allocates nothing.
                    VISUAL_UNITS:
                      # Master switch; false leaves every module's direct
                      # particle/sound calls exactly as they are today.
                      ENABLED: false
                      # Live-unit ceiling; over it the lowest-priority unit is
                      # dropped first (the newcomer when it is the lowest).
                      MAX_ACTIVE: 64
                      # Units admitted per tick.
                      MAX_SPAWN_PER_TICK: 16
                      # Advance-pass wall-clock budget in milliseconds
                      # (0 = no time bound); the remainder waits a tick.
                      MAX_MS_PER_TICK: 1

                    # Mob behavior engine: budgeted, distance-gated execution of
                    # entity behavior profiles registered by modules (actions like
                    # grazing, planting, or torch lighting around moving mobs).
                    # Lazy: costs nothing until a module registers a profile.
                    MOB_BEHAVIOR:
                      # Master switch (false = registered profiles stay frozen).
                      ENABLED: true
                      # Server ticks between engine runs.
                      INTERVAL_TICKS: 3
                      # Entity buckets: each entity is evaluated every N-th run,
                      # spreading per-entity checks across time.
                      STAGGER_BUCKETS: 4
                      # Chunk radius around each online player considered active.
                      SCAN_RADIUS_CHUNKS: 2
                      # Soft processing budget per run in milliseconds; excess is
                      # deferred to the next run (never a hard tick spike).
                      BUDGET_MILLIS_PER_RUN: 5
                      # Hard ceiling of world block changes per run.
                      MAX_BLOCK_EDITS_PER_RUN: 96
                      # Hard ceiling of entities summoned per run. Mob scenarios
                      # spawn minions, golems and horses; an unbounded summon is
                      # the one way this subsystem can flood a tick.
                      MAX_ENTITY_SPAWNS_PER_RUN: 8
                      # Cap on scenario-claimed entities the engine advances per
                      # run even when no player is near them (journeys, camps).
                      MAX_SUSTAINED_PER_RUN: 64
                      # Cap on multi-stage pipeline scenarios (find a village,
                      # march, raid) advanced per run. A pipeline owns a mob's
                      # whole scenario rather than one roll of the odds.
                      MAX_PIPELINES_PER_RUN: 64

                    # Custom mobs: vanilla base entities carrying a Mineplus
                    # identity, their own visual model and Core-driven behavior.
                    # A custom mob never runs vanilla AI; its behavior comes from
                    # profiles bound to its id, never to the base entity type.
                    CUSTOM_MOBS:
                      # Master switch; false makes registration and spawning
                      # no-ops (existing custom mobs keep their profiles).
                      ENABLED: true
                      # Build per-part visual rigs (hidden base + ItemDisplays).
                      # Rigs need the pack subsystem; the modern representation
                      # names each part model in the item_model component and a
                      # legacy one reaches it through a reserved custom_model_data
                      # override. Without the pack the base entity stays visible.
                      RENDERING: true
                      # Server ticks between rig pose pushes. Keep at 1: a pose
                      # pushed every other tick needs a 2-tick client window to
                      # look smooth, and any window longer than the cadence lags.
                      RENDER_INTERVAL_TICKS: 1
                      # Ticks a display eases its transformation over. 1 matches
                      # the push cadence; raise it only to soften a coarse
                      # RENDER_INTERVAL_TICKS.
                      RENDER_INTERPOLATION_TICKS: 1
                      # Blocks beyond which a rig stops receiving pose updates.
                      RENDER_DISTANCE: 48
                      # Hard ceiling of rigged custom mobs per world.
                      MAX_RIGGED_PER_WORLD: 96
                      # Remove a custom-tagged entity whose definition is not
                      # registered when its chunk loads.
                      REMOVE_UNKNOWN_ON_LOAD: true

                    # Bone rigs: pack levels declaring renderKind: "bones" render
                    # as one ItemDisplay per bone instead of one per cube.
                    RIGS:
                      # Master switch; false keeps every pack level on its
                      # per-cube virtual/pack path.
                      ENABLED: false
                      # Hard ceiling of display bones per rendered rig; a larger
                      # model falls back to its other renderer.
                      MAX_DISPLAYS_PER_INSTANCE: 64
                      # Spawn invisible barrier-sized displays for bones tagged
                      # "hitbox" (opt-in ray-trace targets).
                      HITBOX_BONES: false
                    """;
            try {
                Files.writeString(configFile.toPath(), defaultConfigContent);
                logger.info("Generated default settings.mp.yml template at: " + configFile.getAbsolutePath());
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Could not generate default settings.mp.yml", e);
            }
        }
    }

    public MineplusConfig getConfig() {
        return config != null ? config : new MineplusConfig();
    }
}
