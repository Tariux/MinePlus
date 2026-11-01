package com.mineplus.devtools.assetlab;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mineplus.util.DebugLogger;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The daemon's {@code pack} op: read a compiled resource-pack artifact
 * ({@code mp-<hash>.zip} / {@code mineplus-resource-pack.zip}) straight off disk
 * with {@link ZipFile} — no new dependencies — so the tool shows the exact bytes
 * the client downloads instead of a re-compiled approximation.
 *
 * <p>Three shapes, all on one op so the dev server keeps one daemon:</p>
 * <pre>{@code
 * {"op":"pack","list":true}
 *   -> {"artifacts":[{"file","name","bytes","mtime","entryCount"}],"roots":[...],"errors":[...]}
 * {"op":"pack","file":"<abs .zip>","tree":true}
 *   -> {"entries":[{"path","bytes","size","kind"}],"models":[{"path","json",
 *       "ambientocclusion","elementShade","elementCount"}],"textures":[{"path","bytes"}],
 *       "packMcmeta":"<string>","mcmeta":"<string>","size":<zip bytes>,"packFormat":N}
 * {"op":"pack","file":"<abs .zip>","entry":"<zip entry path>"}
 *   -> {"path","kind","bytes","text"} for .json/.mcmeta/.lang,
 *      {"path","kind","bytes","pngBase64","width","height"} for .png,
 *      else {"path","kind","bytes","base64"}
 * }</pre>
 *
 * <p>The Node dev server may spell the mode
 * {@code {"action":"list"|"tree"|"entry"}} (and defaults to {@code action:"list"})
 * — both spellings are accepted, and an explicit {@code entry} always wins over a
 * defaulted action.</p>
 *
 * <p>Guards: {@code file} must end with {@code .zip} and exist and, whenever
 * {@code roots} is supplied, must canonicalize inside one of them — so the op can
 * never be pointed at an arbitrary archive. Every entry name is checked
 * zip-slip-style (absolute paths, drive letters, backslashes and {@code ..} or
 * {@code .} segments rejected) and only {@link ZipFile#getEntry} resolves it, so no
 * path arithmetic ever touches the filesystem.</p>
 */
final class PackOps {

    private PackOps() {
    }

    /** Models whose JSON is parsed for the tree view; the entry list stays exhaustive. */
    private static final int MAX_PARSED_MODELS = 500;

    /** Text bodies above this size come back truncated rather than bloating the pipe. */
    private static final int MAX_TEXT_BYTES = 2_000_000;

    /** Entries with no size in the central directory are measured up to this cap. */
    private static final int MAX_MEASURE_BYTES = 16_000_000;

    /** Zip artifacts are found by walking each root, bounded so a repo scan stays cheap. */
    private static final int MAX_WALK_DEPTH = 6;
    private static final int MAX_ARTIFACTS = 200;

    // ------------------------------------------------------------------
    // op
    // ------------------------------------------------------------------

    static JsonObject pack(JsonObject request) throws Exception {
        String mode = mode(request);
        JsonObject response = new JsonObject();
        if ("list".equals(mode)) {
            appendList(response, request);
            return response;
        }
        File file = validatedZip(request);
        try (ZipFile zip = new ZipFile(file)) {
            response.addProperty("file", file.getAbsolutePath());
            response.addProperty("name", file.getName());
            response.addProperty("size", file.length());
            response.addProperty("bytes", file.length());
            response.addProperty("mtime", file.lastModified());
            if ("tree".equals(mode)) {
                appendTree(response, zip);
            } else {
                appendEntry(response, zip, Json.str(request, "entry", null));
            }
        }
        return response;
    }

    /** {@code list} | {@code tree} | {@code entry}, from either spelling. */
    private static String mode(JsonObject request) {
        if (Json.str(request, "entry", null) != null) {
            return "entry";
        }
        if (Json.bool(request, "tree", false)) {
            return "tree";
        }
        if (Json.bool(request, "list", false)) {
            return "list";
        }
        String named = Json.str(request, "action", null);
        if (named == null) {
            named = Json.str(request, "mode", null);
        }
        if (named == null) {
            named = Json.str(request, "sub", null);
        }
        named = named == null ? "" : named.trim().toLowerCase(Locale.ROOT);
        switch (named) {
            case "entry":
            case "read":
            case "file":
                return "entry";
            case "tree":
            case "entries":
            case "list-entries":
                return "tree";
            default:
                return "list";
        }
    }

    // ------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------

    private static void appendList(JsonObject response, JsonObject request) {
        JsonArray artifacts = new JsonArray();
        JsonArray rootList = new JsonArray();
        JsonArray errors = new JsonArray();
        List<File> roots = roots(request);
        for (File root : roots) {
            rootList.add(root.getAbsolutePath());
        }
        Map<String, JsonObject> byPath = new LinkedHashMap<>();
        for (File root : roots) {
            walk(root, 0, byPath, errors);
        }
        List<JsonObject> sorted = new ArrayList<>(byPath.values());
        sorted.sort((a, b) -> Long.compare(b.get("mtime").getAsLong(), a.get("mtime").getAsLong()));
        for (JsonObject artifact : sorted) {
            artifacts.add(artifact);
        }
        response.add("artifacts", artifacts);
        response.add("roots", rootList);
        response.add("errors", errors);
        response.addProperty("artifactCount", artifacts.size());
    }

    private static void walk(File dir, int depth, Map<String, JsonObject> out, JsonArray errors) {
        if (depth > MAX_WALK_DEPTH || out.size() >= MAX_ARTIFACTS) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                walk(child, depth + 1, out, errors);
            } else if (child.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                out.put(safeKey(child), artifactJson(child, errors));
            }
        }
    }

    private static String safeKey(File file) {
        try {
            return file.getCanonicalPath().toLowerCase(Locale.ROOT);
        } catch (IOException unreadable) {
            return file.getAbsolutePath().toLowerCase(Locale.ROOT);
        }
    }

    private static JsonObject artifactJson(File file, JsonArray errors) {
        JsonObject json = new JsonObject();
        json.addProperty("file", file.getAbsolutePath());
        json.addProperty("name", file.getName());
        json.addProperty("bytes", file.length());
        json.addProperty("size", file.length());
        json.addProperty("mtime", file.lastModified());
        int entryCount = -1;
        try (ZipFile zip = new ZipFile(file)) {
            entryCount = zip.size();
        } catch (Exception unreadable) {
            JsonObject error = new JsonObject();
            error.addProperty("file", file.getAbsolutePath());
            error.addProperty("error", unreadable.getClass().getSimpleName() + ": "
                    + unreadable.getMessage());
            errors.add(error);
        }
        json.addProperty("entryCount", entryCount);
        return json;
    }

    // ------------------------------------------------------------------
    // tree + entry
    // ------------------------------------------------------------------

    /** Validate the requested archive against the supplied roots before opening it. */
    private static File validatedZip(JsonObject request) throws IOException {
        String path = Json.str(request, "file", null);
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("the pack op needs 'file' (an absolute .zip path)");
        }
        if (!path.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("pack 'file' must end with .zip: " + path);
        }
        File candidate = new File(path);
        if (!candidate.isFile()) {
            throw new FileNotFoundException("pack artifact not found: " + path);
        }
        List<File> roots = roots(request);
        if (roots.isEmpty()) {
            // No roots supplied (a direct daemon call): the suffix + existence checks are
            // all there is to enforce, so say so rather than pretend there was a guard.
            DebugLogger.warning("asset-lab: pack op 'file' validated without roots: " + path);
            return candidate;
        }
        String target = candidate.getCanonicalPath();
        for (File root : roots) {
            String prefix = root.getCanonicalPath();
            if (target.equalsIgnoreCase(prefix)) {
                return candidate;
            }
            if (target.regionMatches(true, 0, prefix + File.separatorChar, 0,
                    prefix.length() + 1)) {
                return candidate;
            }
        }
        throw new SecurityException("pack file is outside every provided root: " + target);
    }

    /** Requested roots, defaulting to nothing when absent (never a blind filesystem walk). */
    private static List<File> roots(JsonObject request) {
        List<File> out = new ArrayList<>();
        JsonElement element = request.get("roots");
        if (element == null || !element.isJsonArray()) {
            return out;
        }
        for (JsonElement raw : element.getAsJsonArray()) {
            if (!raw.isJsonPrimitive()) {
                continue;
            }
            File root = new File(raw.getAsString());
            if (root.isDirectory()) {
                out.add(root);
            }
        }
        return out;
    }

    private static void appendTree(JsonObject response, ZipFile zip) {
        JsonArray entries = new JsonArray();
        JsonArray models = new JsonArray();
        JsonArray textures = new JsonArray();
        String mcmeta = null;
        long uncompressed = 0;
        int parsedModels = 0;
        Enumeration<? extends ZipEntry> enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !isSafeEntryName(name)) {
                continue;
            }
            String kind = kindOf(name);
            long bytes = entry.getSize();
            if (bytes < 0) {
                bytes = readAll(zip, entry, MAX_MEASURE_BYTES).length;
            }
            uncompressed += bytes;
            JsonObject row = new JsonObject();
            row.addProperty("path", name);
            row.addProperty("bytes", bytes);
            row.addProperty("size", bytes);
            row.addProperty("kind", kind);
            row.addProperty("stored", entry.getMethod() == ZipEntry.STORED);
            entries.add(row);
            if ("texture".equals(kind)) {
                JsonObject texture = new JsonObject();
                texture.addProperty("path", name);
                texture.addProperty("bytes", bytes);
                textures.add(texture);
            } else if ("model".equals(kind) && parsedModels < MAX_PARSED_MODELS) {
                parsedModels++;
                JsonObject model = modelSummary(zip, entry, name);
                if (model != null) {
                    models.add(model);
                }
            }
            if (mcmeta == null && name.equals("pack.mcmeta")) {
                mcmeta = new String(readAll(zip, entry, MAX_TEXT_BYTES), StandardCharsets.UTF_8);
            }
        }
        response.add("entries", entries);
        response.add("models", models);
        response.add("textures", textures);
        response.addProperty("entryCount", entries.size());
        response.addProperty("modelCount", models.size());
        response.addProperty("textureCount", textures.size());
        response.addProperty("uncompressedBytes", uncompressed);
        response.addProperty("packMcmeta", mcmeta == null ? "" : mcmeta);
        response.addProperty("mcmeta", mcmeta == null ? "" : mcmeta);
        response.addProperty("packFormat", packFormat(mcmeta));
    }

    /** {@code pack.pack_format} from pack.mcmeta, or -1 when absent or malformed. */
    private static int packFormat(String mcmeta) {
        if (mcmeta == null || mcmeta.isBlank()) {
            return -1;
        }
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(mcmeta).getAsJsonObject();
            if (root.has("pack") && root.get("pack").isJsonObject()) {
                JsonObject pack = root.getAsJsonObject("pack");
                if (pack.has("pack_format") && !pack.get("pack_format").isJsonNull()) {
                    return pack.get("pack_format").getAsInt();
                }
            }
        } catch (Exception malformed) {
            // A malformed mcmeta is still reported as raw text; nothing else to say.
        }
        return -1;
    }

    /** The shading verdicts read out of the shipped model JSON, never hardcoded. */
    private static JsonObject modelSummary(ZipFile zip, ZipEntry entry, String name) {
        try {
            String json = new String(readAll(zip, entry, MAX_TEXT_BYTES), StandardCharsets.UTF_8);
            JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            JsonObject model = new JsonObject();
            model.addProperty("path", name);
            model.addProperty("json", json);
            model.addProperty("ambientocclusion", Json.flag(
                    root.has("ambientocclusion") ? root.get("ambientocclusion") : null, true));
            boolean elementShade = true;
            int elements = 0;
            if (root.has("elements") && root.get("elements").isJsonArray()) {
                JsonArray array = root.getAsJsonArray("elements");
                elements = array.size();
                if (elements > 0) {
                    elementShade = false;
                    for (JsonElement raw : array) {
                        if (!raw.isJsonObject()) {
                            continue;
                        }
                        JsonObject element = raw.getAsJsonObject();
                        if (!element.has("shade") || Json.flag(element.get("shade"), true)) {
                            elementShade = true;
                            break;
                        }
                    }
                }
            }
            model.addProperty("elementShade", elementShade);
            model.addProperty("elementCount", elements);
            return model;
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static void appendEntry(JsonObject response, ZipFile zip, String requested)
            throws IOException {
        if (requested == null || requested.isBlank()) {
            throw new IllegalArgumentException("the pack entry read needs 'entry'");
        }
        String name = requested.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (!isSafeEntryName(name)) {
            throw new SecurityException("rejected zip entry path: " + requested);
        }
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            throw new FileNotFoundException("no such zip entry: " + name);
        }
        byte[] body = readAll(zip, entry, -1);
        response.addProperty("path", name);
        response.addProperty("kind", kindOf(name));
        response.addProperty("bytes", body.length);
        if (name.toLowerCase(Locale.ROOT).endsWith(".png")) {
            response.addProperty("pngBase64", Base64.getEncoder().encodeToString(body));
            int[] dims = pngSize(body);
            if (dims != null) {
                response.addProperty("width", dims[0]);
                response.addProperty("height", dims[1]);
            }
            return;
        }
        if (isTextual(name)) {
            int capped = Math.min(body.length, MAX_TEXT_BYTES);
            response.addProperty("text", new String(body, 0, capped, StandardCharsets.UTF_8));
            response.addProperty("truncated", body.length > capped);
            return;
        }
        response.addProperty("base64", Base64.getEncoder().encodeToString(body));
    }

    /**
     * Zip-slip guard: no absolute path, no drive letter, no backslash separator and no
     * {@code ..}/{@code .} segment, so an entry name can never resolve outside the
     * archive's logical root even if a caller later uses it as a file path.
     */
    private static boolean isSafeEntryName(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
            return false;
        }
        if (name.startsWith("/") || name.startsWith("\\") || name.contains(":")) {
            return false;
        }
        if (name.contains("\\")) {
            return false;
        }
        for (String segment : name.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                return false;
            }
        }
        return true;
    }

    /** {@code model | texture | blockstate | meta | other}, the browser's grouping key. */
    private static String kindOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mcmeta")) {
            return "meta";
        }
        if (lower.endsWith(".png")) {
            return lower.contains("/textures/") || lower.startsWith("textures/")
                    ? "texture" : "other";
        }
        if (lower.endsWith(".json")) {
            if (lower.contains("/models/") || lower.startsWith("models/")
                    || lower.contains("/items/") || lower.startsWith("items/")) {
                return "model";
            }
            if (lower.contains("/blockstates/") || lower.startsWith("blockstates/")) {
                return "blockstate";
            }
            return "other";
        }
        return "other";
    }

    private static boolean isTextual(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".json") || lower.endsWith(".mcmeta") || lower.endsWith(".lang")
                || lower.endsWith(".txt") || lower.endsWith(".glsl") || lower.endsWith(".fsh")
                || lower.endsWith(".vsh");
    }

    /** Full entry bytes, or the first {@code limit} of them ({@code limit < 0} = all). */
    private static byte[] readAll(ZipFile zip, ZipEntry entry, int limit) {
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                total += read;
                if (limit >= 0 && total >= limit) {
                    break;
                }
            }
            return out.toByteArray();
        } catch (IOException failure) {
            DebugLogger.warning("asset-lab: unreadable zip entry '" + entry.getName() + "': "
                    + failure.getMessage());
            return new byte[0];
        }
    }

    /** PNG width/height straight from the IHDR bytes (no ImageIO, no half-decoded zip). */
    private static int[] pngSize(byte[] png) {
        if (png.length < 24 || png[0] != (byte) 0x89 || png[1] != 'P' || png[2] != 'N'
                || png[3] != 'G') {
            return null;
        }
        int width = (png[16] & 0xFF) << 24 | (png[17] & 0xFF) << 16 | (png[18] & 0xFF) << 8
                | png[19] & 0xFF;
        int height = (png[20] & 0xFF) << 24 | (png[21] & 0xFF) << 16 | (png[22] & 0xFF) << 8
                | png[23] & 0xFF;
        return new int[] {width, height};
    }

    /** Kept for callers that only want the artifact list shape (audit, future ops). */
    static JsonArray artifacts(JsonObject request) {
        JsonObject response = new JsonObject();
        appendList(response, request);
        return response.getAsJsonArray("artifacts");
    }
}
