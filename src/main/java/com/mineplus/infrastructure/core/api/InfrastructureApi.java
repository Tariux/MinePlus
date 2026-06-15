package com.mineplus.infrastructure.core.api;

import com.mineplus.infrastructure.core.events.MultiBlockSignal;
import com.mineplus.infrastructure.core.gui.InfrastructureGui;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleEvent;
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

    void sendSignal(UUID sourceId, UUID targetId, String channel, Map<String, String> data);

    MultiBlockInstance getBlock(UUID id);

    MultiBlockInstance getBlockAt(Location location);

    MultiBlockSignal createSignal(UUID sourceId, UUID targetId, String channel, Map<String, String> data, int hops);

    void setTextureOverride(String textureName, org.bukkit.Material material);
}
