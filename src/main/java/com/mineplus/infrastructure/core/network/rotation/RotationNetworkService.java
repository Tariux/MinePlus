package com.mineplus.infrastructure.core.network.rotation;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleEventType;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import com.mineplus.infrastructure.core.network.EnergyGrid;
import com.mineplus.infrastructure.core.network.KineticGrid;
import com.mineplus.infrastructure.model.BlockCoordinate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Bukkit-facing kinetic rotation service over the multiblock registry,
 * backed by the P6 {@link KineticGrid} solver. Machines join by
 * 6-adjacency; a link between two cogwheel types is a sideways mesh
 * (rotation sign flips), anything else is coaxial. Profiles come from a
 * type's {@code guiOptions} ({@code rotation.*} keys), so a config-only
 * machine needs no code. Per-member accumulated angles live here so
 * callers render continuous spin at fractional speeds; a brake toggle
 * re-registers the lever node as a huge-stress consumer, driving its
 * network over-stressed to a standstill.
 */
public final class RotationNetworkService {

    /** Per-level static profile of a rotating machine type. */
    public record RotationProfile(KineticGrid.Kind kind, int su, int stress, float speed, boolean cogwheel) {
    }

    /** Solved per-instance state pushed to the sink each tick. */
    public record MemberState(float speed, int direction, float angle, boolean overStressed) {
    }

    /** Stress demand of an engaged brake: forces over-stress to ~zero speed. */
    private static final float BRAKE_STRESS = 1_000_000f;

    private final MultiBlockRegistry registry;
    private final KineticGrid grid = new KineticGrid();
    private final Map<Long, UUID> members = new HashMap<>();
    private final Map<Long, Float> angles = new HashMap<>();
    private final Map<Long, Boolean> braked = new HashMap<>();
    private final Map<String, RotationProfile> profiles = new HashMap<>();
    private BiConsumer<MultiBlockInstance, MemberState> stateSink;

    public RotationNetworkService(MultiBlockRegistry registry) {
        this.registry = registry;
    }

    /** Registers the rotation profile of a machine type; replaces any previous. */
    public void registerProfile(String typeId, RotationProfile profile) {
        profiles.put(typeId.toLowerCase(), profile);
    }

