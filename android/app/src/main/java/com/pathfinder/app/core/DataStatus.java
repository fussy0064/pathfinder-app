package com.pathfinder.app.core;

import java.io.File;

/** Where the offline data files are and which of them exist. */
public final class DataStatus {
    public final File dir;
    public final boolean hasGraph;
    public final boolean hasCh;
    public final boolean hasPlaces;
    public final boolean hasTiles;

    private DataStatus(File dir) {
        this.dir = dir;
        this.hasGraph = new File(dir, "map_graph.bin").isFile();
        this.hasCh = new File(dir, "map_graph.bin.ch").isFile();
        this.hasPlaces = new File(dir, "places.db").isFile();
        this.hasTiles = new File(dir, "tiles.mbtiles").isFile();
    }

    public static DataStatus check(File dir) {
        if (!dir.isDirectory()) dir.mkdirs();
        return new DataStatus(dir);
    }

    public File graph() { return new File(dir, "map_graph.bin"); }
    public File places() { return new File(dir, "places.db"); }
    public File tiles() { return new File(dir, "tiles.mbtiles"); }

    /** Deletes the installed map data files (nothing else). */
    public static void deleteAll(File dir) {
        for (String n : Catalog.ALLOWED_FILES) {
            File f = new File(dir, n);
            if (f.exists()) f.delete();
        }
    }
}
