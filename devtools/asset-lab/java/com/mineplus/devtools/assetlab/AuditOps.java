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
 * rows:[{category, file, name, status, summary, cubes?, textures?, elements?, bones?,
 * bake?}]}}.</p>
 *
 * <p>For block rows the audit also reports the real texel bake — whether it
 * succeeds and how many displays the emitter produces against the plate budget
 * — because that is the answer to "will this actually render in game". It is
 * capped hard ({@link #MAX_BAKES} models and {@link #BAKE_BUDGET_MS} wall clock,
 * displays suppressed) so a whole-module audit stays a button press, not a
 * build.</p>
 */
final class AuditOps {

    /** Models whose texel bake is attempted in one audit pass. */
    private static final int MAX_BAKES = 24;
    /** Wall-clock ceiling for all texel bakes in one audit pass. */
    private static final long BAKE_BUDGET_MS = 6_000L;

    private AuditOps() {
    }

    static JsonObject audit(JsonObject request) throws Exception {
        JsonArray rows = new JsonArray();
        int okCount = 0;
        int repairedCount = 0;
        int brokenCount = 0;
        BakeBudget budget = new BakeBudget();

        for (String path : paths(request, "blocks")) {
            // Displays are suppressed here: the audit wants counts, not a plate list.
            JsonObject row = run("block", path, () -> BlockOps.block(blockAuditRequest(path)));
            JsonObject bake = budget.bake(path);
            row.add("bake", bake);
            adoptBakeSummary(row, bake);
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
        response.addProperty("bakesAttempted", budget.attempted);
        response.addProperty("bakesSkipped", budget.skipped);
        return response;
    }

    // ------------------------------------------------------------------
    // Capped texel bake enrichment
    // ------------------------------------------------------------------

    /** One audit pass's share of the bake budget; the shape of every row's {@code bake}. */
    private static final class BakeBudget {
        private int attempted;
        private int skipped;
        private long elapsedMs;

        JsonObject bake(String path) {
            JsonObject json = new JsonObject();
            if (attempted >= MAX_BAKES) {
                skipped++;
                json.addProperty("attempted", false);
                json.addProperty("reason", "the audit's bake cap of " + MAX_BAKES
                        + " model(s) is already spent — ask the bake op for this one directly");
                return json;
            }
            if (elapsedMs >= BAKE_BUDGET_MS) {
                skipped++;
                json.addProperty("attempted", false);
                json.addProperty("reason", "the audit's " + BAKE_BUDGET_MS + " ms bake budget is"
                        + " spent — ask the bake op for this one directly");
                return json;
            }
            long start = System.currentTimeMillis();
            try {
                JsonObject request = single("model", path);
                request.addProperty("includeDisplays", false);
                JsonObject result = BakeOps.bake(request);
                attempted++;
                JsonObject texel = result.has("texel") && result.get("texel").isJsonObject()
                        ? result.getAsJsonObject("texel") : new JsonObject();
                int plates = texel.has("totalPlates") ? texel.get("totalPlates").getAsInt() : 0;
                int faceBudget = texel.has("effectiveMaxPlatesPerFace")
                        ? texel.get("effectiveMaxPlatesPerFace").getAsInt() : 0;
                int instanceBudget = texel.has("effectiveMaxPlatesPerInstance")
                        ? texel.get("effectiveMaxPlatesPerInstance").getAsInt() : 0;
                int maxOnFace = texel.has("maxPlatesOnFace")
                        ? texel.get("maxPlatesOnFace").getAsInt() : 0;
                int fallbacks = texel.has("faceBudgetFallbacks")
                        ? texel.get("faceBudgetFallbacks").getAsInt() : 0;
                if (texel.has("instanceBudgetFallbacks")) {
                    fallbacks += texel.get("instanceBudgetFallbacks").getAsInt();
                }
                json.addProperty("attempted", true);
                json.addProperty("bakeStatus", result.has("status")
                        ? result.get("status").getAsString() : "BROKEN");
                json.addProperty("enabled", texel.has("enabled")
                        && texel.get("enabled").getAsBoolean());
                json.addProperty("facesBaked", texel.has("facesBaked")
                        ? texel.get("facesBaked").getAsInt() : 0);
                json.addProperty("facesTotal", texel.has("facesTotal")
                        ? texel.get("facesTotal").getAsInt() : 0);
                json.addProperty("totalPlates", plates);
                json.addProperty("maxPlatesOnFace", maxOnFace);
                json.addProperty("effectiveMaxPlatesPerFace", faceBudget);
                json.addProperty("effectiveMaxPlatesPerInstance", instanceBudget);
                json.addProperty("budgetFallbacks", fallbacks);
                json.addProperty("bakeTimeMs", texel.has("bakeTimeMs")
                        ? texel.get("bakeTimeMs").getAsDouble() : 0.0);
                json.addProperty("displayCount", result.has("displayCount")
                        ? result.get("displayCount").getAsInt() : 0);
                json.addProperty("solidDisplays", result.has("solidDisplays")
                        ? result.get("solidDisplays").getAsInt() : 0);
                json.addProperty("texelDisplays", result.has("texelDisplays")
                        ? result.get("texelDisplays").getAsInt() : 0);
                json.addProperty("displaysComplete", !result.has("displaysComplete")
                        || result.get("displaysComplete").getAsBoolean());
                json.addProperty("plateCount", plates);
                json.add("lighting", result.has("lighting") && result.get("lighting").isJsonObject()
                        ? result.getAsJsonObject("lighting") : new JsonObject());
                json.addProperty("platesWithinBudget",
                        maxOnFace <= faceBudget && fallbacks == 0);
            } catch (Throwable failure) {
                attempted++;
                json.addProperty("attempted", true);
                json.addProperty("bakeStatus", "BROKEN");
                json.addProperty("enabled", false);
                json.addProperty("error", failure.getClass().getSimpleName() + ": "
                        + failure.getMessage());
                json.addProperty("displayCount", 0);
                json.addProperty("totalPlates", 0);
                json.addProperty("plateCount", 0);
            } finally {
                elapsedMs += System.currentTimeMillis() - start;
            }
            return json;
        }
    }

    // ------------------------------------------------------------------
    // Per-row execution
    // ------------------------------------------------------------------

    /**
     * A block audit request: the model plus {@code includeDisplays:false}, so the
     * daemon still runs the real {@code DisplayEmitter} pass and reports counts
     * without serializing thousands of plate transforms into the audit.
     */
    private static JsonObject blockAuditRequest(String path) {
        JsonObject request = single("model", path);
        request.addProperty("includeDisplays", false);
        request.addProperty("wantPack", false);
        return request;
    }

    /**
     * Put the bake's rendering summary on the row itself: {@code displayCount}
     * (real emitted displays), {@code plateCount} (texel plates) and the
     * {@code lighting} digest (max emission + whether any display carries a
     * brightness override). The {@code bake} object keeps the same data for detail.
     */
    private static void adoptBakeSummary(JsonObject row, JsonObject bake) {
        if (!bake.has("attempted") || !bake.get("attempted").getAsBoolean()) {
            return;
        }
        if (bake.has("displayCount")) {
            row.addProperty("displayCount", bake.get("displayCount").getAsInt());
        }
        if (bake.has("solidDisplays")) {
            row.addProperty("solidDisplays", bake.get("solidDisplays").getAsInt());
        }
        if (bake.has("texelDisplays")) {
            row.addProperty("texelDisplays", bake.get("texelDisplays").getAsInt());
        }
        if (bake.has("displaysComplete")) {
            row.addProperty("displaysComplete", bake.get("displaysComplete").getAsBoolean());
        }
        row.addProperty("plateCount", bake.has("totalPlates") ? bake.get("totalPlates").getAsInt() : 0);
        if (bake.has("lighting") && bake.get("lighting").isJsonObject()) {
            row.add("lighting", bake.getAsJsonObject("lighting").deepCopy());
        }
    }

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
            copyRenderingSummary(row, result);
        } catch (Throwable failure) {
            // Headless pipeline classes can raise Errors (Bukkit registry lookups);
            // one broken asset must not end the audit, let alone the daemon.
            row.addProperty("name", MobOps.stripExtension(basename(path)));
            row.addProperty("status", "BROKEN");
            row.addProperty("summary", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        return row;
    }

    /**
     * Mirror the op's own rendering summary onto the row when it produced one
     * (block and bake ops do): display counts and the {@code lighting} digest.
     */
    private static void copyRenderingSummary(JsonObject row, JsonObject result) {
        for (String key : new String[] {"displayCount", "solidDisplays",
                "texelDisplays", "plateCount", "displaysComplete"}) {
            if (result.has(key) && result.get(key).isJsonPrimitive()) {
                row.add(key, result.get(key).deepCopy());
            }
        }
        if (result.has("lighting") && result.get("lighting").isJsonObject()) {
            row.add("lighting", result.getAsJsonObject("lighting").deepCopy());
        }
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

    private static java.util.List<String> paths(JsonObject request, String key) {
        java.util.List<String> paths = new java.util.ArrayList<>();
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
