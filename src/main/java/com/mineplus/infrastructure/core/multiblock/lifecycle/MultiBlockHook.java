package com.mineplus.infrastructure.core.multiblock.lifecycle;

import com.mineplus.infrastructure.core.events.MultiBlockSignal;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import org.bukkit.entity.Player;

public interface MultiBlockHook {

    default void onCreate(MultiBlockInstance instance, Player actor) {
    }

    default void onCraft(MultiBlockInstance instance, Player actor) {
    }

    default void onPlace(MultiBlockInstance instance, Player actor) {
    }

    default void onInteract(MultiBlockInstance instance, Player actor) {
    }

    default void onTick(MultiBlockInstance instance) {
    }

    default void onUpgrade(MultiBlockInstance instance, int previousLevel, int nextLevel, Player actor) {
    }

    default void onBreak(MultiBlockInstance instance, Player actor) {
    }

    default void onRemove(MultiBlockInstance instance, Player actor) {
    }

    default void onModelReload(MultiBlockInstance instance) {
    }

    default void onSignal(MultiBlockInstance instance, MultiBlockSignal signal) {
    }
}
