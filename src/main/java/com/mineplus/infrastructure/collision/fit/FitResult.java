package com.mineplus.infrastructure.collision.fit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineplus.infrastructure.collision.atlas.AtlasKey;
import java.util.ArrayList;
import java.util.List;

/**
 * The result of one fit: per-cell choices, backend assignment, metrics and
 * warnings. Serialisable so a fit can be baked and reused without recomputing.
 *
 * <p>The record is immutable. {@link #toJson()} / {@link #fromJson(String)} use a
 * stable field order and only primitive values, so two runs over the same input
 * produce byte-identical JSON (the golden-test property).</p>
 */
public record FitResult(
        String modelKey,
        String atlasKey,
        String profileName,
        double yaw,
        int resolution,
        int sizeX,
        int sizeY,
        int sizeZ,
        List<CellChoice> cells,
        FitMetrics metrics,
        List<String> warnings) {

    public FitResult {
        cells = cells == null ? List.of() : List.copyOf(cells);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        metrics = metrics == null
                ? new FitMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0) : metrics;
    }

    /** Cache key: everything that can change the fit, and nothing else. */
    public String cacheKey() {
        return AtlasKey.sha256(modelKey + '|' + atlasKey + '|' + profileName + '|' + yaw + '|'
                + resolution + '|' + sizeX + 'x' + sizeY + 'x' + sizeZ);
    }

    /** Same choices as a plan (the parts list is the placed subset). */
    public BackendPlan plan() {
        return new BackendPlan(cells, metrics, warnings);
    }

    /** Stable JSON for the fit cache and golden tests. */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.addProperty("model", modelKey);
        root.addProperty("atlas", atlasKey);
        root.addProperty("profile", profileName);
        root.addProperty("yaw", yaw);
        root.addProperty("resolution", resolution);
        root.addProperty("sizeX", sizeX);
        root.addProperty("sizeY", sizeY);
        root.addProperty("sizeZ", sizeZ);
        JsonObject metricsNode = new JsonObject();
        metricsNode.addProperty("cells", metrics.cells());
        metricsNode.addProperty("blocks", metrics.blocks());
        metricsNode.addProperty("ghosts", metrics.ghosts());
        metricsNode.addProperty("entities", metrics.entities());
        metricsNode.addProperty("pruned", metrics.prunedInterior());
        metricsNode.addProperty("extra", metrics.extraVolume());
        metricsNode.addProperty("missing", metrics.missingVolume());
        metricsNode.addProperty("target", metrics.targetVolume());
        metricsNode.addProperty("maxLedge", metrics.maxLedge());
        metricsNode.addProperty("violations", metrics.ledgeViolations());
        root.add("metrics", metricsNode);
        JsonArray cellArray = new JsonArray();
        for (CellChoice cell : cells) {
            JsonObject node = new JsonObject();
            node.addProperty("x", cell.x());
            node.addProperty("y", cell.y());
            node.addProperty("z", cell.z());
            if (cell.state() != null) {
                node.addProperty("state", cell.state());
            }
            node.addProperty("backend", cell.backend().name());
            node.addProperty("score", cell.score());
            node.addProperty("extra", cell.extraBits());
            node.addProperty("missing", cell.missingBits());
            node.addProperty("top", cell.topSurface());
            node.addProperty("standing", cell.standingSurface());
            node.addProperty("reason", cell.reason());
            cellArray.add(node);
        }
        root.add("cells", cellArray);
        JsonArray warningArray = new JsonArray();
        warnings.forEach(warningArray::add);
        root.add("warnings", warningArray);
        return root.toString();
    }

    /** Parses a baked fit; throws {@link IllegalArgumentException} when invalid. */
    public static FitResult fromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject metricsNode = root.getAsJsonObject("metrics");
        FitMetrics metrics = new FitMetrics(
                metricsNode.get("cells").getAsInt(),
                metricsNode.get("blocks").getAsInt(),
                metricsNode.get("ghosts").getAsInt(),
                metricsNode.get("entities").getAsInt(),
                metricsNode.get("pruned").getAsInt(),
                metricsNode.get("extra").getAsLong(),
                metricsNode.get("missing").getAsLong(),
                metricsNode.get("target").getAsLong(),
                metricsNode.get("maxLedge").getAsDouble(),
                metricsNode.get("violations").getAsInt());
        List<CellChoice> cells = new ArrayList<>();
        JsonArray cellArray = root.getAsJsonArray("cells");
        for (JsonElement element : cellArray) {
            JsonObject node = element.getAsJsonObject();
            String state = node.has("state") ? node.get("state").getAsString() : null;
            cells.add(new CellChoice(
                    node.get("x").getAsInt(), node.get("y").getAsInt(), node.get("z").getAsInt(),
                    state, BackendType.valueOf(node.get("backend").getAsString()),
                    node.get("score").getAsDouble(), node.get("extra").getAsInt(),
                    node.get("missing").getAsInt(), node.get("top").getAsDouble(),
                    node.get("standing").getAsBoolean(),
                    node.has("reason") ? node.get("reason").getAsString() : ""));
        }
        List<String> warnings = new ArrayList<>();
        JsonArray warningArray = root.getAsJsonArray("warnings");
        for (JsonElement element : warningArray) {
            warnings.add(element.getAsString());
        }
        return new FitResult(root.get("model").getAsString(), root.get("atlas").getAsString(),
                root.get("profile").getAsString(), root.get("yaw").getAsDouble(),
                root.get("resolution").getAsInt(), root.get("sizeX").getAsInt(),
                root.get("sizeY").getAsInt(), root.get("sizeZ").getAsInt(), cells, metrics,
                warnings);
    }

    /** One cell by position, or null. */
    public CellChoice cell(int x, int y, int z) {
        for (CellChoice cell : cells) {
            if (cell.x() == x && cell.y() == y && cell.z() == z) {
                return cell;
            }
        }
        return null;
    }

    /** Stable signatures for golden tests. */
    public List<String> signatures() {
        List<String> out = new ArrayList<>(cells.size());
        cells.forEach(cell -> out.add(cell.signature()));
        return out;
    }
}
