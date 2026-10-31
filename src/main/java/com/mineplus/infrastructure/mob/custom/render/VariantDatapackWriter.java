package com.mineplus.infrastructure.mob.custom.render;

import com.mineplus.util.ServerVersion;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

/**
 * The 1.21.5+ data-driven variant channel behind
 * {@link com.mineplus.infrastructure.mob.custom.MobRenderSpec.RenderType#RETEXTURE_VANILLA}
 * — the only correct way to reskin a vanilla mob, because the base entity
 * keeps its own geometry and only its texture is swapped.
 *
 * <p>One datapack ({@code mineplus_mob_variants}) inside the default world's
 * {@code datapacks/} folder carries one registry entry per reskinned mob:
 * {@code data/<ns>/<registry>/<id>.json} with
 * {@code asset_id <ns>:entity/<id>} (the client reads the texture from
 * {@code assets/<ns>/textures/entity/<id>.png}) and empty
 * {@code spawn_conditions}, so the variant never spawns naturally. Entries are
 * written idempotently; when anything changed, the caller logs that a restart
 * (or a manual {@code /reload}) is required before the registry syncs.</p>
 *
 * <p>Application to a spawned entity is reflective ({@code RegistryAccess} →
 * {@code RegistryKey.<TYPE>_VARIANT} → {@code setVariant}), because this code
 * compiles against the 1.21 API where those types do not exist. Any failure
 * degrades to the vanilla texture — never purple/black, just unreskinned.</p>
 */
public final class VariantDatapackWriter {

    private static final String PACK_FOLDER = "mineplus_mob_variants";
    private static final String PACK_DESCRIPTION = "Mineplus custom mob variants";

    private VariantDatapackWriter() {
    }

    /** The registry folder name for {@code baseType}'s variant registry, or {@code null}. */
    public static String registryFor(EntityType baseType) {
        return switch (baseType) {
            case PIG -> "pig_variant";
            case COW -> "cow_variant";
            case CHICKEN -> "chicken_variant";
            case CAT -> "cat_variant";
            case WOLF -> "wolf_variant";
            case FROG -> "frog_variant";
            case SALMON -> "salmon_variant";
            default -> null;
        };
    }

    /** The {@code RegistryKey} constant for {@code baseType}'s variant registry, or {@code null}. */
    public static String registryKeyConstant(EntityType baseType) {
        String registry = registryFor(baseType);
        return registry == null ? null : registry.toUpperCase(Locale.ROOT);
    }

    /** The vanilla entity texture size a reskin must match, or {@code null} when unchecked. */
    public static int[] expectedTextureSize(EntityType baseType) {
        return switch (baseType) {
            case PIG, COW, CHICKEN -> new int[]{64, 32};
            case CAT, WOLF -> new int[]{64, 32};
            default -> null;
        };
    }

    /** The datapack format number for the running server ({@code -1} when unknown). */
    public static int datapackFormat() {
        int[] v = ServerVersion.version();
        if (v[0] != 1) {
            return -1;
        }
        if (v[1] < 21 || (v[1] == 21 && v[2] < 5)) {
            return -1;
        }
        if (v[2] == 5) {
            return 71;
        }
        if (v[2] == 6) {
            return 80;
        }
        return 81;
    }

    /**
     * Writes (or skips, when unchanged) the variant's registry entry and the
     * pack manifest.
     *
     * @return {@code true} when files changed and a reload is required
     */
    public static synchronized boolean ensureEntry(String namespace, String idPath, String registry) {
        if (Bukkit.getWorlds().isEmpty()) {
            return false;
        }
        File worldFolder = Bukkit.getWorlds().get(0).getWorldFolder();
        File packFolder = new File(worldFolder, "datapacks/" + PACK_FOLDER);
        File dataFile = new File(packFolder, "data/" + namespace + "/" + registry + "/" + idPath + ".json");
        boolean changed = false;

        int format = datapackFormat();
        String manifest = "{\"pack\":{\"pack_format\":" + format
                + ",\"description\":\"" + PACK_DESCRIPTION + "\"}}";
        File manifestFile = new File(packFolder, "pack.mcmeta");
        if (format > 0 && (!manifestFile.isFile() || !equalsFile(manifestFile, manifest))) {
            writeFile(manifestFile, manifest);
            changed = true;
        }

        String json = "{\"asset_id\":\"" + namespace + ":entity/" + idPath
                + "\",\"spawn_conditions\":[]}";
        if (!dataFile.isFile() || !equalsFile(dataFile, json)) {
            writeFile(dataFile, json);
            changed = true;
        }
        return changed;
    }

    /** Removes one variant entry (unregister path); {@code true} when a file was deleted. */
    public static synchronized boolean removeEntry(String namespace, String idPath, String registry) {
        if (Bukkit.getWorlds().isEmpty()) {
            return false;
        }
        File worldFolder = Bukkit.getWorlds().get(0).getWorldFolder();
        File dataFile = new File(worldFolder, "datapacks/" + PACK_FOLDER
                + "/data/" + namespace + "/" + registry + "/" + idPath + ".json");
        return dataFile.isFile() && dataFile.delete();
    }

    /**
     * Reflectively applies the variant to a spawned entity.
     *
     * @return {@code true} when the variant applied; {@code false} when the
     *         server has no such registry (too old, not Paper, entry not
     *         loaded yet) — the caller degrades to the vanilla texture
     */
    public static boolean applyVariant(LivingEntity entity, String namespace, String idPath,
                                       EntityType baseType) {
        String constant = registryKeyConstant(baseType);
        if (constant == null) {
            return false;
        }
        try {
            Class<?> accessClass = Class.forName("io.papermc.paper.registry.RegistryAccess");
            Object access = accessClass.getMethod("registryAccess").invoke(null);
            Class<?> keyClass = Class.forName("io.papermc.paper.registry.RegistryKey");
            Object key = null;
            for (java.lang.reflect.Field field : keyClass.getFields()) {
                if (field.getName().equals(constant)) {
                    key = field.get(null);
                    break;
                }
            }
            if (key == null) {
                return false;
            }
            Object registry = accessClass.getMethod("getRegistry", keyClass).invoke(access, key);
            NamespacedKey entryKey = new NamespacedKey(namespace, idPath);
            Object variant = registry.getClass().getMethod("get", NamespacedKey.class)
                    .invoke(registry, entryKey);
            if (variant == null) {
                return false;
            }
            for (java.lang.reflect.Method method : entity.getClass().getMethods()) {
                if (method.getName().equals("setVariant") && method.getParameterCount() == 1) {
                    method.invoke(entity, variant);
                    return true;
                }
            }
            return false;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // File plumbing (called off the main thread)
    // ------------------------------------------------------------------

    private static boolean equalsFile(File file, String content) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8).equals(content);
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static void writeFile(File file, String content) {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            com.mineplus.util.DebugLogger.warning("[CustomMobs] Could not create " + parent);
            return;
        }
        try {
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException failed) {
            com.mineplus.util.DebugLogger.warning("[CustomMobs] Could not write " + file + ": "
                    + failed.getMessage());
        }
    }
}