    /**
     * Derives a rotation profile from a type's min-level {@code guiOptions}:
     * {@code rotation.kind} (source|consumer|shaft), {@code rotation.su}
     * (source capacity), {@code rotation.stress} (consumer demand),
     * {@code rotation.speed} (RPM-ish source speed) and
     * {@code rotation.cogwheel} (sideways-meshing gear). Types without
     * {@code rotation.kind} are not kinetic.
     */
    public void registerFromType(MultiBlockType type) {
        for (var level : type.levels().values()) {
            if (level.level() != type.minLevel()) {
                continue;
            }
            Map<String, String> gui = level.guiOptions();
            String kind = gui.get("rotation.kind");
            if (kind == null || kind.isBlank()) {
                return;
            }
            KineticGrid.Kind kindEnum;
            try {
                kindEnum = KineticGrid.Kind.valueOf(kind.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return;
            }
            registerProfile(type.id(), new RotationProfile(kindEnum,
                    positiveInt(gui.get("rotation.su")),
                    positiveInt(gui.get("rotation.stress")),
                    positiveFloat(gui.get("rotation.speed")),
                    Boolean.parseBoolean(gui.get("rotation.cogwheel"))));
            return;
        }
    }

    private static int positiveInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static float positiveFloat(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0f;
        }
        try {
            return Math.max(0f, Float.parseFloat(raw.trim()));
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    /** Receives every member state after each solve. One sink per service. */
    public void setStateSink(BiConsumer<MultiBlockInstance, MemberState> sink) {
        this.stateSink = sink;
    }

    /** True when the type declares a rotation profile. */
    public boolean isKinetic(String typeId) {
        return profiles.containsKey(typeId.toLowerCase());
    }

    /** True when both instances exist and share one connected network. */
    public boolean connected(UUID a, UUID b) {
        MultiBlockInstance ia = registry.getInstance(a);
        MultiBlockInstance ib = registry.getInstance(b);
        if (ia == null || ib == null) {
            return false;
        }
        return grid.connected(keyOf(ia.coordinate()), keyOf(ib.coordinate()));
    }

    /**
     * Engages or releases the brake on a placed lever/machine instance: an
     * engaged brake joins its network as a huge-stress consumer, which
     * over-stresses the network to a standstill on the next solve.
     */
    public void setBraked(MultiBlockInstance instance, boolean engaged) {
        RotationProfile profile = profiles.get(instance.typeId().toLowerCase());
        if (profile == null) {
            return;
        }
        long key = keyOf(instance.coordinate());
        braked.put(key, engaged);
        putNode(key, profile);
    }

    private void putNode(long key, RotationProfile profile) {
        boolean isBrakeEngaged = Boolean.TRUE.equals(braked.get(key));
        switch (profile.kind()) {
            // A dynamic SU override (e.g. derived from adjacent flowing
            // water) replaces the profile's static capacity when present.
            case SOURCE -> grid.put(key, KineticGrid.Kind.SOURCE, profile.speed(),
                    suOverrides.getOrDefault(key, profile.su()), 0f);
            case CONSUMER -> grid.put(key, KineticGrid.Kind.CONSUMER, 0f, 0f,
                    isBrakeEngaged ? BRAKE_STRESS : profile.stress());
            default -> grid.put(key, KineticGrid.Kind.SHAFT, 0f, 0f, 0f);
        }
    }

    private final Map<Long, Integer> suOverrides = new HashMap<>();

    /** Current dynamic SU of a placed source (profile value when never overridden). */
    public int sourceSu(MultiBlockInstance instance) {
        long key = keyOf(instance.coordinate());
        Integer override = suOverrides.get(key);
        if (override != null) {
            return override;
        }
        RotationProfile profile = profiles.get(instance.typeId().toLowerCase());
        return profile == null ? 0 : profile.su();
    }

    /**
     * Updates a placed source's dynamic SU (Create-style: a water wheel's
     * output follows how much flowing water drives it) and marks its
     * network dirty so the next solve rebalances. Ignored for non-sources.
     */
    public void setSourceSu(MultiBlockInstance instance, int su) {
        RotationProfile profile = profiles.get(instance.typeId().toLowerCase());
        if (profile == null || profile.kind() != KineticGrid.Kind.SOURCE) {
            return;
        }
        long key = keyOf(instance.coordinate());
        int clamped = Math.max(0, su);
        Integer previous = suOverrides.put(key, clamped);
        if (previous != null && previous == clamped) {
            return;
        }
        putNode(key, profile);
    }


    /**
     * Syncs one placed instance into the grid; no-op for non-kinetic
     * types. Neighbours already in the grid are linked: cogwheel-to-
     * cogwheel adjacency meshes (sign flip), all else is coaxial.
     */
    public void onPlaced(MultiBlockInstance instance) {
        String typeId = instance.typeId().toLowerCase();
        RotationProfile profile = profiles.get(typeId);
        if (profile == null) {
            // Types registered by a module after context construction never saw
            // registerFromType(); derive the profile on first placement so the
            // machine still joins its kinetic network.
            var type = registry.getType(typeId);
            if (type == null) {
                return;
            }
            registerFromType(type);
            profile = profiles.get(typeId);
        }
        if (profile == null) {
            return;
        }
        long key = keyOf(instance.coordinate());
        members.put(key, instance.id());
        putNode(key, profile);
        BlockCoordinate at = instance.coordinate();
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : dirs) {
            long n = EnergyGrid.pack(at.x() + d[0], at.y() + d[1], at.z() + d[2]);
            if (grid.node(n) != null) {
                boolean meshed = profile.cogwheel() && isCogwheelNode(n);
                grid.link(key, n, meshed);
            }
        }
    }

    private boolean isCogwheelNode(long key) {
        UUID memberId = members.get(key);
        if (memberId == null) {
            return false;
        }
        MultiBlockInstance instance = registry.getInstance(memberId);
        RotationProfile profile = instance == null ? null : profiles.get(instance.typeId().toLowerCase());
        return profile != null && profile.cogwheel();
    }

    /** All placed instances whose profile is a SOURCE (currently spinning or not). */
    public java.util.Collection<MultiBlockInstance> placedSources() {
        java.util.List<MultiBlockInstance> sources = new java.util.ArrayList<>();
        for (var entry : members.entrySet()) {
            MultiBlockInstance instance = registry.getInstance(entry.getValue());
            if (instance == null) {
                continue;
            }
            RotationProfile profile = profiles.get(instance.typeId().toLowerCase());
            if (profile != null && profile.kind() == KineticGrid.Kind.SOURCE) {
                sources.add(instance);
            }
        }
        return sources;
    }

    /** Removes one instance's node from the grid; no-op when absent. */
    public void onRemoved(MultiBlockInstance instance) {
        long key = keyOf(instance.coordinate());
        members.remove(key);
        angles.remove(key);
        braked.remove(key);
        suOverrides.remove(key);
        grid.remove(key);
    }

    /**
     * Solves dirty networks, integrates angles and pushes member states to
     * the sink. Called from the lifecycle post-tick pass.
     */
    public void rotationTick() {
        var rotations = grid.solve();
        if (rotations.isEmpty() || stateSink == null) {
            return;
        }
        // Shafts and cogwheels are members but not solve results; drive them
        // with their network's speed so a connected shaft visibly turns.
        java.util.List<KineticGrid.Rotation> states = new java.util.ArrayList<>(rotations);
        Map<Long, KineticGrid.Rotation> solved = new HashMap<>();
        for (var rotation : rotations) {
            solved.put(rotation.key(), rotation);
        }
        for (var rotation : rotations) {
            for (var node : grid.networkMembers(rotation.key())) {
                if (node.kind() == KineticGrid.Kind.SHAFT && !solved.containsKey(node.key())) {
                    var shaft = new KineticGrid.Rotation(
                            node.key(), rotation.speed(), node.direction(), rotation.overStressed());
                    solved.put(node.key(), shaft);
                    states.add(shaft);
                }
            }
        }
        for (var rotation : states) {
            UUID memberId = members.get(rotation.key());
            MultiBlockInstance instance = memberId == null ? null : registry.getInstance(memberId);
            if (instance == null) {
                continue;
            }
            float speed = rotation.overStressed() ? 0f : rotation.speed();
            float angle = angles.getOrDefault(rotation.key(), 0f);
            angle = (angle + speed * rotation.direction()) % 360f;
            if (angle < 0) {
                angle += 360f;
            }
            angles.put(rotation.key(), angle);
            stateSink.accept(instance, new MemberState(speed, rotation.direction(), angle, rotation.overStressed()));
        }
    }

    /**
     * Listens to lifecycle place/remove events so grid membership follows
     * the registry without feature code.
     */
    public void attachToLifecycle(com.mineplus.infrastructure.core.events.HookBus hookBus) {
        hookBus.registerLifecycleListener(event -> {
            MultiBlockInstance instance = event.instance();
            if (instance == null) {
                return;
            }
            if (event.type() == MultiBlockLifecycleEventType.PLACE) {
                onPlaced(instance);
            } else if (event.type() == MultiBlockLifecycleEventType.REMOVE
                    || event.type() == MultiBlockLifecycleEventType.DESTRUCTION) {
                onRemoved(instance);
            }
        }, MultiBlockLifecycleEventType.PLACE,
                MultiBlockLifecycleEventType.REMOVE,
                MultiBlockLifecycleEventType.DESTRUCTION);
    }

    private long keyOf(BlockCoordinate coordinate) {
        return EnergyGrid.pack(coordinate.x(), coordinate.y(), coordinate.z());
    }
}
