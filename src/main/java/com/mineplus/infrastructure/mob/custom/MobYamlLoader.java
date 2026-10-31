package com.mineplus.infrastructure.mob.custom;

import com.mineplus.infrastructure.mob.custom.MobRenderSpec.FallbackMode;
import com.mineplus.infrastructure.mob.custom.MobRenderSpec.RenderType;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

/**
 * Loads custom-mob definitions from {@code plugins/Mineplus/mobs/*.yml} — one
 * definition per file — and registers them under the Core plugin, so a server
 * operator can add a mob without writing any Java.
 *
 * <p>File layout (every file is re-read by {@link #reload()}):</p>
 *
 * <pre>
 * id: evil_pig              # optional; defaults to the file name
 * base-entity: PIG          # required
 * display-name: "Evil Pig"
 * show-name: true
 * attributes:
 *   max-health: 12.0
 *   movement-speed: 0.28
 *   attack-damage: 4.0
 * render:
 *   type: retexture_vanilla # retexture_vanilla | bbmodel | java_json | none
 *   file: model.bbmodel     # model file, relative to the mobs/ folder
 *   texture: skin.png       # texture file, relative to the mobs/ folder
 *   scale: 1.0
 *   fallback: base_entity   # base_entity | generic_cube
 * hand-items:
 *   weapon_r: IRON_SWORD    # bone -> vanilla material or pack item id
 * animations:
 *   attack: animation.smash # state -> clip alias
 * </pre>
 *
 * <p><b>Legacy key aliases.</b> Older drafts spelled several keys differently;
 * every alias below still parses and is reported once per file at load, so
 * existing mob files survive the render overhaul unchanged:</p>
 * <ul>
 *   <li>{@code base} → {@code base-entity}</li>
 *   <li>top-level {@code render-type} / {@code type} → {@code render.type}</li>
 *   <li>top-level {@code model} / {@code model-file} (and under {@code
 *       render:}) → {@code render.file}</li>
 *   <li>top-level {@code texture} → {@code render.texture}</li>
 *   <li>top-level {@code scale} → {@code render.scale}</li>
 *   <li>{@code fallback-mode} → {@code render.fallback}</li>
 *   <li>{@code hand_items} → {@code hand-items}</li>
 *   <li>{@code attributes.health} / top-level {@code health} →
 *       {@code attributes.max-health}; likewise {@code speed} and
 *       {@code damage}</li>
 * </ul>
 *
 * <p>A file that cannot be parsed, names an unknown base entity, or references
 * a model/texture file that does not exist is skipped with a logged reason —
 * a broken YAML never registers a half-described mob.</p>
 */
public final class MobYamlLoader {

    private static final String DEFAULT_NAMESPACE = "mineplus";

    private final Plugin plugin;
    private final CustomMobApi mobs;
    private final File dir;
    /** Definition ids this loader registered (its reload owns exactly these). */
    private final Set<String> loadedIds = new LinkedHashSet<>();

    public MobYamlLoader(Plugin plugin, CustomMobApi mobs, File dir) {
        this.plugin = plugin;
        this.mobs = mobs;
        this.dir = dir;
    }

