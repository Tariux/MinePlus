package com.mineplus.infrastructure.api;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.core.api.AnimationApi;
import com.mineplus.infrastructure.core.api.BasicInfrastructureApi;
import com.mineplus.infrastructure.core.api.InfrastructureApi;
import com.mineplus.infrastructure.core.api.JsonInfrastructureApi;
import com.mineplus.pack.PackApi;
import java.util.logging.Level;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

/**
 * API v1 registration (mega-plan P5): publishes the stable facade interfaces a
 * third-party plugin consumes through Bukkit's {@link ServicesManager}, so
 * addons never touch {@code PluginContext} (whose bootstrap overloads and
 * engine references are internal and free to change).
 *
 * <p>Addons obtain a service the standard way:
 * <pre>{@code
 * InfrastructureApi api = Bukkit.getServicesManager()
 *         .load(InfrastructureApi.class);
 * }</pre>
 *
 * <p>Every registration uses {@link ServicePriority#Normal}; a module that
 * wants to decorate or wrap a facade may register at a higher priority.
 * Failure is non-fatal and logged: the facades stay reachable through
 * {@code PluginContext} for the Core itself.
 */
public final class MineplusServices {

    private MineplusServices() {
    }

    /**
     * Registers all v1 facades in the server's {@link ServicesManager}.
     * Safe to call on any Bukkit/Spigot/Paper server; never throws.
     *
     * @since API 1.0
     */
    public static void registerAll(Plugin plugin, PluginContext context) {
        if (plugin == null || context == null) {
            return;
        }
        ServicesManager services = plugin.getServer().getServicesManager();
        if (services == null) {
            return;
        }
        register(plugin, InfrastructureApi.class, context.infrastructureApi());
        register(plugin, BasicInfrastructureApi.class, context.basicInfrastructureApi());
        register(plugin, JsonInfrastructureApi.class, context.jsonInfrastructureApi());
        register(plugin, AnimationApi.class, context.animationApi());
        register(plugin, PackApi.class, context.packApi());
    }

    /**
     * Unregisters every facade this class registered. Called on disable so a
     * soft reload does not leave stale services pointing at a dead context.
     *
     * @since API 1.0
     */
    public static void unregisterAll(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        ServicesManager services = plugin.getServer().getServicesManager();
        if (services == null) {
            return;
        }
        unregister(services, InfrastructureApi.class);
        unregister(services, BasicInfrastructureApi.class);
        unregister(services, JsonInfrastructureApi.class);
        unregister(services, AnimationApi.class);
        unregister(services, PackApi.class);
    }

    private static <T> void register(Plugin plugin, Class<T> clazz, T instance) {
        if (instance == null) {
            return;
        }
        try {
            plugin.getServer().getServicesManager().register(clazz, instance, plugin, ServicePriority.Normal);
        } catch (RuntimeException e) {
            // A missing service registry on stripped/headless servers must not
            // break plugin enable; the facades stay reachable via context.
            java.util.logging.Logger logger = java.util.logging.Logger.getLogger("Mineplus");
            logger.log(Level.WARNING, "Could not register API service " + clazz.getName(), e);
        }
    }

    private static void unregister(ServicesManager services, Class<?> clazz) {
        try {
            services.unregister(clazz);
        } catch (RuntimeException ignored) {
            // Nothing was registered (or the registry is absent); nothing to do.
        }
    }
}
