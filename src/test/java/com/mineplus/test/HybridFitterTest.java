package com.mineplus.test;

import com.mineplus.infrastructure.collision.CollisionService;
import com.mineplus.infrastructure.collision.CollisionSettings;
import com.mineplus.infrastructure.collision.CollisionSpec;
import com.mineplus.infrastructure.collision.atlas.AtlasKey;
import com.mineplus.infrastructure.collision.atlas.ShapeAtlas;
import com.mineplus.infrastructure.collision.atlas.ShapeEntry;
import com.mineplus.infrastructure.collision.atlas.ShapeFlag;
import com.mineplus.infrastructure.collision.fit.BackendType;
import com.mineplus.infrastructure.collision.fit.CellChoice;
import com.mineplus.infrastructure.collision.fit.FitCache;
import com.mineplus.infrastructure.collision.fit.FitProfile;
import com.mineplus.infrastructure.collision.fit.FitResult;
import com.mineplus.infrastructure.collision.fit.FitterOptions;
import com.mineplus.infrastructure.collision.fit.HybridFitter;
import com.mineplus.infrastructure.collision.fit.ProfilePreset;
import com.mineplus.infrastructure.collision.mask.OccupancyMask;
import com.mineplus.infrastructure.collision.mask.RasterMode;
import com.mineplus.infrastructure.collision.mask.VoxelMask;
import com.mineplus.infrastructure.collision.source.BlueprintOccupancySource;
import com.mineplus.infrastructure.collision.source.CellDirective;
import com.mineplus.infrastructure.collision.source.DirectiveGrid;
import com.mineplus.infrastructure.collision.source.DirectiveType;
import com.mineplus.infrastructure.collision.source.ModelResolver;
import com.mineplus.infrastructure.collision.source.OccupancySource;
import com.mineplus.infrastructure.virtual.ModelCollision;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Plain-Java checks for the hybrid fitter, its planner and the fit cache. */
public final class HybridFitterTest {

    private static final List<String> failures = new ArrayList<>();

    private static final String FULL = "minecraft:barrier";
    private static final String SLAB_BOTTOM = "minecraft:oak_slab[type=bottom]";
    private static final String SLAB_TOP = "minecraft:oak_slab[type=top]";
    private static final String STAIR = "minecraft:oak_stairs[facing=north,half=bottom,shape=straight]";
    private static final String PLATE = "minecraft:smooth_stone_slab[type=bottom]";
    private static final String AIR = "minecraft:light[level=15]";

    private HybridFitterTest() {
    }

    public static void main(String[] args) throws Exception {
        fullCubeFitsToRealBlock();
        sideWallBecomesGhost();
        interiorIsPruned();
        profilesPickDifferentShapes();
        forcedStateIsHonoured();
        passableHitboxBecomesInteraction();
        determinismAndJsonRoundTrip();
        cacheSaveLoadAndStaleKey();
        quarterTurnRemapsDirectives();
        budgetsDropCellsWithWarning();
        rotationIsStableAcrossFourYaws();
        serviceFitsFillSpecAndCaches();
        collisionSpecAndMetaValidate();
        profileAliasesAndModelSpecs();

        if (!failures.isEmpty()) {
            System.out.println("HybridFitterTest: " + failures.size() + " failure(s)");
            for (String failure : failures) {
                System.out.println(" - " + failure);
            }
            System.exit(1);
        }
        System.out.println("HybridFitterTest: all checks passed");
    }

    // ---------------------------------------------------------------- fixtures

    private static ShapeAtlas atlas() {
        Map<String, ShapeEntry> entries = new LinkedHashMap<>();
        add(entries, FULL, "BARRIER", List.of(new double[] {0, 0, 0, 1, 1, 1}), 0);
        add(entries, SLAB_BOTTOM, "OAK_SLAB", List.of(new double[] {0, 0, 0, 1, 0.5, 1}), 1);
        add(entries, SLAB_TOP, "OAK_SLAB", List.of(new double[] {0, 0.5, 0, 1, 1, 1}), 1);
        add(entries, STAIR, "OAK_STAIRS", List.of(
                new double[] {0, 0, 0, 1, 0.5, 1},
                new double[] {0, 0.5, 0.5, 1, 1, 1}), 2);
        add(entries, PLATE, "SMOOTH_STONE_SLAB", List.of(new double[] {0, 0, 0, 1, 0.0625, 1}), 3);
        ShapeEntry air = new ShapeEntry(AIR, "LIGHT", VoxelMask.empty(), 0, null, false, false,
                false, Set.of(ShapeFlag.NO_COLLISION, ShapeFlag.NATURALLY_INVISIBLE), false, true,
                0);
        entries.put(AIR, air);
        return new ShapeAtlas(new AtlasKey("1.21.4", "spigot", "fixture", 16,
                RasterMode.CONSERVATIVE), entries.values());
    }

