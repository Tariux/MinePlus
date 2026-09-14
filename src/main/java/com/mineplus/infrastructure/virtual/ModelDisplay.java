package com.mineplus.infrastructure.virtual;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.joml.Vector3f;

/**
 * Authored Blockbench {@code display} transforms for a model: one entry per
 * vanilla display context ({@code firstperson_righthand}, {@code gui},
 * {@code ground}, ...) mapping to the vanilla rotation/translation/scale
 * triple. Imported verbatim from a {@code .bbmodel} and emitted for item
 * models, so a weapon keeps the first-person pose its author authored instead
 * of the generic item defaults.
 *
 * <p>Immutable. Context names are lowercased; an absent context is simply not
 * present, and {@link #isEmpty()} lets callers fall back to the vanilla
 * defaults. {@link #merge(ModelDisplay)} expresses the override precedence
 * (a meta-file {@code display} wins over the bbmodel's own).
 */
public final class ModelDisplay {

    /** One context's transform. Rotation is degrees, translation is in pixels. */
    public record Transform(Vector3f rotation, Vector3f translation, Vector3f scale) {

        public Transform {
            rotation = rotation == null ? new Vector3f() : new Vector3f(rotation);
            translation = translation == null ? new Vector3f() : new Vector3f(translation);
            // A zero or missing scale would collapse the model; treat it as
            // "unset" so an author who only wrote rotation still renders.
            if (scale == null || (scale.x == 0 && scale.y == 0 && scale.z == 0)) {
                scale = new Vector3f(1.0f, 1.0f, 1.0f);
            } else {
                scale = new Vector3f(scale);
            }
        }

        /** Identity transform (no rotation/translation, unit scale). */
        public static Transform identity() {
            return new Transform(new Vector3f(), new Vector3f(), new Vector3f(1.0f, 1.0f, 1.0f));
        }
    }

    public static final ModelDisplay EMPTY = new ModelDisplay(Map.of());

    private final Map<String, Transform> contexts;

    public ModelDisplay(Map<String, Transform> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            this.contexts = Map.of();
            return;
        }
        Map<String, Transform> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Transform> entry : contexts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) {
                copy.put(key, entry.getValue());
            }
        }
        this.contexts = Map.copyOf(copy);
    }

    /** Context names (lowercased) to transforms, in deterministic order. */
    public Map<String, Transform> contexts() {
        return contexts;
    }

    public boolean isEmpty() {
        return contexts.isEmpty();
    }

    /** The transform for one context (case-insensitive), or {@code null}. */
    public Transform context(String name) {
        if (name == null || name.isBlank() || contexts.isEmpty()) {
            return null;
        }
        return contexts.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * This display with {@code override}'s contexts taking precedence. Used to
     * apply a per-model {@code .meta.json} display over the imported one.
     */
    public ModelDisplay merge(ModelDisplay override) {
        if (override == null || override.isEmpty()) {
            return this;
        }
        if (contexts.isEmpty()) {
            return override;
        }
        Map<String, Transform> merged = new LinkedHashMap<>(contexts);
        merged.putAll(override.contexts);
        return new ModelDisplay(merged);
    }

    /**
     * Reads a bbmodel/meta {@code display} object from a streaming reader. The
     * reader must be positioned before the value; a non-object value is skipped
     * and yields {@link #EMPTY}.
     */
    public static ModelDisplay fromJson(JsonReader json) throws Exception {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue();
            return EMPTY;
        }
        Map<String, Transform> contexts = new LinkedHashMap<>();
        json.beginObject();
        while (json.hasNext()) {
            String context = json.nextName();
            if (json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue();
                continue;
            }
            Vector3f rotation = null;
            Vector3f translation = null;
            Vector3f scale = null;
            json.beginObject();
            while (json.hasNext()) {
                String field = json.nextName();
                switch (field) {
                    case "rotation" -> rotation = nextVector3(json);
                    case "translation" -> translation = nextVector3(json);
                    case "scale" -> scale = nextVector3(json);
                    default -> json.skipValue();
                }
            }
            json.endObject();
            contexts.put(context, new Transform(rotation, translation, scale));
        }
        json.endObject();
        return contexts.isEmpty() ? EMPTY : new ModelDisplay(contexts);
    }

    private static Vector3f nextVector3(JsonReader json) throws Exception {
        if (json.peek() == JsonToken.NULL) {
            json.nextNull();
            return null;
        }
        if (json.peek() != JsonToken.BEGIN_ARRAY) {
            json.skipValue();
            return null;
        }
        float[] values = new float[3];
        json.beginArray();
        int index = 0;
        while (json.hasNext() && index < 3) {
            values[index++] = readFloat(json);
        }
        while (json.hasNext()) {
            json.skipValue();
        }
        json.endArray();
        return new Vector3f(values[0], values[1], values[2]);
    }

    private static float readFloat(JsonReader json) throws Exception {
        JsonToken peek = json.peek();
        if (peek == JsonToken.NUMBER) {
            return (float) json.nextDouble();
        }
        if (peek == JsonToken.STRING) {
            try {
                return Float.parseFloat(json.nextString().trim());
            } catch (NumberFormatException ignored) {
                return 0.0f;
            }
        }
        if (peek == JsonToken.NULL) {
            json.nextNull();
            return 0.0f;
        }
        json.skipValue();
        return 0.0f;
    }
}
