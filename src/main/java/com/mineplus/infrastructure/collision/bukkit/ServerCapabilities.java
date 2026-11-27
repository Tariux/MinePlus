package com.mineplus.infrastructure.collision.bukkit;

import com.mineplus.util.ServerVersion;
import java.lang.reflect.Method;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;

/**
 * What this server can do for the collision system, probed once at startup.
 *
 * <p>The collision system targets Spigot and Paper from 1.21 through the
 * calendar versions (26.x). Instead of version tables it asks three questions:
 * does this build expose Paper's contextual shape API, which spelling of the
 * scale attribute exists, and does the entity/chunk surface carry the pieces the
 * backends need. No NMS is ever referenced.</p>
 *
 * @param mcVersion        Minecraft version, e.g. {@code 1.21.1}
 * @param provider         {@code paper}, {@code folia} or {@code spigot}
 * @param paperShapeApi    {@code BlockData#getCollisionShape(Location)} exists
 * @param scaleAttribute   a {@code SCALE}/{@code GENERIC_SCALE} attribute exists
 * @param interactionEntity {@code Interaction} entities exist
 * @param ghostBlocks      {@code Player#sendBlockChange} exists
 * @param chunkPdc         chunk persistent data is available (Paper only)
 * @param folia            the server runs Folia region threads
 */
public record ServerCapabilities(
        String mcVersion,
        String provider,
        boolean paperShapeApi,
        boolean scaleAttribute,
        boolean interactionEntity,
        boolean ghostBlocks,
        boolean chunkPdc,
        boolean folia) {

    /** Probes the running server. Safe to call off-thread (no world access). */
    public static ServerCapabilities detect() {
        String version = ServerVersion.rawVersion();
        String bukkitVersion = safeLower(Bukkit.getVersion());
        String serverName = safeLower(Bukkit.getName());
        boolean folia = classPresent("io.papermc.paper.threadedregions.RegionizedServer");
        boolean paper = folia || classPresent("io.papermc.paper.configuration.Configuration")
                || bukkitVersion.contains("paper") || serverName.contains("paper");
        String provider = folia ? "folia" : paper ? "paper" : "spigot";

        boolean paperShapeApi = hasMethod(BlockData.class, "getCollisionShape", Location.class);
        boolean scale = enumConstantPresent("org.bukkit.attribute.Attribute", "SCALE")
                || enumConstantPresent("org.bukkit.attribute.Attribute", "GENERIC_SCALE");
        boolean interaction = classPresent("org.bukkit.entity.Interaction");
        boolean ghost = hasMethod(org.bukkit.entity.Player.class, "sendBlockChange",
                Location.class, BlockData.class);
        boolean chunkPdc = hasMethod(org.bukkit.Chunk.class, "getPersistentDataContainer");
        return new ServerCapabilities(version, provider, paperShapeApi, scale, interaction,
                ghost, chunkPdc, folia);
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, ServerCapabilities.class.getClassLoader());
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }

    private static boolean hasMethod(Class<?> owner, String name, Class<?>... parameters) {
        try {
            owner.getMethod(name, parameters);
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }

    private static boolean enumConstantPresent(String owner, String constant) {
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<? extends Enum> type = (Class<? extends Enum>) Class.forName(owner);
            Enum.valueOf(type, constant);
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }

    /** One-line summary for startup logs. */
    public String describe() {
        return mcVersion + " (" + provider + ", paperShapeApi=" + paperShapeApi
                + ", scale=" + scaleAttribute + ", interaction=" + interactionEntity
                + ", ghost=" + ghostBlocks + ", chunkPdc=" + chunkPdc + ')';
    }
}
