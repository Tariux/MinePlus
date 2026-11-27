package com.mineplus.infrastructure.core.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.mineplus.infrastructure.render.RenderMode;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Reads {@code blocks/**&#47;*.json} into the {@link BlockRegistry}. One bad file
 * never stops the load: every problem is reported, and only definitions with an
 * error are dropped.
 */
public final class BlockDefinitionLoader {

    public static final String FOLDER = "blocks";
    public static final String MODELS_FOLDER = "models";

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");
    private static final Set<String> KNOWN_KEYS = new HashSet<>(Arrays.asList(
            "id", "displayName", "visuals", "collision", "occupancy", "breaking",
            "properties", "placement", "item", "events", "state", "tooltip"));
    private static final Set<String> COLLISION_MATERIALS = new HashSet<>(Arrays.asList(
            "barrier", "slime", "honey", "cobweb", "none"));
    private static final Set<String> TOOL_TYPES = new HashSet<>(Arrays.asList(
            "any", "pickaxe", "axe", "shovel", "hoe", "shears", "sword"));
    private static final Set<String> TOOL_TIERS = new HashSet<>(Arrays.asList(
            "any", "wooden", "stone", "copper", "iron", "golden", "diamond", "netherite"));
    private static final Set<String> SUPPORTS = new HashSet<>(Arrays.asList(
            "any", "ground", "wall", "ceiling"));
    private static final Set<String> RENDER_KEYS = renderKeys();

    private final BlockRegistry registry;
    private final Consumer<String> warn;

    public BlockDefinitionLoader(BlockRegistry registry, Consumer<String> warn) {
        this.registry = registry;
        this.warn = warn == null ? message -> { } : warn;
    }

    public BlockLoadReport reload(File dataFolder) {
        return load(new File(dataFolder, FOLDER), new File(dataFolder, MODELS_FOLDER));
    }

    public BlockLoadReport load(File blocksFolder, File modelsFolder) {
        List<BlockLoadReport.Problem> problems = new ArrayList<>();
        registry.clear();

        if (!blocksFolder.exists() && !blocksFolder.mkdirs()) {
            problems.add(problem(blocksFolder, BlockLoadReport.Severity.ERROR,
                    "cannot create the blocks folder"));
            return new BlockLoadReport(problems, 0);
        }

        List<File> files = new ArrayList<>();
        collect(blocksFolder, files);
        files.sort((a, b) -> a.getPath().compareToIgnoreCase(b.getPath()));

        int accepted = 0;
        for (File file : files) {
            List<BlockLoadReport.Problem> fileProblems = new ArrayList<>();
            BlockDefinition definition = parse(file, blocksFolder, modelsFolder, fileProblems);
            boolean failed = fileProblems.stream()
                    .anyMatch(p -> p.severity() == BlockLoadReport.Severity.ERROR);
            if (definition != null && !failed) {
                BlockDefinition clash = registry.register(definition);
                if (clash != null) {
                    fileProblems.add(problem(file, BlockLoadReport.Severity.ERROR,
                            "duplicate id " + definition.id() + " (already defined by "
                                    + relative(clash.source()) + ")"));
                    failed = true;
                }
            }
            if (!failed && definition != null) {
                accepted++;
            }
            problems.addAll(fileProblems);
        }

        for (BlockLoadReport.Problem p : problems) {
            if (p.severity() == BlockLoadReport.Severity.ERROR) {
                warn.accept("blocks: " + p.message() + " [" + relative(p.file()) + "]");
            }
        }
        return new BlockLoadReport(problems, accepted);
    }

    private BlockDefinition parse(
            File file,
            File blocksFolder,
            File modelsFolder,
            List<BlockLoadReport.Problem> problems
    ) {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                problems.add(problem(file, BlockLoadReport.Severity.ERROR, "top level must be an object"));
                return null;
            }
            root = parsed.getAsJsonObject();
        } catch (JsonSyntaxException badJson) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                    "invalid JSON: " + badJson.getMessage()));
            return null;
        } catch (IOException unreadable) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                    "cannot read: " + unreadable.getMessage()));
            return null;
        }

        String namespace = namespaceOf(file, blocksFolder);
        String rawId = string(root, "id", stem(file));
        String id = rawId.indexOf(':') >= 0 ? rawId : namespace + ":" + rawId;
        if (!validId(id)) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                    "id must be lower_snake 'namespace:path', got '" + id + "'"));
            return null;
        }
        if (id.indexOf(':') >= 0 && !id.substring(0, id.indexOf(':')).equals(namespace)) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                    "id namespace '" + id.substring(0, id.indexOf(':'))
                            + "' differs from folder namespace '" + namespace + "'"));
            return null;
        }

        for (String key : root.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                warn.accept("blocks: unknown key '" + key + "' in " + relative(file));
            }
        }

        JsonObject visuals = object(root, "visuals");
        String model = string(visuals, "model", "");
        if (model.isBlank()) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR, "visuals.model is required"));
            return null;
        }
        if (modelsFolder != null && !modelExists(modelsFolder, model)) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                    "model '" + model + "' not found under " + MODELS_FOLDER + "/"));
            return null;
        }

        List<String> autoplay = stringList(object(visuals, "animations"), "autoplay");
        String renderKey = string(visuals, "renderMode", "auto");
        RenderMode renderMode = resolveRenderMode(renderKey, autoplay, file, problems);
        if (renderMode == null) {
            return null;
        }

        JsonObject rotation = object(visuals, "rotation");
        int snap = integer(rotation, "snap", 90);
        if (snap <= 0 || 360 % snap != 0) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                    "visuals.rotation.snap must divide 360, got " + snap));
            return null;
        }

        int light = clamp(integer(visuals, "light", 0), 0, 15);
        if (light > 0) {
            problems.add(problem(file, BlockLoadReport.Severity.WARN,
                    "light " + light + " is clamped to 0: the note-block carrier cannot emit light"));
            light = 0;
        }

        JsonObject collision = object(root, "collision");
        BlockDefinition.Collision collisionMode = BlockDefinition.Collision.parse(string(collision, "mode", "geometry"));
        if (collisionMode == null) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                    "collision.mode must be geometry, surface, single, box or none"));
            return null;
        }
        String material = string(collision, "material", "barrier").toLowerCase(Locale.ROOT);
        if (!COLLISION_MATERIALS.contains(material)) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                    "collision.material must be barrier, slime, honey, cobweb or none"));
            return null;
        }
        if (collisionMode != BlockDefinition.Collision.NONE && snap % 90 != 0) {
            problems.add(problem(file, BlockLoadReport.Severity.WARN,
                    "collidable blocks should snap rotation to 90 degrees, got " + snap));
        }
        double[] box = collisionBox(collision, file, problems);
        if (box == null && collisionMode == BlockDefinition.Collision.BOX) {
            return null;
        }

        JsonObject occupancy = object(root, "occupancy");
        int width = integer(occupancy, "width", 1);
        int height = integer(occupancy, "height", 1);
        int depth = integer(occupancy, "depth", 1);
        if (outOfRange(width) || outOfRange(height) || outOfRange(depth)) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                    "occupancy width/height/depth must be 1..32"));
            return null;
        }

        BlockDefinition.Breaking breaking = breaking(object(root, "breaking"), file, problems);
        if (breaking == null) {
            return null;
        }
        BlockDefinition.Placement placement = placement(object(root, "placement"), file, problems);
        if (placement == null) {
            return null;
        }

        JsonObject item = object(root, "item");
        String itemMaterial = string(item, "material", "PAPER").toUpperCase(Locale.ROOT);
        if (org.bukkit.Material.matchMaterial(itemMaterial) == null) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                    "item.material '" + itemMaterial + "' is not a Bukkit material"));
            return null;
        }
        String itemModel = string(item, "model", model);

        JsonObject properties = object(root, "properties");
        boolean waterloggable = bool(properties, "waterloggable", false);
        boolean flammable = bool(properties, "flammable", false);
        BlockDefinition.Events events = events(root, file, problems);

        return new BlockDefinition(
                id,
                string(root, "displayName", pretty(stem(file))),
                file,
                model,
                renderMode,
                autoplay,
                snap,
                light,
                collisionMode,
                material,
                box,
                width,
                height,
                depth,
                breaking,
                placement,
                itemMaterial,
                itemModel,
                events,
                waterloggable,
                flammable);
    }

    private RenderMode resolveRenderMode(
            String key,
            List<String> autoplay,
            File file,
            List<BlockLoadReport.Problem> problems
    ) {
        String normalized = key.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace('+', '_').replace(' ', '_');
        if (normalized.equals("auto")) {
            return autoplay.isEmpty() ? RenderMode.PACK_BLOCK : RenderMode.VIRTUAL;
        }
        if (!RENDER_KEYS.contains(normalized)) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                    "visuals.renderMode '" + key + "' is not a known render mode"));
            return null;
        }
        return RenderMode.fromKey(key);
    }

    private BlockDefinition.Events events(
            JsonObject root,
            File file,
            List<BlockLoadReport.Problem> problems
    ) {
        JsonObject events = object(root, "events");
        return new BlockDefinition.Events(
                actions(events, "onPlace", file, problems),
                actions(events, "onBreak", file, problems),
                actions(events, "onInteract", file, problems));
    }

    private List<BlockAction> actions(
            JsonObject events,
            String key,
            File file,
            List<BlockLoadReport.Problem> problems
    ) {
        List<BlockAction> parsed = new ArrayList<>();
        for (String raw : stringList(events, key)) {
            BlockAction action = BlockAction.parse(raw);
            if (action.kind() == BlockAction.Kind.UNKNOWN) {
                problems.add(problem(file, BlockLoadReport.Severity.WARN,
                        "events." + key + " entry '" + raw
                                + "' is not message:/sound:/command:, ignored"));
                continue;
            }
            parsed.add(action);
        }
        return parsed;
    }

    private BlockDefinition.Breaking breaking(
            JsonObject breaking,
            File file,
            List<BlockLoadReport.Problem> problems
    ) {
        double hardness = number(breaking, "hardness", 1.0);
        if (!Double.isFinite(hardness) || hardness < 0.0 || hardness > 3600.0) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                    "breaking.hardness must be 0..3600 seconds"));
            return null;
        }
        JsonObject tool = object(breaking, "tool");
        String type = string(tool, "type", "any").toLowerCase(Locale.ROOT);
        String tier = string(tool, "tier", "any").toLowerCase(Locale.ROOT);
        if (!TOOL_TYPES.contains(type)) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                    "breaking.tool.type '" + type + "' is not supported"));
            return null;
        }
        if (!TOOL_TIERS.contains(tier)) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                    "breaking.tool.tier '" + tier + "' is not supported"));
            return null;
        }
        int xp = clamp(integer(breaking, "xp", 0), 0, 100);

        List<BlockDefinition.Drop> drops = new ArrayList<>();
        for (JsonObject drop : objectList(breaking, "drops")) {
            String item = string(drop, "item", "");
            if (item.isBlank()) {
                problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                        "breaking.drops entries need an item"));
                return null;
            }
            int count = Math.max(1, integer(drop, "count", 1));
            drops.add(new BlockDefinition.Drop(item, count, count,
                    number(drop, "chance", 1.0)));
        }
        return new BlockDefinition.Breaking(
                hardness, new BlockDefinition.Tool(type, tier), drops, xp);
    }

    private BlockDefinition.Placement placement(
            JsonObject placement,
            File file,
            List<BlockLoadReport.Problem> problems
    ) {
        String support = string(placement, "support", "any").toLowerCase(Locale.ROOT);
        if (!SUPPORTS.contains(support)) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                    "placement.support must be any, ground, wall or ceiling"));
            return null;
        }
        List<String> replaceable = new ArrayList<>();
        for (String entry : stringList(placement, "replaceable")) {
            replaceable.add(entry.toLowerCase(Locale.ROOT));
        }
        if (replaceable.isEmpty()) {
            replaceable.add("air");
            replaceable.add("water");
        }
        return new BlockDefinition.Placement(support, replaceable,
                bool(placement, "spaceCheck", true));
    }

    private double[] collisionBox(
            JsonObject collision,
            File file,
            List<BlockLoadReport.Problem> problems
    ) {
        JsonArray array = array(collision, "box");
        if (array == null) {
            return null;
        }
        double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                        "collision.box entries must be numbers"));
                return null;
            }
            values[i] = element.getAsDouble();
            if (!Double.isFinite(values[i])) {
                problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                        "collision.box values must be finite"));
                return null;
            }
        }
        if (values.length < 2 || values.length % 2 != 0) {
            problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                    "collision.box needs min/max pairs"));
            return null;
        }
        for (int axis = 0; axis < values.length / 2; axis++) {
            if (values[axis * 2] >= values[axis * 2 + 1]) {
                problems.add(problem(file, BlockLoadReport.Severity.ERROR,
                        "collision.box pair " + axis + " has min >= max"));
                return null;
            }
        }
        return values;
    }

    private static boolean modelExists(File modelsFolder, String model) {
        String relative = model.endsWith(".bbmodel") ? model : model + ".bbmodel";
        File direct = new File(modelsFolder, relative.replace('/', File.separatorChar));
        return direct.isFile();
    }

    private static boolean validId(String id) {
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                return false;
            }
            if (c == ' ') {
                return false;
            }
        }
        return NAMESPACE.matcher(id.substring(0, colon)).matches()
                && PATH.matcher(id.substring(colon + 1)).matches();
    }

    private static boolean outOfRange(int value) {
        return value < 1 || value > 32;
    }

    private static String namespaceOf(File file, File blocksFolder) {
        String relative;
        try {
            relative = blocksFolder.toPath().toAbsolutePath()
                    .relativize(file.toPath().toAbsolutePath()).toString();
        } catch (IllegalArgumentException notInside) {
            relative = file.getName();
        }
        relative = relative.replace(File.separatorChar, '/');
        int slash = relative.indexOf('/');
        return slash > 0 ? relative.substring(0, slash) : "mineplus";
    }

    private static String relative(File file) {
        return file == null ? "" : file.getName();
    }

    private static String stem(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
    }

    private static String pretty(String stem) {
        String[] parts = stem.split("[_-]");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private static void collect(File folder, List<File> out) {
        File[] children = folder.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, out);
            } else if (child.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
                out.add(child);
            }
        }
    }

    private static BlockLoadReport.Problem problem(File file, BlockLoadReport.Severity severity, String message) {
        return new BlockLoadReport.Problem(file, severity, message);
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return new JsonObject();
        }
        return parent.getAsJsonObject(key);
    }

    private static JsonArray array(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) {
            return null;
        }
        return parent.getAsJsonArray(key);
    }

    private static String string(JsonObject parent, String key, String fallback) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            String value = parent.get(key).getAsString();
            return value == null || value.isBlank() ? fallback : value.trim();
        } catch (RuntimeException notAString) {
            return fallback;
        }
    }

    private static double number(JsonObject parent, String key, double fallback) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return parent.get(key).getAsDouble();
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    private static int integer(JsonObject parent, String key, int fallback) {
        return (int) Math.round(number(parent, key, fallback));
    }

    private static boolean bool(JsonObject parent, String key, boolean fallback) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return parent.get(key).getAsBoolean();
        } catch (RuntimeException notABoolean) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static List<String> stringList(JsonObject parent, String key) {
        JsonArray array = array(parent, key);
        List<String> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                String value = element.getAsString();
                if (value != null && !value.isBlank()) {
                    out.add(value.trim());
                }
            }
        }
        return out;
    }

    private static List<JsonObject> objectList(JsonObject parent, String key) {
        JsonArray array = array(parent, key);
        List<JsonObject> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                out.add(element.getAsJsonObject());
            }
        }
        return out;
    }

    private static Set<String> renderKeys() {
        Set<String> keys = new HashSet<>();
        for (RenderMode mode : RenderMode.values()) {
            keys.add(mode.key());
        }
        keys.add("block");
        keys.add("display");
        keys.add("texel");
        keys.add("pack_block");
        keys.add("hybrid");
        return keys;
    }
}
