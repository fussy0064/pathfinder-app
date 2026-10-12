package com.pathfinder.app.core;

/** key is a text key (see Strings). */
public final class AiException extends Exception {
    private static final long serialVersionUID = 1L;

    public final String key;

    public AiException(String key) {
        super(key);
        this.key = key;
    }
}
