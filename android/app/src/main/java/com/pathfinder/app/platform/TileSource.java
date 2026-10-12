package com.pathfinder.app.platform;

/** Anything that can give map tiles (XYZ, y from the top). */
public interface TileSource {
    int minZoom();

    int maxZoom();

    /** PNG/JPG bytes, or null when the tile is not available. */
    byte[] tile(int z, int x, int y);

    /** False when this source never has the tile (outside its area): the map will not ask for it. */
    default boolean covers(int z, int x, int y) {
        return true;
    }

    /** Text the map must show (data owner), or null. */
    default String attribution() {
        return null;
    }

    void close();
}
