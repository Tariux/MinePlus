package com.mineplus.devtools.assetlab;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineplus.config.MineplusConfig;
import com.mineplus.util.DebugLogger;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

/**
 * Headless daemon for the Asset Lab dev tool — the single web bridge that shows
 * exactly what the Minecraft client will render for a Mineplus asset: models,
 * mobs, blocks, items, animations, textures and the shipped pack artifacts.
 *
 * <p>It runs the real, compiled pipeline classes (mob model parsers,
 * {@code ModelBundleCompiler}, {@code PackModelEmitter}, {@code BbModelImporter},
 * {@code TexelSurfaceBaker}, {@code DisplayEmitter}, {@code ModelJsonWriter},
 * raw item-model validation, resource-pack zip inspection) with zero
 * production-code modifications — render parity with the server is structural,
 * not maintained by discipline. The browser draws the numbers this daemon emits;
 * it never re-derives geometry in JavaScript.</p>
 *
 * <p>Speaks line-delimited JSON over stdin/stdout: one request object per line
 * in, one response object per line out. Logs go to stderr (JUL's default
 * console handler), never to stdout. Ops: {@code ping}, {@code block},
 * {@code mob}, {@code poses}, {@code item}, {@code audit}, {@code bake},
 * {@code pack}.</p>
 *
 * <p>{@code bake} is the former texel tool's daemon op, ported here (with the
 * emitted display list added) so one tool covers every asset axis; the texel
 * directory stays as a read-only reference of the same pipeline.</p>
 *
 * <p>The dev server (Node) owns this process: it recompiles the pipeline on
 * source changes, restarts the daemon, and proxies op requests. The daemon
 * itself never watches anything — restart is the hot-reload mechanism.</p>
 */
public final class AssetDaemon {

    private AssetDaemon() {
    }

    public static void main(String[] args) throws Exception {
        // DebugLogger is a no-op until initialized; initializing with additional-debug
        // logs on (and JUL's default stderr console handler) surfaces pipeline warnings
        // in the dev tool without ever polluting the stdout JSON channel.
        DebugLogger.init(new MineplusConfig(true), Logger.getLogger("mineplus-devtool"));

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            JsonObject response;
            try {
                response = handle(JsonParser.parseString(line).getAsJsonObject());
            } catch (Exception failure) {
                response = new JsonObject();
                response.addProperty("ok", false);
                response.addProperty("error", String.valueOf(failure.getMessage()));
                response.addProperty("errorType", failure.getClass().getSimpleName());
                failure.printStackTrace();
            }
            out.write(response.toString());
            out.write('\n');
            out.flush();
        }
    }

    private static JsonObject handle(JsonObject request) {
        String op = str(request, "op", "");
        JsonObject response = ok(request);
        switch (op) {
            case "ping" -> response.addProperty("pong", true);
            case "block" -> run(response, () -> BlockOps.block(request));
            case "mob" -> run(response, () -> MobOps.mob(request));
            case "poses" -> run(response, () -> PoseOps.poses(request));
            case "item" -> run(response, () -> ItemOps.item(request));
            case "bake" -> run(response, () -> BakeOps.bake(request));
            case "pack" -> run(response, () -> PackOps.pack(request));
            case "audit" -> run(response, () -> AuditOps.audit(request));
            default -> throw new IllegalArgumentException("unknown op '" + op + "'");
        }
        return response;
    }

    /**
     * Runs one op and merges its body into the response, reporting any failure as
     * that op's error instead of letting it take the JVM down. Catches
     * {@code Throwable}, not {@code Exception}: a headless daemon has no Bukkit
     * runtime, so a class the server would supply (an unvendored jar, say) arrives
     * here as {@code NoClassDefFoundError} — one op should fail with a readable
     * message, not kill every op the browser has queued.
     */
    private static void run(JsonObject response, Callable<JsonObject> op) {
        try {
            merge(response, op.call());
        } catch (Throwable failure) {
            response.addProperty("ok", false);
            response.addProperty("error", String.valueOf(failure.getMessage()));
            response.addProperty("errorType", failure.getClass().getName());
            failure.printStackTrace();
        }
    }

    private static void merge(JsonObject response, JsonObject body) {
        for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
            response.add(entry.getKey(), entry.getValue());
        }
    }

    private static JsonObject ok(JsonObject request) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        if (request.has("id") && !request.get("id").isJsonNull()) {
            response.add("id", request.get("id"));
        }
        return response;
    }

    private static String str(JsonObject o, String name, String fallback) {
        if (!o.has(name) || o.get(name).isJsonNull()) {
            return fallback;
        }
        try {
            return o.get(name).getAsString();
        } catch (Exception malformed) {
            return fallback;
        }
    }
}
