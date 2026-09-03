package com.mineplus.infrastructure.virtual.display.nms;

import org.bukkit.Bukkit;

/**
 * Maps the running Minecraft version to an {@link NmsAdapter}. The adapter is loaded
 * reflectively; the reflection implementation self-verifies its class surface, so an
 * unsupported runtime fails fast with a clear error instead of breaking the plugin.
 */
public final class NmsAdapterFactory {

    private NmsAdapterFactory() {
    }

    public static NmsAdapter create() {
        String className = "com.mineplus.infrastructure.virtual.display.nms.ReflectionNmsAdapter";
        try {
            return (NmsAdapter) Class.forName(className).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("NmsAdapter failed to initialize (server "
                    + Bukkit.getVersion() + ")", ex);
        }
    }
}
