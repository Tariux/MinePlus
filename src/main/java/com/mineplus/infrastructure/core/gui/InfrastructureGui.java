package com.mineplus.infrastructure.core.gui;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import org.bukkit.entity.Player;

@FunctionalInterface
public interface InfrastructureGui {

    void open(Player player, MultiBlockInstance instance);
}
