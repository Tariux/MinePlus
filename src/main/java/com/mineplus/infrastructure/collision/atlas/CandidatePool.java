package com.mineplus.infrastructure.collision.atlas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The configurable candidate pool used to build the atlas.
 *
 * <p>A pool is data only: family → material names, plus the set of states the
 * server resource pack makes invisible. Expanding a family into concrete
 * {@code BlockData} states needs the Bukkit API and lives in
 * {@code BukkitCandidateExpander}. The pool's canonical text feeds
 * {@link AtlasKey#poolHash()}, so editing the config invalidates the cache.</p>
 */
public final class CandidatePool {

    /**
     * A group of materials sharing an enumeration strategy.
     *
     * @param name             family key, e.g. {@code stairs}
     * @param materials        material names, lower-case
     * @param invisibleCapable whether every state of the family is invisible
     *                         without a pack (barrier, light, structure void)
     * @param cost             backend preference cost (0 cheapest)
     */
    public record Family(String name, List<String> materials, boolean invisibleCapable, int cost) {
        public Family {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("family needs a name");
            }
            List<String> cleaned = new ArrayList<>();
            for (String material : materials == null ? List.<String>of() : materials) {
                if (material != null && !material.isBlank()) {
                    cleaned.add(material.trim().toLowerCase(Locale.ROOT));
                }
            }
            materials = List.copyOf(cleaned);
            cost = Math.max(0, cost);
        }
    }

    private final List<Family> families;
    private final List<String> extraInvisibleStates;
    private final Set<String> excludedMaterials;
    private final String hash;
    private final String canonical;

    public CandidatePool(List<Family> families, List<String> extraInvisibleStates,
            Set<String> excludedMaterials) {
        this.families = List.copyOf(families == null ? List.of() : families);
        List<String> invisible = new ArrayList<>();
        for (String state : extraInvisibleStates == null ? List.<String>of() : extraInvisibleStates) {
            if (state != null && !state.isBlank()) {
                invisible.add(state.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.extraInvisibleStates = List.copyOf(invisible);
        Set<String> excluded = new LinkedHashSet<>();
        for (String material : excludedMaterials == null ? Set.<String>of() : excludedMaterials) {
            if (material != null && !material.isBlank()) {
                excluded.add(material.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.excludedMaterials = Collections.unmodifiableSet(excluded);
        this.canonical = canonicalText();
        this.hash = AtlasKey.sha256(canonical);
    }

    /** The default pool required by the collision specification. */
    public static CandidatePool defaults() {
        return defaults(List.of(), List.of(), Set.of());
    }

    /** Default pool plus server-specific additions. */
    public static CandidatePool defaults(List<String> extraMaterials,
            List<String> extraInvisibleStates, Set<String> excludedMaterials) {
        List<Family> families = new ArrayList<>(defaultFamilies());
        if (extraMaterials != null && !extraMaterials.isEmpty()) {
            families.add(new Family("custom", extraMaterials, false, 5));
        }
        return new CandidatePool(families, extraInvisibleStates, excludedMaterials);
    }

    private static List<Family> defaultFamilies() {
        List<Family> families = new ArrayList<>();
        families.add(new Family("full", List.of(
                "barrier", "light", "structure_void", "stone", "smooth_stone",
                "oak_planks", "glass"), true, 1));
        families.add(new Family("slabs", List.of(
                "smooth_stone_slab", "oak_slab", "stone_slab"), false, 1));
        families.add(new Family("stairs", List.of(
                "oak_stairs", "stone_stairs", "smooth_stone_stairs"), false, 2));
        families.add(new Family("trapdoors", List.of(
                "oak_trapdoor", "iron_trapdoor"), false, 3));
        families.add(new Family("walls", List.of(
                "cobblestone_wall", "mossy_cobblestone_wall"), false, 4));
        families.add(new Family("fences", List.of(
                "oak_fence", "nether_brick_fence"), false, 3));
        families.add(new Family("panes", List.of(
                "glass_pane", "iron_bars"), false, 3));
        families.add(new Family("chains", List.of("chain"), false, 3));
        families.add(new Family("end_rods", List.of("end_rod"), false, 3));
        families.add(new Family("candles", List.of("candle"), false, 3));
        families.add(new Family("snow", List.of("snow"), false, 2));
        families.add(new Family("carpets", List.of("white_carpet"), false, 1));
        families.add(new Family("cauldrons", List.of(
                "cauldron", "water_cauldron", "lava_cauldron", "powder_snow_cauldron"), false, 4));
        families.add(new Family("utility", List.of(
                "hopper", "brewing_stand", "lectern", "campfire", "bell",
                "daylight_detector", "pointed_dripstone"), false, 5));
        families.add(new Family("heads", List.of(
                "skeleton_skull", "skeleton_wall_skull"), false, 4));
        families.add(new Family("no_collision", List.of(
                "tripwire", "torch", "wall_torch", "redstone_torch", "redstone_wall_torch",
                "dandelion", "poppy", "oak_button", "stone_button", "oak_pressure_plate",
                "stone_pressure_plate", "vine", "ladder"), false, 2));
        return families;
    }

    public List<Family> families() {
        return families;
    }

    public List<String> extraInvisibleStates() {
        return extraInvisibleStates;
    }

    public Set<String> excludedMaterials() {
        return excludedMaterials;
    }

    /** Stable sha-256 over the pool contents; part of the atlas cache key. */
    public String hash() {
        return hash;
    }

    /** Canonical text used for the hash (also handy in debug output). */
    public String canonical() {
        return canonical;
    }

    /** True when a state of this material is listed as pack-invisible. */
    public boolean isListedInvisible(String state) {
        if (state == null) {
            return false;
        }
        String normalised = state.toLowerCase(Locale.ROOT).trim();
        String bare = normalised.startsWith("minecraft:") ? normalised.substring(10) : normalised;
        for (String listed : extraInvisibleStates) {
            String value = listed.startsWith("minecraft:") ? listed.substring(10) : listed;
            if (normalised.startsWith(value) || bare.startsWith(value)) {
                return true;
            }
        }
        return false;
    }

    private String canonicalText() {
        StringBuilder builder = new StringBuilder();
        for (Family family : families) {
            builder.append(family.name()).append('|')
                    .append(family.invisibleCapable() ? '1' : '0').append('|')
                    .append(family.cost()).append('|')
                    .append(String.join(",", family.materials())).append('\n');
        }
        builder.append("invisible|").append(String.join(",", extraInvisibleStates)).append('\n');
        builder.append("excluded|").append(String.join(",", excludedMaterials));
        return builder.toString();
    }

    @Override
    public String toString() {
        return "CandidatePool{" + families.size() + " families, " + hash.substring(0, 8) + "}";
    }
}
