package com.mineplus.pack;

import com.mineplus.pack.compile.PackArtifact;
import com.mineplus.pack.compile.PackCache;
import com.mineplus.util.DebugLogger;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.bukkit.entity.Player;

/**
 * Resolves artifact URLs per delivery mode and pushes packs to players,
 * tracking per-player state. Delivery is independent of compilation: this
 * service only consumes {@link PackArtifact} metadata produced elsewhere.
 *
 * <p>URL resolution is per-player and automatic: PUBLIC_URL override first,
 * then loopback detection for clients on the server machine, then the
 * operator-configured server IP, then the hostname the client used to connect
 * (Paper's virtual host, probed reflectively), then the machine's LAN
 * address. This makes the same default configuration work for single-player
 * LAN testing and remote dedicated servers without manual URL setup.</p>
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

    /**
     * New artifact became current. Returns {@code true} when the artifact
     * content actually changed (same content re-publishes keep player states,
     * so unchanged recompiles never re-prompt anyone).
     */
    boolean publish(PackArtifact artifact) {
        PackArtifact previous = this.current;
        this.current = artifact;
        if (artifact == null) {
            return false;
        }
        if (previous != null && previous.artifactName().equals(artifact.artifactName())) {
            return false;
        }
        // Players who previously loaded a pack get the new one on their next
        // join/push; states reset so prompts re-run against the new hash.
        playerStates.clear();
        return true;
    }

    PackArtifact current() {
        return current;
    }

    boolean isDeliverable() {
        return settings.deliveryMode() != PackDeliveryMode.DISABLED && current != null && urlFor(current, null) != null;
    }

    /** URL for the artifact to an anonymous client, or null when undeliverable. */
    String urlFor(PackArtifact artifact) {
        return urlFor(artifact, null);
    }

    /** URL for the artifact as reachable by one player, or null when undeliverable. */
    String urlFor(PackArtifact artifact, Player player) {
        return switch (settings.deliveryMode()) {
            case LOCAL -> localUrl(artifact, player);
            case STATIC_URL -> staticUrl(artifact);
            case DISABLED -> null;
        };
    }

    private String localUrl(PackArtifact artifact, Player player) {
        // A URL for an endpoint that is not listening is a guaranteed client
        // "failed to apply resource pack": never hand one out.
        if (localEndpoint == null || !localEndpoint.isRunning()) {
            return null;
        }
        String base = settings.publicUrl().isBlank()
                ? "http://" + resolveHost(player) + ":" + localEndpoint.port()
                : trimTrailingSlash(settings.publicUrl());
        return base + "/pack/" + artifact.artifactName();
    }

    private String staticUrl(PackArtifact artifact) {
        if (settings.staticUrl().isBlank()) {
            return null;
        }
        return settings.staticUrl().replace("{hash}", artifact.artifactName());
    }

    /**
     * The host a client should use to reach this server, in priority order:
     * loopback for same-machine clients, the operator-configured server IP,
     * the hostname the player actually connected through (Paper virtual
     * host), then the machine's LAN address.
     */
    private String resolveHost(Player player) {
        if (player != null) {
            SocketAddress address = player.getAddress();
            if (address instanceof java.net.InetSocketAddress inet
                    && inet.getAddress() != null
                    && inet.getAddress().isLoopbackAddress()) {
                return "127.0.0.1";
            }
        }
        String ip = org.bukkit.Bukkit.getIp();
        if (ip != null && !ip.isBlank() && !"0.0.0.0".equals(ip) && !"0:0:0:0:0:0:0:0".equals(ip)) {
            return ip;
        }
        String virtualHost = virtualHostOf(player);
        if (virtualHost != null && !virtualHost.isBlank() && !virtualHost.startsWith("[")) {
            return virtualHost;
        }
        return machineLanAddress();
    }

    /** Paper's handshake hostname (the address the client used), or null off-Paper. */
    private String virtualHostOf(Player player) {
        if (player == null) {
            return null;
        }
        try {
            Method method = player.getClass().getMethod("getVirtualHost");
            Object host = method.invoke(player);
            if (host instanceof java.net.InetSocketAddress inet) {
                return inet.getHostString();
            }
        } catch (Throwable ignored) {
            // Not Paper, or the handshake is unavailable; the next resolver applies.
        }
        return null;
    }

    /** First up, non-loopback, site-local IPv4 address of this machine. */
    private String machineLanAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (java.net.InterfaceAddress interfaceAddress : nic.getInterfaceAddresses()) {
                    InetAddress address = interfaceAddress.getAddress();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to the localhost heuristic below.
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
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
        String url = urlFor(current, player);
        if (url == null) {
            return false;
        }
        state(player.getUniqueId(), PlayerPackState.REQUESTED);
        byte[] hash = HexFormat.of().parseHex(current.sha1());
        String prompt = settings.promptMessage().isBlank() ? null : settings.promptMessage();
        try {
            player.setResourcePack(url, hash, prompt);
        } catch (Throwable promptSignatureMissing) {
            // Keep the hash (the client's integrity check) as long as any
            // overload accepts it; only the last resort drops it.
            try {
                player.setResourcePack(url, hash);
            } catch (Throwable hashSignatureMissing) {
                try {
                    player.setResourcePack(url);
                } catch (Throwable failure) {
                    state(player.getUniqueId(), PlayerPackState.FAILED);
                    DebugLogger.warning("[PackDelivery] Push to " + player.getName() + " failed: "
                            + failure.getMessage());
                    return false;
                }
            }
        }
        DebugLogger.info("[PackDelivery] Pushed " + current.artifactName() + " to "
                + player.getName() + " at " + url);
        return true;
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

    /** The port the local endpoint actually bound, or -1 (diagnostics/status). */
    int localPort() {
        return localEndpoint == null ? -1 : localEndpoint.port();
    }

    /** True when the local endpoint is serving on a port above the configured one. */
    boolean localPortDrifted() {
        return localEndpoint != null && localEndpoint.portDrifted();
    }

    PackSettings settings() {
        return settings;
    }
}
