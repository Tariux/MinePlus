package com.mineplus.infrastructure.collision.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mineplus.infrastructure.collision.mask.OccupancyMask;
import com.mineplus.infrastructure.collision.mask.RasterMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AUTO occupancy: voxelises a vanilla/Blockbench model JSON.
 *
 * <p>Elements are cuboids in 1/16-block units with an optional rotation about
 * {@code origin} on one axis. Parent models are resolved and their elements are
 * merged (union), so a child model inherits the parent's volume — which is what
 * the client renders. Textures and faces are ignored except for the
 * zero-thickness rule below.</p>
 *
 * <p>Rotation handling: 90° multiples land on exact cells; other angles are
 * sampled as {@link RasterMode} says. {@code CONSERVATIVE} tests the sub-voxel
 * centre and its eight corners, {@code CENTERED} only the centre, and
 * {@code MAJORITY} requires five of eight samples. A cuboid with zero thickness
 * on one axis contributes nothing unless {@code thinPlanesAsSlabs} is on, in
 * which case it becomes the thinnest slab the grid can hold.</p>
 *
 * <p>The grid is normalised so the transformed model's minimum corner sits at
 * the origin; the model origin is therefore the placement anchor. Thread-safe
 * to build off-thread: this class never touches Bukkit.</p>
 */
public final class AutoOccupancySource implements OccupancySource {

    private final String modelJson;
    private final String sourceName;
    private final DisplayTransform display;
    private final int resolution;
    private final RasterMode rasterMode;
    private final boolean thinPlanesAsSlabs;
    private final ModelResolver resolver;
    private final int maxBlocks;

    public AutoOccupancySource(String modelJson, String sourceName, DisplayTransform display,
            int resolution, RasterMode rasterMode, boolean thinPlanesAsSlabs,
            ModelResolver resolver, int maxBlocks) {
        if (modelJson == null || modelJson.isBlank()) {
            throw new IllegalArgumentException("AUTO occupancy needs model JSON");
        }
        this.modelJson = modelJson;
        this.sourceName = sourceName == null ? "model" : sourceName;
        this.display = display == null ? DisplayTransform.IDENTITY : display;
        this.resolution = Math.max(1, Math.min(64, resolution));
        this.rasterMode = rasterMode == null ? RasterMode.CONSERVATIVE : rasterMode;
        this.thinPlanesAsSlabs = thinPlanesAsSlabs;
        this.resolver = resolver == null ? ModelResolver.NONE : resolver;
        this.maxBlocks = Math.max(1, maxBlocks);
    }

