package com.mineplus.infrastructure.collision.bukkit;

import com.mineplus.infrastructure.collision.atlas.AtlasKey;
import com.mineplus.infrastructure.collision.atlas.CandidatePool;
import com.mineplus.infrastructure.collision.atlas.ShapeAtlas;
import com.mineplus.infrastructure.collision.atlas.ShapeAtlasCache;
import com.mineplus.infrastructure.collision.atlas.ShapeEntry;
import com.mineplus.infrastructure.collision.atlas.ShapeFlag;
import com.mineplus.infrastructure.collision.mask.RasterMode;
import com.mineplus.infrastructure.collision.mask.VoxelMask;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

/**
 * Builds a {@link ShapeAtlas} from the running server and keeps it on disk.
 *
 * <p>Candidate states are expanded from the {@link CandidatePool} and measured
 * with a {@link ShapeReader}. Nothing about a block's shape is written in code:
 * the atlas is whatever the server reports. Builds can run all at once
 * ({@link #buildNow}) or spread over ticks ({@link #buildChunked}) so startup
 * never stalls on ~1800 scratch reads.</p>
 *
 * <p>Thread model: {@link #buildNow} and {@link #buildChunked} must run on the
 * thread that owns the scratch world (main thread on Paper/Spigot). Cache
 * load/save is pure IO and may run off-thread.</p>
 */
public final class ShapeAtlasBuilder {

    private static final Logger LOGGER = Logger.getLogger("Mineplus");
    private static final int MAX_STATES_PER_MATERIAL = 512;
    private static final Set<String> NATURALLY_INVISIBLE = Set.of(
            "barrier", "light", "structure_void");

    private final CandidatePool pool;
    private final ServerCapabilities capabilities;
    private final RasterMode rasterMode;
    private final Path cacheDirectory;

    public ShapeAtlasBuilder(CandidatePool pool, ServerCapabilities capabilities,
            RasterMode rasterMode, Path cacheDirectory) {
        this.pool = pool == null ? CandidatePool.defaults() : pool;
        this.capabilities = capabilities == null ? ServerCapabilities.detect() : capabilities;
        this.rasterMode = rasterMode == null ? RasterMode.CONSERVATIVE : rasterMode;
        this.cacheDirectory = cacheDirectory;
    }

    public CandidatePool pool() {
        return pool;
    }

    public ServerCapabilities capabilities() {
        return capabilities;
    }

    /** Cache key for this configuration and server. */
    public AtlasKey key() {
        return new AtlasKey(capabilities.mcVersion(), capabilities.provider(), pool.hash(), 16,
                rasterMode);
    }

    /** The cache file this builder reads and writes. */
    public Path cacheFile() {
        return cacheDirectory.resolve(key().cacheFileName());
    }

    /** Loads a matching cache, or empty when absent/stale/corrupt. */
    public Optional<ShapeAtlas> loadCache() {
        return ShapeAtlasCache.load(cacheFile(), key());
    }

    /** Expands the pool into concrete states. Bukkit API, main thread. */
    public List<BlockData> expandCandidates() {
        return new BukkitCandidateExpander(pool).expand();
    }

    /** Measures every candidate and builds the atlas (main thread). */
    public ShapeAtlas buildNow(World world, Location scratch, List<BlockData> candidates) {
        try (ShapeReader reader = openReader(world, scratch)) {
            ShapeAtlas atlas = measure(candidates, reader, null);
            save(atlas);
            return atlas;
        }
    }

    /** Builds from a reader without saving, for tests and previews. */
    public ShapeAtlas buildFrom(Collection<BlockData> candidates, ShapeReader reader) {
        return measure(candidates, reader, null);
    }

    /**
     * Spreads the build over ticks: {@code statesPerTick} scratch reads per tick,
     * then the cache is written and {@code onDone} runs on the main thread.
     *
     * @return a handle that can be cancelled when the plugin disables
     */
    public com.mineplus.util.SchedulerCompat.Handle buildChunked(Plugin plugin, World world,
            Location scratch, int statesPerTick, Consumer<ShapeAtlas> onDone) {
        BuildTask task = new BuildTask(expandCandidates(), openReader(world, scratch),
                Math.max(1, statesPerTick), onDone);
        com.mineplus.util.SchedulerCompat.Handle handle =
                com.mineplus.util.SchedulerCompat.repeat(plugin, 1L, 1L, task);
        task.attach(handle);
        return handle;
    }

