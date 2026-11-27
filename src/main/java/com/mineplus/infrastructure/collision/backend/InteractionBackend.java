package com.mineplus.infrastructure.collision.backend;

import com.mineplus.infrastructure.collision.fit.BackendType;
import com.mineplus.infrastructure.collision.runtime.PlacedFurniture;
import com.mineplus.infrastructure.collision.runtime.PlacedPart;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;

/**
 * Clickable, non-solid hitboxes built from {@link Interaction} entities.
 *
 * <p>Used for pass-through decoration and for parts that must receive clicks
 * without blocking movement. The hitbox size comes from the configured values
 * on the part; a cell-sized default keeps clicks on the whole cell.
 */
public class InteractionBackend implements CollisionBackend {

    /** Default width and height of a cell-sized interaction box. */
    public static final float CELL_SIZE = 0.98f;

    private final BackendType kind;

    public InteractionBackend() {
        this(BackendType.INTERACTION_ONLY);
    }

    protected InteractionBackend(BackendType kind) {
        this.kind = kind;
    }

    @Override
    public BackendType type() {
        return kind;
    }

    @Override
    public boolean supports(PlacedPart part) {
        return true;
    }

    @Override
    public int cost(PlacedPart part) {
        return 2;
    }

    @Override
    public void apply(BackendContext context, PlacedFurniture furniture, PlacedPart part) {
        if (part.entityId() != null && Bukkit.getEntity(part.entityId()) != null) {
            return;
        }
        Location location = context.centerLocation(furniture, part);
        Entity spawned = context.spawnTechnical(location, EntityType.INTERACTION);
        spawned.setCustomName(null);
        if (spawned instanceof Interaction interaction) {
            interaction.setInteractionWidth(CELL_SIZE);
            interaction.setInteractionHeight(CELL_SIZE);
            interaction.setResponsive(true);
        }
        part.setEntityType(EntityType.INTERACTION.name());
        part.setEntityId(spawned.getUniqueId());
    }

    @Override
    public void remove(BackendContext context, PlacedFurniture furniture, PlacedPart part) {
        if (part.entityId() == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(part.entityId());
        if (entity != null) {
            entity.remove();
        }
        part.setEntityId(null);
    }
}