    @Override
    public OccupancyResult build() {
        List<String> warnings = new ArrayList<>();
        List<Element> elements = collectElements(modelJson, sourceName, warnings, 0,
                new HashSet<>());
        if (elements.isEmpty()) {
            warnings.add("model '" + sourceName + "' has no elements; occupancy is empty");
            return new OccupancyResult(OccupancyMask.empty(resolution, 1, 1, 1),
                    DirectiveGrid.empty(1, 1, 1), warnings);
        }

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        List<double[][]> transformedCorners = new ArrayList<>(elements.size());
        for (Element element : elements) {
            double[][] corners = new double[8][];
            int index = 0;
            for (int xi = 0; xi < 2; xi++) {
                for (int yi = 0; yi < 2; yi++) {
                    for (int zi = 0; zi < 2; zi++) {
                        double[] local = rotate(element, xi == 0 ? element.minX : element.maxX,
                                yi == 0 ? element.minY : element.maxY,
                                zi == 0 ? element.minZ : element.maxZ, 1);
                        double[] block = display.forward(local[0], local[1], local[2]);
                        corners[index++] = block;
                        minX = Math.min(minX, block[0]);
                        minY = Math.min(minY, block[1]);
                        minZ = Math.min(minZ, block[2]);
                        maxX = Math.max(maxX, block[0]);
                        maxY = Math.max(maxY, block[1]);
                        maxZ = Math.max(maxZ, block[2]);
                    }
                }
            }
            transformedCorners.add(corners);
        }

        double width = Math.max(1.0e-4, maxX - minX);
        double height = Math.max(1.0e-4, maxY - minY);
        double depth = Math.max(1.0e-4, maxZ - minZ);
        int blocksX = clampBlocks(width, warnings, "X");
        int blocksY = clampBlocks(height, warnings, "Y");
        int blocksZ = clampBlocks(depth, warnings, "Z");
        OccupancyMask mask = OccupancyMask.empty(resolution, blocksX, blocksY, blocksZ);

        double unit = 1.0 / resolution;
        for (int e = 0; e < elements.size(); e++) {
            Element element = elements.get(e);
            double[][] corners = transformedCorners.get(e);
            double elMinX = Double.MAX_VALUE;
            double elMinY = Double.MAX_VALUE;
            double elMinZ = Double.MAX_VALUE;
            double elMaxX = -Double.MAX_VALUE;
            double elMaxY = -Double.MAX_VALUE;
            double elMaxZ = -Double.MAX_VALUE;
            for (double[] corner : corners) {
                elMinX = Math.min(elMinX, corner[0]);
                elMinY = Math.min(elMinY, corner[1]);
                elMinZ = Math.min(elMinZ, corner[2]);
                elMaxX = Math.max(elMaxX, corner[0]);
                elMaxY = Math.max(elMaxY, corner[1]);
                elMaxZ = Math.max(elMaxZ, corner[2]);
            }
            int x0 = clampCell((elMinX - minX) / unit - 1, mask.cellsX());
            int y0 = clampCell((elMinY - minY) / unit - 1, mask.cellsY());
            int z0 = clampCell((elMinZ - minZ) / unit - 1, mask.cellsZ());
            int x1 = clampCell(Math.ceil((elMaxX - minX) / unit), mask.cellsX() - 1);
            int y1 = clampCell(Math.ceil((elMaxY - minY) / unit), mask.cellsY() - 1);
            int z1 = clampCell(Math.ceil((elMaxZ - minZ) / unit), mask.cellsZ() - 1);
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        if (cellSolid(element, mask, x, y, z, minX, minY, minZ, unit)) {
                            mask.set(x, y, z, true);
                        }
                    }
                }
            }
        }

        if (mask.isEmpty()) {
            warnings.add("model '" + sourceName + "' produced no solid cells at resolution "
                    + resolution);
        }
        return new OccupancyResult(mask,
                DirectiveGrid.empty(mask.blocksX(), mask.blocksY(), mask.blocksZ()), warnings);
    }

    private int clampBlocks(double sizeBlocks, List<String> warnings, String axis) {
        int blocks = (int) Math.ceil(sizeBlocks - 1.0e-6);
        blocks = Math.max(1, blocks);
        if (blocks > maxBlocks) {
            warnings.add("model '" + sourceName + "' is " + blocks + " blocks on " + axis
                    + "; clamped to " + maxBlocks);
            blocks = maxBlocks;
        }
        long cells = (long) blocks * maxBlocks * maxBlocks * resolution * resolution * resolution;
        if (cells > 120_000_000L) {
            warnings.add("occupancy grid is very large (" + cells + " cells); fitting may be slow");
        }
        return blocks;
    }

    private static int clampCell(double value, int max) {
        return Math.max(0, Math.min(max, (int) Math.floor(value)));
    }

    private boolean cellSolid(Element element, OccupancyMask mask,
            int cellX, int cellY, int cellZ,
            double originX, double originY, double originZ, double unit) {
        double baseX = originX + cellX * unit;
        double baseY = originY + cellY * unit;
        double baseZ = originZ + cellZ * unit;
        return switch (rasterMode) {
            case CENTERED -> inside(element, baseX + unit * 0.5, baseY + unit * 0.5,
                    baseZ + unit * 0.5);
            case CONSERVATIVE -> inside(element, baseX + unit * 0.5, baseY + unit * 0.5,
                    baseZ + unit * 0.5)
                    || inside(element, baseX, baseY, baseZ)
                    || inside(element, baseX + unit, baseY, baseZ)
                    || inside(element, baseX, baseY + unit, baseZ)
                    || inside(element, baseX, baseY, baseZ + unit)
                    || inside(element, baseX + unit, baseY + unit, baseZ)
                    || inside(element, baseX + unit, baseY, baseZ + unit)
                    || inside(element, baseX, baseY + unit, baseZ + unit)
                    || inside(element, baseX + unit, baseY + unit, baseZ + unit);
            case MAJORITY -> {
                int hits = 0;
                for (int sy = 0; sy < 2; sy++) {
                    for (int sz = 0; sz < 2; sz++) {
                        for (int sx = 0; sx < 2; sx++) {
                            double fx = sx == 0 ? 0.25 : 0.75;
                            double fy = sy == 0 ? 0.25 : 0.75;
                            double fz = sz == 0 ? 0.25 : 0.75;
                            if (inside(element, baseX + unit * fx, baseY + unit * fy,
                                    baseZ + unit * fz)) {
                                hits++;
                            }
                        }
                    }
                }
                yield hits >= 5;
            }
        };
    }

    private boolean inside(Element element, double blockX, double blockY, double blockZ) {
        double[] model = display.inverse(blockX, blockY, blockZ);
        double[] local = rotate(element, model[0], model[1], model[2], -1);
        return local[0] >= element.minX - 1.0e-6 && local[0] <= element.maxX + 1.0e-6
                && local[1] >= element.minY - 1.0e-6 && local[1] <= element.maxY + 1.0e-6
                && local[2] >= element.minZ - 1.0e-6 && local[2] <= element.maxZ + 1.0e-6;
    }

    /** Rotates a model point about the element origin (direction +1 or -1). */
    private static double[] rotate(Element element, double x, double y, double z, int direction) {
        if (element.angle == 0 || element.axis == null) {
            return new double[] {x, y, z};
        }
        double radians = Math.toRadians(element.angle) * direction;
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double px = x - element.originX;
        double py = y - element.originY;
        double pz = z - element.originZ;
        double rx;
        double ry;
        double rz;
        switch (element.axis) {
            case "x" -> {
                rx = px;
                ry = cos * py - sin * pz;
                rz = sin * py + cos * pz;
            }
            case "y" -> {
                rx = cos * px + sin * pz;
                ry = py;
                rz = -sin * px + cos * pz;
            }
            case "z" -> {
                rx = cos * px - sin * py;
                ry = sin * px + cos * py;
                rz = pz;
            }
            default -> {
                rx = px;
                ry = py;
                rz = pz;
            }
        }
        return new double[] {rx + element.originX, ry + element.originY, rz + element.originZ};
    }

    private List<Element> collectElements(String json, String name, List<String> warnings,
            int depth, Set<String> seen) {
        if (depth > 8) {
            warnings.add("model parent chain deeper than 8 at '" + name + "'; truncated");
            return List.of();
        }
        if (!seen.add(name)) {
            warnings.add("model parent cycle at '" + name + "'; truncated");
            return List.of();
        }
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new ModelParseException(name + ": model JSON must be an object");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonSyntaxException broken) {
            throw new ModelParseException(name + ": " + broken.getMessage(), broken);
        }

        List<Element> elements = new ArrayList<>();
        JsonElement parent = root.get("parent");
        if (parent != null && parent.isJsonPrimitive()) {
            String parentName = normaliseParent(parent.getAsString());
            String parentJson = resolver.resolve(parentName);
            if (parentJson == null) {
                warnings.add("parent model '" + parentName + "' not found; using only '"
                        + name + "'");
            } else {
                elements.addAll(collectElements(parentJson, parentName, warnings, depth + 1, seen));
            }
        }

        JsonElement elementsNode = root.get("elements");
        if (elementsNode == null) {
            return elements;
        }
        if (!elementsNode.isJsonArray()) {
            throw new ModelParseException(name + ": 'elements' must be an array");
        }
        JsonArray array = elementsNode.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement node = array.get(i);
            if (!node.isJsonObject()) {
                throw new ModelParseException(name + ": elements[" + i + "] must be an object");
            }
            JsonObject element = node.getAsJsonObject();
            double[] from = vector(element, "from", name, i);
            double[] to = vector(element, "to", name, i);
            double minX = Math.min(from[0], to[0]);
            double minY = Math.min(from[1], to[1]);
            double minZ = Math.min(from[2], to[2]);
            double maxX = Math.max(from[0], to[0]);
            double maxY = Math.max(from[1], to[1]);
            double maxZ = Math.max(from[2], to[2]);

            boolean flatX = maxX - minX < 1.0e-9;
            boolean flatY = maxY - minY < 1.0e-9;
            boolean flatZ = maxZ - minZ < 1.0e-9;
            int flats = (flatX ? 1 : 0) + (flatY ? 1 : 0) + (flatZ ? 1 : 0);
            if (flats > 0) {
                if (flats == 2) {
                    warnings.add(name + ": elements[" + i
                            + "] is a line with no volume; skipped");
                    continue;
                }
                if (!thinPlanesAsSlabs) {
                    warnings.add(name + ": elements[" + i
                            + "] is a flat plane; enable AUTO thin planes to include it");
                    continue;
                }
                if (flatX) {
                    minX -= 0.5;
                    maxX += 0.5;
                }
                if (flatY) {
                    minY -= 0.5;
                    maxY += 0.5;
                }
                if (flatZ) {
                    minZ -= 0.5;
                    maxZ += 0.5;
                }
            }

            String axis = null;
            double originX = 8;
            double originY = 8;
            double originZ = 8;
            double angle = 0;
            JsonElement rotationNode = element.get("rotation");
            if (rotationNode != null && rotationNode.isJsonObject()) {
                JsonObject rotation = rotationNode.getAsJsonObject();
                JsonElement axisNode = rotation.get("axis");
                if (axisNode != null && axisNode.isJsonPrimitive()) {
                    axis = axisNode.getAsString().toLowerCase(java.util.Locale.ROOT);
                    if (!axis.equals("x") && !axis.equals("y") && !axis.equals("z")) {
                        throw new ModelParseException(name + ": elements[" + i
                                + "].rotation.axis must be x, y or z");
                    }
                }
                if (rotation.has("origin")) {
                    double[] origin = vector(rotation, "origin", name, i);
                    originX = origin[0];
                    originY = origin[1];
                    originZ = origin[2];
                }
                if (rotation.has("angle")) {
                    angle = number(rotation, "angle", name + ": elements[" + i + "].rotation");
                }
            }
            elements.add(new Element(minX, minY, minZ, maxX, maxY, maxZ, axis,
                    originX, originY, originZ, angle));
        }
        return elements;
    }

    private static String normaliseParent(String parent) {
        String value = parent;
        if (value.startsWith("minecraft:")) {
            value = value.substring("minecraft:".length());
        }
        if (value.startsWith("block/")) {
            value = value.substring("block/".length());
        }
        return value;
    }

    private static double[] vector(JsonObject object, String key, String model, int elementIndex) {
        JsonElement node = object.get(key);
        if (node == null || !node.isJsonArray()) {
            throw new ModelParseException(model + ": elements[" + elementIndex + "] needs '"
                    + key + "' as [x,y,z]");
        }
        JsonArray array = node.getAsJsonArray();
        if (array.size() != 3) {
            throw new ModelParseException(model + ": elements[" + elementIndex + "]." + key
                    + " must have three numbers");
        }
        double[] values = new double[3];
        for (int i = 0; i < 3; i++) {
            JsonElement value = array.get(i);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new ModelParseException(model + ": elements[" + elementIndex + "]." + key
                        + "[" + i + "] must be a number");
            }
            values[i] = value.getAsDouble();
        }
        return values;
    }

    private static double number(JsonObject object, String key, String path) {
        JsonElement node = object.get(key);
        if (node == null || !node.isJsonPrimitive() || !node.getAsJsonPrimitive().isNumber()) {
            throw new ModelParseException(path + "." + key + " must be a number");
        }
        return node.getAsDouble();
    }

    @Override
    public String describe() {
        return "AUTO(" + sourceName + ", res " + resolution + ", " + rasterMode.name()
                + (thinPlanesAsSlabs ? ", planes" : "") + ')';
    }

    /** One cuboid from the model JSON. */
    private record Element(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            String axis, double originX, double originY, double originZ, double angle) {
    }

    /** Model JSON that cannot be parsed or does not match the schema. */
    public static final class ModelParseException extends RuntimeException {
        public ModelParseException(String message) {
            super(message);
        }

        public ModelParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
