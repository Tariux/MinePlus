package com.mineplus.infrastructure.collision.source;

/** A blueprint that is malformed or violates the schema. */
public class BlueprintException extends RuntimeException {

    public BlueprintException(String message) {
        super(message);
    }

    public BlueprintException(String message, Throwable cause) {
        super(message, cause);
    }
}
