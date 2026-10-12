package com.pathfinder.app.core;

/** The SQL used on places.db. The same SQL is tested in tests/test_places.py (all values bound as TEXT, like Android does). */
public final class PlacesSql {
    private PlacesSql() {}

    public static final String SEARCH =
            "SELECT id, name, kind, lat, lon FROM places WHERE name_norm LIKE ? ESCAPE '\\' "
                    + "ORDER BY (name_norm LIKE ? ESCAPE '\\') DESC, length(name_norm), name_norm LIMIT ?";

    /** Makes % _ and \ match themselves in a LIKE pattern. */
    public static String escapeLike(String q) {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** n = number of kinds (1..5), near = order by distance to a position (6 extra arguments). */
    public static String kinds(int n, boolean near) {
        StringBuilder marks = new StringBuilder();
        for (int i = 0; i < n; i++) marks.append(i == 0 ? "?" : ",?");
        return "SELECT id, name, kind, lat, lon FROM places WHERE kind IN (" + marks + ") ORDER BY "
                + (near ? "((lat - ?) * (lat - ?) + ((lon - ?) * ?) * ((lon - ?) * ?))" : "name_norm") + " LIMIT ?";
    }
}
