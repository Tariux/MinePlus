package com.mineplus.infrastructure.collision.backend;

import com.mineplus.infrastructure.collision.fit.BackendType;
import com.mineplus.infrastructure.collision.runtime.PlacedFurniture;
import com.mineplus.infrastructure.collision.runtime.PlacedPart;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Shulker;

/**
 * Off-grid collision from scaled, invisible shulker entities.
 *
 * <p>Shulkers are the only vanilla entity whose hitbox can be scaled by the
 * server without a client mod ({@code generic.scale}, 1.20.5+). They are
 * spawned silent, invulnerable, non-persistent and with AI off, so the only
 * thing they contribute is the box. The backend falls back to the real-block
 * backend when the server lacks the scale attribute.
 */
public final class ShulkerBackend implements CollisionBackend {

    private final CollisionBackend fallback;
    private final boolean scaleSupported;
    private final double scale;

    public ShulkerBackend(CollisionBackend fallback, boolean scaleSupported, double scale) {
        this.fallback = fallback;
        this.scaleSupported = scaleSupported;
        this.scale = clamp(scale);
    }

    @Override
    public BackendType type() {
        return BackendType.SHULKER;
    }

    @Override
    public boolean supports(PlacedPart part) {
        return scaleSupported || fallback.supports(part);
    }

    @Override
    public int cost(PlacedPart part) {
        return 5;
    }

    @Override
    public void apply(BackendContext context, PlacedFurniture furniture, PlacedPart part) {
        if (!scaleSupported) {
            fallback.apply(context, furniture, part);
            return;
        }
        if (part.entityId() != null && Bukkit.getEntity(part.entityId()) != null) {
            return;
        }
        Location location = context.centerLocation(furniture, part);
        Entity spawned = context.spawnTechnical(location, EntityType.SHULKER);
        if (spawned instanceof Shulker shulker) {
            shulker.setPeek(0.0f);
            shulker.setAI(false);
            shulker.setCollidable(true);
            applyScale(shulker);
        }
        part.setEntityType(EntityType.SHULKER.name());
        part.setEntityId(spawned.getUniqueId());
    }

    @Override
    public void remove(BackendContext context, PlacedFurniture furniture, PlacedPart part) {
        if (part.entityId() == null) {
            fallback.remove(context, furniture, part);
            return;
        }
        Entity entity = Bukkit.getEntity(part.entityId());
        if (entity != null) {
            entity.remove();
        }
        part.setEntityId(null);
    }

    private void applyScale(LivingEntity entity) {
        try {
            AttributeInstance instance = entity.getAttribute(scaleAttribute());
            if (instance != null) {
                instance.setBaseValue(scale);
            }
        } catch (ReflectiveOperationException | RuntimeException unsupported) {
            contextWarn(entity, unsupported);
        }
    }

    private void contextWarn(Entity entity, Throwable unsupported) {
        // The entity is still usable at native size; the fitter already sized the
        // cell, so a missing scale only makes the box coarser.
        entity.setCustomNameVisible(false);
    }

    private static Attribute scaleAttribute() throws ReflectiveOperationException {
        try {
            Field field = Attribute.class.getField("GENERIC_SCALE");
            return (Attribute) field.get(null);
        } catch (NoSuchFieldException newer) {
            Method valueOf = Attribute.class.getMethod("valueOf", String.class);
            return (Attribute) valueOf.invoke(null, "SCALE");
        }
    }

    private static double clamp(double value) {
        return Math.max(0.0625, Math.min(16.0, value));
    }
}
