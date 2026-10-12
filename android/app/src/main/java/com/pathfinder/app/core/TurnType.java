package com.pathfinder.app.core;

/** Maneuver kinds. The order matches PF_TURN_* in core/include/pathfinder.h. */
public enum TurnType {
    DEPART, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, U_TURN, ARRIVE;

    public static TurnType fromCode(int code) {
        TurnType[] all = values();
        return (code >= 0 && code < all.length) ? all[code] : STRAIGHT;
    }
}
