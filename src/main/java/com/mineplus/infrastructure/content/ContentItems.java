package com.mineplus.infrastructure.content;

import com.mineplus.infrastructure.definition.ItemCategory;
import com.mineplus.pack.item.ItemFrames;
import com.mineplus.util.DebugLogger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Parses a config section into a {@link ContentItem} — the shared, clamped,
 * never-throws presentation parser every content module would otherwise
 * re-roll. Conventions (identical across Mineplus setups, so modules stay
 * thin):
 *
 * <pre>{@code
 * <item-id>:
 *   display-name: "&fColt Peacemaker"
 *   category: WEAPON              # ItemCategory name, optional
 *   model: gun/knife-01-light     # virtual .bbmodel stem (models/ folder)
 *   backing-material: IRON_INGOT  # the vanilla carrier; unsafe ones substituted
 *   price: 500                    # shop metadata, module-interpreted
 *   hidden: false                 # shop visibility
 *   frames:                       # frame-animated content (raw pack JSONs)
 *     idle: idle
 *     loaded: ready               # optional
 *     fire: fire                  # optional (the pose shown while in use)
 *     reload: [reload0, reload1]  # optional, in play order
 *     texture: colt_peacemaker.png
 *   description: ["line one", "line two"]   # optional static lore
 * }</pre>
 *
 * <p>Frame model references become {@code <namespace>:item/<id>/<stem>}; the
 * stems and texture ride on the {@link ContentModel} as install facts. Bad
 * values degrade to documented defaults with a warning — one bad entry never
 * disables a module.</p>
 */
public final class ContentItems {

    /**
     * Default carrier for presented items: a vanilla material with no use
     * action at all, so a pack-less client can never trigger accidental
     * vanilla behaviour while holding the item.
     */
    public static final Material DEFAULT_CARRIER = Material.IRON_INGOT;

    /**
     * Carriers with no server-side use action and no client-side effect, so a
     * mis-cancelled interaction (or a pack-less client) can never produce an
     * accidental vanilla action. Anything outside this set is substituted.
     *
     * <p>A carrier with a vanilla use action (bow, crossbow, food) is
     * deliberately excluded: the client puts itself into its "using item"
     * state for such an item and then stops repeating the use packet, which
     * silently changes how a hold-to-fire trigger behaves. The Core's trigger
     * model hides most of that, but the carrier still decides whether the
     * client keeps the gesture alive, so a presented item always rides a
     * neutral carrier.</p>
     */
    private static final Set<Material> SAFE_CARRIERS = Set.of(
            Material.IRON_INGOT, Material.GOLD_INGOT, Material.COPPER_INGOT, Material.NETHERITE_INGOT,
            Material.IRON_NUGGET, Material.GOLD_NUGGET, Material.STICK, Material.BONE, Material.FEATHER,
            Material.FLINT, Material.STRING, Material.PAPER, Material.BOOK, Material.BLAZE_ROD,
            Material.BREEZE_ROD, Material.GUNPOWDER, Material.GLOWSTONE_DUST, Material.SUGAR,
            Material.PRISMARINE_SHARD, Material.PRISMARINE_CRYSTALS, Material.NAUTILUS_SHELL,
            Material.PHANTOM_MEMBRANE, Material.SHULKER_SHELL, Material.ECHO_SHARD, Material.AMETHYST_SHARD,
            Material.QUARTZ, Material.CHARCOAL, Material.COAL, Material.NETHER_STAR, Material.NETHERITE_SCRAP,
            Material.RAW_IRON, Material.RAW_COPPER, Material.RAW_GOLD, Material.LEATHER, Material.RABBIT_HIDE,
            Material.SLIME_BALL, Material.MAGMA_CREAM, Material.GHAST_TEAR, Material.BRICK, Material.NETHER_BRICK,
            Material.CLAY_BALL, Material.INK_SAC, Material.GLOW_INK_SAC);

    private ContentItems() {
    }

    /**
     * Parses one item section into a {@link ContentItem}. Never throws: a bad
     * field is logged (against the item id) and falls back to a documented
     * default. {@code section} may be null, in which case a bare default item
     * (id-named, no model) is returned.
     *
     * @param namespace pack namespace of the owning module (e.g. {@code mineplusgun})
     * @param id        item id (also the frame folder name for framed items)
     * @param section   the item's own config section, or null
     * @return the parsed content item
     */
    public static ContentItem parse(String namespace, String id, ConfigurationSection section) {
        if (section == null) {
            return new ContentItem(namespace, id, id, List.of(), ItemCategory.UTILITY,
                    DEFAULT_CARRIER, 0.0, false, null);
        }
        return new ContentItem(
                namespace,
                id,
                string(section, "display-name", id),
                section.getStringList("description"),
                category(section.getString("category")),
                carrier(section.getString("backing-material"), id),
                clamp(section.getDouble("price", 0.0), 0.0, 1.0E7),
                section.getBoolean("hidden", false),
                model(namespace, id, section));
    }

