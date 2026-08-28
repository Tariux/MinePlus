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
