package com.mineplus.infrastructure.collision.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mineplus.infrastructure.collision.mask.OccupancyMask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A hand-authored occupancy blueprint (versioned JSON).
 *
 * <p>Schema version 1:</p>
 * <pre>{@code
 * {
 *   "schema": 1,
 *   "resolution": 16,
 *   "size": [2, 2, 1],
 *   "regions": [
 *     {"type": "fill",  "from": [0,0,0], "to": [1,1,0], "directive": "SOLID"},
 *     {"type": "layer", "y": 1, "directive": "TOP_SURFACE"},
 *     {"type": "box",   "from": [1,0,0], "to": [1,0,0], "directive": "STEP"}
 *   ],
 *   "cells": [{"x": 0, "y": 1, "z": 0, "directive": "FORCE_BLOCK:minecraft:oak_slab[type=top]"}]
 * }
 * }</pre>
 *
 * <p>Coordinates are block cells. Regions are applied in order, then explicit
 * cells, so later entries win. Unknown keys are ignored so newer files stay
 * loadable, but wrong types, unknown directives and out-of-range coordinates are
 * hard errors with the JSON path (and the Gson line/column for syntax errors).</p>
 *
 * <p>Immutable after parsing; safe to build off-thread.</p>
 */
public final class Blueprint {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_SIZE = 32;

    private final String name;
    private final int resolution;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final Map<Long, CellDirective> cells;
    private final List<String> warnings;

    private Blueprint(String name, int resolution, int sizeX, int sizeY, int sizeZ,
            Map<Long, CellDirective> cells, List<String> warnings) {
        this.name = name;
        this.resolution = resolution;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = Map.copyOf(cells);
        this.warnings = List.copyOf(warnings);
    }

