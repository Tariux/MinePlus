package com.mineplus.infrastructure.mob.custom;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineplus.util.DebugLogger;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.Plugin;

/**
 * Per-mob presentation corrections authored outside the code, read once from
 * {@code plugins/Mineplus/mobs/<id>.model.json} (the mob id's path segment).
 *
 * <p>A rig's pose is a function of its authored model, and two models of the
 * same base type rarely want the same cadence: an imported silhouette may face
 * {@code +z} while the mob walks {@code -z}, its walk clip may be authored at
 * twice the speed it should play, or a leg group may carry a name that says
 * nothing about which role swings it. Those are content decisions, so they live
 * in a file an operator can edit and reload, not in a rebuild.</p>
 *
 * <p>Every field is optional; a missing file or key keeps the default, which is
 * exactly the behaviour the code alone produces. Values outside a sane range
 * are ignored with a warning rather than silently multiplied into a seizure.</p>
 *
 * @param animationScale    clip playback multiplier ({@code 1} = authored tempo)
 * @param walkSpeedReference blocks per second at which a walk clip plays at its
 *                          authored tempo
 * @param yawOffsetDegrees  extra rotation of the whole model about the mob's yaw
 * @param clipsAllowed      {@code "procedural"} forces the role pose, {@code
 *                          "clips"} the authored pose, {@code "auto"} picks by
 *                          whether the model has clips
 * @param strideBlocks      distance per full leg cycle of the procedural pose
 * @param roleOverrides     part name to {@link MobPart.Role} name, applied when
 *                          the model was authored with unusable group names
 */
public record MobModelOverrides(float animationScale, float walkSpeedReference,
                                float yawOffsetDegrees, String clipsAllowed,
                                double strideBlocks, Map<String, String> roleOverrides) {

    /** What every model gets when nobody authors anything. */
    public static final MobModelOverrides DEFAULTS =
            new MobModelOverrides(1.0f, 2.0f, 0.0f, "auto", 1.05, Map.of());

    private static final Map<String, MobModelOverrides> CACHE = new ConcurrentHashMap<>();

    /**
     * The overrides for one mob id path, loaded from the plugin data folder on
     * first use and cached for the JVM lifetime; {@code /mineplus reload} (or a
     * {@link #clearCache()}) re-reads them.
     */
    public static MobModelOverrides forMob(Plugin plugin, String idPath) {
        if (plugin == null || idPath == null || idPath.isBlank()) {
            return DEFAULTS;
        }
        return CACHE.computeIfAbsent(idPath.toLowerCase(Locale.ROOT),
                key -> read(plugin.getDataFolder(), key));
    }

    /** Drops every cached file so the next mob rig re-reads its overrides. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static MobModelOverrides read(File dataFolder, String idPath) {
        File file = new File(new File(dataFolder, "mobs"), idPath + ".model.json");
        if (!file.isFile()) {
            return DEFAULTS;
        }
        try {
            JsonElement parsed = JsonParser.parseString(
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                DebugLogger.warning("[CustomMobs] '" + file.getName() + "' is not a JSON object; ignored.");
                return DEFAULTS;
            }
            return merge(parsed.getAsJsonObject());
        } catch (Exception unreadable) {
            warnUnreadable(file, unreadable);
            return DEFAULTS;
        }
    }

    private static void warnUnreadable(File file, Exception cause) {
        DebugLogger.warning("[CustomMobs] Could not read '" + file.getPath() + "': " + cause
                + "; using the model's authored values.");
    }

    private static MobModelOverrides merge(JsonObject root) {
        float animationScale = number(root, "animationScale", DEFAULTS.animationScale(), 0.05f, 20.0f);
        float walkSpeed = number(root, "walkSpeedReference", DEFAULTS.walkSpeedReference(), 0.05f, 20.0f);
        float yawOffset = number(root, "yawOffsetDegrees", DEFAULTS.yawOffsetDegrees(), -360.0f, 360.0f);
        String mode = text(root, "poseMode", DEFAULTS.clipsAllowed());
        if (!mode.equals("auto") && !mode.equals("clips") && !mode.equals("procedural")) {
            DebugLogger.warning("[CustomMobs] poseMode '" + mode + "' is unknown; expected auto|clips|procedural.");
            mode = DEFAULTS.clipsAllowed();
        }
        double stride = number(root, "strideBlocks", (float) DEFAULTS.strideBlocks(), 0.05f, 20.0f);
        Map<String, String> roles = roles(root);
        return new MobModelOverrides(animationScale, walkSpeed, yawOffset, mode, stride, roles);
    }

    private static Map<String, String> roles(JsonObject root) {
        JsonElement map = root.get("legRoleMap");
        if (map == null || !map.isJsonObject()) {
            return Map.of();
        }
        Map<String, String> roles = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : map.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            String role = value != null && value.isJsonPrimitive()
                    ? value.getAsString().toUpperCase(Locale.ROOT) : "";
            try {
                roles.put(entry.getKey().toLowerCase(Locale.ROOT), MobPart.Role.valueOf(role).name());
            } catch (IllegalArgumentException unknown) {
                DebugLogger.warning("[CustomMobs] legRoleMap part '" + entry.getKey()
                        + "' asks for role '" + role + "', which is not a MobPart.Role; ignored.");
            }
        }
        return Map.copyOf(roles);
    }

    private static float number(JsonObject root, String key, float fallback, float min, float max) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            float value = element.getAsFloat();
            if (!Float.isFinite(value) || value < min || value > max) {
                DebugLogger.warning("[CustomMobs] '" + key + "' = " + value + " is outside "
                        + min + ".." + max + "; using " + fallback + ".");
                return fallback;
            }
            return value;
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static String text(JsonObject root, String key, String fallback) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        return element.getAsString().trim().toLowerCase(Locale.ROOT);
    }

    /** The role a part should pose with, or {@code fallback} when unoverridden. */
    public MobPart.Role roleFor(MobPart part, MobPart.Role fallback) {
        String override = roleOverrides.get(part.name());
        return override == null ? fallback : MobPart.Role.valueOf(override);
    }

    /** {@code true} when the model's clips drive the pose. */
    public boolean useClips(boolean hasClips) {
        return switch (clipsAllowed) {
            case "clips" -> hasClips;
            case "procedural" -> false;
            default -> hasClips;
        };
    }
}
