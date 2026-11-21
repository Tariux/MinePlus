package com.mineplus.pack;

import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Best-effort probe of a player's real client protocol through ViaVersion —
 * the one scenario where the server version stops describing the client.
 *
 * <p>ViaVersion/ViaBackwards do not translate resource packs: the byte-for-byte
 * artifact this server pushes is what an older client evaluates, including its
 * {@code pack_format} check and its item-model reader. A 1.21.3 client joined
 * through ViaBackwards therefore rejects or mis-renders a pack compiled with
 * the modern {@code item_model} representation, and a client below 1.21.6
 * cannot render free-angle element rotations.</p>
 *
 * <p>Everything here is reflective (the build has no ViaVersion dependency);
 * when ViaVersion is absent the probe simply reports "unknown" and delivery
 * behaves exactly as before. This class only informs diagnostics and gating —
 * it never changes what is compiled.</p>
 */
final class ViaCompatProbe {

    /** 1.21.4: first client that understands the {@code item_model} component. */
    static final int PROTOCOL_1_21_4 = 769;
    /** 1.21.6: first client that accepts free-angle element rotations. */
    static final int PROTOCOL_1_21_6 = 771;

    private volatile boolean viaPresent;
    private volatile Method viaGetApi;
    private volatile Method viaPlayerVersion;

    /** Resolves ViaVersion's API surface once, reflectively; safe to call repeatedly. */
    void rescan() {
        viaPresent = Bukkit.getPluginManager().getPlugin("ViaVersion") != null;
        if (!viaPresent) {
            viaGetApi = null;
            viaPlayerVersion = null;
            return;
        }
        if (viaGetApi != null && viaPlayerVersion != null) {
            return;
        }
        for (String apiClass : new String[]{
                "com.viaversion.viaversion.api.Via", "us.myles.ViaVersion.api.Via"}) {
            try {
                Class<?> via = Class.forName(apiClass);
                Method getApi = via.getMethod("getAPI");
                Object api = getApi.invoke(null);
                Method playerVersion = api.getClass().getMethod("getPlayerVersion", UUID.class);
                viaGetApi = getApi;
                viaPlayerVersion = playerVersion;
                return;
            } catch (Throwable ignored) {
                // Try the next known API package; absent both, stay "unknown".
            }
        }
    }

    boolean viaVersionPresent() {
        rescan();
        return viaPresent;
    }

    /**
     * The client's protocol version, or {@code <= 0} when unknown (no
     * ViaVersion, unresolvable API, or a player the API has not mapped yet).
     */
    int clientProtocol(Player player) {
        if (player == null) {
            return -1;
        }
        rescan();
        if (!viaPresent || viaGetApi == null || viaPlayerVersion == null) {
            return -1;
        }
        try {
            Object api = viaGetApi.invoke(null);
            return (int) viaPlayerVersion.invoke(api, player.getUniqueId());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** True when the client is known to support the modern item_model component. */
    boolean supportsModernItemModels(Player player) {
        int protocol = clientProtocol(player);
        return protocol <= 0 || protocol >= PROTOCOL_1_21_4;
    }

    /** True when the client is known to render free-angle rotations (1.21.6+). */
    boolean supportsFreeAngles(Player player) {
        int protocol = clientProtocol(player);
        return protocol <= 0 || protocol >= PROTOCOL_1_21_6;
    }

    /** Human-readable client era for a protocol number ("1.21.4", "26.3+", "unknown"). */
    static String describeProtocol(int protocol) {
        if (protocol <= 0) {
            return "unknown";
        }
        if (protocol >= 773) {
            return "calendar-era (1.21.9/26.x)";
        }
        return switch (protocol) {
            case 769 -> "1.21.4";
            case 770 -> "1.21.5";
            case 771 -> "1.21.6";
            case 772 -> "1.21.7/1.21.8";
            default -> "protocol " + protocol;
        };
    }
}
