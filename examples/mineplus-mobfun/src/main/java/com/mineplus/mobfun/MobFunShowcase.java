package com.mineplus.mobfun;

import com.mineplus.infrastructure.mob.MobBehaviorApi;
import com.mineplus.infrastructure.mob.MobProfile;
import java.util.Locale;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

/**
 * One of the MobFun showcase behaviors: owns a single {@link MobProfile} for
 * one mob type, can be toggled through {@code /mobfun}, and rebuilds its
 * profile on every enable so toggles are always clean.
 */
public final class MobFunShowcase implements MobFunShowcaseEntry {

    private final Plugin plugin;
    private final String id;
    private final String label;
    private final EntityType entityType;
    private final Supplier<MobProfile> profileFactory;
    private final MobBehaviorApi api;

    private MobProfile activeProfile;
    private boolean enabled;

    MobFunShowcase(Plugin plugin, String id, String label, EntityType entityType,
                   MobBehaviorApi api, Supplier<MobProfile> profileFactory) {
        this.plugin = plugin;
        this.id = id;
        this.label = label;
        this.entityType = entityType;
        this.api = api;
        this.profileFactory = profileFactory;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public EntityType entityType() {
        return entityType;
    }

    @Override
    public LivingEntity spawn(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        Entity spawned = location.getWorld().spawnEntity(location, entityType);
        return spawned instanceof LivingEntity living ? living : null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Enables the showcase: registers a fresh profile with the Core engine. */
    public void enable() {
        disable();
        activeProfile = profileFactory.get();
        api.register(plugin, activeProfile);
        enabled = true;
    }

    /** Disables the showcase: unregisters its profile; other showcases are untouched. */
    public void disable() {
        if (activeProfile != null) {
            api.unregister(plugin, activeProfile);
            activeProfile = null;
        }
        enabled = false;
    }

    /** Registers with the Core engine on module start. */
    @Override
    public void start() {
        enable();
    }

    /** Unregisters from the Core engine on module stop. */
    @Override
    public void stop() {
        disable();
    }

    public String statusLine() {
        return "  " + label + " (" + entityType.name().toLowerCase(Locale.ROOT) + "): "
                + (enabled ? "ON" : "OFF");
    }
}
