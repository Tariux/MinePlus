package com.mineplus.pack;

import com.mineplus.pack.compile.PackArtifact;
import com.mineplus.pack.compile.PackCache;
import com.mineplus.util.DebugLogger;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.bukkit.entity.Player;

/**
 * Resolves artifact URLs per delivery mode and pushes packs to players,
 * tracking per-player state. Delivery is independent of compilation: this
 * service only consumes {@link PackArtifact} metadata produced elsewhere.
 */
final class PackDeliveryService {

    private final PackSettings settings;
    private final PackCache cache;
    private final LocalPackEndpoint localEndpoint;
    private final Map<UUID, PlayerPackState> playerStates = new ConcurrentHashMap<>();
    private volatile PackArtifact current;

    PackDeliveryService(PackSettings settings, PackCache cache) {
        this.settings = settings;
        this.cache = cache;
        this.localEndpoint = settings.deliveryMode() == PackDeliveryMode.LOCAL
                ? new LocalPackEndpoint(cache, settings.localHost(), settings.localPort())
                : null;
    }

    boolean start() {
        if (localEndpoint != null && !localEndpoint.start()) {
            return false;
        }
        return true;
    }

    void stop() {
        if (localEndpoint != null) {
            localEndpoint.stop();
        }
        playerStates.clear();
    }

    /** New artifact became current (compile finished); resets prompted players. */
    void publish(PackArtifact artifact) {
        this.current = artifact;
        if (artifact == null) {
            return;
        }
        // Players who previously loaded a pack get the new one on their next
        // join/push; states reset so prompts re-run against the new hash.
        playerStates.clear();
    }

    PackArtifact current() {
        return current;
    }

    boolean isDeliverable() {
        return settings.deliveryMode() != PackDeliveryMode.DISABLED && current != null && urlFor(current) != null;
    }

    /** URL for the artifact under the active delivery mode, or null when undeliverable. */
    String urlFor(PackArtifact artifact) {
        return switch (settings.deliveryMode()) {
            case LOCAL -> localUrl(artifact);
            case STATIC_URL -> staticUrl(artifact);
            case DISABLED -> null;
        };
    }

    private String localUrl(PackArtifact artifact) {
        if (localEndpoint == null) {
            return null;
        }
        String base = settings.publicUrl().isBlank()
                ? derivePublicHost() + ":" + settings.localPort()
                : trimTrailingSlash(settings.publicUrl());
        return base + "/pack/" + artifact.artifactName();
    }

    private String staticUrl(PackArtifact artifact) {
        if (settings.staticUrl().isBlank()) {
            return null;
        }
        return settings.staticUrl().replace("{hash}", artifact.artifactName());
    }

    private String derivePublicHost() {
        String ip = org.bukkit.Bukkit.getIp();
        if (ip == null || ip.isBlank() || "0.0.0.0".equals(ip)) {
            return "http://" + getMachineHost();
        }
        return "http://" + ip;
    }

    private String getMachineHost() {
        try {
            String host = java.net.InetAddress.getLocalHost().getHostAddress();
            return host;
        } catch (java.net.UnknownHostException exception) {
            return "127.0.0.1";
        }
    }

    private String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Pushes the current pack to a player and records the request. No-ops
     * safely when delivery is disabled or no artifact exists.
     */
    boolean deliver(Player player) {
        if (player == null || current == null) {
            return false;
        }
        String url = urlFor(current);
        if (url == null) {
            return false;
        }
        state(player.getUniqueId(), PlayerPackState.REQUESTED);
        try {
            byte[] hash = HexFormat.of().parseHex(current.sha1());
            player.setResourcePack(url, hash,
                    settings.promptMessage().isBlank() ? null : settings.promptMessage());
            return true;
        } catch (Throwable legacySignature) {
            try {
                player.setResourcePack(url);
                return true;
            } catch (Throwable failure) {
                state(player.getUniqueId(), PlayerPackState.FAILED);
                DebugLogger.warning("[PackDelivery] Push to " + player.getName() + " failed: "
                        + failure.getMessage());
                return false;
            }
        }
    }

    PlayerPackState state(UUID playerId) {
        return playerStates.getOrDefault(playerId, PlayerPackState.UNKNOWN);
    }

    void state(UUID playerId, PlayerPackState state) {
        playerStates.put(playerId, state);
    }

    void forget(UUID playerId) {
        playerStates.remove(playerId);
    }

    PackSettings settings() {
        return settings;
    }
}