    /**
     * Re-reads every {@code *.yml} in the folder: unregisters the ids this
     * loader registered last time, then loads what is on disk now. Returns how
     * many definitions were (re)registered.
     */
    public synchronized int reload() {
        for (String id : loadedIds) {
            mobs.unregister(plugin, id);
        }
        loadedIds.clear();
        if (!mobs.isAvailable()) {
            plugin.getLogger().info("[yaml-mobs] Custom mobs are disabled; skipping " + dir);
            return 0;
        }
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null || files.length == 0) {
            return 0;
        }
        Arrays.sort(files);
        int loaded = 0;
        for (File file : files) {
            try {
                if (load(file)) {
                    loaded++;
                }
            } catch (Throwable unreadable) {
                plugin.getLogger().warning("[yaml-mobs] " + file.getName()
                        + " could not be read: " + unreadable);
            }
        }
        plugin.getLogger().info("[yaml-mobs] " + loaded + " definition(s) loaded from " + dir);
        return loaded;
    }

    private boolean load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> problems = new ArrayList<>();
        Set<String> migrated = new LinkedHashSet<>();
        CustomMobDefinition definition = parse(file, yaml, problems, migrated);
        for (String problem : problems) {
            plugin.getLogger().warning("[yaml-mobs] " + file.getName() + ": " + problem);
        }
        if (!migrated.isEmpty()) {
            plugin.getLogger().info("[yaml-mobs] " + file.getName()
                    + ": legacy key(s) " + String.join(", ", migrated)
                    + " still work; rename them to the current spelling.");
        }
        if (definition == null) {
            return false;
        }
        mobs.register(plugin, definition);
        loadedIds.add(definition.id());
        return true;
    }

    private CustomMobDefinition parse(File file, YamlConfiguration yaml,
                                      List<String> problems, Set<String> migrated) {
        String rawId = yaml.getString("id");
        if (rawId == null || rawId.isBlank()) {
            String name = file.getName();
            rawId = name.substring(0, name.length() - 4);
        }
        String id = rawId.trim().toLowerCase(Locale.ROOT);
        if (!id.contains(":")) {
            id = DEFAULT_NAMESPACE + ":" + id;
        }

        String baseName = str(yaml, migrated, "base-entity", "base");
        EntityType baseType = entityType(baseName);
        if (baseType == null) {
            problems.add("missing or unknown 'base-entity: " + baseName + "'");
            return null;
        }

        CustomMobDefinition.Builder builder = CustomMobDefinition.builder(id, baseType);
        String displayName = str(yaml, migrated, "display-name", "name");
        if (displayName != null) {
            builder.displayName(displayName);
        }
        Boolean showName = bool(yaml, migrated, "show-name", "show_name");
        if (showName != null) {
            builder.showName(showName);
        }
        Boolean persistent = bool(yaml, migrated, "persistent");
        if (persistent != null) {
            builder.persistent(persistent);
        }

        CustomMobAttributes.Builder attributes = CustomMobAttributes.builder();
        Double maxHealth = num(yaml, migrated, "attributes.max-health", "attributes.health", "health");
        if (maxHealth != null) {
            attributes.maxHealth(maxHealth);
        }
        Double movementSpeed = num(yaml, migrated, "attributes.movement-speed", "attributes.speed", "speed");
        if (movementSpeed != null) {
            attributes.movementSpeed(movementSpeed);
        }
        Double attackDamage = num(yaml, migrated, "attributes.attack-damage", "attributes.damage", "damage");
        if (attackDamage != null) {
            attributes.attackDamage(attackDamage);
        }
        Double followRange = num(yaml, migrated, "attributes.follow-range");
        if (followRange != null) {
            attributes.followRange(followRange);
        }
        Double armor = num(yaml, migrated, "attributes.armor");
        if (armor != null) {
            attributes.armor(armor);
        }
        Double knockbackResistance = num(yaml, migrated, "attributes.knockback-resistance");
        if (knockbackResistance != null) {
            attributes.knockbackResistance(knockbackResistance);
        }
        Double attackKnockback = num(yaml, migrated, "attributes.attack-knockback");
        if (attackKnockback != null) {
            attributes.attackKnockback(attackKnockback);
        }
        Double scale = num(yaml, migrated, "attributes.scale");
        if (scale != null) {
            attributes.scale(scale);
        }
        Double jumpStrength = num(yaml, migrated, "attributes.jump-strength");
        if (jumpStrength != null) {
            attributes.jumpStrength(jumpStrength);
        }
        Double gravity = num(yaml, migrated, "attributes.gravity");
        if (gravity != null) {
            attributes.gravity(gravity);
        }
        builder.attributes(attributes.build());

        MobRenderSpec render = renderSpec(yaml, problems, migrated);
        if (render != null) {
            builder.render(render);
        }

        CustomMobDefinition definition;
        try {
            definition = builder.build();
        } catch (RuntimeException rejected) {
            problems.add("definition rejected: " + rejected.getMessage());
            return null;
        }
        return definition;
    }

    private MobRenderSpec renderSpec(YamlConfiguration yaml, List<String> problems, Set<String> migrated) {
        String typeText = str(yaml, migrated, "render.type", "render-type", "type");
        String fileText = str(yaml, migrated, "render.file", "render.model", "render.model-file",
                "model", "model-file", "file");
        String textureText = str(yaml, migrated, "render.texture", "texture");
        Double scale = num(yaml, migrated, "render.scale", "scale");
        String fallbackText = str(yaml, migrated, "render.fallback", "render.fallback-mode", "fallback-mode");
        if (typeText == null && fileText == null && textureText == null && scale == null && fallbackText == null) {
            return null;
        }

        RenderType type = typeText == null ? RenderType.NONE : renderType(typeText);
        if (type == null) {
            problems.add("unknown render.type '" + typeText + "'");
            return null;
        }
        boolean needsModel = type == RenderType.BBMODEL || type == RenderType.JAVA_JSON;
        byte[] modelBytes = needsModel ? fileBytes(fileText, problems) : null;
        if (needsModel && modelBytes == null) {
            return null;
        }
        byte[] textureBytes = textureText == null ? null : fileBytes(textureText, problems);
        if (type == RenderType.RETEXTURE_VANILLA && textureBytes == null) {
            return null;
        }
        if (type == RenderType.BBMODEL && textureBytes == null) {
            problems.add("bbmodel without 'texture:' — embedded exports still work,"
                    + " non-embedded ones cannot resolve their texture");
        }

        MobRenderSpec.Builder spec = MobRenderSpec.builder(type,
                fileText != null ? fileText : (textureText != null ? textureText : "model"));
        if (modelBytes != null) {
            spec.modelBytes(() -> modelBytes);
        }
        if (textureBytes != null) {
            spec.textureBytes(() -> textureBytes);
        }
        if (scale != null && scale > 0.0) {
            spec.scale(scale.floatValue());
        }
        FallbackMode fallback = fallbackMode(fallbackText);
        if (fallback != null) {
            spec.fallback(fallback);
        }
        ConfigurationSection hitbox = yaml.getConfigurationSection("hitbox");
        if (hitbox != null) {
            double width = hitbox.getDouble("width", 0.0);
            double height = hitbox.getDouble("height", 0.0);
            if (width > 0.0 && height > 0.0) {
                spec.hitbox(width, height);
            }
        }
        ConfigurationSection handItems = section(yaml, migrated, "hand-items", "hand_items");
        if (handItems != null) {
            for (String bone : handItems.getKeys(false)) {
                String item = handItems.getString(bone);
                if (item != null && !item.isBlank()) {
                    spec.handItem(bone, item.trim());
                }
            }
        }
        ConfigurationSection animations = section(yaml, migrated, "animations", "clips");
        if (animations != null) {
            for (String state : animations.getKeys(false)) {
                String clip = animations.getString(state);
                if (clip != null && !clip.isBlank()) {
                    spec.animation(state, clip.trim());
                }
            }
        }

        try {
            return spec.build();
        } catch (RuntimeException rejected) {
            problems.add("render spec rejected: " + rejected.getMessage());
            return null;
        }
    }

    private RenderType renderType(String text) {
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "retexture_vanilla", "retexture", "vanilla_texture", "texture" -> RenderType.RETEXTURE_VANILLA;
            case "bbmodel", "blockbench" -> RenderType.BBMODEL;
            case "java_json", "json" -> RenderType.JAVA_JSON;
            case "none" -> RenderType.NONE;
            default -> null;
        };
    }

    private FallbackMode fallbackMode(String text) {
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "base_entity", "base", "vanilla" -> FallbackMode.BASE_ENTITY;
            case "generic_cube", "cube" -> FallbackMode.GENERIC_CUBE;
            default -> null;
        };
    }

    private EntityType entityType(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String name = text.trim().toUpperCase(Locale.ROOT);
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        try {
            return EntityType.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private byte[] fileBytes(String name, List<String> problems) {
        if (name == null || name.isBlank()) {
            problems.add("a render type that needs a file was given none");
            return null;
        }
        File target = new File(dir, name);
        if (!target.isFile()) {
            problems.add("file '" + name + "' does not exist in " + dir.getName() + "/");
            return null;
        }
        try {
            return Files.readAllBytes(target.toPath());
        } catch (IOException unreadable) {
            problems.add("file '" + name + "' could not be read: " + unreadable.getMessage());
            return null;
        }
    }

    /** First non-blank value across the current spelling and its legacy aliases. */
    private String str(YamlConfiguration yaml, Set<String> migrated, String canonicalPath, String... aliasPaths) {
        String value = yaml.getString(canonicalPath);
        if (value != null && !value.isBlank()) {
            return value;
        }
        for (String alias : aliasPaths) {
            value = yaml.getString(alias);
            if (value != null && !value.isBlank()) {
                migrated.add(alias + " -> " + canonicalPath);
                return value;
            }
        }
        return null;
    }

    private Double num(YamlConfiguration yaml, Set<String> migrated, String canonicalPath, String... aliasPaths) {
        if (yaml.isSet(canonicalPath)) {
            return yaml.getDouble(canonicalPath);
        }
        for (String alias : aliasPaths) {
            if (yaml.isSet(alias)) {
                migrated.add(alias + " -> " + canonicalPath);
                return yaml.getDouble(alias);
            }
        }
        return null;
    }

    private Boolean bool(YamlConfiguration yaml, Set<String> migrated, String canonicalPath, String... aliasPaths) {
        if (yaml.isSet(canonicalPath)) {
            return yaml.getBoolean(canonicalPath);
        }
        for (String alias : aliasPaths) {
            if (yaml.isSet(alias)) {
                migrated.add(alias + " -> " + canonicalPath);
                return yaml.getBoolean(alias);
            }
        }
        return null;
    }

    private ConfigurationSection section(YamlConfiguration yaml, Set<String> migrated,
                                         String canonicalPath, String... aliasPaths) {
        ConfigurationSection found = yaml.getConfigurationSection(canonicalPath);
        if (found != null) {
            return found;
        }
        for (String alias : aliasPaths) {
            found = yaml.getConfigurationSection(alias);
            if (found != null) {
                migrated.add(alias + " -> " + canonicalPath);
                return found;
            }
        }
        return null;
    }
}
