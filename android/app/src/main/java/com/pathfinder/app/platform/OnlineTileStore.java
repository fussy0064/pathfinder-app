package com.pathfinder.app.platform;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

/** Downloads raster map tiles from OpenStreetMap. */
public final class OnlineTileStore implements TileSource {
    public final int minZoom = 1;
    public final int maxZoom = 19;

    private final ConcurrentHashMap<Long, byte[]> memory = new ConcurrentHashMap<>();
    private static final int MAX_CACHE = 256;

    @Override public int minZoom() { return minZoom; }

    @Override public int maxZoom() { return maxZoom; }

    @Override public void close() { memory.clear(); }

    @Override
    public byte[] tile(int z, int x, int y) {
        if (z < minZoom || z > maxZoom) return null;
        if (x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z)) return null;
        long key = ((long) z << 48) | ((long) x << 24) | y;
        byte[] cached = memory.get(key);
        if (cached != null) return cached;

        try {
            // OpenStreetMap tile server
            String url = "https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png";
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "PathfinderApp/1.0");
            c.setInstanceFollowRedirects(true);

            if (c.getResponseCode() != 200) {
                c.disconnect();
                return null;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            byte[] data = out.toByteArray();
            if (memory.size() < MAX_CACHE) memory.put(key, data);
            return data;
        } catch (Exception e) {
            return null;
        }
    }
}