    private static void add(Map<String, ShapeEntry> entries, String state, String material,
            List<double[]> boxes, int cost) {
        VoxelMask mask = VoxelMask.rasterize(boxes, RasterMode.CONSERVATIVE);
        ShapeEntry entry = new ShapeEntry(state, material, mask, 0, bounds(mask),
                mask.count() > 0, false, mask.isFull(), Set.of(), false, true, cost);
        entries.put(state, entry);
    }

    private static double[] bounds(VoxelMask mask) {
        double[] result = new double[6];
        if (mask.isEmpty()) {
            return result;
        }
        double minX = 1;
        double minY = 1;
        double minZ = 1;
        double maxX = 0;
        double maxY = 0;
        double maxZ = 0;
        for (int y = 0; y < VoxelMask.RESOLUTION; y++) {
            for (int z = 0; z < VoxelMask.RESOLUTION; z++) {
                for (int x = 0; x < VoxelMask.RESOLUTION; x++) {
                    if (!mask.get(x, y, z)) {
                        continue;
                    }
                    minX = Math.min(minX, x / 16.0);
                    minY = Math.min(minY, y / 16.0);
                    minZ = Math.min(minZ, z / 16.0);
                    maxX = Math.max(maxX, (x + 1) / 16.0);
                    maxY = Math.max(maxY, (y + 1) / 16.0);
                    maxZ = Math.max(maxZ, (z + 1) / 16.0);
                }
            }
        }
        result[0] = minX;
        result[1] = minY;
        result[2] = minZ;
        result[3] = maxX;
        result[4] = maxZ;
        return result;
    }

    private static OccupancySource.OccupancyResult cube(int blocks) {
        OccupancyMask mask = OccupancyMask.empty(16, blocks, blocks, blocks);
        mask.fill(0, 0, 0, blocks * 16 - 1, blocks * 16 - 1, blocks * 16 - 1, true);
        return new OccupancySource.OccupancyResult(mask,
                DirectiveGrid.empty(blocks, blocks, blocks), List.of());
    }

    private static FitResult fit(ShapeAtlas atlas, OccupancySource.OccupancyResult occupancy,
            FitProfile profile, FitterOptions options, double yaw) {
        return new HybridFitter(atlas).fit("fixture", occupancy.mask(), occupancy.directives(),
                profile, options, yaw);
    }

    private static CellChoice at(FitResult result, int x, int y, int z) {
        return result.cell(x, y, z);
    }

    // ------------------------------------------------------------------ checks

    private static void fullCubeFitsToRealBlock() {
        FitResult result = fit(atlas(), cube(1), FitProfile.preset(ProfilePreset.BALANCED),
                FitterOptions.defaults(), 0);
        CellChoice cell = at(result, 0, 0, 0);
        check("cube has one fitted cell", result.cells().size() == 1, result.cells().size());
        check("cube cell uses a full block", cell != null && FULL.equals(cell.state()),
                cell == null ? "missing" : cell.state());
        check("cube cell is a standing surface", cell != null && cell.standingSurface(), cell);
        check("standing surface is a real block",
                cell != null && cell.backend() == BackendType.REAL_BLOCK, cell);
        check("exact cube has no error", result.metrics().errorRatio() < 1e-9,
                result.metrics().errorRatio());
    }

