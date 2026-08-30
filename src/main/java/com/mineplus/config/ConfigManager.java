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

        try {
            YamlConfiguration yamlConfig = YamlConfiguration.loadConfiguration(configFile);
            boolean additionalDebugLogs = yamlConfig.getBoolean("ADDITIONAL_DEBUG_LOGS",
                    yamlConfig.getBoolean("additionalDebugLogs", false));

            this.config = new MineplusConfig(
                    additionalDebugLogs,
                    MineplusConfig.parseVirtualRendering(yamlConfig, VirtualRenderingSettings.defaults()),
                    MineplusConfig.parseAnimation(yamlConfig, com.mineplus.infrastructure.virtual.animation.AnimationSettings.defaults()),
                    MineplusConfig.parseTexelBaking(yamlConfig, com.mineplus.infrastructure.virtual.texel.TexelBakingSettings.defaults()),
                    MineplusConfig.parseVoxelRendering(yamlConfig, com.mineplus.infrastructure.virtual.voxel.VoxelRenderingSettings.defaults()),
                    yamlConfig.getInt("UPDATE_CHECKER.RESOURCE_ID", 0)
            );
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Could not load configuration from settings.mp.yml, using defaults.", e);
            this.config = new MineplusConfig();
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

                    # Virtual rendering engine (bbmodel -> BlockDisplay pipeline).
                    # Per-model overrides live in models/<key>.meta.json.
                    VIRTUAL_RENDERING:
                      # Collision proxy voxelization: AABB | GEOMETRY | SURFACE
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

                    # Voxel rendering: adaptive strategy selection per model. When a
                    # model is best represented as 1x1x1 voxel units (blocky, lattice-
                    # snapped, texture-backed geometry), it is reconstructed voxel-by-
                    # voxel — each voxel colored by sampling the real geometry/UV
                    # mapping — instead of per-cube displays. All other models keep the
                    # existing classic/face/texel pipeline unchanged. Per-model
                    # overrides live in models/<key>.meta.json ("voxelMode": AUTO | ON |
                    # OFF, "maxVoxelDisplays": 1024).
                    VOXEL_RENDERING:
                      # Global enable (false = adaptive selection never picks voxels).
                      ENABLED: true
                      # AUTO: voxelize only axis-aligned, lattice-snapped, texture-backed,
                      # non-animated models inside the display budget (zero change for
                      # existing content). ON: attempt voxelization for any non-animated
                      # model (off-lattice geometry approximated). OFF: never voxelize.
                      MODE: AUTO
                      # Whole-model display budget after run merging; above it the model
                      # falls back to the legacy pipeline.
                      MAX_DISPLAYS: 1024
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
