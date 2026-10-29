package com.mineplus.mobfun;

import com.mineplus.infrastructure.mob.custom.CustomMobApi;
import com.mineplus.infrastructure.mob.custom.CustomMobDefinition;
import java.util.Locale;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

/**
 * A showcase that registers a whole custom mob through the Core's
 * {@link CustomMobApi}: vanilla base entity, Mineplus identity, own visual
 * model and Core-driven behavior. Disabling it unregisters the definition and
 * removes its live mobs, so a toggle leaves nothing behind.
 *
 * <p>The definition is rebuilt on every enable, exactly like a behavior
 * showcase's profile, so a toggle never reuses stale state.</p>
 */
public final class CustomMobShowcase implements MobFunShowcaseEntry {

    private final Plugin plugin;
    private final CustomMobApi api;
    private final String id;
    private final String label;
    private final EntityType baseType;
    private final Supplier<CustomMobDefinition> definitionFactory;

    private CustomMobDefinition definition;
    private boolean enabled;

    CustomMobShowcase(Plugin plugin, CustomMobApi api, String id, String label,
                      EntityType baseType, Supplier<CustomMobDefinition> definitionFactory) {
        this.plugin = plugin;
        this.api = api;
        this.id = id;
        this.label = label;
        this.baseType = baseType;
        this.definitionFactory = definitionFactory;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public EntityType entityType() {
        return baseType;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void enable() {
        disable();
        if (!api.isAvailable()) {
            plugin.getLogger().warning("[MobFun] Custom mob '" + label
                    + "' not registered: CUSTOM_MOBS.ENABLED is false in settings.mp.yml.");
            return;
        }
        definition = definitionFactory.get();
        api.register(plugin, definition);
        enabled = true;
    }

    public void disable() {
        if (definition != null) {
            api.unregister(plugin, definition.id());
            definition = null;
        }
        enabled = false;
    }

    public LivingEntity spawn(Location location) {
        if (definition == null) {
            return null;
        }
        return api.spawn(location, definition.id());
    }

    public String statusLine() {
        return "  " + label + " (custom " + baseType.name().toLowerCase(Locale.ROOT)
                + (definition == null ? "" : " " + definition.id()) + "): "
                + (enabled ? "ON" : "OFF");
    }
}
