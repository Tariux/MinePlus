package com.mineplus.infrastructure.collision.source;

/**
 * One blueprint directive.
 *
 * @param type       directive kind
 * @param forceState full block state for {@code FORCE_BLOCK}, otherwise null
 */
public record CellDirective(DirectiveType type, String forceState) {

    public CellDirective {
        if (type == null) {
            throw new IllegalArgumentException("directive needs a type");
        }
        if (type == DirectiveType.FORCE_BLOCK && (forceState == null || forceState.isBlank())) {
            throw new IllegalArgumentException("FORCE_BLOCK needs a state");
        }
        if (type != DirectiveType.FORCE_BLOCK) {
            forceState = null;
        }
    }

    public static CellDirective solid() {
        return new CellDirective(DirectiveType.SOLID, null);
    }

    public static CellDirective empty() {
        return new CellDirective(DirectiveType.EMPTY, null);
    }

    public boolean forcesState() {
        return type == DirectiveType.FORCE_BLOCK;
    }

    /** Token form used by the parser and debug output. */
    public String format() {
        return forcesState() ? type.name() + ':' + forceState : type.name();
    }
}
