package com.pathfinder.app.core;

/**
 * Tanzania regions, found from a position. No data file needed.
 * Each region has a few anchor points (centre and main towns); the nearest anchor gives the region.
 * Near a region border the answer can be the neighbour region: treat it as a good guess, not a legal border.
 */
public final class TzRegions {
    /** Farther than this (in degrees, about 175 km) from every anchor: not in Tanzania. */
    private static final double MAX_DEG = 1.6;

    private static final String[] DATA = {
        "Arusha|-3.45|36.55",
        "Arusha|-3.37|36.68",
        "Dar es Salaam|-6.82|39.25",
        "Dar es Salaam|-6.95|39.3",
        "Dar es Salaam|-6.76|39.1",
        "Dodoma|-6.15|35.9",
        "Dodoma|-6.17|35.74",
        "Dodoma|-5.2|35.8",
        "Geita|-3.0|32.2",
        "Geita|-2.87|32.23",
        "Iringa|-8.0|35.2",
        "Iringa|-7.77|35.69",
        "Kagera|-1.9|31.3",
        "Kagera|-1.33|31.81",
        "Kagera|-2.4|31.2",
        "Katavi|-6.4|31.2",
        "Katavi|-6.35|31.07",
        "Kigoma|-4.8|29.95",
        "Kigoma|-4.88|29.63",
        "Kigoma|-4.58|30.1",
        "Kilimanjaro|-3.65|37.6",
        "Kilimanjaro|-3.35|37.34",
        "Kilimanjaro|-4.07|37.73",
        "Lindi|-9.6|38.8",
        "Lindi|-9.99|39.71",
        "Lindi|-9.0|39.3",
        "Lindi|-10.37|38.77",
        "Manyara|-4.7|36.6",
        "Manyara|-4.21|35.75",
        "Manyara|-3.85|35.5",
        "Mara|-1.75|34.1",
        "Mara|-1.5|33.8",
        "Mbeya|-8.9|33.6",
        "Mbeya|-8.9|33.46",
        "Mbeya|-9.2|33.7",
        "Morogoro|-7.3|37.0",
        "Morogoro|-6.82|37.66",
        "Morogoro|-8.13|36.68",
        "Mtwara|-10.6|39.4",
        "Mtwara|-10.27|40.18",
        "Mtwara|-10.95|39.28",
        "Mwanza|-2.6|32.9",
        "Mwanza|-2.52|32.9",
        "Njombe|-9.3|34.8",
        "Njombe|-9.34|34.77",
        "Pwani|-7.2|38.8",
        "Pwani|-6.77|38.92",
        "Pwani|-7.9|38.9",
        "Pwani|-6.44|38.9",
        "Rukwa|-7.8|31.6",
        "Rukwa|-7.97|31.62",
        "Ruvuma|-10.7|36.0",
        "Ruvuma|-10.68|35.65",
        "Ruvuma|-11.1|37.35",
        "Shinyanga|-3.65|33.3",
        "Shinyanga|-3.66|33.42",
        "Simiyu|-3.0|34.4",
        "Simiyu|-2.8|33.98",
        "Singida|-5.6|34.7",
        "Singida|-4.82|34.74",
        "Songwe|-8.9|32.8",
        "Songwe|-9.1|32.93",
        "Tabora|-5.3|32.9",
        "Tabora|-5.02|32.8",
        "Tanga|-4.9|38.5",
        "Tanga|-5.07|39.1",
        "Mjini Magharibi|-6.17|39.22",
        "Kaskazini Unguja|-5.85|39.3",
        "Kusini Unguja|-6.3|39.45",
        "Kaskazini Pemba|-5.05|39.77",
        "Kusini Pemba|-5.33|39.72"
    };
    private static final String[] NAME = new String[DATA.length];
    private static final double[] LAT = new double[DATA.length];
    private static final double[] LON = new double[DATA.length];

    static {
        for (int i = 0; i < DATA.length; i++) {
            String[] p = DATA[i].split("\\|");
            NAME[i] = p[0];
            LAT[i] = Double.parseDouble(p[1]);
            LON[i] = Double.parseDouble(p[2]);
        }
    }

    private TzRegions() {}

    private static int nearest(double lat, double lon, double[] outDeg) {
        double cos = Math.cos(Math.toRadians(lat));
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < LAT.length; i++) {
            double dy = lat - LAT[i], dx = (lon - LON[i]) * cos;
            double d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        outDeg[0] = Math.sqrt(bestD);
        return best;
    }

    /** Region name for a position inside Tanzania, or null. */
    public static String regionOf(double lat, double lon) {
        if (!GeoPoint.valid(lat, lon)) return null;
        double[] deg = new double[1];
        int i = nearest(lat, lon, deg);
        return i >= 0 && deg[0] <= MAX_DEG ? NAME[i] : null;
    }

    /** True when the position is inside Tanzania's box and not far from every region. */
    public static boolean inTanzania(double lat, double lon) {
        return lat >= Tanzania.MIN_LAT && lat <= Tanzania.MAX_LAT && lon >= Tanzania.MIN_LON && lon <= Tanzania.MAX_LON
                && regionOf(lat, lon) != null;
    }
}
