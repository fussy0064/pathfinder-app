package com.pathfinder.app.platform;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.pathfinder.app.core.Place;
import com.pathfinder.app.core.PlacesSql;
import com.pathfinder.app.core.Ports;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline place search over places.db (built by tools/places/build_places.py). Opened read-only.
 * All values are bound as TEXT (Android rawQuery does that); tests/test_places.py runs the same SQL the same way.
 */
public final class PlacesDb implements Ports.PlacesIndex {
    private final SQLiteDatabase db;
    private volatile boolean closed;

    private PlacesDb(SQLiteDatabase db) {
        this.db = db;
    }

    public static PlacesDb open(String path) {
        SQLiteDatabase d = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor c = d.rawQuery("PRAGMA user_version", null)) {
            if (!c.moveToFirst() || c.getInt(0) != 1) throw new IllegalStateException("Unsupported places.db version");
            return new PlacesDb(d);
        } catch (RuntimeException e) {
            d.close();
            throw e;
        }
    }

    @Override
    public List<Place> search(String query, int limit) {
        if (closed) return new ArrayList<>();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.length() < 2) return new ArrayList<>();
        if (q.length() > 80) q = q.substring(0, 80);
        String e = PlacesSql.escapeLike(q);
        return read(PlacesSql.SEARCH, new String[] {"%" + e + "%", e + "%", Integer.toString(clamp(limit))});
    }

    @Override
    public List<Place> byKinds(List<String> kinds, boolean hasPosition, double lat, double lon, int limit) {
        if (closed || kinds.isEmpty()) return new ArrayList<>();
        List<String> ks = kinds.size() > 5 ? kinds.subList(0, 5) : kinds;
        List<String> args = new ArrayList<>(ks);
        boolean near = hasPosition && !Double.isNaN(lat) && !Double.isNaN(lon);
        if (near) {
            String c = Double.toString(Math.cos(Math.toRadians(lat)));
            String la = Double.toString(lat), lo = Double.toString(lon);
            args.add(la);
            args.add(la);
            args.add(lo);
            args.add(c);
            args.add(lo);
            args.add(c);
        }
        args.add(Integer.toString(clamp(limit)));
        return read(PlacesSql.kinds(ks.size(), near), args.toArray(new String[0]));
    }

    private List<Place> read(String sql, String[] args) {
        List<Place> out = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, args)) {
            while (c.moveToNext()) out.add(new Place(c.getLong(0), c.getString(1), c.getString(2), c.getDouble(3), c.getDouble(4)));
        }
        return out;
    }

    private static int clamp(int limit) {
        return Math.min(50, Math.max(1, limit));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        db.close();
    }
}
