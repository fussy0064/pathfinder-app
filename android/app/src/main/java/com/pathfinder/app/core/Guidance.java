package com.pathfinder.app.core;

import java.util.Locale;

/** Texts for turns, spoken phrases and distances/durations, in the language of the given Strings. */
public final class Guidance {
    private Guidance() {}

    public static String turnText(Strings t, TurnType type) {
        switch (type) {
            case DEPART: return t.get("turnDepart");
            case SLIGHT_LEFT: return t.get("turnSlightLeft");
            case LEFT: return t.get("turnLeft");
            case SHARP_LEFT: return t.get("turnSharpLeft");
            case SLIGHT_RIGHT: return t.get("turnSlightRight");
            case RIGHT: return t.get("turnRight");
            case SHARP_RIGHT: return t.get("turnSharpRight");
            case U_TURN: return t.get("turnUturn");
            case ARRIVE: return t.get("turnArrive");
            case STRAIGHT:
            default: return t.get("turnStraight");
        }
    }

    public static String lowerFirst(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toLowerCase(Locale.ROOT) + s.substring(1);
    }

    /** Distance as it is spoken: "50 meters", "300 meters", "1.2 kilometers" / "mita 50", "kilomita 1.2". */
    public static String spokenDistance(Strings t, double meters) {
        double m = (Double.isNaN(meters) || meters < 0) ? 0.0 : meters;
        if (m >= 1000) {
            double km = m / 1000.0;
            String n = km < 10 ? String.format(Locale.ROOT, "%.1f", km) : Long.toString(Math.round(km));
            return t.get("vKilometers", "n", n);
        }
        long rounded = m < 100 ? Math.max(10, Math.round(m / 10.0) * 10) : Math.round(m / 50.0) * 50;
        return t.get("vMeters", "n", Long.toString(rounded));
    }

    /** What to say for a voice cue. level: 1 about 500 m, 2 about 200 m, 3 now. null = nothing to say. */
    public static String phrase(Strings t, TurnType type, int level, double distanceM) {
        if (level <= 0) return null;
        if (type == TurnType.ARRIVE) return t.get("vArriveIn", "dist", spokenDistance(t, distanceM));
        String action = turnText(t, type);
        if (level >= 3) return action + ".";
        return t.get("vInDist", "dist", spokenDistance(t, distanceM), "action", lowerFirst(action));
    }

    public static String formatDistance(double m) {
        if (Double.isNaN(m) || m < 0) return "-";
        if (m < 1000) {
            long r = m < 100 ? Math.round(m / 5.0) * 5 : Math.round(m / 10.0) * 10;
            return r + " m";
        }
        return String.format(Locale.ROOT, "%.1f km", m / 1000.0);
    }

    public static String formatDuration(Strings t, double seconds) {
        if (Double.isNaN(seconds) || seconds < 0) return "-";
        long min = Math.round(seconds / 60.0);
        if (min < 1) return t.get("lessThanMin");
        if (min < 60) return t.get("minutes", "n", Long.toString(min));
        return t.get("hoursMinutes", "h", Long.toString(min / 60), "m", String.format(Locale.ROOT, "%02d", min % 60));
    }
}
