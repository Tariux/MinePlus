package com.mineplus.devtools.assetlab;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The daemon's {@code audit} op: run every registered asset through its op and
 * summarize the whole pack in one pass — the "is anything broken before I go in
 * game" button. Each entry is exception-isolated; one unreadable file becomes a
 * {@code BROKEN} row, never a failed audit.
 *
 * <p>Request:
 * <pre>{@code
 * {"op":"audit",
 *  "blocks":[".../model.bbmodel"],
 *  "mobs":[".../evil_pig.yml"],
 *  "mobModels":[".../soldier.bbmodel"],
 *  "items":[".../pistol.json"]}
 * }</pre>
 *
 * <p>Response: {@code {checked, okCount, repairedCount, brokenCount,
 * rows:[{category, file, name, status, summary, cubes?, textures?, elements?, bones?}]}}.</p>
 */
final class AuditOps {

    private AuditOps() {
    }

    static JsonObject audit(JsonObject request) throws Exception {
        JsonArray rows = new JsonArray();
        int okCount = 0;
        int repairedCount = 0;
        int brokenCount = 0;

        for (String path : paths(request, "blocks")) {
            JsonObject row = run("block", path, () -> BlockOps.block(single("model", path)));
            okCount += tallyOk(row);
            repairedCount += tallyRepaired(row);
            brokenCount += tallyBroken(row);
            rows.add(row);
        }
        for (String path : paths(request, "mobs")) {
            JsonObject row = run("mob", path, () -> MobOps.mob(single("yml", path)));
            okCount += tallyOk(row);
            repairedCount += tallyRepaired(row);
            brokenCount += tallyBroken(row);
            rows.add(row);
        }
        for (String path : paths(request, "mobModels")) {
            JsonObject row = run("mob", path, () -> MobOps.mob(single("model", path)));
            okCount += tallyOk(row);
            repairedCount += tallyRepaired(row);
            brokenCount += tallyBroken(row);
            rows.add(row);
        }
        for (String path : paths(request, "items")) {
            JsonObject row = run("item", path, () -> ItemOps.item(single("model", path)));
            okCount += tallyOk(row);
            repairedCount += tallyRepaired(row);
            brokenCount += tallyBroken(row);
            rows.add(row);
        }

        JsonObject response = new JsonObject();
        response.addProperty("checked", rows.size());
        response.addProperty("okCount", okCount);
        response.addProperty("repairedCount", repairedCount);
        response.addProperty("brokenCount", brokenCount);
        response.add("rows", rows);
        return response;
    }

    // ------------------------------------------------------------------
    // Per-row execution
    // ------------------------------------------------------------------

    private interface Op {
        JsonObject run() throws Exception;
    }

    private static JsonObject run(String category, String path, Op op) {
        JsonObject row = new JsonObject();
        row.addProperty("category", category);
        row.addProperty("file", path);
        try {
            JsonObject result = op.run();
            row.addProperty("name", nameOf(result, path));
            row.addProperty("status", result.has("status") && result.get("status").isJsonPrimitive()
                    ? result.get("status").getAsString() : "BROKEN");
            row.addProperty("summary", result.has("summary") && result.get("summary").isJsonPrimitive()
                    ? result.get("summary").getAsString() : "no summary returned");
            row.addProperty("cubes", result.has("cubeCount")
                    ? result.get("cubeCount").getAsInt()
                    : result.has("stats")
                            ? result.getAsJsonObject("stats").get("cubeCount").getAsInt() : 0);
            row.addProperty("bones", result.has("stats")
                    ? result.getAsJsonObject("stats").get("boneCount").getAsInt() : 0);
            row.addProperty("elements", result.has("elementCount")
                    ? result.get("elementCount").getAsInt() : 0);
            row.addProperty("textures", texturesText(result));
        } catch (Exception failure) {
            row.addProperty("name", MobOps.stripExtension(basename(path)));
            row.addProperty("status", "BROKEN");
            row.addProperty("summary", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        return row;
    }

    private static int tallyOk(JsonObject row) {
        return "OK".equals(row.get("status").getAsString()) ? 1 : 0;
    }

    private static int tallyRepaired(JsonObject row) {
        return "REPAIRED".equals(row.get("status").getAsString()) ? 1 : 0;
    }

    private static int tallyBroken(JsonObject row) {
        return "BROKEN".equals(row.get("status").getAsString()) ? 1 : 0;
    }

    private static String nameOf(JsonObject result, String path) {
        if (result.has("name") && result.get("name").isJsonPrimitive()) {
            return result.get("name").getAsString();
        }
        return MobOps.stripExtension(basename(path));
    }

    private static String texturesText(JsonObject result) {
        if (!result.has("textures") || !result.get("textures").isJsonArray()) {
            return "";
        }
        JsonArray textures = result.getAsJsonArray("textures");
        if (textures.size() == 0) {
            return "";
        }
        int resolved = 0;
        for (JsonElement element : textures) {
            if (element.isJsonObject() && element.getAsJsonObject().has("resolved")
                    && element.getAsJsonObject().get("resolved").getAsBoolean()) {
                resolved++;
            }
        }
        return resolved + "/" + textures.size();
    }

    private static JsonArray paths(JsonObject request, String key) {
        JsonArray paths = new JsonArray();
        if (!request.has(key) || !request.get(key).isJsonArray()) {
            return paths;
        }
        for (JsonElement element : request.getAsJsonArray(key)) {
            if (element.isJsonPrimitive()) {
                paths.add(element.getAsString());
            }
        }
        return paths;
    }

    private static JsonObject single(String key, String value) {
        JsonObject request = new JsonObject();
        request.addProperty(key, value);
        return request;
    }

    private static String basename(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
}
