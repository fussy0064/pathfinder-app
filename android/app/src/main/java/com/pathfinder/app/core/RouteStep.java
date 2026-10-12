package com.pathfinder.app.core;

public final class RouteStep {
    public final TurnType type;
    public final int pointIndex;
    /** Distance from this step to the next one (meters). */
    public final double distanceM;

    public RouteStep(TurnType type, int pointIndex, double distanceM) {
        this.type = type;
        this.pointIndex = pointIndex;
        this.distanceM = distanceM;
    }
}
