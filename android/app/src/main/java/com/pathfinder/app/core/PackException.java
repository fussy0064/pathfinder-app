package com.pathfinder.app.core;

/** Message is English (download details); the screens show it as is. */
public final class PackException extends Exception {
    private static final long serialVersionUID = 1L;

    public PackException(String message) {
        super(message);
    }
}
