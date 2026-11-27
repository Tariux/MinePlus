package com.mineplus.infrastructure.collision.fit;

/** How a planned cell becomes something the client and server respect. */
public enum BackendType {
    /** Nothing is placed for this cell. */
    NONE,
    /** A real block in the world: solid for players, entities, projectiles. */
    REAL_BLOCK,
    /** A client-only block change for nearby viewers. */
    GHOST_BLOCK,
    /** A scaled, invisible shulker entity approximating an off-grid box. */
    SHULKER,
    /** An Interaction entity: clicks and hits without collision. */
    INTERACTION_ONLY,
    /** A no-collision block state that still draws an outline and takes clicks. */
    PASSABLE_OUTLINE,
    /** A boat/minecart platform for thin walkable plates (opt-in). */
    PLATE;

    public boolean isEntity() {
        return this == SHULKER || this == INTERACTION_ONLY || this == PLATE
                || this == PASSABLE_OUTLINE;
    }

    public boolean isRealBlock() {
        return this == REAL_BLOCK;
    }

    public boolean placesSomething() {
        return this != NONE;
    }

    /** Relative cost used by debug output and metrics. */
    public int cost() {
        return switch (this) {
            case NONE -> 0;
            case REAL_BLOCK -> 1;
            case GHOST_BLOCK -> 2;
            case PASSABLE_OUTLINE -> 3;
            case INTERACTION_ONLY -> 4;
            case SHULKER -> 6;
            case PLATE -> 8;
        };
    }
}
