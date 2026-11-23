package com.mineplus.create.rotational;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.module.ModuleFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Wiring of the pure-Java {@link RotationalNetworkManager} into the server:
 * a repeating tick task (5 ticks period), persistence to a snapshot file in
 * the module data folder, and chunk-unload awareness (nodes in unloaded
 * chunks are skipped by the simulation).
 */
public final class RotationalNetworkFeature extends ModuleFeature {

    private static final long TICK_PERIOD = 5L;
    private static final String SNAPSHOT_FILE = "rotational-networks.txt";

    private final RotationalNetworkManager manager = new RotationalNetworkManager();
    private int taskId = -1;

    public RotationalNetworkFeature(JavaPlugin plugin, PluginContext context) {
        super(plugin, context);
    }

    @Override
    public String id() {
        return "rotational";
    }

    @Override
    protected void onEnable() {
        Path snapshot = plugin.getDataFolder().toPath().resolve(SNAPSHOT_FILE);
        if (Files.isRegularFile(snapshot)) {
            try {
                manager.load(Files.readString(snapshot, StandardCharsets.UTF_8));
                plugin.getLogger().info("[rotational] restored " + manager.size() + " rotating blocks.");
            } catch (IOException exception) {
                plugin.getLogger().severe("[rotational] could not read snapshot, starting empty: " + exception);
            }
        }
        this.taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD).getTaskId();
    }

    @Override
    protected void onDisable() {
        if (this.taskId != -1) {
            Bukkit.getScheduler().cancelTask(this.taskId);
            this.taskId = -1;
        }
        Path snapshot = plugin.getDataFolder().toPath().resolve(SNAPSHOT_FILE);
        try {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, manager.save(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().severe("[rotational] could not write snapshot: " + exception);
        }
    }

    private void tick() {
        String snapped = manager.tick(node -> !chunkLoaded(node));
        if (snapped != null) {
            // Overload snap: the block break and its effects are handled by the
            // placement listeners in later phases; the manager already removed
            // the node and re-split the component.
            plugin.getLogger().info("[rotational] shaft snapped from overload: " + snapped);
        }
    }

    private boolean chunkLoaded(RotationalNetworkManager.Node node) {
        String[] parts = node.key.split(";");
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return false;
        }
        return world.isChunkLoaded(Integer.parseInt(parts[1]) >> 4, Integer.parseInt(parts[3]) >> 4);
    }

    public RotationalNetworkManager networks() {
        return manager;
    }
}
