package com.pathfinder.app.core;

public final class GeoPoint {
    public final double lat;
    public final double lon;

    public GeoPoint(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
    }

    public static boolean valid(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }
}
