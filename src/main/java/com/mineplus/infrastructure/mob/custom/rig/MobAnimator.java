package com.mineplus.infrastructure.mob.custom.rig;

import com.mineplus.infrastructure.mob.custom.model.ModelAnimation;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The animation state machine of one rigged mob: an idle/walk/run base layer
 * blended by movement speed, one-shot attack and hurt overlays, a terminal
 * death state, and procedural leg/arm swing when the model has no movement
 * clip to cover its limbs.
 *
 * <p>Clips are bound by name: a state picks the authored clip whose name
 * matches ({@code idle/stand/breath}, {@code walk}, {@code run/sprint},
 * {@code attack/swing/melee}, {@code hurt/hit/damage}, {@code death/die}) or
 * the definition's explicit {@code animationMap} alias. Missing clips fall
 * back along the chain run→walk→idle→procedural, so a model with only an idle
 * clip still breathes and still swings its legs while moving.</p>
 *
 * <p>The pose output is a reused per-bone map (no per-tick allocation): each
 * tick every driven bone gets additive Euler degrees, a position offset in
 * model pixels and a scale multiplier. The engine composes this with rest
 * rotations and head look.</p>
 */
public final class MobAnimator {

    public enum State {
        IDLE,
        WALK,
        RUN,
        DEATH
    }

    /** One bone's sampled pose; instances are reused every tick. */
    public static final class BonePose {
        public float rotationX;
        public float rotationY;
        public float rotationZ;
        public float offsetX;
        public float offsetY;
        public float offsetZ;
        public float scale = 1.0f;

        void reset() {
            rotationX = 0.0f;
            rotationY = 0.0f;
            rotationZ = 0.0f;
            offsetX = 0.0f;
            offsetY = 0.0f;
            offsetZ = 0.0f;
            scale = 1.0f;
        }
    }

    private static final float RUN_SPEED_THRESHOLD = 0.16f;   // blocks per tick
    private static final float WALK_SPEED_THRESHOLD = 0.015f;
    private static final float CROSSFADE_SECONDS = 0.15f;
    private static final float OVERLAY_FADE_SECONDS = 0.08f;
    private static final float PROCEDURAL_LEG_AMPLITUDE = 42.0f;
    private static final float PROCEDURAL_ARM_AMPLITUDE = 30.0f;
    private static final float STRIDE_BLOCKS_PER_CYCLE = 1.6f;

    private final Map<String, ModelAnimation> animations;
    private final Map<String, String> aliases;
    private final Map<String, BonePose> poses = new HashMap<>();
    private final List<String> legBones;
    private final List<String> armBones;

    private ModelAnimation idleClip;
    private ModelAnimation walkClip;
    private ModelAnimation runClip;
    private ModelAnimation attackClip;
    private ModelAnimation hurtClip;
    private ModelAnimation deathClip;
    private ModelAnimation overlayClip;

    private float time;
    private float stridePhase;
    private final float[] sampleBuffer = new float[3];
    private State baseState = State.IDLE;
    private State previousState = State.IDLE;
    private float crossfade = 1.0f;
    private float attackTime = Float.MAX_VALUE;
    private float hurtTime = Float.MAX_VALUE;
    private float overlayTime = Float.MAX_VALUE;
    private boolean dead;

    /**
     * @param animations every clip of the model, keyed by lower-case name
     * @param aliases    the definition's {@code state → clip name} overrides
     * @param legBones   bone names recognized as legs (for procedural swing)
     * @param armBones   bone names recognized as arms (for procedural swing)
     */
    public MobAnimator(Map<String, ModelAnimation> animations, Map<String, String> aliases,
                       List<String> legBones, List<String> armBones) {
        this.animations = animations;
        this.aliases = aliases == null ? Map.of() : aliases;
        this.legBones = legBones;
        this.armBones = armBones;
        idleClip = resolve("idle", "stand", "breath");
        walkClip = resolve("walk");
        runClip = resolve("run", "sprint");
        attackClip = resolve("attack", "swing", "melee");
        hurtClip = resolve("hurt", "hit", "damage");
        deathClip = resolve("death", "die");
        if (runClip == null) {
            runClip = walkClip;
        }
        if (walkClip == null) {
            walkClip = idleClip;
        }
    }

    private ModelAnimation resolve(String... stateNames) {
        for (String state : stateNames) {
            String alias = aliases.get(state);
            if (alias != null) {
                ModelAnimation bound = animations.get(alias.toLowerCase(Locale.ROOT));
                if (bound != null) {
                    return bound;
                }
            }
        }
        for (String state : stateNames) {
            for (ModelAnimation clip : animations.values()) {
                String name = clip.name();
                if (name.contains(state)) {
                    return clip;
                }
            }
        }
        return null;
    }

