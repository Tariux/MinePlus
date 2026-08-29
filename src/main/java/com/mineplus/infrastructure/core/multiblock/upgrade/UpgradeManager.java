package com.mineplus.infrastructure.core.multiblock.upgrade;

import com.mineplus.infrastructure.core.items.InfrastructureItemManager;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockLevel;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import java.util.Map;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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
        if (isFreeFor(player)) {
            return true;
        }
        return itemManager.hasRequirements(player, next.upgradeCost());
    }

    public boolean consumeUpgradeCost(MultiBlockType type, MultiBlockInstance instance, Player player) {
        MultiBlockLevel next = type.level(instance.level() + 1);
        if (next == null) {
            return false;
        }
        if (isFreeFor(player)) {
            return true;
        }
        return itemManager.consumeRequirements(player, next.upgradeCost());
    }

    /** Returns a consumed upgrade cost to the player after a failed swap (inventory first, then drops). */
    public void refundUpgradeCost(Player player, Map<String, Integer> cost) {
        if (player == null || cost == null || cost.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Integer> entry : cost.entrySet()) {
            int remaining = Math.max(entry.getValue(), 0);
            if (remaining == 0) {
                continue;
            }

            ItemStack template = itemManager.createItem(entry.getKey());
            if (template == null) {
                continue;
            }

            while (remaining > 0) {
                int size = Math.min(remaining, template.getMaxStackSize());
                ItemStack stack = template.clone();
                stack.setAmount(size);
                remaining -= size;

                Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
                for (ItemStack leftover : overflow.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
        }
    }

    /** Vanilla convention: creative-mode players never pay or consume upgrade materials. */
    private boolean isFreeFor(Player player) {
        return player != null && player.getGameMode() == GameMode.CREATIVE;
    }
}
