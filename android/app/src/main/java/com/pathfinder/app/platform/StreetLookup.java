package com.pathfinder.app.platform;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Finds the street name for a point (online build only) using OpenStreetMap Nominatim.
 * Only the point's latitude and longitude are sent. Returns null when offline or not found.
 */
public final class StreetLookup {
    private static long lastCall;

    private StreetLookup() {}

    /** One request per second at most (Nominatim rule). Call from a background thread. */
    public static synchronized String lookup(double lat, double lon, String lang) {
        long wait = 1000 - (System.currentTimeMillis() - lastCall);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        lastCall = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            String l = "sw".equals(lang) ? "sw" : "en";
            String url = String.format(Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&accept-language=%s&lat=%.6f&lon=%.6f", l, lat, lon);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "PathfinderApp/1.0");
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 65536) out.write(buf, 0, n);
            }
            return name(new JSONObject(out.toString("UTF-8")));
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String name(JSONObject json) {
        JSONObject a = json.optJSONObject("address");
        if (a != null) {
            String road = first(a, "road", "pedestrian", "footway", "path", "residential", "track");
            String area = first(a, "suburb", "neighbourhood", "city_district", "quarter", "village", "town", "city", "county");
            if (!road.isEmpty() && !area.isEmpty()) return road + ", " + area;
            if (!road.isEmpty()) return road;
            if (!area.isEmpty()) return area;
        }
        String display = json.optString("display_name", "");
        if (display.isEmpty()) return null;
        String[] parts = display.split(",");
        return parts.length >= 2 ? parts[0].trim() + ", " + parts[1].trim() : parts[0].trim();
    }

    private static String first(JSONObject a, String... keys) {
        for (String k : keys) {
            String v = a.optString(k, "").trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }
}