    public boolean hasAttackClip() {
        return attackClip != null;
    }

    /** True while an attack, hurt or command overlay is playing. */
    public boolean overlayActive() {
        return attackTime < overlayLength(attackClip) || hurtTime < overlayLength(hurtClip)
                || overlayTime < overlayLength(overlayClip);
    }

    private static float overlayLength(ModelAnimation clip) {
        return clip == null || clip.lengthSeconds() <= 0.0f ? 0.5f : clip.lengthSeconds();
    }

    /** Restarts the attack overlay unless it is already in its first frames. */
    public void notifyAttack() {
        if (attackClip != null && attackTime > 2.0f / 20.0f) {
            attackTime = 0.0f;
        }
    }

    /** Restarts the hurt overlay. */
    public void notifyHurt() {
        if (hurtClip != null) {
            hurtTime = 0.0f;
        }
    }

    /** Plays any authored clip as a one-shot overlay (the {@code anim} command). */
    public boolean playOverlay(ModelAnimation clip) {
        if (clip == null || dead) {
            return false;
        }
        overlayClip = clip;
        overlayTime = 0.0f;
        return true;
    }

    /** Terminal: the death clip plays once and holds its last pose. */
    public void markDead() {
        dead = true;
    }

    /** True when a death clip is bound; without one the rig despawns right away. */
    public boolean hasDeathClip() {
        return deathClip != null;
    }

    /** Length of the bound death clip in seconds; a short hold when none is bound. */
    public float deathClipLength() {
        return deathClip == null ? 0.5f : Math.max(0.05f, deathClip.lengthSeconds());
    }

    public State baseState() {
        return baseState;
    }

    /** Advances the clocks and resamples every pose. Pure; no clock source of its own. */
    public void tick(float deltaSeconds, float speedBlocksPerTick) {
        time += Math.max(0.0f, deltaSeconds);
        stridePhase += Math.max(0.0f, speedBlocksPerTick) / STRIDE_BLOCKS_PER_CYCLE;
        attackTime += Math.max(0.0f, deltaSeconds);
        hurtTime += Math.max(0.0f, deltaSeconds);
        overlayTime += Math.max(0.0f, deltaSeconds);

        State target = dead ? State.DEATH
                : speedBlocksPerTick > RUN_SPEED_THRESHOLD && runClip != null ? State.RUN
                : speedBlocksPerTick > WALK_SPEED_THRESHOLD && walkClip != null ? State.WALK
                : State.IDLE;
        if (target != baseState) {
            previousState = baseState;
            baseState = target;
            crossfade = 0.0f;
        }
        crossfade = Math.min(1.0f, crossfade + deltaSeconds / CROSSFADE_SECONDS);

        for (BonePose pose : poses.values()) {
            pose.reset();
        }
        if (dead && deathClip != null) {
            sample(deathClip, deathClip.wrap(time), 1.0f);
            return;
        }

        ModelAnimation current = baseClip(baseState);
        ModelAnimation previous = previousState == baseState ? null : baseClip(previousState);
        float blend = dead ? 1.0f : crossfade;
        if (previous != null && blend < 1.0f) {
            sample(previous, previous.wrap(time), 1.0f - blend);
        }
        if (current != null) {
            sample(current, current.wrap(time), 1.0f);
        } else {
            procedural(baseState, speedBlocksPerTick);
        }

        float attackWeight = overlayWeight(attackTime, attackClip);
        if (attackWeight > 0.0f) {
            sample(attackClip, attackClip.wrap(attackTime), attackWeight);
        }
        float hurtWeight = overlayWeight(hurtTime, hurtClip);
        if (hurtWeight > 0.0f) {
            sample(hurtClip, hurtClip.wrap(hurtTime), hurtWeight);
        }
        float extraWeight = overlayWeight(overlayTime, overlayClip);
        if (extraWeight > 0.0f) {
            sample(overlayClip, overlayClip.wrap(overlayTime), extraWeight);
        }
    }

    private ModelAnimation baseClip(State state) {
        return switch (state) {
            case RUN -> runClip;
            case WALK -> walkClip;
            case DEATH -> deathClip;
            default -> idleClip;
        };
    }

