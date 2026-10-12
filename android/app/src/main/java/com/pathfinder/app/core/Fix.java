package com.pathfinder.app.core;

/** One GPS reading. */
public final class Fix {
    public final double lat;
    public final double lon;
    public final float accuracyM;
    public final float speedMs;
    public final float bearingDeg;
    public final boolean hasBearing;

    public Fix(double lat, double lon, float accuracyM, float speedMs, float bearingDeg, boolean hasBearing) {
        this.lat = lat;
        this.lon = lon;
        this.accuracyM = accuracyM;
        this.speedMs = speedMs;
        this.bearingDeg = bearingDeg;
        this.hasBearing = hasBearing;
    }
}