    /** One chunked build, cancelled cleanly when the plugin disables. */
    private final class BuildTask implements Runnable {

        private final List<BlockData> candidates;
        private final ShapeReader reader;
        private final int budget;
        private final Consumer<ShapeAtlas> onDone;
        private final List<ShapeEntry> entries;
        private com.mineplus.util.SchedulerCompat.Handle handle;
        private int cursor;
        private boolean finished;

        private BuildTask(List<BlockData> candidates, ShapeReader reader, int budget,
                Consumer<ShapeAtlas> onDone) {
            this.candidates = candidates;
            this.reader = reader;
            this.budget = budget;
            this.onDone = onDone;
            this.entries = new ArrayList<>(candidates.size());
        }

        private void attach(com.mineplus.util.SchedulerCompat.Handle handle) {
            this.handle = handle;
        }

        @Override
        public void run() {
            if (finished) {
                return;
            }
            int end = Math.min(candidates.size(), cursor + budget);
            for (; cursor < end; cursor++) {
                ShapeEntry entry = measureOne(candidates.get(cursor), reader);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            if (cursor >= candidates.size()) {
                finished = true;
                if (handle != null) {
                    handle.cancel();
                }
                reader.close();
                ShapeAtlas atlas = new ShapeAtlas(key(), entries);
                save(atlas);
                try {
                    onDone.accept(atlas);
                } catch (Throwable failure) {
                    LOGGER.warning("[collision] atlas completion callback failed: "
                            + failure.getMessage());
                }
            }
        }
    }

    /** Measures with a supplied reader and saves to the cache. */
    public void save(ShapeAtlas atlas) {
        if (cacheDirectory == null || atlas == null) {
            return;
        }
        try {
            ShapeAtlasCache.save(cacheFile(), atlas);
        } catch (Exception failure) {
            LOGGER.warning("[collision] could not write atlas cache " + cacheFile()
                    + ": " + failure.getMessage());
        }
    }

    private ShapeReader openReader(World world, Location scratch) {
        if (capabilities.paperShapeApi()) {
            PaperShapeReader paper = PaperShapeReader.create(scratch);
            if (paper != null) {
                return paper;
            }
        }
        if (world == null) {
            throw new IllegalStateException("Shape Atlas needs a scratch world on this server");
        }
        Location location = scratch != null ? scratch
                : new Location(world, 0, Math.min(world.getMaxHeight() - 1, 319), 0);
        return new ScratchShapeReader(world, location);
    }

    private ShapeAtlas measure(Collection<BlockData> candidates, ShapeReader reader,
            List<ShapeEntry> ignored) {
        List<ShapeEntry> entries = new ArrayList<>();
        for (BlockData data : candidates) {
            ShapeEntry entry = measureOne(data, reader);
            if (entry != null) {
                entries.add(entry);
            }
        }
        ShapeAtlas atlas = new ShapeAtlas(key(), entries);
        LOGGER.info("[collision] Shape Atlas built: " + atlas.stats().entries()
                + " states (" + atlas.stats().invisibleCapable() + " invisible-capable) in "
                + key().describe());
        return atlas;
    }