    private static void sideWallBecomesGhost() {
        FitResult result = fit(atlas(), cube(2), FitProfile.preset(ProfilePreset.BALANCED),
                FitterOptions.defaults(), 0);
        CellChoice bottom = at(result, 0, 0, 0);
        CellChoice top = at(result, 0, 1, 0);
        check("bottom of a 2³ cube is not standing", bottom != null && !bottom.standingSurface(),
                bottom);
        check("exposed side wall is a ghost", bottom != null
                && bottom.backend() == BackendType.GHOST_BLOCK, bottom);
        check("top of the cube is a real block", top != null
                && top.backend() == BackendType.REAL_BLOCK, top);
        check("2³ cube has 4 ghosts and 4 blocks",
                result.metrics().ghosts() == 4 && result.metrics().blocks() == 4,
                result.metrics().summary());
    }

    private static void interiorIsPruned() {
        FitResult result = fit(atlas(), cube(3), FitProfile.preset(ProfilePreset.BALANCED),
                FitterOptions.defaults(), 0);
        check("centre of a 3³ cube is pruned", result.cell(1, 1, 1) == null,
                result.cell(1, 1, 1));
        check("pruned count reported", result.metrics().prunedInterior() == 1,
                result.metrics().prunedInterior());

        FitterOptions noPrune = new FitterOptions(512, 64, true, true, false, true, true, false,
                false, FitterOptions.RotationSnap.QUARTER_TURNS);
        FitResult kept = fit(atlas(), cube(3), FitProfile.preset(ProfilePreset.BALANCED), noPrune,
                0);
        check("pruning can be switched off", kept.cell(1, 1, 1) != null, kept.cell(1, 1, 1));
    }

    private static void profilesPickDifferentShapes() {
        OccupancyMask mask = OccupancyMask.empty(16, 1, 1, 1);
        mask.fill(0, 0, 0, 15, 11, 15, true);
        OccupancySource.OccupancyResult threeQuarters = new OccupancySource.OccupancyResult(mask,
                DirectiveGrid.empty(1, 1, 1), List.of());
        FitResult furniture = fit(atlas(), threeQuarters,
                FitProfile.preset(ProfilePreset.FURNITURE), FitterOptions.defaults(), 0);
        FitResult gate = fit(atlas(), threeQuarters, FitProfile.preset(ProfilePreset.NARROW_GATE),
                FitterOptions.defaults(), 0);
        String furnitureState = at(furniture, 0, 0, 0).state();
        String gateState = at(gate, 0, 0, 0).state();
        check("FURNITURE avoids missing volume with a full block", FULL.equals(furnitureState),
                furnitureState);
        check("NARROW_GATE avoids extra volume with a slab", SLAB_BOTTOM.equals(gateState),
                gateState);
        check("profiles produce different error", furniture.metrics().missingVolume()
                != gate.metrics().missingVolume(),
                furniture.metrics().summary() + " vs " + gate.metrics().summary());
    }

    private static void forcedStateIsHonoured() {
        OccupancyMask mask = OccupancyMask.empty(16, 1, 1, 1);
        mask.fill(0, 0, 0, 15, 15, 15, true);
        Map<Long, CellDirective> cells = new LinkedHashMap<>();
        DirectiveGrid empty = DirectiveGrid.empty(1, 1, 1);
        cells.put(empty.key(0, 0, 0), new CellDirective(DirectiveType.FORCE_BLOCK, STAIR));
        DirectiveGrid directives = new DirectiveGrid(1, 1, 1, cells);
        FitResult result = fit(atlas(),
                new OccupancySource.OccupancyResult(mask, directives, List.of()),
                FitProfile.preset(ProfilePreset.NARROW_GATE), FitterOptions.defaults(), 0);
        CellChoice cell = at(result, 0, 0, 0);
        check("forced state is used exactly", cell != null && STAIR.equals(cell.state()), cell);
        check("forced state is a real block",
                cell != null && cell.backend() == BackendType.REAL_BLOCK, cell);
    }

