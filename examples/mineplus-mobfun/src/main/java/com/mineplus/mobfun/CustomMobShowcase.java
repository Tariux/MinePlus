package com.mineplus.mobfun;

import com.mineplus.infrastructure.mob.custom.CustomMobApi;
import com.mineplus.infrastructure.mob.custom.CustomMobDefinition;
import com.mineplus.infrastructure.mob.custom.CustomMobModel;
import java.util.Locale;
import java.util.function.Supplier;
import org.bukkit.ChatColor;
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
        StringBuilder line = new StringBuilder("  ").append(label).append(" (custom ")
                .append(baseType.name().toLowerCase(Locale.ROOT))
                .append(definition == null ? "" : " " + definition.id()).append("): ")
                .append(enabled ? "ON" : "OFF");
        if (definition == null) {
            return line.toString();
        }
        CustomMobModel model = definition.model();
        line.append(ChatColor.GRAY).append(" — visual ").append(definition.visual())
                .append(", ").append(model == null ? "no model" : model.parts().size() + " part(s)"
                        + (model.animations().isEmpty() ? " procedural"
                        : " clips " + model.animations().size()))
                .append(", ").append(definition.attributes().overrideCount()).append(" attribute(s)")
                .append(definition.keepsVanillaGoals() ? " on donor AI" : " isolated")
                .append(ChatColor.WHITE).append(", live ").append(api.liveCount(definition.id()));
        return line.toString();
    }

    /** The live definition while the showcase is on, for the rig/pack diagnostics. */
    public CustomMobDefinition definition() {
        return definition;
    }

    /** The Core's custom-mob API this showcase registers through. */
    public CustomMobApi api() {
        return api;
    }
}