    private ShapeEntry measureOne(BlockData data, ShapeReader reader) {
        if (data == null) {
            return null;
        }
        ShapeReader.ShapeSample sample;
        try {
            sample = reader.read(data);
        } catch (Throwable failure) {
            LOGGER.warning("[collision] shape read failed for " + data.getAsString() + ": "
                    + failure.getMessage());
            return null;
        }
        VoxelMask mask = VoxelMask.rasterize(sample.boxes(), rasterMode);
        String state = data.getAsString();
        String materialName = data.getMaterial().name();
        String materialKey = materialName.toLowerCase(Locale.ROOT);
        double[] bounds = new double[6];
        if (!sample.boxes().isEmpty()) {
            double minX = 1;
            double minY = 1;
            double minZ = 1;
            double maxX = 0;
            double maxY = 0;
            double maxZ = 0;
            for (double[] box : sample.boxes()) {
                minX = Math.min(minX, box[0]);
                minY = Math.min(minY, box[1]);
                minZ = Math.min(minZ, box[2]);
                maxX = Math.max(maxX, box[3]);
                maxY = Math.max(maxY, box[4]);
                maxZ = Math.max(maxZ, box[5]);
            }
            bounds = new double[] {minX, minY, minZ, maxX, maxY, maxZ};
        }

        Set<ShapeFlag> flags = EnumSet.noneOf(ShapeFlag.class);
        boolean naturallyInvisible = NATURALLY_INVISIBLE.contains(materialKey)
                || pool.isListedInvisible(state);
        if (naturallyInvisible) {
            flags.add(ShapeFlag.NATURALLY_INVISIBLE);
        }
        if (mask.isEmpty()) {
            flags.add(ShapeFlag.NO_COLLISION);
        }
        if (isPhysicsSensitive(data.getMaterial())) {
            flags.add(ShapeFlag.PHYSICS_SENSITIVE);
        }
        if (isInteractable(data)) {
            flags.add(ShapeFlag.INTERACTABLE);
        }
        try {
            if (data.getLightEmission() > 0) {
                flags.add(ShapeFlag.EMITS_LIGHT);
            }
            if (data.isOccluding()) {
                flags.add(ShapeFlag.OCCLUDING);
            }
        } catch (Throwable ignored) {
            // Degraded servers keep the defaults.
        }
        boolean tileEntity = hasBlockEntity(data.getMaterial());
        if (tileEntity) {
            flags.add(ShapeFlag.TILE_ENTITY);
        }
        int cost = costOf(materialKey, tileEntity);
        boolean invisibleInPack = naturallyInvisible;
        boolean visible = !naturallyInvisible;

        return new ShapeEntry(state, materialName, mask, mask.count(), bounds,
                !sample.boxes().isEmpty(), sample.hasOutline(), mask.isFull(), flags, visible,
                invisibleInPack, cost);
    }

    private static boolean isPhysicsSensitive(Material material) {
        String name = material.name();
        return name.endsWith("_BUTTON") || name.endsWith("_PRESSURE_PLATE")
                || name.endsWith("_TORCH") || name.equals("TORCH") || name.equals("TRIPWIRE")
                || name.equals("VINE") || name.equals("LADDER") || name.endsWith("_SAPLING")
                || name.endsWith("_FLOWER") || name.equals("DANDELION") || name.equals("POPPY");
    }

    private static boolean isInteractable(org.bukkit.block.data.BlockData data) {
        String name = data.getMaterial().name();
        return data instanceof org.bukkit.block.data.type.Switch
                || name.endsWith("_PRESSURE_PLATE") || name.equals("CANDLE");
    }

    private static boolean hasBlockEntity(Material material) {
        String name = material.name();
        return name.endsWith("_SKULL") || name.endsWith("_HEAD") || name.equals("HOPPER")
                || name.equals("BREWING_STAND") || name.equals("LECTERN") || name.equals("BELL")
                || name.equals("CAMPFIRE") || name.equals("SOUL_CAMPFIRE")
                || name.endsWith("CAULDRON") || name.equals("DAYLIGHT_DETECTOR")
                || name.equals("POINTED_DRIPSTONE");
    }

    private static int costOf(String material, boolean tileEntity) {
        if (material.equals("barrier") || material.equals("light")
                || material.equals("structure_void")) {
            return 0;
        }
        if (tileEntity) {
            return 6;
        }
        if (material.contains("stairs") || material.contains("trapdoor")) {
            return 2;
        }
        if (material.contains("wall") || material.contains("fence")
                || material.contains("pane") || material.contains("bars")) {
            return 4;
        }
        if (material.contains("slab") || material.contains("carpet")) {
            return 1;
        }
        return 3;
    }
}