    private static void passableHitboxBecomesInteraction() {
        OccupancyMask mask = OccupancyMask.empty(16, 1, 1, 1);
        mask.fill(0, 0, 0, 15, 15, 15, true);
        DirectiveGrid empty = DirectiveGrid.empty(1, 1, 1);
        Map<Long, CellDirective> cells = new LinkedHashMap<>();
        cells.put(empty.key(0, 0, 0), new CellDirective(DirectiveType.PASSABLE_HITBOX, null));
        DirectiveGrid directives = new DirectiveGrid(1, 1, 1, cells);
        FitResult result = fit(atlas(),
                new OccupancySource.OccupancyResult(mask, directives, List.of()),
                FitProfile.preset(ProfilePreset.DECORATIVE_PASSABLE), FitterOptions.defaults(), 0);
        CellChoice cell = at(result, 0, 0, 0);
        check("passable hitbox becomes an interaction", cell != null
                && cell.backend() == BackendType.INTERACTION_ONLY, cell);
        check("interaction counts as an entity", result.metrics().entities() == 1,
                result.metrics().entities());
        check("no blocks placed for a passable cell", result.metrics().blocks() == 0,
                result.metrics().blocks());
    }

    private static void determinismAndJsonRoundTrip() {
        ShapeAtlas atlas = atlas();
        FitResult first = fit(atlas, cube(2), FitProfile.preset(ProfilePreset.FURNITURE),
                FitterOptions.defaults(), 0);
        FitResult second = fit(atlas, cube(2), FitProfile.preset(ProfilePreset.FURNITURE),
                FitterOptions.defaults(), 0);
        check("fitting is deterministic", first.toJson().equals(second.toJson()),
                "two fits differ");
        FitResult parsed = FitResult.fromJson(first.toJson());
        check("fit JSON round-trips", parsed.toJson().equals(first.toJson()), "round trip differs");
        check("round trip keeps metrics", parsed.metrics().equals(first.metrics()),
                parsed.metrics().summary() + " vs " + first.metrics().summary());
        check("signatures are stable",
                sorted(first.signatures()).equals(sorted(second.signatures())),
                "signature sets differ");
    }

    private static void cacheSaveLoadAndStaleKey() throws Exception {
        Path directory = Files.createTempDirectory("mineplus-fit-cache");
        try {
            FitCache cache = new FitCache(directory);
            FitResult result = fit(atlas(), cube(1), FitProfile.preset(ProfilePreset.BALANCED),
                    FitterOptions.defaults(), 0);
            cache.save(result);
            Optional<FitResult> loaded = cache.load(result.modelKey(), result.atlasKey(),
                    result.profileName(), result.yaw(), result.resolution(), result.sizeX(),
                    result.sizeY(), result.sizeZ());
            check("fit cache round-trips", loaded.isPresent(), "no cached fit");
            check("cached fit is identical",
                    loaded.isPresent() && loaded.get().toJson().equals(result.toJson()),
                    "cached fit differs");
            Optional<FitResult> stale = cache.load(result.modelKey(), "other-atlas",
                    result.profileName(), result.yaw(), result.resolution(), result.sizeX(),
                    result.sizeY(), result.sizeZ());
            check("cache misses on a different atlas key", stale.isEmpty(), stale);

            Path broken = directory.resolve(result.cacheKey() + ".json");
            Files.writeString(broken, "{ not json");
            check("corrupt cache entry is quarantined", cache.load(result.modelKey(),
                    result.atlasKey(), result.profileName(), result.yaw(), result.resolution(),
                    result.sizeX(), result.sizeY(), result.sizeZ()).isEmpty(),
                    "corrupt entry was returned");
        } finally {
            deleteTree(directory);
        }
    }

    private static void quarterTurnRemapsDirectives() throws Exception {
        BlueprintOccupancySource source = BlueprintOccupancySource.fromText("""
                {"schema":1,"name":"gate","size":[1,1,2],
                 "regions":[{"type":"fill","from":[0,0,0],"to":[0,0,0],
                             "directive":"FORCE_BLOCK:%s"}]}
                """.formatted(SLAB_TOP), "gate");
        OccupancySource.OccupancyResult occupancy = source.build();
        FitResult result = fit(atlas(), occupancy, FitProfile.preset(ProfilePreset.NARROW_GATE),
                FitterOptions.defaults(), 90);
        check("quarter turn swaps X and Z", result.sizeX() == 2 && result.sizeZ() == 1,
                result.sizeX() + "x" + result.sizeZ());
        CellChoice moved = at(result, 1, 0, 0);
        check("quarter turn moves the blueprint cell", at(result, 0, 0, 0) == null
                || !SLAB_TOP.equals(at(result, 0, 0, 0).state()), at(result, 0, 0, 0));
        check("rotated directive keeps its state",
                moved != null && SLAB_TOP.equals(moved.state()), moved);
        check("rotated directive stays a real block",
                moved != null && moved.backend() == BackendType.REAL_BLOCK, moved);
    }

