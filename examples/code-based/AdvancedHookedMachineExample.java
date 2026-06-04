package com.mineplus.examples;

import com.mineplus.infrastructure.core.api.InfrastructureApi;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockLevel;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.joml.Quaternionf;

public final class AdvancedHookedMachineExample {

    private final InfrastructureApi api;

    public AdvancedHookedMachineExample(InfrastructureApi api) {
        this.api = api;
    }

    public void registerType() {
        Map<Integer, MultiBlockLevel> levels = new LinkedHashMap<>();
        levels.put(1, new MultiBlockLevel(
                1,
                "models/furnace_lv1.bbmodel",
                1.0,
                100.0,
                Map.of("core_plate", 4),
                Map.of("title", "Furnace I")
        ));
        levels.put(2, new MultiBlockLevel(
                2,
                "models/furnace_lv2.bbmodel",
                1.4,
                160.0,
                Map.of("core_plate", 8),
                Map.of("title", "Furnace II")
        ));

        api.registerMultiBlock(new MultiBlockType(
                "advanced_furnace",
                "Advanced Furnace",
                levels,
                new MultiBlockHook() {
                    @Override
                    public void onInteract(MultiBlockInstance instance, Player actor) {
                        actor.sendMessage("[Mineplus] Interacted with " + instance.typeId());
                    }
                },
                ""
        ));
    }

    public MultiBlockInstance spawn(Player actor, Location location) {
        MultiBlockInstance instance = api.createMultiBlock(
                "advanced_furnace",
                location,
                actor.getUniqueId(),
                actor.getUniqueId(),
                new Quaternionf()
        );
        if (instance == null) {
            return null;
        }

        return api.placeMultiBlock(instance.id(), actor) ? instance : null;
    }

    public void link(MultiBlockInstance source, MultiBlockInstance target) {
        if (source == null || target == null) {
            return;
        }
        api.linkBlocks(source.id(), target.id());
        api.sendSignal(source.id(), target.id(), "power", Map.of("level", "1"));
    }

    public void remove(UUID id, Player actor) {
        api.removeBlock(id, actor, true);
    }
}