    private static ContentModel model(String namespace, String id, ConfigurationSection section) {
        String virtualModel = string(section, "model", null);
        ContentModel frames = frames(namespace, id, section);
        if (virtualModel != null) {
            if (frames != null) {
                DebugLogger.warning("Item '" + id + "' declares both 'model' and 'frames'; using 'model'.");
            }
            return ContentModel.ofVirtual(virtualModel);
        }
        return frames;
    }

    /**
     * The {@code frames} block, or null. The idle stem is required whenever
     * any other stem (or the texture) is present; loaded, fire and reload
     * stems are optional. References resolve to
     * {@code <namespace>:item/<id>/<stem>}.
     */
    private static ContentModel frames(String namespace, String id, ConfigurationSection section) {
        ConfigurationSection frames = section.getConfigurationSection("frames");
        if (frames == null) {
            return null;
        }
        String idle = trim(frames.getString("idle"));
        String loaded = trim(frames.getString("loaded"));
        String fire = trim(frames.getString("fire"));
        String texture = trim(frames.getString("texture"));
        List<String> reload = new ArrayList<>();
        for (String stem : frames.getStringList("reload")) {
            if (stem != null && !stem.isBlank()) {
                reload.add(stem.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (idle == null) {
            if (loaded != null || fire != null || !reload.isEmpty() || texture != null) {
                DebugLogger.warning("Item '" + id + "' declares frames but no 'idle' model; frames ignored.");
            }
            return null;
        }

        LinkedHashSet<String> stems = new LinkedHashSet<>();
        stems.add(idle);
        if (loaded != null) {
            stems.add(loaded);
        }
        if (fire != null) {
            stems.add(fire);
        }
        stems.addAll(reload);

        ItemFrames spec = fire == null
                ? ItemFrames.with(
                        reference(namespace, id, idle),
                        loaded == null ? null : reference(namespace, id, loaded),
                        reload.stream().map(stem -> reference(namespace, id, stem)).toList())
                : ItemFrames.withFire(
                        reference(namespace, id, idle),
                        loaded == null ? null : reference(namespace, id, loaded),
                        reload.stream().map(stem -> reference(namespace, id, stem)).toList(),
                        reference(namespace, id, fire));
        return ContentModel.ofFrames(spec, List.copyOf(stems), texture);
    }

    /** The pack model reference of one frame stem: {@code <namespace>:item/<id>/<stem>}. */
    private static String reference(String namespace, String id, String stem) {
        return namespace + ":item/" + id + "/" + stem.toLowerCase(Locale.ROOT);
    }

    /**
     * True when the material is a carrier with no vanilla use action: holding
     * it produces no client-side gesture, no accidental vanilla behavior, and
     * no change to how the Core's trigger model behaves.
     */
    public static boolean isNeutralCarrier(Material material) {
        return material != null && SAFE_CARRIERS.contains(material);
    }

    /**
     * Resolves the carrier material, refusing any material whose vanilla
     * behaviour would leak through (a carrier's vanilla behaviour is NOT
     * replaced by the model override). Unsafe carriers are substituted with
     * {@link #DEFAULT_CARRIER} and reported.
     */
    private static Material carrier(String configured, String id) {
        Material material = material(configured, DEFAULT_CARRIER, id);
        if (!SAFE_CARRIERS.contains(material)) {
            DebugLogger.warning("Item '" + id + "' backing-material " + material
                    + " has a vanilla use behaviour (e.g. SPYGLASS zooms, FLINT_AND_STEEL ignites) and was "
                    + "replaced with " + DEFAULT_CARRIER + ".");
            return DEFAULT_CARRIER;
        }
        return material;
    }

    private static ItemCategory category(String name) {
        if (name == null || name.isBlank()) {
            return ItemCategory.UTILITY;
        }
        try {
            return ItemCategory.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ItemCategory.UTILITY;
        }
    }

    private static Material material(String name, Material fallback, String owner) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        if (material == null) {
            DebugLogger.warning("'" + owner + "' material '" + name + "' is unknown; using " + fallback + ".");
            return fallback;
        }
        return material;
    }

    private static String string(ConfigurationSection section, String key, String fallback) {
        String value = section.getString(key, fallback);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
