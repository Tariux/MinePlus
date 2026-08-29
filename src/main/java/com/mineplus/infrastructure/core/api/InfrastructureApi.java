package com.mineplus.infrastructure.core.api;

import com.mineplus.infrastructure.core.events.MultiBlockSignal;
import com.mineplus.infrastructure.core.gui.InfrastructureGui;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleEvent;
import com.mineplus.infrastructure.core.multiblock.progress.MachineProcess;
import com.mineplus.infrastructure.core.recipes.MachineRecipe;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.joml.Quaternionf;

public interface InfrastructureApi {

    void registerMultiBlock(MultiBlockType type);

    MultiBlockInstance createMultiBlock(String typeId, Location location, UUID owner, UUID creator, Quaternionf rotation);

    boolean placeMultiBlock(UUID id, Player actor);

    boolean upgradeBlock(UUID id, Player actor);

    boolean removeBlock(UUID id, Player actor, boolean destroy);

    void registerHook(String typeId, MultiBlockHook hook);

    void registerLifecycleListener(Consumer<MultiBlockLifecycleEvent> listener);

    void createRecipe(MachineRecipe recipe);

    void registerGui(String key, InfrastructureGui gui);

    boolean openGui(String key, Player player, MultiBlockInstance instance);

    boolean linkBlocks(UUID from, UUID to);

    boolean unlinkBlocks(UUID from, UUID to);

    Set<UUID> getLinkedBlocks(UUID sourceId);

    /**
     * Automatically links an instance to every other instance within a cube of the
     * given radius around it (pipe-network style auto-connect).
     *
     * @param sourceId the instance to link from
     * @param radius   the Chebyshev search radius (typically 1)
     * @return the number of new links created
     */
    int autoLinkNeighbors(UUID sourceId, int radius);

    void sendSignal(UUID sourceId, UUID targetId, String channel, Map<String, String> data);

    MultiBlockInstance getBlock(UUID id);

    MultiBlockInstance getBlockAt(Location location);

    MultiBlockSignal createSignal(UUID sourceId, UUID targetId, String channel, Map<String, String> data, int hops);

    /**
     * Starts a timed crafting process for a recipe on an ACTIVE instance. The
     * process counts down over the recipe's {@code craftTimeTicks}, scaled by the
     * machine level's {@code speedMultiplier}, survives restarts via stateData,
     * and notifies via {@code PROCESS_START}/{@code PROCESS_COMPLETE} lifecycle
     * events and {@code onProcessStart}/{@code onProcessComplete} hooks.
     *
     * @param instanceId the machine instance to run the process on
     * @param recipeId   the recipe to run
     * @return {@code true} if the process was started
     */
    boolean startProcess(UUID instanceId, String recipeId);

    /**
     * Cancels the running timed process on an instance, if any.
     *
     * @param instanceId the machine instance
     * @return {@code true} if a process was running and has been cancelled
     */
    boolean cancelProcess(UUID instanceId);

    /**
     * Returns the timed process currently running on an instance.
     *
     * @param instanceId the machine instance
     * @return the current process, or {@code null} if none is running
     */
    MachineProcess getProcess(UUID instanceId);

    /**
     * Stages an instance's current state (including {@code stateData}) for
     * asynchronous incremental persistence. Call this after mutating
     * {@code instance.mutableStateData()} from hooks or GUI callbacks — the
     * Core's own lifecycle paths stage themselves, but state written outside
     * them would otherwise wait for the next lifecycle event.
     *
     * @param instanceId the machine instance whose state changed
     */
    void stagePersist(UUID instanceId);
}