    private static float overlayWeight(float startTime, ModelAnimation clip) {
        if (clip == null || startTime >= overlayLength(clip)) {
            return 0.0f;
        }
        float fadeIn = Math.min(1.0f, startTime / OVERLAY_FADE_SECONDS);
        float remaining = overlayLength(clip) - startTime;
        float fadeOut = Math.min(1.0f, remaining / OVERLAY_FADE_SECONDS);
        return Math.min(fadeIn, fadeOut);
    }

    /**
     * Adds one clip's curves into the pose map; {@code weight} blends against
     * what earlier samples already wrote (base first, overlays last).
     */
    private void sample(ModelAnimation clip, float playhead, float weight) {
        for (Map.Entry<String, ModelAnimation.BoneTrack> entry : clip.trackEntries().entrySet()) {
            BonePose pose = poses.computeIfAbsent(entry.getKey(), name -> new BonePose());
            ModelAnimation.BoneTrack track = entry.getValue();
            if (track.rotation() != null) {
                track.rotation().sample(playhead, sampleBuffer);
                pose.rotationX = blendChannel(pose.rotationX, sampleBuffer[0], weight, true);
                pose.rotationY = blendChannel(pose.rotationY, sampleBuffer[1], weight, true);
                pose.rotationZ = blendChannel(pose.rotationZ, sampleBuffer[2], weight, true);
            }
            if (track.position() != null) {
                track.position().sample(playhead, sampleBuffer);
                pose.offsetX = blendChannel(pose.offsetX, sampleBuffer[0], weight, false);
                pose.offsetY = blendChannel(pose.offsetY, sampleBuffer[1], weight, false);
                pose.offsetZ = blendChannel(pose.offsetZ, sampleBuffer[2], weight, false);
            }
            if (track.scale() != null) {
                track.scale().sample(playhead, sampleBuffer);
                float sampled = sampleBuffer[0] != 0.0f ? sampleBuffer[0] : 1.0f;
                pose.scale = pose.scale * (1.0f - weight) + sampled * weight;
            }
        }
    }

    /** Rotations/positions add across layers; scale multiplies. */
    private static float blendChannel(float existing, float sampled, float weight, boolean additive) {
        if (additive || existing == 0.0f) {
            return existing + sampled * weight;
        }
        return existing * (1.0f - weight) + sampled * weight;
    }

    /**
     * Simple limb swing for models without a movement clip: legs counter-swing,
     * arms swing against their side's legs, amplitude follows speed.
     */
    private void procedural(State state, float speedBlocksPerTick) {
        if (state == State.IDLE || state == State.DEATH) {
            return;
        }
        float amplitude = PROCEDURAL_LEG_AMPLITUDE * Math.min(1.0f, speedBlocksPerTick / 0.2f);
        swing(legBones, amplitude, 0.0f);
        swing(armBones, amplitude * PROCEDURAL_ARM_AMPLITUDE / PROCEDURAL_LEG_AMPLITUDE, 0.5f);
    }

    private void swing(List<String> bones, float amplitudeDegrees, float phaseOffset) {
        if (bones.isEmpty() || amplitudeDegrees <= 0.01f) {
            return;
        }
        for (int i = 0; i < bones.size(); i++) {
            float phase = stridePhase * (float) (Math.PI * 2.0) + phaseOffset + (i % 2 == 0 ? 0.0f : (float) Math.PI);
            float angle = (float) Math.sin(phase) * amplitudeDegrees;
            BonePose pose = poses.computeIfAbsent(bones.get(i), name -> new BonePose());
            pose.rotationX += angle;
        }
    }

    /** The live pose map (mutated in place each tick; do not retain). */
    public Map<String, BonePose> poses() {
        return poses;
    }

    /** A one-line state summary for status output. */
    public String snapshot() {
        StringBuilder line = new StringBuilder();
        line.append(baseState.name().toLowerCase(Locale.ROOT));
        if (attackTime < overlayLength(attackClip)) {
            line.append("+attack");
        }
        if (hurtTime < overlayLength(hurtClip)) {
            line.append("+hurt");
        }
        if (overlayTime < overlayLength(overlayClip)) {
            line.append("+anim");
        }
        if (dead) {
            line.append(" (dead)");
        }
        return line.toString();
    }

    /** The bound clip names per state, for the inspect command. */
    public Map<String, String> binding() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("idle", clipName(idleClip));
        out.put("walk", clipName(walkClip));
        out.put("run", clipName(runClip));
        out.put("attack", clipName(attackClip));
        out.put("hurt", clipName(hurtClip));
        out.put("death", clipName(deathClip));
        return out;
    }

    private static String clipName(ModelAnimation clip) {
        return clip == null ? "-" : clip.rawName();
    }
}
