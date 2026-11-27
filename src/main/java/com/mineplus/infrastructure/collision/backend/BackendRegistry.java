package com.mineplus.infrastructure.collision.backend;

import com.mineplus.infrastructure.collision.fit.BackendType;
import java.util.EnumMap;
import java.util.Map;

/**
 * The set of backends one server instance can use.
 *
 * <p>Built once at startup from the capability probe: Paper/Spigot differences,
 * the optional plate flag and the scale attribute availability are resolved
 * here, so the rest of the system never branches on server version.
 */
public final class BackendRegistry {

    private final Map<BackendType, CollisionBackend> backends =
            new EnumMap<>(BackendType.class);

    public BackendRegistry(boolean scaleSupported, double shulkerScale, boolean plates,
            boolean minecartPlates) {
        RealBlockBackend real = new RealBlockBackend();
        backends.put(BackendType.REAL_BLOCK, real);
        backends.put(BackendType.GHOST_BLOCK, new GhostBlockBackend());
        backends.put(BackendType.SHULKER, new ShulkerBackend(real, scaleSupported, shulkerScale));
        backends.put(BackendType.INTERACTION_ONLY, new InteractionBackend());
        backends.put(BackendType.PASSABLE_OUTLINE, new PassableOutlineBackend());
        backends.put(BackendType.PLATE, new PlateBackend(plates, minecartPlates, real));
    }

    /** Backend for a type, or the real-block backend when an id is unknown. */
    public CollisionBackend get(BackendType type) {
        CollisionBackend backend = backends.get(type);
        return backend != null ? backend : backends.get(BackendType.REAL_BLOCK);
    }

    public boolean has(BackendType type) {
        return backends.containsKey(type);
    }

    public Map<BackendType, CollisionBackend> all() {
        return Map.copyOf(backends);
    }
}
