package com.mineplus.infrastructure.module;

import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import com.mineplus.infrastructure.model.BlockCoordinate;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Module-facing toolkit that absorbs the boilerplate every Mineplus module
 * previously duplicated: installing embedded resources into the Core's data
 * folder, resolving "the machine I am looking at", and registering module
 * commands on the server's command map.
 *
 * <p>Obtain it through {@code context.moduleSupport()}.
 */
public final class ModuleSupport {

    private final MineplusPlugin core;
    private final MultiBlockRegistry registry;
    private final VirtualBlockManager virtualBlockManager;

    public ModuleSupport(MineplusPlugin core, MultiBlockRegistry registry, VirtualBlockManager virtualBlockManager) {
        this.core = core;
        this.registry = registry;
        this.virtualBlockManager = virtualBlockManager;
    }

    /**
     * Copies an embedded resource from a module jar into the <em>Core's</em>
     * data folder so the Core's loaders pick it up.
     *
     * <p>Overwrite policy convention: {@code overwrite=true} for models (a
     * module update must fix rendering), {@code overwrite=false} for JSON
     * configs (server owners' edits must survive module updates).
     *
     * @param module            the module plugin whose jar/classloader holds the resource
     * @param classpathResource resource path inside the module jar (e.g. {@code defaults/models/x.bbmodel})
     * @param dataRelativePath  target path relative to the Core's data folder (e.g. {@code models/x.bbmodel})
     * @param overwrite         whether to replace an existing file
     * @return {@code true} if the file exists at the target afterwards
     */
    public boolean installDefault(JavaPlugin module, String classpathResource, String dataRelativePath, boolean overwrite) {
        File target = new File(core.getDataFolder(), dataRelativePath);
        if (target.exists() && !overwrite) {
            return true;
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            module.getLogger().warning("Failed to create folder for " + dataRelativePath);
            return target.exists();
        }

        try (InputStream stream = module.getResource(classpathResource)) {
            if (stream == null) {
                module.getLogger().warning("Missing embedded resource: " + classpathResource);
                return target.exists();
            }
            Files.copy(stream, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException exception) {
            module.getLogger().warning("Failed to install resource " + dataRelativePath + ": " + exception.getMessage());
            return target.exists();
        }
    }

    /**
     * Installs several embedded resources at once. The map keys are
     * {@code classpathResource -> dataRelativePath}; the value of the inner
     * map entry is the overwrite flag.
     */
    public void installDefaults(JavaPlugin module, Map<String, String> resources, java.util.function.Predicate<String> overwrite) {
        resources.forEach((resource, target) ->
                installDefault(module, resource, target, overwrite.test(target)));
    }

    /**
     * Resolves the multiblock instance the player is looking at: first by
     * anchor/origin block, then through the rendered-model id (barrier
     * collision cells map back to instances).
     *
     * @param player the looking player
     * @param range  maximum ray-trace distance in blocks
     * @param typeId optional type filter (case-insensitive); {@code null} accepts any type
     * @return the looked-at instance, or {@code null}
     */
    public MultiBlockInstance resolveLooked(Player player, double range, String typeId) {
        Block block = player.getTargetBlockExact((int) range);
        if (block == null) {
            return null;
        }

        Location location = block.getLocation();
        MultiBlockInstance byOrigin = registry.getByLocation(BlockCoordinate.from(block));
        if (matches(byOrigin, typeId)) {
            return byOrigin;
        }

        UUID renderedModelId = virtualBlockManager.getInstanceIdAt(location);
        if (renderedModelId == null) {
            return null;
        }
        MultiBlockInstance byRender = registry.getInstanceByRenderedModelId(renderedModelId);
        return matches(byRender, typeId) ? byRender : null;
    }

    private boolean matches(MultiBlockInstance instance, String typeId) {
        return instance != null && (typeId == null || instance.typeId().equalsIgnoreCase(typeId));
    }

    /**
     * Registers a {@link SubCommand} as a top-level Bukkit command under the
     * owning module's namespace, without a {@code plugin.yml} command entry.
     * The {@code permission} of the subcommand is enforced by the command
     * wrapper. Registering a label that already exists on the command map is
     * ignored.
     *
     * @param module the owning module plugin
     * @param label  the command label (e.g. {@code "cannon"})
     * @param subCommand the subcommand implementation
     * @return {@code true} if the command was registered
     */
    public boolean registerCommand(JavaPlugin module, String label, SubCommand subCommand) {
        CommandMap commandMap = commandMap();
        if (commandMap == null) {
            module.getLogger().warning("Could not access the server command map; command '/" + label + "' not registered.");
            return false;
        }
        if (commandMap.getCommand(label) != null) {
            return false;
        }
        commandMap.register(module.getName().toLowerCase(java.util.Locale.ROOT), new ModuleCommand(label, subCommand));
        return true;
    }

    private CommandMap commandMap() {
        try {
            Method direct = Bukkit.class.getMethod("getCommandMap");
            return (CommandMap) direct.invoke(null);
        } catch (ReflectiveOperationException | ClassCastException exception) {
            try {
                Object server = Bukkit.getServer();
                return (CommandMap) server.getClass().getMethod("getCommandMap").invoke(server);
            } catch (ReflectiveOperationException | ClassCastException fallbackFailure) {
                return null;
            }
        }
    }
}
