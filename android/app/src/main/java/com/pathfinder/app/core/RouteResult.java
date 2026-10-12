package com.pathfinder.app.core;

/** A route. handle is the native route (release it with EngineClient.release when no longer needed). */
public final class RouteResult {
    public final long handle;
    public final double distanceM;
    public final double timeS;
    public final double[] lats;
    public final double[] lons;
    public final RouteStep[] steps;

    public RouteResult(long handle, double distanceM, double timeS, double[] lats, double[] lons, RouteStep[] steps) {
        this.handle = handle;
        this.distanceM = distanceM;
        this.timeS = timeS;
        this.lats = lats;
        this.lons = lons;
        this.steps = steps;
    }
}
