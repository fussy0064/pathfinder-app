package com.pathfinder.app.platform;

import com.pathfinder.app.core.Tanzania;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Downloads raster map tiles (Tanzania only) from MapTiler (when a key is set) or OpenStreetMap, and keeps a copy on the phone (fast next time, works offline). */
public final class OnlineTileStore implements TileSource {
    public final int minZoom = 5;
    public final int maxZoom = 19;

    private static final int MAX_CACHE = 256;
    private static final long FRESH_MS = 7L * 24 * 3600 * 1000;
    private static final long MAX_DISK_BYTES = 60L * 1024 * 1024;

    /** OpenStreetMap asks for at most 2 downloads at once. Reading from the phone cache is not limited. */
    private final Semaphore net;
    private final String key; // MapTiler key (letters and digits only), or "" to use OpenStreetMap

    private final ConcurrentHashMap<Long, byte[]> memory = new ConcurrentHashMap<>();
    private final File dir; // may be null: no disk cache
    private int writes;

    public OnlineTileStore(File cacheDir, String mapTilerKey) {
        this.key = mapTilerKey != null && mapTilerKey.matches("[A-Za-z0-9]{8,64}") ? mapTilerKey : "";
        this.net = new Semaphore(key.isEmpty() ? 2 : 6);
        this.dir = cacheDir;
        if (dir != null) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
    }

    @Override public int minZoom() { return minZoom; }

    @Override public int maxZoom() { return maxZoom; }

    @Override public void close() { memory.clear(); }

    @Override
    public boolean covers(int z, int x, int y) {
        return z >= minZoom && z <= maxZoom && x >= 0 && y >= 0 && x < (1 << z) && y < (1 << z) && Tanzania.tileInside(z, x, y);
    }

    @Override
    public byte[] tile(int z, int x, int y) {
        if (z < minZoom || z > maxZoom) return null;
        if (!covers(z, x, y)) return null; // never download outside Tanzania
        long id = ((long) z << 48) | ((long) x << 24) | y;
        byte[] cached = memory.get(id);
        if (cached != null) return cached;

        File f = dir == null ? null : new File(dir, z + "_" + x + "_" + y + ".png");
        boolean onDisk = f != null && f.isFile() && f.length() > 0;
        if (onDisk && System.currentTimeMillis() - f.lastModified() < FRESH_MS) {
            byte[] d = read(f);
            if (d != null) return remember(id, d);
        }

        byte[] data = download(z, x, y);
        if (data == null) return onDisk ? read(f) : null; // offline: an old copy is better than a blank tile
        if (f != null) write(f, data);
        return remember(id, data);
    }

    /** The text the map must show. */
    @Override
    public String attribution() {
        return key.isEmpty() ? "\u00a9 OpenStreetMap contributors" : "\u00a9 MapTiler \u00a9 OpenStreetMap contributors";
    }

    private byte[] remember(long id, byte[] data) {
        if (memory.size() < MAX_CACHE) memory.put(id, data);
        return data;
    }

    private byte[] download(int z, int x, int y) {
        try {
            net.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        HttpURLConnection c = null;
        boolean reusable = false;
        try {
            String url = key.isEmpty() ? "https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png"
                    : "https://api.maptiler.com/maps/streets-v2/256/" + z + "/" + x + "/" + y + ".png?key=" + key;
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "PathfinderApp/1.0");
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            reusable = true; // fully read: Android keeps the connection open for the next tile (no new TLS handshake)
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null && !reusable) c.disconnect();
            net.release();
        }
    }

    private static byte[] read(File f) {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private void write(File f, byte[] data) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(data);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        if (++writes % 200 == 0) prune();
    }

    /** Keep the disk cache under the size limit by deleting the oldest tiles. */
    private void prune() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File x : files) total += x.length();
        if (total <= MAX_DISK_BYTES) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File x : files) {
            if (total <= MAX_DISK_BYTES * 8 / 10) break;
            long len = x.length();
            if (x.delete()) total -= len;
        }
    }
}
