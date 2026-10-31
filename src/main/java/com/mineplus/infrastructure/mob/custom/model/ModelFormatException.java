package com.mineplus.infrastructure.mob.custom.model;

/** A model file this subsystem cannot use at all (unreadable JSON, wrong format). */
public class ModelFormatException extends RuntimeException {

    public ModelFormatException(String message) {
        super(message);
    }

    public ModelFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
