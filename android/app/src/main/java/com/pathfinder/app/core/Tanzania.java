package com.pathfinder.app.core;

/** The only area the map shows: Tanzania (with a small margin). */
public final class Tanzania {
    public static final double MIN_LAT = -11.80, MAX_LAT = -0.90, MIN_LON = 29.30, MAX_LON = 40.50;
    public static final double CENTER_LAT = -6.37, CENTER_LON = 34.89;

    private Tanzania() {}

    public static double clampLat(double lat) {
        return Math.max(MIN_LAT, Math.min(MAX_LAT, lat));
    }

    public static double clampLon(double lon) {
        return Math.max(MIN_LON, Math.min(MAX_LON, lon));
    }

    /** True when the map tile touches Tanzania. */
    public static boolean tileInside(int z, int x, int y) {
        double west = Mercator.lon(x * (double) Mercator.TILE, z), east = Mercator.lon((x + 1) * (double) Mercator.TILE, z);
        double north = Mercator.lat(y * (double) Mercator.TILE, z), south = Mercator.lat((y + 1) * (double) Mercator.TILE, z);
        return east >= MIN_LON && west <= MAX_LON && north >= MIN_LAT && south <= MAX_LAT;
    }
}
