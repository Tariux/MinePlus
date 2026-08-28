package com.mineplus.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Optional SpigotMC update check: compares the installed plugin version against
 * the resource's latest version through the legacy update API. Enabled by
 * setting {@code UPDATE_CHECKER.RESOURCE_ID} in {@code settings.mp.yml} to the
 * published resource id; {@code 0} (default) disables it.
 *
 * <p>The HTTP request runs on an async task with a short timeout — it never
 * blocks the main thread, and failures are logged at {@code FINE} level only.
 */
public final class UpdateChecker {

    private static final String SPIGOT_UPDATE_API = "https://api.spigotmc.org/legacy/update.php?resource=";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private UpdateChecker() {
    }

    /** Starts the async version check for the given SpigotMC resource id. */
    public static void check(JavaPlugin plugin, int resourceId) {
        if (resourceId <= 0) {
            return;
        }

        String currentVersion = plugin.getDescription().getVersion();
        String resourceUrl = "https://www.spigotmc.org/resources/" + resourceId + "/";

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    HttpClient client = HttpClient.newBuilder()
                            .connectTimeout(TIMEOUT)
                            .build();
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(SPIGOT_UPDATE_API + resourceId))
                            .timeout(TIMEOUT)
                            .header("User-Agent", "Mineplus-UpdateChecker")
                            .GET()
                            .build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    String latest = response.body() == null ? "" : response.body().trim();
                    if (latest.isBlank() || latest.equalsIgnoreCase(currentVersion)) {
                        return;
                    }
                    plugin.getLogger().info("A new version is available: " + latest
                            + " (you are running " + currentVersion + "). " + resourceUrl);
                } catch (Exception exception) {
                    plugin.getLogger().fine("Update check skipped: " + exception.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }
}
