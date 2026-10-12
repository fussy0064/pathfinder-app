package com.pathfinder.app.core;

/** Smooths a compass heading (degrees) without the 359 -> 1 jump problem. */
public final class Heading {
    private Heading() {}

    public static double normalize(double degrees) {
        double r = degrees % 360.0;
        return r < 0 ? r + 360.0 : r;
    }

    /** previous == null: first value. alpha 0..1: higher follows new values faster. */
    public static double smooth(Double previous, double next, double alpha) {
        double n = normalize(next);
        if (previous == null) return n;
        double diff = n - previous;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        double a = Math.min(1.0, Math.max(0.0, alpha));
        return normalize(previous + diff * a);
    }
}
