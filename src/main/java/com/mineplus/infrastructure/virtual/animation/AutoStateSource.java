package com.mineplus.infrastructure.virtual.animation;

import org.bukkit.entity.LivingEntity;
import java.util.UUID;

/**
 * Resolves the living entity that drives automatic state selection for a
 * rendered model. A bridge (multiblock, rig, module) that binds a model to an
 * entity supplies this; models with no entity simply never auto-select.
 */
@FunctionalInterface
public interface AutoStateSource {

    /** Owning entity of the rendered model, or {@code null} when none is bound. */
    LivingEntity entityFor(UUID renderedModelId);
}
