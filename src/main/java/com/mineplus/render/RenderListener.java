package com.mineplus.render;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RenderListener implements Listener {

    private final JavaPlugin plugin;
    private final EntityPoolManager pool = new EntityPoolManager();
    private final PacketOptimizer optimizer = new PacketOptimizer();

    // keep track of CustomBlockEntity instances that are active on the server
    private final Map<CustomBlockEntity, Set<UUID>> activeStructures = new ConcurrentHashMap<>();

    public RenderListener(JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // -----------------------------------------------------------------
    // Player join / quit
    // -----------------------------------------------------------------
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // register the new player for all existing structures
        for (CustomBlockEntity cbe : activeStructures.keySet()) {
            for (UUID dispId : cbe.getDisplayIds()) {
                optimizer.registerEntityForPlayer(dispId, p.getUniqueId());
            }
        }
        // optional: initialise hand‑item renderer for the player (if you want it on login)
        // HandItemRenderer.setHandItem(p, ...);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        optimizer.clearPlayer(p.getUniqueId());
        // remove player from all structures
        activeStructures.entrySet().removeIf(e1 -> {
            e1.getValue().remove(p.getUniqueId());
            return e1.getValue().isEmpty();
        });
        // cleanup any pools associated with this player
        pool.cleanupChunk(p.getWorld().getSpawnChunk());
    }

    // -----------------------------------------------------------------
    // Player move – LOD update (runs every tick, you may throttle)
    // -----------------------------------------------------------------
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        // simple LOD: iterate over active structures and check distance
        for (CustomBlockEntity cbe : new ArrayList<>(activeStructures.keySet())) {
            // For each online player, decide visibility
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (cbe.isPlayerInRange(p)) {
                    cbe.updateLOD(p, p.getWorld());
                } else {
                    // hide from this player
                    // the optimizer already has them as HIDDEN
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Player interact – manual ray‑cast / distance check
    // -----------------------------------------------------------------
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (e.getHand() == org.bukkit.hand.Hand.HAND && e.getAction().name().contains("RIGHT_CLICK")) {
            Player p = e.getPlayer();
            // iterate over structures the player might be looking at
            for (CustomBlockEntity cbe : activeStructures.keySet()) {
                if (!cbe.isPlayerInRange(p)) continue;
                // get the list of display entities for this structure
                for (EntityHuman disp : cbe.getDisplays()) {
                    Location loc = ((CraftEntity) disp).getBukkitLocation();
                    if (loc.distanceSquared(p.getLocation()) < 2.0) {
                        // call the underlying MinePlusCore block logic
                        coreBlockOnInteract(cbe, disp, p);
                        break; // handle only one part per click
                    }
                }
            }
        }
    }

    private void coreBlockOnInteract(CustomBlockEntity cbe, EntityHuman disp, Player p) {
        // forward to your MinePlusCore CustomBlock.onInteract (you need to expose it)
        // CustomBlock cb = cbe.getCoreBlock();
        // cb.onInteract(p);
        plugin.getLogger().info("Interacted with custom 3D block at " + cbe.getAnchor());
    }

    // -----------------------------------------------------------------
    // Helper methods to register / unregister structures
    // -----------------------------------------------------------------
    public void registerStructure(CustomBlockEntity cbe) {
        activeStructures.putIfAbsent(cbe, new HashSet<>());
    }

    public void unregisterStructure(CustomBlockEntity cbe) {
        activeStructures.remove(cbe);
    }
}