package com.mineplus.pack;

import org.bukkit.entity.Player;
import com.mineplus.util.SchedulerCompat;
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
        SchedulerCompat.runForPlayer(system.plugin(), player,
                delivery.settings().promptDelayTicks(),
                () -> {
                    // UNKNOWN guards against double prompts: the post-compile
                    // auto-push may already have delivered the pack while the
                    // join prompt was pending.
                    if (player.isOnline() && delivery.isDeliverable()
                            && delivery.state(player.getUniqueId()) == PlayerPackState.UNKNOWN) {
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
        // The client-side pack state is invisible to every other layer: without
        // this line, "guns do not render" is indistinguishable between a bad
        // artifact and a client that declined or failed the download.
        system.plugin().getLogger().info("[Pack] " + event.getPlayer().getName()
                + " pack state: " + mapped + " (" + event.getStatus() + ")");
        delivery.state(event.getPlayer().getUniqueId(), mapped);
        system.onPlayerPackState(event.getPlayer().getUniqueId(), mapped);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        system.delivery().forget(event.getPlayer().getUniqueId());
    }
}
