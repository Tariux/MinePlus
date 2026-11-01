package com.mineplus.devtools.assetlab;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import org.joml.Quaternionf;

/**
 * Shared JSON access helpers for every Asset Lab daemon op.
 *
 * <p>One implementation of the tolerant request reads ({@link #str}, {@link #bool},
 * {@link #intg}, {@link #dbl}) and of the JSON-safe geometry writers
 * ({@link #vec}, {@link #quat}): a malformed request value falls back to the
 * default instead of failing the whole op, and a non-finite float becomes
 * {@code 0} because Gson would otherwise emit bare {@code NaN}/{@code Infinity}
 * tokens that standard JSON parsers reject.</p>
 */
final class Json {

    private Json() {
    }

    static boolean has(JsonObject o, String name) {
        return o != null && o.has(name) && !o.get(name).isJsonNull();
    }

    static String str(JsonObject o, String name, String fallback) {
        if (!has(o, name)) {
            return fallback;
        }
        try {
            return o.get(name).getAsString();
        } catch (Exception malformed) {
            return fallback;
        }
    }

    static boolean bool(JsonObject o, String name, boolean fallback) {
        if (!has(o, name)) {
            return fallback;
        }
        try {
            return o.get(name).getAsBoolean();
        } catch (Exception malformed) {
            return fallback;
        }
    }

    static int intg(JsonObject o, String name, int fallback) {
        if (!has(o, name)) {
            return fallback;
        }
        try {
            return o.get(name).getAsInt();
        } catch (Exception malformed) {
            return fallback;
        }
    }

    static double dbl(JsonObject o, String name, double fallback) {
        if (!has(o, name)) {
            return fallback;
        }
        try {
            return o.get(name).getAsDouble();
        } catch (Exception malformed) {
            return fallback;
        }
    }

    /** A boolean JSON member read as a plain flag, missing or malformed = {@code fallback}. */
    static boolean flag(JsonElement element, boolean fallback) {
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (Exception malformed) {
            return fallback;
        }
    }

    /** Requested integer, or {@code null} when absent — for override helpers that must not unbox. */
    static Integer optionalInt(JsonObject o, String name) {
        if (!has(o, name)) {
            return null;
        }
        try {
            return o.get(name).getAsInt();
        } catch (Exception malformed) {
            return null;
        }
    }

    static JsonArray vec(float... values) {
        JsonArray array = new JsonArray();
        for (float value : values) {
            array.add(finite(value));
        }
        return array;
    }

    static JsonArray quat(Quaternionf q) {
        return vec(q == null ? new float[4]
                : new float[] {q.x, q.y, q.z, q.w});
    }

    static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            for (String value : values) {
                array.add(value);
            }
        }
        return array;
    }

    static JsonArray stringArray(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /**
     * JSON-safe float: Gson serializes NaN/Infinity as bare tokens that standard
     * JSON parsers reject, and some models carry degenerate cubes (zero-length
     * quaternion normalizations). Non-finite values become 0 so the response
     * always parses; the affected cube renders degenerate, which is itself a
     * useful diagnostic.
     */
    static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }

    /** String list from a request array member; never {@code null}. */
    static List<String> strings(JsonObject o, String name) {
        List<String> values = new java.util.ArrayList<>();
        if (o == null || !has(o, name) || !o.get(name).isJsonArray()) {
            return values;
        }
        for (JsonElement element : o.getAsJsonArray(name)) {
            if (element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    /** A request object member, or an empty object — so override reads never null-check. */
    static JsonObject object(JsonObject o, String name) {
        if (o != null && has(o, name) && o.get(name).isJsonObject()) {
            return o.getAsJsonObject(name);
        }
        return new JsonObject();
    }
}
