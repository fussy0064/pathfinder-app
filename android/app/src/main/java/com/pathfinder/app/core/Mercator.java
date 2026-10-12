package com.pathfinder.app.core;

/** Web-Mercator math for the map view (256 px tiles). Pixel values are "world pixels" at the given zoom. */
public final class Mercator {
    public static final int TILE = 256;
    public static final double MAX_LAT = 85.0511287798;

    private Mercator() {}

    public static double worldSize(double zoom) {
        return TILE * Math.pow(2.0, zoom);
    }

    public static double x(double lon, double zoom) {
        return (lon + 180.0) / 360.0 * worldSize(zoom);
    }

    public static double y(double lat, double zoom) {
        double l = Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
        double s = Math.sin(Math.toRadians(l));
        double frac = 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
        return frac * worldSize(zoom);
    }

    public static double lon(double x, double zoom) {
        return x / worldSize(zoom) * 360.0 - 180.0;
    }

    public static double lat(double y, double zoom) {
        double n = Math.PI - 2.0 * Math.PI * y / worldSize(zoom);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }
}
