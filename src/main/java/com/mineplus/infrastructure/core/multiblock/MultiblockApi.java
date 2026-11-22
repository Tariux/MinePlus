package com.mineplus.infrastructure.core.multiblock;

import com.mineplus.infrastructure.core.multiblock.progress.MachineProcess;
import com.mineplus.infrastructure.core.multiblock.progress.MachineProcessManager;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import com.mineplus.infrastructure.model.BlockCoordinate;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Multiblock 2.0 facade: everything a module needs to run machines — find
 * instances, read progress, start and cancel processes — without touching the
 * registry, lifecycle or process managers directly.
 */
public final class MultiblockApi {

    private final MultiBlockRegistry registry;
    private final MachineProcessManager processes;

    public MultiblockApi(MultiBlockRegistry registry, MachineProcessManager processes) {
        this.registry = registry;
        this.processes = processes;
    }

    /** The machine instance at a world position, or {@code null} for vanilla ground. */
    public MultiBlockInstance instanceAt(Location location) {
        return registry.getByLocation(BlockCoordinate.from(location));
    }

    /** All live machine instances. */
    public List<MultiBlockInstance> instances() {
        return registry.getInstances();
    }

    /** A registered machine type by id, or {@code null}. */
    public MultiBlockType type(String typeId) {
        return registry.getType(typeId);
    }

    /** All registered machine types. */
    public Collection<MultiBlockType> types() {
        return registry.getTypes();
    }

    /** Starts a crafting/processing recipe on a machine; {@code false} when refused. */
    public boolean startProcess(UUID instanceId, String recipeId) {
        return processes.start(instanceId, recipeId);
    }

    /** Cancels a machine's running process; {@code false} when none was running. */
    public boolean cancelProcess(UUID instanceId) {
        return processes.cancel(instanceId);
    }

    /** The machine's running process, or {@code null}. */
    public MachineProcess processOf(UUID instanceId) {
        return processes.get(instanceId);
    }

    /**
     * Advances every machine process on the plugin's tick budget. Returns the ids
     * of machines whose state changed (redstone-flip ready) so modules can update
     * displays without polling.
     */
    public List<UUID> advanceProcesses(int intervalTicks) {
        List<UUID> dirty = new ArrayList<>();
        processes.advanceAll(intervalTicks, dirty::add);
        return dirty;
    }

    /** Same as {@link #advanceProcesses(int)} with a sink invoked per dirty machine. */
    public int advanceProcesses(int intervalTicks, Consumer<UUID> dirtySink) {
        return processes.advanceAll(intervalTicks, dirtySink);
    }
}
