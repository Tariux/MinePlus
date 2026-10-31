package com.mineplus.devtools.assetlab;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineplus.infrastructure.mob.custom.model.ModelData;
import com.mineplus.infrastructure.mob.custom.rig.MobAnimator;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The daemon's {@code poses} op: drives the Core's real {@link MobAnimator} —
 * the exact state-machine class the render engine ticks — through a scripted
 * movement/event timeline at the engine's 20 Hz tick and returns one pose
 * snapshot per tick. The web viewer replays these frames, so what it plays is
 * the plugin's actual clip binding, crossfade, overlay, death and
 * procedural-swing logic, never a re-implementation.
 *
 * <p>The animator is wired exactly like {@code MobRenderEngine} builds one:
 * clips from the parsed model, aliases from the definition's
 * {@code animations} map, leg bones = names containing {@code leg}, arm bones
 * = {@code arm} (else-if, so a bone is never counted as both).</p>
 *
 * <p>Input:</p>
 * <pre>{@code
 * {"op":"poses","yml":".../evil_pig.yml","preset":"full|attack|hurt|death"}
 * {"op":"poses","model":".../soldier.bbmodel",
 *  "timeline":[{"duration":1.0,"speed":0.1,"events":["attack"]}]}
 * }</pre>
 *
 * <p>Speeds are blocks per tick, exactly what the engine feeds the animator
 * from entity movement ({@code >0.16} counts as run when a run clip is bound,
 * {@code >0.015} as walk). Events mirror the engine's entry points:
 * {@code attack} → {@code notifyAttack()}, {@code hurt} → {@code notifyHurt()},
 * {@code death} → {@code markDead()}.</p>
 */
final class PoseOps {

    /** The engine advances the animator once per server tick. */
    private static final float TICK_SECONDS = 0.05f;
    private static final int MAX_FRAMES = 2000;

    private PoseOps() {
    }

    static JsonObject poses(JsonObject request) throws Exception {
        String ymlPath = MobOps.Json.str(request, "yml", null);
        String modelPath = MobOps.Json.str(request, "model", null);
        if ((ymlPath == null || ymlPath.isBlank()) && (modelPath == null || modelPath.isBlank())) {
            throw new IllegalArgumentException("the poses op needs 'yml' or 'model'");
        }

        MobOps.Spec spec = ymlPath != null && !ymlPath.isBlank()
                ? MobOps.readSpec(new File(ymlPath))
                : MobOps.Spec.fromDirectFiles(new File(modelPath), null);

        JsonObject response = new JsonObject();
        response.addProperty("source", spec.sourceLabel);
        if (spec.type == MobOps.RenderKind.RETEXTURE_VANILLA || spec.type == MobOps.RenderKind.NONE) {
            response.addProperty("status", "BROKEN");
            response.addProperty("summary", "a " + spec.type.name() + " mob has no rig to animate");
            return response;
        }
        if (spec.modelFile == null) {
            response.addProperty("status", "BROKEN");
            response.addProperty("summary", "the spec declares " + spec.type.name()
                    + " but has no render.file to parse");
            return response;
        }
        ModelData model = MobOps.parseModel(spec);
        if (model == null) {
            response.addProperty("status", "BROKEN");
            response.addProperty("summary", spec.parseError == null ? "the model did not parse" : spec.parseError);
            return response;
        }

        // The real animator, wired like MobRenderEngine.Session.createAnimator.
        List<String> legs = new ArrayList<>();
        List<String> arms = new ArrayList<>();
        for (int i = 0; i < model.boneCount(); i++) {
            String name = model.bone(i).name();
            if (name.contains("leg")) {
                legs.add(name);
            } else if (name.contains("arm")) {
                arms.add(name);
            }
        }
        MobAnimator animator = new MobAnimator(model.animations(), spec.animationMap, legs, arms);

        response.addProperty("renderType", spec.type.name());
        response.addProperty("tickSeconds", TICK_SECONDS);
        JsonArray binding = new JsonArray();
        for (Map.Entry<String, String> state : animator.binding().entrySet()) {
            JsonObject sj = new JsonObject();
            sj.addProperty("state", state.getKey());
            sj.addProperty("clip", state.getValue());
            binding.add(sj);
        }
        response.add("binding", binding);
        response.addProperty("hasAttackClip", animator.hasAttackClip());
        response.addProperty("hasDeathClip", animator.hasDeathClip());
        response.addProperty("deathClipLength", animator.deathClipLength());
        response.add("legBones", MobOps.Json.stringArray(legs));
        response.add("armBones", MobOps.Json.stringArray(arms));

        List<Segment> segments = timeline(request, animator);
        JsonArray frames = new JsonArray();
        JsonArray applied = new JsonArray();
        int tick = 0;
        boolean truncated = false;
        float time = 0.0f;
        for (Segment segment : segments) {
            for (String event : segment.events()) {
                applyEvent(animator, event);
                JsonObject ej = new JsonObject();
                ej.addProperty("tick", tick);
                ej.addProperty("time", round(time));
                ej.addProperty("event", event);
                applied.add(ej);
            }
            int ticks = Math.max(1, Math.round(segment.duration() / TICK_SECONDS));
            for (int k = 0; k < ticks; k++) {
                if (tick >= MAX_FRAMES) {
                    truncated = true;
                    break;
                }
                animator.tick(TICK_SECONDS, segment.speed());
                frames.add(frameJson(tick, time + TICK_SECONDS, animator));
                tick++;
                time += TICK_SECONDS;
            }
            if (truncated) {
                break;
            }
        }
        response.add("events", applied);
        response.add("frames", frames);
        response.addProperty("frameCount", tick);
        response.addProperty("durationSeconds", round(time));
        response.addProperty("truncated", truncated);
        response.addProperty("status", "OK");
        Map<String, String> bound = animator.binding();
        response.addProperty("summary", tick + " engine ticks of the real MobAnimator (idle '"
                + bound.get("idle") + "', walk '" + bound.get("walk") + "', run '" + bound.get("run") + "')");
        return response;
    }

