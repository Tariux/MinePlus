package com.mineplus.fun.cabinet.gui;

import com.mineplus.fun.cabinet.CabinetKeys;
import com.mineplus.fun.cabinet.CabinetStore;
import com.mineplus.infrastructure.core.api.InfrastructureApi;
import com.mineplus.infrastructure.core.gui.AbstractMachineGui;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleManager;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Cabinet storage menu: rows 0-1 are plain container slots, the bottom row is
 * filler. Contents persist through {@link CabinetStore}; closing the menu
 * (any path — E, Esc, opening another inventory) swaps the model back to the
 * closed cabinet via the Core's level mechanism.
 */
public final class CabinetGui extends AbstractMachineGui {

    private static final Set<Integer> STORAGE_SLOTS = IntStream
            .range(0, CabinetKeys.STORAGE_SLOTS)
            .boxed()
            .collect(Collectors.toUnmodifiableSet());

    private final MultiBlockLifecycleManager lifecycleManager;
    private final InfrastructureApi infrastructureApi;

    public CabinetGui(
            JavaPlugin plugin,
            MultiBlockRegistry registry,
            MultiBlockLifecycleManager lifecycleManager,
            InfrastructureApi infrastructureApi
    ) {
        super(plugin, registry, CabinetKeys.STORAGE_SLOTS + 9);
        this.lifecycleManager = lifecycleManager;
        this.infrastructureApi = infrastructureApi;
    }

    @Override
    protected String title(MultiBlockInstance instance) {
        return ChatColor.GOLD + "Cabinet";
    }

    @Override
    protected void layout(Inventory inventory, MultiBlockInstance instance) {
        for (int slot = 0; slot < CabinetKeys.STORAGE_SLOTS; slot++) {
            ItemStack stored = CabinetStore.loadSlot(instance, slot);
            inventory.setItem(slot, stored == null ? null : stored.clone());
        }
        for (int slot = CabinetKeys.STORAGE_SLOTS; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, fillerPane());
        }
    }

    @Override
    protected Set<Integer> containerSlots() {
        return STORAGE_SLOTS;
    }

    @Override
    protected void onButtonClick(Player player, MultiBlockInstance instance, int slot, InventoryClickEvent event) {
        // Filler row only; clicks are cancelled by the base class.
    }

    @Override
    protected void capture(Player player, MultiBlockInstance instance, Inventory inventory) {
        for (int slot = 0; slot < CabinetKeys.STORAGE_SLOTS; slot++) {
            CabinetStore.saveSlot(instance, slot, inventory.getItem(slot));
        }
        infrastructureApi.stagePersist(instance.id());
    }

    @Override
    protected void onClosed(Player player, MultiBlockInstance instance) {
        if (instance.level() == CabinetKeys.LEVEL_OPEN) {
            lifecycleManager.setLevel(instance.id(), CabinetKeys.LEVEL_CLOSED);
        }
    }
}
