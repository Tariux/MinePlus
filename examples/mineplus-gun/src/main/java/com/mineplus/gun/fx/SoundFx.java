package com.mineplus.gun.fx;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Plays additive sounds under the {@code mineplusgun:} namespace. No vanilla
 * sound event is ever replaced; an unshipped event simply plays nothing on the
 * client.
 */
public final class SoundFx {

    /** Location sound (heard by everyone in range). */
    public void play(Location location, String event, float volume, float pitch) {
        if (location == null || event == null || event.isBlank()) {
            return;
        }
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        world.playSound(location, event, volume, pitch);
    }

    /** Self-only sound (reload/empty feedback the shooter alone should hear). */
    public void playTo(Player player, String event, float volume, float pitch) {
        if (player == null || event == null || event.isBlank()) {
            return;
        }
        player.playSound(player.getLocation(), event, volume, pitch);
    }
}
