package com.mineplus.gun.input;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Input adapter used when a packet library (PacketEvents) is present. The
 * module never compiles against it: a true press/release stream is obtained
 * reflectively by the integration layer, and this class is the seam the
 * runtime selects. Until that integration is linked it inherits the
 * {@link BukkitInputAdapter} event set, so behaviour is correct on every
 * server while the packet upgrade remains a drop-in.
 */
public final class PacketInputAdapter extends BukkitInputAdapter {

    private final JavaPlugin plugin;

    public PacketInputAdapter(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return plugin != null && plugin.getServer().getPluginManager().getPlugin("packetevents") != null
                ? "packetevents"
                : "bukkit(packet-fallback)";
    }

    @Override
    public void unregister() {
        super.unregister();
    }
}
