package com.mineplus.infrastructure.api.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginManager;

/**
 * Entry point for the public API-v1 events. Dispatches through Bukkit's
 * plugin manager; on headless/test servers (no {@code Server} registered)
 * dispatch is a no-op instead of throwing, so event firing never breaks
 * unit tests or tooling.
 *
 * @since 1.3.0
 */
public final class ApiEvents {

    private ApiEvents() {
    }

    /**
     * Fires the event synchronously and returns it so callers can read the
     * (possibly cancelled) state in one expression.
     *
     * @param event event to fire
     * @param <E>   event type
     * @return the same event, after all listeners ran
     * @since 1.3.0
     */
    public static <E extends Event> E dispatch(E event) {
        try {
            PluginManager pm = Bukkit.getPluginManager();
            if (pm != null) {
                pm.callEvent(event);
            }
        } catch (IllegalStateException | IllegalArgumentException headless) {
            // No server registered (unit tests, CLI tooling): skip dispatch.
        }
        return event;
    }
}