    /** Parses and validates a blueprint. */
    public static Blueprint parse(String json, String name) {
        String label = name == null || name.isBlank() ? "blueprint" : name;
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new BlueprintException(label + ": blueprint must be a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonSyntaxException broken) {
            throw new BlueprintException(label + ": invalid JSON (" + broken.getMessage() + ")",
                    broken);
        }

        int schema = root.has("schema") ? integer(root, "schema", label) : SCHEMA_VERSION;
        if (schema > SCHEMA_VERSION) {
            throw new BlueprintException(label + ": schema " + schema
                    + " is newer than this plugin supports (" + SCHEMA_VERSION + ")");
        }
        if (schema < 1) {
            throw new BlueprintException(label + ": schema must be >= 1");
        }
        int resolution = root.has("resolution") ? integer(root, "resolution", label) : 16;
        if (resolution < 1 || resolution > 64) {
            throw new BlueprintException(label + ": resolution must be 1..64");
        }

        JsonArray sizeNode = array(root, "size", label);
        if (sizeNode.size() != 3) {
            throw new BlueprintException(label + ": 'size' must be [x,y,z]");
        }
        int sizeX = positive(sizeNode, 0, label, "size[0]");
        int sizeY = positive(sizeNode, 1, label, "size[1]");
        int sizeZ = positive(sizeNode, 2, label, "size[2]");
        if (sizeX > MAX_SIZE || sizeY > MAX_SIZE || sizeZ > MAX_SIZE) {
            throw new BlueprintException(label + ": size must not exceed " + MAX_SIZE
                    + " blocks per axis");
        }

        Map<Long, CellDirective> cells = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        JsonElement regionsNode = root.get("regions");
        if (regionsNode != null) {
            if (!regionsNode.isJsonArray()) {
                throw new BlueprintException(label + ": 'regions' must be an array");
            }
            JsonArray regions = regionsNode.getAsJsonArray();
            for (int i = 0; i < regions.size(); i++) {
                JsonElement node = regions.get(i);
                if (!node.isJsonObject()) {
                    throw new BlueprintException(label + ": regions[" + i + "] must be an object");
                }
                applyRegion(node.getAsJsonObject(), i, label, sizeX, sizeY, sizeZ, cells, warnings);
            }
        }
        JsonElement cellsNode = root.get("cells");
        if (cellsNode != null) {
            if (!cellsNode.isJsonArray()) {
                throw new BlueprintException(label + ": 'cells' must be an array");
            }
            JsonArray array = cellsNode.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement node = array.get(i);
                if (!node.isJsonObject()) {
                    throw new BlueprintException(label + ": cells[" + i + "] must be an object");
                }
                JsonObject cell = node.getAsJsonObject();
                int x = integer(cell, "x", label + ": cells[" + i + "]");
                int y = integer(cell, "y", label + ": cells[" + i + "]");
                int z = integer(cell, "z", label + ": cells[" + i + "]");
                CellDirective directive = directive(cell, label + ": cells[" + i + "]");
                put(cells, warnings, label, x, y, z, directive, sizeX, sizeY, sizeZ,
                        "cells[" + i + "]");
            }
        }
        if (cells.isEmpty()) {
            warnings.add(label + ": no directives; the blueprint yields an empty occupancy");
        }
        return new Blueprint(label, resolution, sizeX, sizeY, sizeZ, cells, warnings);
    }

    private static void applyRegion(JsonObject region, int index, String label,
            int sizeX, int sizeY, int sizeZ, Map<Long, CellDirective> cells,
            List<String> warnings) {
        String path = label + ": regions[" + index + "]";
        String type = string(region, "type", path).toLowerCase(Locale.ROOT);
        CellDirective directive = directive(region, path);
        int[] from;
        int[] to;
        switch (type) {
            case "fill", "box" -> {
                from = vector(region, "from", path);
                to = vector(region, "to", path);
                if (type.equals("box") && !region.has("to")) {
                    throw new BlueprintException(path + ": 'box' needs 'from' and 'to'");
                }
                if (!region.has("to")) {
                    to = from.clone();
                }
            }
            case "layer" -> {
                int y = integer(region, "y", path);
                int[] fullFrom = {0, y, 0};
                int[] fullTo = {sizeX - 1, y, sizeZ - 1};
                from = region.has("from") ? vector(region, "from", path) : fullFrom;
                to = region.has("to") ? vector(region, "to", path) : fullTo;
                from[1] = y;
                to[1] = y;
            }
            default -> throw new BlueprintException(path + ": unknown region type '" + type
                    + "' (expected fill, box or layer)");
        }
        int x0 = Math.min(from[0], to[0]);
        int y0 = Math.min(from[1], to[1]);
        int z0 = Math.min(from[2], to[2]);
        int x1 = Math.max(from[0], to[0]);
        int y1 = Math.max(from[1], to[1]);
        int z1 = Math.max(from[2], to[2]);
        if (x0 < 0 || y0 < 0 || z0 < 0 || x1 >= sizeX || y1 >= sizeY || z1 >= sizeZ) {
            warnings.add(path + ": box " + java.util.Arrays.toString(from) + " → "
                    + java.util.Arrays.toString(to) + " is outside size ["
                    + sizeX + "," + sizeY + "," + sizeZ + "]; clipped");
            x0 = Math.max(0, x0);
            y0 = Math.max(0, y0);
            z0 = Math.max(0, z0);
            x1 = Math.min(sizeX - 1, x1);
            y1 = Math.min(sizeY - 1, y1);
            z1 = Math.min(sizeZ - 1, z1);
        }
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    put(cells, warnings, label, x, y, z, directive, sizeX, sizeY, sizeZ, path);
                }
            }
        }
    }

    private static void put(Map<Long, CellDirective> cells, List<String> warnings, String label,
            int x, int y, int z, CellDirective directive,
            int sizeX, int sizeY, int sizeZ, String path) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            warnings.add(path + ": cell [" + x + "," + y + "," + z + "] is outside the size");
            return;
        }
        long key = (long) y * sizeZ * sizeX + (long) z * sizeX + x;
        CellDirective previous = cells.put(key, directive);
        if (previous != null && previous.type() != directive.type()) {
            warnings.add(label + ": cell [" + x + "," + y + "," + z + "] redefined from "
                    + previous.format() + " to " + directive.format() + "; last one wins");
        }
    }

    private static CellDirective directive(JsonObject object, String path) {
        JsonElement node = object.get("directive");
        if (node == null || !node.isJsonPrimitive()) {
            throw new BlueprintException(path + ": needs a 'directive' string");
        }
        try {
            return DirectiveType.parse(node.getAsString());
        } catch (IllegalArgumentException invalid) {
            throw new BlueprintException(path + ": " + invalid.getMessage(), invalid);
        }
    }

    private static int[] vector(JsonObject object, String key, String path) {
        JsonElement node = object.get(key);
        if (node == null || !node.isJsonArray()) {
            throw new BlueprintException(path + ": needs '" + key + "' as [x,y,z]");
        }
        JsonArray array = node.getAsJsonArray();
        if (array.size() != 3) {
            throw new BlueprintException(path + ": '" + key + "' must have three integers");
        }
        int[] values = new int[3];
        for (int i = 0; i < 3; i++) {
            JsonElement value = array.get(i);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new BlueprintException(path + ": '" + key + "[" + i + "]' must be a number");
            }
            values[i] = value.getAsInt();
        }
        return values;
    }

    private static JsonArray array(JsonObject object, String key, String path) {
        JsonElement node = object.get(key);
        if (node == null || !node.isJsonArray()) {
            throw new BlueprintException(path + ": needs '" + key + "' as an array");
        }
        return node.getAsJsonArray();
    }

    private static int positive(JsonArray array, int index, String path, String label) {
        JsonElement value = array.get(index);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new BlueprintException(path + ": '" + label + "' must be a number");
        }
        int number = value.getAsInt();
        if (number < 1) {
            throw new BlueprintException(path + ": '" + label + "' must be at least 1");
        }
        return number;
    }

    private static int integer(JsonObject object, String key, String path) {
        JsonElement node = object.get(key);
        if (node == null || !node.isJsonPrimitive() || !node.getAsJsonPrimitive().isNumber()) {
            throw new BlueprintException(path + ": needs integer '" + key + "'");
        }
        return node.getAsInt();
    }

    private static String string(JsonObject object, String key, String path) {
        JsonElement node = object.get(key);
        if (node == null || !node.isJsonPrimitive() || !node.getAsJsonPrimitive().isString()) {
            throw new BlueprintException(path + ": needs string '" + key + "'");
        }
        return node.getAsString();
    }

    /** Grid of the blueprint's dimensions. */
    public OccupancyMask toMask() {
        OccupancyMask mask = OccupancyMask.empty(resolution, sizeX, sizeY, sizeZ);
        for (Map.Entry<Long, CellDirective> entry : cells.entrySet()) {
            if (!entry.getValue().type().name().equals("EMPTY")
                    && !entry.getValue().type().name().equals("PASSABLE_HITBOX")) {
                int[] cell = coordinates(entry.getKey());
                mask.fill(cell[0] * resolution, cell[1] * resolution, cell[2] * resolution,
                        (cell[0] + 1) * resolution - 1, (cell[1] + 1) * resolution - 1,
                        (cell[2] + 1) * resolution - 1, true);
            }
        }
        return mask;
    }

    private int[] coordinates(long key) {
        int x = (int) (key % sizeX);
        long rest = key / sizeX;
        int z = (int) (rest % sizeZ);
        int y = (int) (rest / sizeZ);
        return new int[] {x, y, z};
    }

    /** Directives in the same block space as {@link #toMask()}. */
    public DirectiveGrid directives() {
        return new DirectiveGrid(sizeX, sizeY, sizeZ, cells);
    }

    public int resolution() {
        return resolution;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public String name() {
        return name;
    }

    public List<String> warnings() {
        return warnings;
    }

    public int cellCount() {
        return cells.size();
    }
}