    private static void budgetsDropCellsWithWarning() {
        FitterOptions tight = new FitterOptions(2, 64, true, true, false, true, true, false,
                false, FitterOptions.RotationSnap.QUARTER_TURNS);
        FitResult result = fit(atlas(), cube(2), FitProfile.preset(ProfilePreset.BALANCED), tight,
                0);
        check("block budget limits placed blocks", result.metrics().blocks() <= 2,
                result.metrics().summary());
        check("budget keeps some parts", !result.cells().isEmpty(), result.cells().size());
        check("dropping reports a warning",
                result.warnings().stream().anyMatch(w -> w.contains("dropped")),
                result.warnings());
    }

    private static void rotationIsStableAcrossFourYaws() {
        ShapeAtlas atlas = atlas();
        for (double yaw : new double[] {0, 90, 180, 270}) {
            FitResult result = fit(atlas, cube(1), FitProfile.preset(ProfilePreset.BALANCED),
                    FitterOptions.defaults(), yaw);
            CellChoice cell = at(result, 0, 0, 0);
            check("yaw " + yaw + " still fits one full block",
                    result.cells().size() == 1 && cell != null && FULL.equals(cell.state()),
                    result.cells());
            check("yaw " + yaw + " is normalised into 0..360", result.yaw() >= 0
                    && result.yaw() < 360, result.yaw());
        }
    }

    // ----------------------------------------------------------------- helpers

