package com.mineplus.infrastructure.collision;

import com.mineplus.infrastructure.collision.atlas.AtlasKey;
import com.mineplus.infrastructure.collision.atlas.CandidatePool;
import com.mineplus.infrastructure.collision.atlas.ShapeAtlas;
import com.mineplus.infrastructure.collision.bukkit.ServerCapabilities;
import com.mineplus.infrastructure.collision.bukkit.ShapeAtlasBuilder;
import com.mineplus.infrastructure.collision.backend.BackendRegistry;
import com.mineplus.infrastructure.collision.fit.FitCache;
import com.mineplus.infrastructure.collision.fit.FitProfile;
import com.mineplus.infrastructure.collision.fit.FitResult;
import com.mineplus.infrastructure.collision.fit.HybridFitter;
import com.mineplus.infrastructure.collision.fit.ProfilePreset;
import com.mineplus.infrastructure.collision.runtime.CollisionManager;
import com.mineplus.infrastructure.collision.runtime.CollisionStore;
import com.mineplus.infrastructure.collision.source.ModelResolver;
import com.mineplus.infrastructure.collision.source.OccupancySource;
import com.mineplus.util.SchedulerCompat;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Public entry point of the collision system.
 *
 * <p>Addons only see this class: they describe a model with a
 * {@link CollisionSpec}, fit it, place it through the {@link CollisionManager},
 * and query the atlas. The service also owns the atlas lifecycle (cache load,
 * chunked rebuild) and the durable store, so {@code /collision} commands and the
 * plugin lifecycle share one object instead of duplicating wiring.</p>
 *
 * <p>The pure constructor (settings, atlas, cache, warn) does no Bukkit work, so
 * the fit and report paths are testable without a server. {@link #start} is the
 * server entry point.</p>
 */
public final class CollisionService {

    /** Directory under the plugin data folder. */
    public static final String DIRECTORY = "collision";
    /** Y level used for the scratch block on servers without Paper shape reads. */
    public static final int SCRATCH_Y = 300;

    private final CollisionSettings settings;
    private final FitCache fitCache;
    private final Consumer<String> warn;
    private final List<String> startupWarnings = new ArrayList<>();

    private volatile ShapeAtlas atlas;
    private volatile ShapeAtlasBuilder atlasBuilder;
    private volatile boolean buildRunning;

    private CollisionManager manager;
    private CollisionStore store;

    /** Pure constructor: no Bukkit access, safe on any thread and in tests. */
    public CollisionService(CollisionSettings settings, ShapeAtlas atlas, FitCache fitCache,
            Consumer<String> warn) {
        this.settings = settings == null ? CollisionSettings.defaults() : settings;
        this.atlas = atlas == null ? emptyAtlas(this.settings) : atlas;
        this.fitCache = fitCache;
        this.warn = warn == null ? message -> {
        } : warn;
        this.startupWarnings.addAll(this.settings.deprecationWarnings());
    }

    /** A disabled service with an empty atlas, for plugin code that runs before enable. */
    public static CollisionService disabled() {
        CollisionSettings defaults = CollisionSettings.defaults();
        CollisionSettings off = new CollisionSettings(false, defaults.resolution(),
                defaults.rasterMode(), defaults.defaultProfile(), defaults.maxBlocks(),
                defaults.maxEntities(), false, false, false, false, false, false, false,
                defaults.shulkerScale(), defaults.partsPerTick(), false, false,
                List.of(), List.of(), java.util.Set.of(), List.of());
        return new CollisionService(off, null, null, null);
    }

    private static ShapeAtlas emptyAtlas(CollisionSettings settings) {
        return ShapeAtlas.empty(new AtlasKey("unknown", "spigot", "none",
                Math.max(8, settings.resolution()), settings.rasterMode()));
    }

    /**
     * Server entry point: loads the atlas cache, creates the store and manager and
     * starts the chunked atlas build when nothing is cached.
     *
     * <p>Must run on the main thread. The manager is only created when the
     * feature is enabled and the server exposes a world.</p>
     */
    public static CollisionService start(Plugin plugin, CollisionSettings settings,
            File dataFolder, Consumer<String> warn) {
        Consumer<String> log = warn == null ? message -> {
        } : warn;
        CollisionSettings effective = settings == null ? CollisionSettings.defaults() : settings;
        Path root = dataFolder.toPath().resolve(DIRECTORY);
        FitCache cache = new FitCache(effective.cacheFits() ? root.resolve("fits") : null);
        CollisionService service = new CollisionService(effective, null, cache, log);
        for (String line : service.startupWarnings) {
            log.accept(line);
        }
        if (!effective.enabled()) {
            return service;
        }

        ServerCapabilities capabilities = ServerCapabilities.detect();
        CandidatePool pool = CandidatePool.defaults(effective.extraMaterials(),
                effective.invisibleStates(), effective.excludedMaterials());
        ShapeAtlasBuilder builder = new ShapeAtlasBuilder(pool, capabilities,
                effective.rasterMode(), root.resolve("atlas"));
        service.atlasBuilder = builder;

        Optional<ShapeAtlas> cached = builder.loadCache();
        if (cached.isPresent()) {
            service.atlas = cached.get();
        } else {
            log.accept("collision: no shape atlas cache yet; building in the background");
        }

        CollisionStore store = new CollisionStore(
                dataFolder.toPath().resolve("collision_state.json"), log);
        BackendRegistry registry = new BackendRegistry(capabilities.scaleAttribute(),
                effective.shulkerScale(), effective.allowPlates(), effective.minecartPlates());
        CollisionManager manager = new CollisionManager(plugin, store, registry, log,
                effective.partsPerTick());
        manager.enable();
        service.store = store;
        service.manager = manager;

        if (cached.isEmpty()) {
            service.requestAtlasBuild(plugin);
        }
        return service;
    }

    /** Schedules the chunked atlas build on the first world, if one is loaded. */
    public void requestAtlasBuild(Plugin plugin) {
        ShapeAtlasBuilder builder = atlasBuilder;
        if (builder == null || buildRunning || plugin == null) {
            return;
        }
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            warn.accept("collision: no world loaded; atlas build deferred");
            return;
        }
        World world = worlds.get(0);
        Location scratch = new Location(world, 0, Math.min(SCRATCH_Y,
                world.getMaxHeight() - 1), 0);
        buildRunning = true;
        builder.buildChunked(plugin, world, scratch, 64, built -> {
            buildRunning = false;
            atlas = built;
            warn.accept("collision: shape atlas built (" + built.size() + " states)");
        });
    }

    /** True when a build is queued or running. */
    public boolean atlasBuilding() {
        return buildRunning;
    }

    // ------------------------------------------------------------- queries

    public boolean enabled() {
        return settings.enabled();
    }

    public CollisionSettings settings() {
        return settings;
    }

    public ShapeAtlas atlas() {
        return atlas;
    }

    public Optional<ShapeAtlasBuilder> atlasBuilder() {
        return Optional.ofNullable(atlasBuilder);
    }

    public Optional<CollisionManager> manager() {
        return Optional.ofNullable(manager);
    }

    public Optional<CollisionStore> store() {
        return Optional.ofNullable(store);
    }

    /** Fit-cache access for tools; empty when caching is off. */
    public Optional<FitCache> fitCache() {
        return Optional.ofNullable(fitCache);
    }

    /** Replaces the atlas after a manual rebuild. */
    public void replaceAtlas(ShapeAtlas replacement) {
        if (replacement != null) {
            this.atlas = replacement;
        }
    }

    // ------------------------------------------------------------- fitting

    /**
     * Fits a spec against the current atlas, reusing the cache when the file
     * exists and the key still matches.
     *
     * @throws IllegalArgumentException when the spec is incomplete
     */
    public FitResult fit(CollisionSpec spec, ModelResolver resolver) {
        OccupancySource.OccupancyResult source = spec.build(resolver, settings);
        int[] size = source.mask().blockSize();
        String profileName = normalizeProfile(spec.profile() == null
                ? settings.defaultProfile() : spec.profile());
        if (fitCache != null) {
            Optional<FitResult> hit = fitCache.load(spec.modelKey(), atlas.key().describe(),
                    profileName, spec.yaw(), source.mask().resolution(),
                    size[0], size[1], size[2]);
            if (hit.isPresent()) {
                return hit.get();
            }
        }
        FitProfile profile = profileFor(profileName);
        HybridFitter fitter = new HybridFitter(atlas);
        FitResult fitted = fitter.fit(spec.modelKey(), source.mask(), source.directives(),
                profile, settings.toFitterOptions(), spec.yaw());
        List<String> warnings = new ArrayList<>(source.warnings());
        warnings.addAll(fitted.warnings());
        FitResult result = new FitResult(fitted.modelKey(), fitted.atlasKey(),
                fitted.profileName(), fitted.yaw(), fitted.resolution(), fitted.sizeX(),
                fitted.sizeY(), fitted.sizeZ(), fitted.cells(), fitted.metrics(), warnings);
        if (fitCache != null) {
            fitCache.save(result);
        }
        return result;
    }

    /**
     * Spec for an installed model: its file-level {@code collision} block plus
     * the model JSON. Shared by the {@code fit} commands and by placement, so
     * both describe the same fit.
     *
     * @param meta the model's meta record (may be {@code null})
     * @param json the model source, or {@code null} when the source has no JSON
     * @param profileOverride profile key from a command, or {@code null} to use
     *        the block's own profile
     * @throws IllegalArgumentException when the model disables collision
     */
    public static CollisionSpec fromModel(String modelKey,
            com.mineplus.infrastructure.virtual.ModelMeta meta, String json,
            String profileOverride) {
        var collision = meta == null ? null : meta.collision();
        if (collision != null && collision.disabled()) {
            throw new IllegalArgumentException("model '" + modelKey
                    + "' declares collision disabled");
        }
        String profile = profileOverride != null ? profileOverride
                : collision == null ? null : collision.profile();
        CollisionSpec.Builder builder = CollisionSpec.builder(modelKey);
        CollisionSpec.Source mode = collision == null || collision.mode() == null
                ? CollisionSpec.Source.AUTO
                : CollisionSpec.Source.valueOf(collision.mode().name());
        switch (mode) {
            case BLUEPRINT -> builder.source(CollisionSpec.Source.BLUEPRINT)
                    .blueprint(collision == null ? null : collision.blueprintJson());
            case FILL -> {
                int[] box = meta == null ? null : meta.fillBox();
                int sx = box == null ? 1 : box[3] - box[0] + 1;
                int sy = box == null ? 1 : box[4] - box[1] + 1;
                int sz = box == null ? 1 : box[5] - box[2] + 1;
                builder.fill(sx, sy, sz);
                if (collision != null && collision.insetBlocks() != null) {
                    builder.inset(collision.insetBlocks());
                }
            }
            case HYBRID -> builder.hybrid(json,
                    collision == null ? null : collision.blueprintJson());
            default -> builder.auto(json);
        }
        if (profile != null) {
            builder.profile(profile);
        }
        if (collision != null && collision.resolution() != null) {
            builder.resolution(collision.resolution());
        }
        if (collision != null && collision.yaw() != null) {
            builder.yaw(collision.yaw());
        }
        return builder.build();
    }

    /**
     * Canonical profile key for a config or command value.
     *
     * <p>Accepts the short keys used in the config template and the {@code fit}
     * commands ({@code DEFAULT}, {@code FURNITURE}, {@code WALKABLE},
     * {@code THIN}) as well as the {@link ProfilePreset} names. Unknown values
     * warn once and fall back to {@code DEFAULT}, matching
     * {@link FitProfile#of(String)}.</p>
     */
    public static String normalizeProfile(String raw) {
        if (raw == null || raw.isBlank()) {
            return "DEFAULT";
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "DEFAULT", "BALANCED", "FURNITURE", "WALKABLE", "WALKABLE_SURFACE",
                 "THIN", "NARROW_GATE", "DECORATIVE_PASSABLE" -> key;
            default -> "DEFAULT";
        };
    }

    /** Preset weights for a canonical profile key, keeping the requested name. */
    private static FitProfile profileFor(String normalized) {
        ProfilePreset preset = switch (normalized) {
            case "FURNITURE" -> ProfilePreset.FURNITURE;
            case "WALKABLE", "WALKABLE_SURFACE" -> ProfilePreset.WALKABLE_SURFACE;
            case "THIN", "NARROW_GATE" -> ProfilePreset.NARROW_GATE;
            case "DECORATIVE_PASSABLE" -> ProfilePreset.DECORATIVE_PASSABLE;
            default -> ProfilePreset.BALANCED;
        };
        return FitProfile.preset(preset).toBuilder().name(normalized).build();
    }

    /** Fits a spec and writes the result to the fit cache, returning it. */
    public FitResult bake(CollisionSpec spec, ModelResolver resolver) {
        FitResult result = fit(spec, resolver);
        if (fitCache != null) {
            fitCache.save(result);
        }
        return result;
    }

    /** Volume fit for one cell count, used by {@code fit debug}. */
    public FitResult fitFill(String modelKey, int sizeX, int sizeY, int sizeZ, String profile) {
        CollisionSpec spec = CollisionSpec.builder(modelKey).fill(sizeX, sizeY, sizeZ)
                .profile(profile).build();
        return fit(spec, ModelResolver.NONE);
    }

    // ------------------------------------------------------------- reports

    /** Human-readable atlas counters, one line each. */
    public List<String> atlasStats() {
        ShapeAtlas.AtlasStats stats = atlas.stats();
        List<String> lines = new ArrayList<>();
        lines.add("atlas: " + stats.key());
        lines.add("entries: " + stats.entries() + " (collision " + stats.withCollision()
                + ", passable " + stats.noCollision() + ")");
        lines.add("invisible-capable: " + stats.invisibleCapable()
                + ", full cubes: " + stats.fullCubes());
        lines.add("sub-voxels: total " + stats.totalBits() + ", min " + stats.minBits()
                + ", max " + stats.maxBits());
        if (atlasBuilder != null) {
            lines.add("cache file: " + atlasBuilder.cacheFile().getFileName());
        }
        return lines;
    }

    /** Stable dump of every atlas entry, for bug reports and diffs. */
    public List<String> atlasDump(int limit) {
        int cap = limit <= 0 ? Integer.MAX_VALUE : limit;
        List<String> lines = new ArrayList<>();
        for (var entry : atlas.entries()) {
            if (lines.size() >= cap) {
                lines.add("... " + (atlas.size() - cap) + " more");
                break;
            }
            lines.add(entry.state() + " bits=" + entry.bitCount()
                    + " invisible=" + entry.invisibleCapable()
                    + " full=" + entry.fullCube());
        }
        return lines;
    }

    /** Human-readable fit summary used by {@code fit report}. */
    public static List<String> describeFit(FitResult result) {
        List<String> lines = new ArrayList<>();
        lines.add(result.modelKey() + " " + result.sizeX() + "x" + result.sizeY()
                + "x" + result.sizeZ() + " profile=" + result.profileName()
                + " atlas=" + result.atlasKey());
        lines.add(result.metrics().summary());
        for (String warning : result.warnings()) {
            lines.add("! " + warning);
        }
        return lines;
    }

    /** Writes a dump to a file and returns its path. */
    public static Path writeLines(Path target, List<String> lines) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.write(target, lines, StandardCharsets.UTF_8);
        return target;
    }

    // ------------------------------------------------------------- lifecycle

    /** Disables the world manager and saves pending state. */
    public void shutdown() {
        if (manager != null) {
            manager.disable();
            manager = null;
        }
        store = null;
    }
}
