package com.mineplus.gun.weapon;

import com.mineplus.infrastructure.PluginContext;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Installs and registers the module's shipped gun assets.
 *
 * <p>The module jar is the source of truth. Every file under
 * {@code defaults/guns/<id>/} is mirrored into the Core's pack staging area:
 * {@code models/…​.json} becomes {@code pack/mineplusgun/models/item/<id>/…},
 * and the folder's per-weapon texture becomes
 * {@code pack/mineplusgun/textures/item/<id>.png}. The sound set under
 * {@code defaults/sounds/} is mirrored into {@code pack/mineplusgun/sounds/}.
 * Nothing vanilla is overridden.</p>
 *
 * <p>Registration walks what was actually installed rather than what the
 * config happens to reference, so a gun folder added to the jar works before
 * anyone writes a config entry for it. Re-registration with identical content
 * is a registry no-op, which keeps {@code /mpgun reload} cheap.</p>
 */
public final class WeaponAssets {

    private static final String GUN_ROOT = "defaults/guns/";
    private static final String SOUND_ROOT = "defaults/sounds/mineplusgun/";
    private static final String SOUND_MANIFEST = "defaults/sounds/sounds.json";

    private static final String MODEL_INSTALL = "pack/mineplusgun/models/item/";
    private static final String TEXTURE_INSTALL = "pack/mineplusgun/textures/item/";
    private static final String SOUND_INSTALL = "pack/mineplusgun/sounds/";
    private static final String MANIFEST_INSTALL = "pack/mineplusgun/sounds.json";

    private WeaponAssets() {
    }

    /**
     * Copies every embedded gun model, texture, sound and the sound manifest
     * into the Core data folder. Always overwrites: assets follow the module
     * jar, not a server owner's edits.
     *
     * @return how many files were installed
     */
    public static int install(JavaPlugin plugin, PluginContext context) {
        int installed = 0;
        for (String resource : resources(plugin)) {
            String target = targetOf(resource);
            if (target != null && context.moduleSupport().installDefault(plugin, resource, target, true)) {
                installed++;
            }
        }
        plugin.getLogger().info("[MineplusGun] Installed " + installed + " asset file(s).");
        // Densify and complete the installed animation before anyone reads the
        // folder: frame discovery and the pack both consume what is on disk.
        WeaponAnimations.generateAll(plugin, context);
        return installed;
    }

    /**
     * Registers every installed gun model, texture, sound and manifest with the
     * pack. The staging folder is authoritative; the config only decides which
     * of those models a weapon item actually uses.
     *
     * @return how many pack entries were registered
     */
    public static int register(JavaPlugin plugin, PluginContext context) {
        int models = registerTree(context, "pack/mineplusgun/models/item", "models/item", ".json");
        int textures = registerFlat(context, "pack/mineplusgun/textures/item", "item", ".png", true);
        int sounds = registerFlat(context, "pack/mineplusgun/sounds", "sounds", ".ogg", false);
        File manifest = context.moduleSupport().dataFile(MANIFEST_INSTALL);
        if (manifest.isFile()) {
            context.packApi().registerRawAsset(WeaponFrames.NAMESPACE, "sounds.json", manifest);
            sounds++;
        }
        int total = models + textures + sounds;
        plugin.getLogger().info("[MineplusGun] Registered " + total + " pack asset(s) ("
                + models + " models, " + textures + " textures, " + sounds + " sounds).");
        return total;
    }

    private static int registerTree(PluginContext context, String dataRoot, String packRoot,
                                    String extension) {
        File root = context.moduleSupport().dataFile(dataRoot);
        List<File> files = new ArrayList<>();
        collect(root, extension, files);
        int registered = 0;
        for (File file : files) {
            String relative = root.toPath().relativize(file.toPath()).toString().replace('\\', '/');
            context.packApi().registerRawAsset(WeaponFrames.NAMESPACE, packRoot + "/" + relative, file);
            registered++;
        }
        return registered;
    }

    private static int registerFlat(PluginContext context, String dataRoot, String packRoot,
                                    String extension, boolean stripExtension) {
        File root = context.moduleSupport().dataFile(dataRoot);
        File[] files = root.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(extension));
        if (files == null) {
            return 0;
        }
        int registered = 0;
        for (File file : files) {
            String name = file.getName();
            String path = packRoot + "/" + (stripExtension ? name.substring(0, name.length() - extension.length()) : name);
            if (stripExtension) {
                context.packApi().registerTexture(WeaponFrames.NAMESPACE, path, file);
            } else {
                context.packApi().registerRawAsset(WeaponFrames.NAMESPACE, path, file);
            }
            registered++;
        }
        return registered;
    }

    private static void collect(File directory, String extension, List<File> out) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, extension, out);
            } else if (child.getName().toLowerCase(Locale.ROOT).endsWith(extension)) {
                out.add(child);
            }
        }
    }

    /** The staging path of one classpath resource, or {@code null} when it is not an asset. */
    private static String targetOf(String resource) {
        if (resource.equals(SOUND_MANIFEST)) {
            return MANIFEST_INSTALL;
        }
        if (resource.startsWith(SOUND_ROOT)) {
            String file = resource.substring(SOUND_ROOT.length());
            // Sound samples only: the folder's authoring notes must never be
            // staged into the pack tree.
            if (file.contains("/") || !isOneOf(file, ".ogg", ".wav")) {
                return null;
            }
            return SOUND_INSTALL + file;
        }
        if (!resource.startsWith(GUN_ROOT)) {
            return null;
        }
        String rest = resource.substring(GUN_ROOT.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String id = rest.substring(0, slash);
        String relative = rest.substring(slash + 1);
        if (relative.startsWith("models/") && relative.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return MODEL_INSTALL + id + "/" + relative.substring("models/".length());
        }
        if (!relative.contains("/") && relative.toLowerCase(Locale.ROOT).endsWith(".png")) {
            return TEXTURE_INSTALL + id + ".png";
        }
        return null;
    }

    private static boolean isOneOf(String name, String... extensions) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /** Every embedded resource path under the module's gun and sound roots. */
    private static List<String> resources(JavaPlugin plugin) {
        Set<String> paths = new TreeSet<>();
        File origin = origin(plugin);
        if (origin == null) {
            return List.of();
        }
        if (origin.isFile()) {
            try (JarFile jar = new JarFile(origin)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && isAsset(entry.getName())) {
                        paths.add(entry.getName());
                    }
                }
            } catch (IOException exception) {
                plugin.getLogger().warning("[MineplusGun] Could not read its own jar: "
                        + exception.getMessage());
            }
            return new ArrayList<>(paths);
        }
        collectResources(origin, origin, paths);
        return new ArrayList<>(paths);
    }

    private static void collectResources(File root, File directory, Set<String> paths) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectResources(root, child, paths);
            } else {
                String relative = root.toPath().relativize(child.toPath()).toString().replace('\\', '/');
                if (isAsset(relative)) {
                    paths.add(relative);
                }
            }
        }
    }

    private static boolean isAsset(String path) {
        return path.startsWith(GUN_ROOT) || path.startsWith(SOUND_ROOT) || path.equals(SOUND_MANIFEST);
    }

    /** The jar or classes directory the module was loaded from. */
    private static File origin(JavaPlugin plugin) {
        try {
            URL location = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            if (location != null && "file".equals(location.getProtocol())) {
                URI uri = location.toURI();
                File file = new File(uri);
                if (file.exists()) {
                    return file;
                }
            }
        } catch (Exception ignored) {
            // No readable code source (unusual loader): nothing to mirror, and
            // install() reports zero assets rather than failing the enable.
        }
        return null;
    }
}