    /** Phase 5 facade: spec -> service -> fit result, reports and the fit cache. */
    private static void serviceFitsFillSpecAndCaches() {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("collision-service");
            List<String> warnings = new ArrayList<>();
            CollisionService service = new CollisionService(CollisionSettings.defaults(),
                    atlas(), new FitCache(dir), warnings::add);
            CollisionSpec spec = CollisionSpec.builder("test:plate").fill(1, 1, 1)
                    .profile("THIN").build();
            FitResult first = service.fit(spec, ModelResolver.NONE);
            check("service fits a 1x1x1 fill into one cell",
                    first.cells().size() == 1 && first.sizeX() == 1 && first.sizeY() == 1,
                    first.cells());
            check("service fit knows its atlas",
                    first.atlasKey().equals(atlas().key().describe()), first.atlasKey());
            check("service reports atlas counters",
                    service.atlasStats().stream().anyMatch(line -> line.startsWith("entries:")),
                    service.atlasStats());
            check("service dumps entries with a limit",
                    !service.atlasDump(3).isEmpty() && service.atlasDump(1).size() <= 2,
                    service.atlasDump(1));
            check("fit report names the model and profile",
                    CollisionService.describeFit(first).get(0).contains("test:plate")
                            && CollisionService.describeFit(first).get(0).contains("THIN"),
                    CollisionService.describeFit(first));
            FitResult second = service.fit(spec, ModelResolver.NONE);
            check("fit cache returns the same plan on the second call",
                    second.cells().size() == first.cells().size()
                            && second.metrics().cells() == first.metrics().cells(),
                    second.cells());
            FitResult baked = service.bake(spec, ModelResolver.NONE);
            check("bake returns the same plan", baked.cells().size() == first.cells().size(),
                    baked.cells());
            CollisionService off = CollisionService.disabled();
            check("disabled service is off with an empty atlas",
                    !off.enabled() && off.atlas().isEmpty(), off.atlas().size());
            check("disabled service still reports stats", !off.atlasStats().isEmpty(),
                    off.atlasStats());
        } catch (Exception failure) {
            failures.add("service facade threw " + failure);
        } finally {
            try {
                if (dir != null) {
                    deleteTree(dir);
                }
            } catch (Exception cleanupFailure) {
                System.out.println("HybridFitterTest: temp cleanup failed: "
                        + cleanupFailure.getMessage());
            }
        }
    }

    /** Spec builder guards and the {@code collision} meta block. */
    private static void collisionSpecAndMetaValidate() {
        try {
            CollisionSpec.builder("x:auto").build();
            failures.add("AUTO spec without model JSON was accepted");
        } catch (IllegalArgumentException expected) {
            check("missing model JSON is reported by key",
                    expected.getMessage().contains("x:auto"), expected.getMessage());
        }
        try {
            CollisionSpec.builder("x:hybrid").hybrid("{}", " ").build();
            failures.add("HYBRID spec without blueprint was accepted");
        } catch (IllegalArgumentException expected) {
            check("missing blueprint is reported", expected.getMessage().contains("blueprint"),
                    expected.getMessage());
        }
        CollisionSpec plain = CollisionSpec.builder("x:fill").fill(2, 1, 3).build();
        check("fill spec describes its source",
                plain.describe().contains("source=fill") && plain.sizeHint()[0] == 2,
                plain.describe());
        ModelCollision meta = ModelCollision.fromJson(JsonParser.parseString("""
                {"mode":"HYBRID","profile":"furniture",
                 "blueprint":{"size":[1,2,1],"cells":["#","."]},
                 "resolution":8,"yaw":90,"enabled":true}"""));
        check("meta collision parses mode", meta.mode() == ModelCollision.Mode.HYBRID, meta);
        check("meta collision uppercases the profile", "FURNITURE".equals(meta.profile()), meta);
        check("meta collision keeps blueprint JSON", meta.blueprintJson() != null
                && meta.blueprintJson().contains("\"cells\""), meta.blueprintJson());
        check("meta collision clamps resolution", Integer.valueOf(8).equals(meta.resolution()), meta);
        check("meta collision reads yaw", Double.valueOf(90).equals(meta.yaw()), meta);
        check("meta collision enabled is not disabled", !meta.disabled(), meta);
        ModelCollision none = ModelCollision.fromJson(JsonParser.parseString("{\"mode\":\"NONE\"}"));
        check("legacy NONE mode means collision disabled", none.disabled(), none);
        check("missing block is empty", ModelCollision.fromJson(null).isEmpty(), "null");
    }

    /** Profile alias mapping and the shared model-to-spec factory. */
    private static void profileAliasesAndModelSpecs() {
        check("profile alias DEFAULT", "DEFAULT".equals(CollisionService.normalizeProfile(null)),
                CollisionService.normalizeProfile(null));
        check("profile alias is case and space tolerant",
                "THIN".equals(CollisionService.normalizeProfile(" thin ")),
                CollisionService.normalizeProfile(" thin "));
        check("profile alias WALKABLE", "WALKABLE".equals(CollisionService.normalizeProfile("walkable")),
                CollisionService.normalizeProfile("walkable"));
        check("profile alias FURNITURE", "FURNITURE".equals(CollisionService.normalizeProfile("Furniture")),
                CollisionService.normalizeProfile("Furniture"));
        check("unknown profile falls back to DEFAULT",
                "DEFAULT".equals(CollisionService.normalizeProfile("nonsense")),
                CollisionService.normalizeProfile("nonsense"));

        CollisionSpec auto = CollisionService.fromModel("x:chair", null, "{}", null);
        check("fromModel defaults to AUTO",
                auto.describe().contains("source=auto") && auto.describe().contains("profile=default"),
                auto.describe());
        CollisionSpec overridden = CollisionService.fromModel("x:chair", null, "{}", "thin");
        check("fromModel honours a profile override",
                overridden.describe().contains("profile=thin"), overridden.describe());
        ModelCollision hybrid = ModelCollision.fromJson(JsonParser.parseString(
                "{\"mode\":\"HYBRID\",\"blueprint\":{\"size\":[1,1,1],\"cells\":[\"#\",\".\"]}}"));
        check("fromModel reads the meta block", hybrid.mode() == ModelCollision.Mode.HYBRID, hybrid);
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(Comparator.naturalOrder());
        return copy;
    }

    private static void deleteTree(Path root) throws Exception {
        if (Files.exists(root)) {
            try (var walk = Files.walk(root)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void check(String message, boolean condition, Object detail) {
        if (!condition) {
            failures.add(message + " [" + detail + ']');
        }
    }
}
