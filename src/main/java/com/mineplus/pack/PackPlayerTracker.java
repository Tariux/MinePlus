package com.mineplus.pack;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/**
 * Runtime listener keeping per-player pack state honest: join prompts (when
 * configured), client status callbacks, and quit cleanup. Handlers touch only
 * a concurrent map and schedule player-scoped pushes — safe on region threads.
 */
final class PackPlayerTracker implements Listener {

    private final PackSystem system;

    PackPlayerTracker(PackSystem system) {
        this.system = system;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        PackDeliveryService delivery = system.delivery();
        if (!delivery.isDeliverable()) {
            return;
        }
        Player player = event.getPlayer();
        PackScheduling.schedulePlayer(system.plugin(), player,
                delivery.settings().promptDelayTicks(),
                () -> {
                    if (player.isOnline() && delivery.isDeliverable()) {
                        delivery.deliver(player);
                    }
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        PackDeliveryService delivery = system.delivery();
        PlayerPackState mapped = switch (event.getStatus()) {
            case ACCEPTED, DOWNLOADED -> PlayerPackState.ACCEPTED;
            case DECLINED, DISCARDED -> PlayerPackState.DECLINED;
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> PlayerPackState.FAILED;
            case SUCCESSFULLY_LOADED -> PlayerPackState.APPLIED;
        };
        delivery.state(event.getPlayer().getUniqueId(), mapped);
        system.onPlayerPackState(event.getPlayer().getUniqueId(), mapped);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        system.delivery().forget(event.getPlayer().getUniqueId());
    }
}
