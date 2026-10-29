package com.mineplus.infrastructure.mob;

import org.bukkit.entity.LivingEntity;

/**
 * Resolves the Mineplus custom-mob identity of a live entity.
 *
 * <p>The behavior engine asks this before selecting profiles: an entity that
 * carries a custom id is evaluated against that id's profiles only, never
 * against the profiles registered for its vanilla {@link org.bukkit.entity.EntityType}.
 * A custom pig therefore cannot inherit the grazing/reforesting behavior of an
 * ordinary pig, and an ordinary pig can never pick up a custom mob's profile.
 * Returning {@code null} means "not a Mineplus custom mob" and leaves vanilla
 * type dispatch unchanged.</p>
 *
 * <p>Thread model: called from the main server thread during engine runs.</p>
 */
@FunctionalInterface
public interface MobIdentityResolver {

    /** The custom mob id ({@code namespace:path}) carried by {@code entity}, or {@code null}. */
    String customMobId(LivingEntity entity);
}
