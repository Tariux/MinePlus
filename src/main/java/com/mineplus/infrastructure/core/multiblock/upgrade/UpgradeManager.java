package com.mineplus.infrastructure.core.multiblock.upgrade;

import com.mineplus.infrastructure.core.items.InfrastructureItemManager;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockLevel;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import org.bukkit.entity.Player;

public final class UpgradeManager {

    private final InfrastructureItemManager itemManager;

    public UpgradeManager(InfrastructureItemManager itemManager) {
        this.itemManager = itemManager;
    }

    public boolean canUpgrade(MultiBlockType type, MultiBlockInstance instance, Player player) {
        MultiBlockLevel current = type.level(instance.level());
        MultiBlockLevel next = type.level(instance.level() + 1);
        if (current == null || next == null) {
            return false;
        }
        return itemManager.hasRequirements(player, next.upgradeCost());
    }

    public boolean consumeUpgradeCost(MultiBlockType type, MultiBlockInstance instance, Player player) {
        MultiBlockLevel next = type.level(instance.level() + 1);
        if (next == null) {
            return false;
        }
        return itemManager.consumeRequirements(player, next.upgradeCost());
    }
}
