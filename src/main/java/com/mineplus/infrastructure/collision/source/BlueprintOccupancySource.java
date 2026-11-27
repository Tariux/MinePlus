package com.mineplus.infrastructure.collision.source;

import com.mineplus.infrastructure.collision.mask.OccupancyMask;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** BLUEPRINT occupancy: everything comes from a hand-authored blueprint file. */
public final class BlueprintOccupancySource implements OccupancySource {

    private final Blueprint blueprint;

    public BlueprintOccupancySource(Blueprint blueprint) {
        this.blueprint = blueprint;
    }

    /** Parses blueprint JSON; throws {@link BlueprintException} with the path. */
    public static BlueprintOccupancySource fromText(String json, String name) {
        return new BlueprintOccupancySource(Blueprint.parse(json, name));
    }

    /** Loads a blueprint file; IO and parse failures carry the file name. */
    public static BlueprintOccupancySource fromFile(Path file) throws java.io.IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return fromText(text, file.getFileName().toString());
    }

    public Blueprint blueprint() {
        return blueprint;
    }

    @Override
    public OccupancyResult build() {
        OccupancyMask mask = blueprint.toMask();
        List<String> warnings = new ArrayList<>(blueprint.warnings());
        return new OccupancyResult(mask, blueprint.directives(), warnings);
    }

    @Override
    public String describe() {
        return "BLUEPRINT(" + blueprint.name() + ", " + blueprint.sizeX() + "×"
                + blueprint.sizeY() + "×" + blueprint.sizeZ() + ", "
                + blueprint.cellCount() + " cells)";
    }
}
