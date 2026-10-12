package com.pathfinder.app.core;

public final class Place {
    public final long id;
    public final String name;
    public final String kind;
    public final double lat;
    public final double lon;

    public Place(long id, String name, String kind, double lat, double lon) {
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.lat = lat;
        this.lon = lon;
    }

    public static Place dropped(double lat, double lon, String name) {
        return new Place(-1, name, "pin", lat, lon);
    }

    /** "amenity:fast_food" -> "Fast food" */
    public String kindLabel() {
        String k = kind.contains(":") ? kind.substring(kind.lastIndexOf(':') + 1) : kind;
        k = k.replace('_', ' ');
        return k.isEmpty() ? "" : k.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + k.substring(1);
    }
}
