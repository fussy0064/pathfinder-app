package com.pathfinder.app.platform;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import java.util.HashMap;
import java.util.Map;

/** Reads raster (png/jpg) map tiles from an .mbtiles file, opened read-only. */
public final class MbtilesStore {
    /** Thrown when the file holds vector tiles (not supported) or cannot be read. */
    public static final class TilesException extends Exception {
        private static final long serialVersionUID = 1L;
        public final boolean vector;

        TilesException(String message, boolean vector) {
            super(message);
            this.vector = vector;
        }
    }

    public final int minZoom;
    public final int maxZoom;
    /** {lat, lon} or null */
    public final double[] center;
    private final SQLiteDatabase db;
    private volatile boolean closed;

    private MbtilesStore(SQLiteDatabase db, int minZoom, int maxZoom, double[] center) {
        this.db = db;
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.center = center;
    }

    public static MbtilesStore open(String path) throws TilesException {
        SQLiteDatabase d;
        try {
            d = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY);
        } catch (SQLiteException e) {
            throw new TilesException("cannot open", false);
        }
        try {
            Map<String, String> meta = new HashMap<>();
            try (Cursor c = d.rawQuery("SELECT name, value FROM metadata", null)) {
                while (c.moveToNext()) meta.put(c.getString(0), c.getString(1));
            } catch (SQLiteException ignored) {
                // no metadata table: use defaults
            }
            String format = meta.containsKey("format") ? meta.get("format") : "png";
            if (format != null && format.equalsIgnoreCase("pbf")) throw new TilesException("vector tiles", true);
            int minZ = clampZoom(parse(meta.get("minzoom"), 0));
            int maxZ = Math.max(minZ, clampZoom(parse(meta.get("maxzoom"), 16)));
            double[] center = null;
            String cs = meta.get("center");
            if (cs != null) {
                String[] parts = cs.split(",");
                if (parts.length >= 2) {
                    try {
                        double lon = Double.parseDouble(parts[0].trim()), lat = Double.parseDouble(parts[1].trim());
                        if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180) center = new double[] {lat, lon};
                    } catch (NumberFormatException ignored) {
                        // bad center: ignore
                    }
                }
            }
            return new MbtilesStore(d, minZ, maxZ, center);
        } catch (TilesException | RuntimeException e) {
            d.close();
            if (e instanceof TilesException) throw (TilesException) e;
            throw new TilesException("cannot read", false);
        }
    }

    private static int parse(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clampZoom(int z) {
        return Math.min(22, Math.max(0, z));
    }

    /** Tile bytes for XYZ coordinates (y from the top), or null when the file has no such tile. */
    public byte[] tile(int z, int x, int y) {
        if (closed || z < 0 || z > 30) return null;
        int row = (1 << z) - 1 - y; // mbtiles rows are TMS (flipped)
        try (Cursor c = db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
                new String[] {Integer.toString(z), Integer.toString(x), Integer.toString(row)})) {
            return c.moveToFirst() ? c.getBlob(0) : null;
        } catch (RuntimeException e) {
            return null; // closed meanwhile or damaged tile table
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        db.close();
    }
}