    // ------------------------------------------------------------------
    // Timeline
    // ------------------------------------------------------------------

    private record Segment(float duration, float speed, List<String> events) {
    }

    private static List<Segment> timeline(JsonObject request, MobAnimator animator) {
        List<Segment> segments = new ArrayList<>();
        if (request.has("timeline") && request.get("timeline").isJsonArray()) {
            JsonArray custom = request.getAsJsonArray("timeline");
            for (int i = 0; i < custom.size(); i++) {
                JsonObject sj = custom.get(i).getAsJsonObject();
                float duration = sj.has("duration") ? sj.get("duration").getAsFloat() : 1.0f;
                float speed = sj.has("speed") ? sj.get("speed").getAsFloat() : 0.0f;
                List<String> events = new ArrayList<>();
                if (sj.has("events") && sj.get("events").isJsonArray()) {
                    for (int e = 0; e < sj.getAsJsonArray("events").size(); e++) {
                        events.add(sj.getAsJsonArray("events").get(e).getAsString());
                    }
                }
                segments.add(new Segment(duration, speed, events));
            }
            if (!segments.isEmpty()) {
                return segments;
            }
        }
        String preset = MobOps.Json.str(request, "preset", "full").toLowerCase(Locale.ROOT);
        switch (preset) {
            case "attack" -> {
                segments.add(new Segment(0.4f, 0.0f, List.of()));
                segments.add(new Segment(0.1f, 0.0f, List.of("attack")));
                segments.add(new Segment(1.3f, 0.0f, List.of()));
            }
            case "hurt" -> {
                segments.add(new Segment(0.4f, 0.0f, List.of()));
                segments.add(new Segment(0.1f, 0.0f, List.of("hurt")));
                segments.add(new Segment(1.3f, 0.0f, List.of()));
            }
            case "death" -> {
                segments.add(new Segment(0.4f, 0.0f, List.of()));
                segments.add(new Segment(Math.max(0.3f, animator.deathClipLength() + 0.8f), 0.0f,
                        List.of("death")));
            }
            default -> {
                // idle → walk (0.1 b/t: above WALK_THRESHOLD, below RUN) → run (0.3) → idle
                segments.add(new Segment(1.0f, 0.0f, List.of()));
                segments.add(new Segment(1.5f, 0.1f, List.of()));
                segments.add(new Segment(1.5f, 0.3f, List.of()));
                segments.add(new Segment(0.75f, 0.0f, List.of()));
            }
        }
        return segments;
    }

    private static void applyEvent(MobAnimator animator, String event) {
        switch (event.toLowerCase(Locale.ROOT)) {
            case "attack" -> animator.notifyAttack();
            case "hurt" -> animator.notifyHurt();
            case "death" -> animator.markDead();
            default -> throw new IllegalArgumentException("unknown timeline event '" + event + "'");
        }
    }

    /**
     * One 20 Hz frame: the animator's live pose map keyed by bone name —
     * {@code [rotationX, rotationY, rotationZ, offsetX, offsetY, offsetZ, scale]}
     * — exactly what the engine composes with rest rotations and head look.
     * {@code s} is the animator's own state summary (base state + overlays).
     */
    private static JsonObject frameJson(int tick, float time, MobAnimator animator) {
        JsonObject fj = new JsonObject();
        fj.addProperty("t", round(time));
        fj.addProperty("s", animator.snapshot());
        JsonObject p = new JsonObject();
        for (Map.Entry<String, MobAnimator.BonePose> entry : animator.poses().entrySet()) {
            MobAnimator.BonePose pose = entry.getValue();
            p.add(entry.getKey(), MobOps.Json.vec(
                    pose.rotationX, pose.rotationY, pose.rotationZ,
                    pose.offsetX, pose.offsetY, pose.offsetZ, pose.scale));
        }
        fj.add("p", p);
        return fj;
    }

    private static float round(float value) {
        return Math.round(value * 1000.0f) / 1000.0f;
    }
}
