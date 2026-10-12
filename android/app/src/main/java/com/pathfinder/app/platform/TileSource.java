package com.pathfinder.app.platform;

/** Anything that can give map tiles (XYZ, y from the top). */
public interface TileSource {
    int minZoom();

    int maxZoom();

    /** PNG/JPG bytes, or null when the tile is not available. */
    byte[] tile(int z, int x, int y);

    void close();
}
