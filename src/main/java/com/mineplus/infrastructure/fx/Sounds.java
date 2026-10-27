package com.mineplus.infrastructure.fx;

import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Sound emission that is safe for custom (resource-pack) sound events.
 *
 * <p>{@link World#playSound(Location, String, float, float)} takes a raw name,
 * but the platform rejects a name it cannot map to a known sound event with a
 * runtime exception. Custom namespace events (a module's own
 * {@code mypack:shot}) are exactly that case on a server whose registry does
 * not know them — a player without the resource pack applied, or a sound event
 * the pack never shipped. Without care, the exception unwinds the caller's
 * whole action: the shot has already consumed its round, and everything after
 * the sound call (muzzle smoke, HUD, frame) silently stops. So emission here
 * is guarded and always resolves to something audible or to nothing, never to
 * a thrown exception.
 *
 * <p>Names are accepted in three forms: an enum constant
 * ({@code BLOCK_NOTE_BLOCK_HAT}), a vanilla key ({@code block.note_block.hat})
 * or a custom key ({@code mypack:shot}). A custom key that the server does not
 * know falls back to the caller's chosen vanilla {@link Sound} when one is
 * given, so effects degrade to an audible sound instead of silence.
 */
public final class Sounds {

    private Sounds() {
    }

    /** Location sound (heard by everyone in range). Never throws. */
    public static void play(Location location, String event, float volume, float pitch) {
        play(location, event, volume, pitch, null);
    }

    /**
     * Location sound with a vanilla fallback for an unresolvable custom event,
     * so a packless client still hears the action.
     */
    public static void play(Location location, String event, float volume, float pitch, Sound fallback) {
        World world = location == null ? null : location.getWorld();
        if (world == null || event == null || event.isBlank()) {
            return;
        }
        Sound constant = enumSound(event);
        if (constant != null) {
            emit(() -> world.playSound(location, constant, volume, pitch));
            return;
        }
        if (emit(() -> world.playSound(location, event, volume, pitch)) || fallback == null) {
            return;
        }
        emit(() -> world.playSound(location, fallback, volume, pitch));
    }

    /** Self-only sound (feedback the shooter alone should hear). Never throws. */
    public static void playTo(Player player, String event, float volume, float pitch) {
        playTo(player, event, volume, pitch, null);
    }

    /** Self-only sound with a vanilla fallback for an unresolvable custom event. */
    public static void playTo(Player player, String event, float volume, float pitch, Sound fallback) {
        if (player == null || event == null || event.isBlank()) {
            return;
        }
        Location at = player.getLocation();
        Sound constant = enumSound(event);
        if (constant != null) {
            emit(() -> player.playSound(at, constant, volume, pitch));
            return;
        }
        if (emit(() -> player.playSound(at, event, volume, pitch)) || fallback == null) {
            return;
        }
        emit(() -> player.playSound(at, fallback, volume, pitch));
    }

    /**
     * Per-shot pitch variance so a magazine of rounds never renders as one
     * identical loop replayed on top of itself.
     */
    public static float variance(float base, float spread) {
        return Math.max(0.1f, base + (float) (Math.random() - 0.5) * 2.0f * spread);
    }

    /**
     * Attempts one emission, returning {@code false} when the platform could
     * not resolve the name. Only the failure case costs an exception, and it is
     * caught here so it never reaches gameplay code: an unresolved custom event
     * must not unwind the caller's action.
     */
    private static boolean emit(Runnable emission) {
        try {
            emission.run();
            return true;
        } catch (Throwable unresolvable) {
            return false;
        }
    }

    /** Enum constants are accepted as sound names too; they route to the typed overload. */
    private static Sound enumSound(String name) {
        String trimmed = name.trim();
        if (trimmed.indexOf(':') >= 0 || !trimmed.equals(trimmed.toUpperCase(Locale.ROOT))) {
            return null;
        }
        try {
            return Sound.valueOf(trimmed);
        } catch (IllegalArgumentException notAnEnum) {
            return null;
        }
    }
}
