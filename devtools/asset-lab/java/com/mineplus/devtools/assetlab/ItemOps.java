package com.mineplus.devtools.assetlab;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineplus.infrastructure.mob.custom.render.PngUtil;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The daemon's {@code item} op: validate one raw Java block/item model JSON —
 * the exact file the resource pack ships — and return everything the web
 * viewer needs to render it before going in game.
 *
 * <p>This intentionally does <b>not</b> go through {@code JavaJsonParser}: the
 * pack ships the raw file verbatim (per-face UV rotation, {@code display}
 * sections and arbitrary {@code texture_size} all survive to the client), so
 * validation runs against the raw JSON with Gson. Checks mirror the client's
 * actual constraints:</p>
 * <ul>
 *   <li>UTF-8 BOM — vanilla's parser rejects the file outright.</li>
 *   <li>{@code #key} texture reference chains (max 8 hops, like the client).</li>
 *   <li>Element window −16..32 px — outside it the client clamps the geometry
 *       and the cube collapses into a missing-texture box.</li>
 *   <li>Element rotation angle ∈ {−45, −22.5, 0, 22.5, 45}.</li>
 *   <li>Face UVs in 0..16 texture units, face rotation ∈ {0, 90, 180, 270}.</li>
 *   <li>Referenced PNGs resolvable on disk (the pack would otherwise ship a
 *       missing texture — magenta checkerboard on the client).</li>
 * </ul>
 *
 * <p>Request: {@code {"op":"item","model":".../pistol.json","textureRoot":".../guns/pistol"}}.
 * Response carries the parsed model for the viewer (elements, textures with
 * base64 PNGs, display section) plus a verdict {@code OK}/{@code REPAIRED}/
 * {@code BROKEN} with structured findings.</p>
 */
final class ItemOps {

    /** The client's element window: −16..32 px on every axis. */
    private static final float WINDOW_MIN = -16.0f;
    private static final float WINDOW_MAX = 32.0f;
    private static final int MAX_CHAIN_HOPS = 8;

    private ItemOps() {
    }

    static JsonObject item(JsonObject request) throws Exception {
        String modelPath = MobOps.Json.str(request, "model", null);
        if (modelPath == null || modelPath.isBlank()) {
            throw new IllegalArgumentException("the item op needs 'model'");
        }
        File modelFile = new File(modelPath);
        if (!modelFile.isFile()) {
            throw new IllegalArgumentException("model file not found: " + modelPath);
        }
        byte[] raw = MobOps.readFileOrNull(modelFile);
        if (raw == null) {
            throw new IllegalStateException("model file unreadable: " + modelPath);
        }

        List<Finding> findings = new ArrayList<>();
        boolean bom = raw.length >= 3 && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF;
        if (bom) {
            findings.add(new Finding("bom",
                    "the file starts with a UTF-8 byte-order mark; vanilla's JSON parser rejects it"
                            + " and the item renders as a missing model — re-export without BOM"));
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }

        JsonObject response = new JsonObject();
        response.addProperty("file", modelFile.getAbsolutePath());
        response.addProperty("name", MobOps.stripExtension(modelFile.getName()));

        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception malformed) {
            response.addProperty("status", "BROKEN");
            response.addProperty("summary", "the file is not valid JSON: " + malformed.getMessage());
            response.add("findings", findingsJson(findings));
            return response;
        }

        String textureRootText = MobOps.Json.str(request, "textureRoot", null);
        File textureRoot = textureRootText == null || textureRootText.isBlank()
                ? null : new File(textureRootText);

        response.addProperty("parent", strOrNull(root, "parent"));
        response.addProperty("guiLight", strOrNull(root, "gui_light"));

        JsonObject display = root.has("display") && root.get("display").isJsonObject()
                ? root.getAsJsonObject("display") : null;
        response.add("display", display);

        int textureWidth = 16;
        int textureHeight = 16;
        if (root.has("texture_size") && root.get("texture_size").isJsonArray()) {
            JsonArray size = root.getAsJsonArray("texture_size");
            if (size.size() >= 2) {
                textureWidth = Math.max(1, size.get(0).getAsInt());
                textureHeight = Math.max(1, size.get(1).getAsInt());
            }
        }
        JsonObject sizeJson = new JsonObject();
        sizeJson.addProperty("width", textureWidth);
        sizeJson.addProperty("height", textureHeight);
        response.add("textureSize", sizeJson);

        // ---- textures map: #chain resolution + on-disk PNG lookup ----------
        Map<String, String> map = new LinkedHashMap<>();
        if (root.has("textures") && root.get("textures").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("textures").entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    map.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }

        JsonObject texturesJson = new JsonObject();
        Map<String, Resolved> resolutions = new LinkedHashMap<>();
        Map<String, Png> pngCache = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            Resolved resolved = resolveChain(entry.getKey(), map);
            resolutions.put(entry.getKey(), resolved);
            if (!resolved.ok) {
                findings.add(new Finding("texture_chain", entry.getKey() + ": " + resolved.error));
            }
            JsonObject tj = new JsonObject();
            tj.addProperty("target", entry.getValue());
            tj.addProperty("resolved", resolved.value);
            tj.addProperty("ok", resolved.ok);
            if (resolved.error != null) {
                tj.addProperty("error", resolved.error);
            }
            if (resolved.ok && resolved.value != null) {
                Png png = pngCache.computeIfAbsent(resolved.value,
                        v -> findPng(v, modelFile.getParentFile(), textureRoot));
                tj.add("png", pngJson(png, findings));
            }
            texturesJson.add(entry.getKey(), tj);
        }
        response.add("textures", texturesJson);

        // ---- elements -------------------------------------------------------
        JsonArray elementsJson = new JsonArray();
        int elementCount = 0;
        int outOfWindow = 0;
        if (root.has("elements") && root.get("elements").isJsonArray()) {
            for (JsonElement elementElement : root.getAsJsonArray("elements")) {
                if (!elementElement.isJsonObject()) {
                    findings.add(new Finding("element", "a 'elements' entry is not an object"));
                    continue;
                }
                JsonObject element = elementElement.getAsJsonObject();
                elementCount++;
                String label = strOrNull(element, "name");
                if (label == null) {
                    label = strOrNull(element, "__comment");
                }
                if (label == null) {
                    label = "element[" + (elementCount - 1) + "]";
                }
                ElementResult result = elementJson(element, label, elementCount - 1,
                        resolutions, textureWidth, textureHeight);
                outOfWindow += result.outOfWindow;
                findings.addAll(result.findings);
                elementsJson.add(result.json);
            }
        }
        response.addProperty("elementCount", elementCount);
        response.addProperty("outOfWindow", outOfWindow);
        response.add("elements", elementsJson);
        response.add("findings", findingsJson(findings));

        if (elementCount == 0 && root.has("parent")) {
            response.addProperty("status", "OK");
            response.addProperty("summary", "no elements of its own; the client renders this through"
                    + " its parent model '" + root.get("parent").getAsString() + "'");
            return response;
        }
        if (outOfWindow > 0) {
            response.addProperty("status", "BROKEN");
            response.addProperty("summary", outOfWindow + " element corner(s) outside the client's"
                    + " −16..32 px window: the client clamps them and the cube collapses into a"
                    + " missing-texture box");
            return response;
        }
        if (!findings.isEmpty()) {
            response.addProperty("status", "REPAIRED");
            response.addProperty("summary", "renders, with " + findings.size() + " finding(s): "
                    + findings.get(0).message);
            return response;
        }
        response.addProperty("status", "OK");
        response.addProperty("summary", elementCount + " element(s), every texture resolved");
        return response;
    }

    // ------------------------------------------------------------------
    // Elements
    // ------------------------------------------------------------------

    private static final class ElementResult {
        final JsonObject json;
        final List<Finding> findings = new ArrayList<>();
        int outOfWindow;

        ElementResult(JsonObject json) {
            this.json = json;
        }
    }

    private static ElementResult elementJson(JsonObject element, String label, int index,
                                             Map<String, Resolved> resolutions,
                                             int textureWidth, int textureHeight) {
        ElementResult result = new ElementResult(new JsonObject());
        JsonObject json = result.json;
        json.addProperty("index", index);
        json.addProperty("name", label);

        float[] from = float3(element, "from", null);
        float[] to = float3(element, "to", null);
        if (from == null || to == null) {
            result.findings.add(new Finding("element_bounds",
                    label + " has no " + (from == null ? "'from'" : "'to'") + " array;"
                            + " the client drops the element entirely"));
            json.addProperty("inWindow", false);
            return result;
        }
        json.add("from", floats(from));
        json.add("to", floats(to));

        boolean inWindow = inWindow(from) && inWindow(to);
        json.addProperty("inWindow", inWindow);
        if (!inWindow) {
            result.outOfWindow++;
            result.findings.add(new Finding("out_of_window",
                    label + " spans outside the client's −16..32 px window (from "
                            + bounds(from) + " to " + bounds(to) + "); the client clamps it and"
                            + " the cube collapses into a missing-texture box"));
        }
        for (int axis = 0; axis < 3; axis++) {
            if (to[axis] <= from[axis]) {
                result.findings.add(new Finding("element_extent",
                        label + " has a zero or negative extent on "
                        + new String[] {"x", "y", "z"}[axis] + "; the client renders nothing"));
            }
        }

        if (element.has("rotation") && element.get("rotation").isJsonObject()) {
            JsonObject rotation = element.getAsJsonObject("rotation");
            JsonObject rj = new JsonObject();
            String axis = strOrNull(rotation, "axis");
            rj.addProperty("axis", axis);
            float angle = num(rotation, "angle", 0.0f);
            rj.addProperty("angle", angle);
            float[] origin = float3(rotation, "origin", new float[] {0.0f, 0.0f, 0.0f});
            rj.add("origin", floats(origin));
            json.add("rotation", rj);
            if (!"x".equals(axis) && !"y".equals(axis) && !"z".equals(axis)) {
                result.findings.add(new Finding("element_rotation",
                        label + " rotation axis '" + axis + "' is not x/y/z"));
            }
            boolean knownAngle = false;
            for (float allowed : new float[] {-45.0f, -22.5f, 0.0f, 22.5f, 45.0f}) {
                if (Math.abs(angle - allowed) < 1.0e-4f) {
                    knownAngle = true;
                    break;
                }
            }
            if (!knownAngle) {
                result.findings.add(new Finding("element_rotation",
                        label + " rotation angle " + angle + " is not one of the client's"
                                + " −45/−22.5/0/22.5/45; the model fails to load"));
            }
        }

        JsonObject facesJson = new JsonObject();
        if (element.has("faces") && element.get("faces").isJsonObject()) {
            for (Map.Entry<String, JsonElement> faceEntry : element.getAsJsonObject("faces").entrySet()) {
                if (!faceEntry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject face = faceEntry.getValue().getAsJsonObject();
                JsonObject fj = new JsonObject();
                if (face.has("uv") && face.get("uv").isJsonArray()
                        && face.getAsJsonArray("uv").size() >= 4) {
                    JsonArray uv = face.getAsJsonArray("uv");
                    float u1 = uv.get(0).getAsFloat();
                    float v1 = uv.get(1).getAsFloat();
                    float u2 = uv.get(2).getAsFloat();
                    float v2 = uv.get(3).getAsFloat();
                    fj.add("uv", floats(new float[] {u1, v1, u2, v2}));
                    boolean uvInRange = u1 >= -1.0e-3f && v1 >= -1.0e-3f
                            && u2 <= textureWidth + 1.0e-3f && v2 <= textureHeight + 1.0e-3f;
                    fj.addProperty("uvInRange", uvInRange);
                    if (!uvInRange) {
                        result.findings.add(new Finding("uv_range",
                                label + " face '" + faceEntry.getKey() + "' uv [" + u1 + "," + v1
                                        + "," + u2 + "," + v2 + "] falls outside the 0.."
                                        + textureWidth + "x0.." + textureHeight
                                        + " texture; the client samples edge or garbage pixels"));
                    }
                }
                int faceRotation = (int) Math.round(num(face, "rotation", 0.0f));
                fj.addProperty("rotation", faceRotation);
                if (faceRotation % 90 != 0) {
                    result.findings.add(new Finding("face_rotation",
                            label + " face '" + faceEntry.getKey() + "' uv rotation " + faceRotation
                                    + " is not a multiple of 90; the client ignores it"));
                }
                String reference = strOrNull(face, "texture");
                fj.addProperty("texture", reference);
                if (reference != null && reference.startsWith("#")) {
                    Resolved resolved = resolutions.get(reference.substring(1));
                    if (resolved != null && resolved.ok) {
                        fj.addProperty("resolved", resolved.value);
                        fj.addProperty("resolvedOk", true);
                    } else {
                        fj.addProperty("resolvedOk", false);
                        if (resolved != null && resolved.error != null) {
                            result.findings.add(new Finding("texture_chain",
                                    label + " face '" + faceEntry.getKey() + "': " + resolved.error));
                        }
                    }
                } else if (reference != null && !reference.isBlank()) {
                    fj.addProperty("resolved", reference);
                    fj.addProperty("resolvedOk", false);
                    result.findings.add(new Finding("texture_chain",
                            label + " face '" + faceEntry.getKey()
                                    + "' texture '" + reference + "' is not a #reference;"
                                    + " the client only resolves face textures through the map"));
                } else {
                    fj.addProperty("resolvedOk", false);
                    result.findings.add(new Finding("texture_chain",
                            label + " face '" + faceEntry.getKey() + "' has no texture reference;"
                                    + " the client renders it with the missing texture"));
                }
                facesJson.add(faceEntry.getKey(), fj);
            }
        }
        json.add("faces", facesJson);
        return result;
    }

    // ------------------------------------------------------------------
    // Texture chains + PNG lookup
    // ------------------------------------------------------------------

    private static final class Resolved {
        final String value;
        final boolean ok;
        final String error;

        Resolved(String value, boolean ok, String error) {
            this.value = value;
            this.ok = ok;
            this.error = error;
        }
    }

    private static Resolved resolveChain(String key, Map<String, String> map) {
        String current = map.get(key);
        if (current == null) {
            return new Resolved(null, false, "texture key '" + key + "' is not defined");
        }
        int hops = 0;
        while (current.startsWith("#")) {
            if (++hops > MAX_CHAIN_HOPS) {
                return new Resolved(null, false, "reference chain from '" + key
                        + "' is longer than " + MAX_CHAIN_HOPS + " hops");
            }
            String next = map.get(current.substring(1));
            if (next == null) {
                return new Resolved(null, false, "reference chain from '" + key
                        + "' ends at the undefined key '" + current + "'");
            }
            current = next;
        }
        return new Resolved(current, true, null);
    }

    private static final class Png {
        final File file;
        final int width;
        final int height;
        final byte[] bytes;

        Png(File file, int width, int height, byte[] bytes) {
            this.file = file;
            this.width = width;
            this.height = height;
            this.bytes = bytes;
        }
    }

    /**
     * Basename lookup mirroring how the pack references textures: a value like
     * {@code mineplusgun:item/pistol} points at {@code pistol.png}. The model's
     * own folder wins, then the explicit texture root and its one-level
     * subfolders ({@code textures/item/…} layouts).
     */
    private static Png findPng(String value, File modelDir, File textureRoot) {
        String name = value.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.endsWith(".png")) {
            name = name.substring(0, name.length() - 4);
        }
        if (name.contains(":")) {
            name = name.substring(name.indexOf(':') + 1);
        }
        List<File> dirs = new ArrayList<>();
        if (modelDir != null) {
            dirs.add(modelDir);
        }
        if (textureRoot != null && textureRoot.isDirectory()) {
            dirs.add(textureRoot);
            File[] subs = textureRoot.listFiles(File::isDirectory);
            if (subs != null) {
                for (File sub : subs) {
                    dirs.add(sub);
                }
            }
        }
        for (String candidate : new String[] {name, name.toLowerCase(Locale.ROOT)}) {
            for (File dir : dirs) {
                File file = new File(dir, candidate + ".png");
                if (!file.isFile()) {
                    continue;
                }
                byte[] bytes = MobOps.readFileOrNull(file);
                if (bytes == null || !PngUtil.isPng(bytes)) {
                    continue;
                }
                int[] dims = PngUtil.dimensions(bytes);
                if (dims == null) {
                    continue;
                }
                return new Png(file, dims[0], dims[1], bytes);
            }
        }
        return null;
    }

    private static JsonObject pngJson(Png png, List<Finding> findings) {
        JsonObject pj = new JsonObject();
        if (png == null) {
            pj.addProperty("found", false);
            findings.add(new Finding("texture_file",
                    "the referenced PNG is not on disk next to the model, in the texture root or"
                            + " one folder below it — the pack ships a missing texture and the"
                            + " client renders a magenta checkerboard"));
            return pj;
        }
        pj.addProperty("found", true);
        pj.addProperty("path", png.file.getAbsolutePath());
        pj.addProperty("width", png.width);
        pj.addProperty("height", png.height);
        pj.addProperty("bytes", png.bytes.length);
        pj.addProperty("pngBase64", Base64.getEncoder().encodeToString(png.bytes));
        return pj;
    }

    // ------------------------------------------------------------------
    // Serialization helpers
    // ------------------------------------------------------------------

    private record Finding(String code, String message) {
    }

    private static JsonArray findingsJson(List<Finding> findings) {
        JsonArray array = new JsonArray();
        for (Finding finding : findings) {
            JsonObject fj = new JsonObject();
            fj.addProperty("code", finding.code);
            fj.addProperty("message", finding.message);
            array.add(fj);
        }
        return array;
    }

    private static String strOrNull(JsonObject o, String name) {
        return MobOps.Json.str(o, name, null);
    }

    private static float num(JsonObject o, String name, float fallback) {
        if (!o.has(name) || o.get(name).isJsonNull()) {
            return fallback;
        }
        try {
            return o.get(name).getAsFloat();
        } catch (Exception malformed) {
            return fallback;
        }
    }

    private static float[] float3(JsonObject o, String name, float[] fallback) {
        if (!o.has(name) || !o.get(name).isJsonArray() || o.getAsJsonArray(name).size() < 3) {
            return fallback;
        }
        JsonArray array = o.getAsJsonArray(name);
        return new float[] {array.get(0).getAsFloat(), array.get(1).getAsFloat(),
                array.get(2).getAsFloat()};
    }

    private static JsonArray floats(float[] values) {
        JsonArray array = new JsonArray();
        for (float value : values) {
            array.add(Float.isFinite(value) ? value : 0.0f);
        }
        return array;
    }

    private static boolean inWindow(float[] v) {
        for (float value : v) {
            if (value < WINDOW_MIN - 1.0e-4f || value > WINDOW_MAX + 1.0e-4f) {
                return false;
            }
        }
        return true;
    }

    private static String bounds(float[] v) {
        return "[" + v[0] + "," + v[1] + "," + v[2] + "]";
    }
}
