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
import java.util.logging.Logger;

/**
 * Headless daemon for the Asset Lab dev tool — the mob/item/block sibling of
 * the texel tool's bake daemon, in its own tool directory and JVM. Runs the
 * real, compiled pipeline classes (mob model parsers, {@code ModelBundleCompiler},
 * {@code PackModelEmitter}, {@code BbModelImporter}, raw item-model validation)
 * with zero production-code modifications — render parity with the server is
 * structural, not maintained by discipline.
 *
 * <p>Speaks line-delimited JSON over stdin/stdout: one request object per line
 * in, one response object per line out. Logs go to stderr (JUL's default
 * console handler), never to stdout. Ops: {@code ping}, {@code block},
 * {@code mob}, {@code poses}, {@code item}, {@code audit}.
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
            case "block" -> merge(response, BlockOps.block(request));
            case "mob" -> merge(response, MobOps.mob(request));
            case "poses" -> merge(response, PoseOps.poses(request));
            case "item" -> merge(response, ItemOps.item(request));
            case "audit" -> merge(response, AuditOps.audit(request));
            default -> throw new IllegalArgumentException("unknown op '" + op + "'");
        }
        return response;
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
