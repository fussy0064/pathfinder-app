package com.pathfinder.app.platform;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

/** Downloads raster map tiles from OpenStreetMap and keeps a copy on the phone (fast next time, works offline). */
public final class OnlineTileStore implements TileSource {
    public final int minZoom = 1;
    public final int maxZoom = 19;

    private static final int MAX_CACHE = 256;
    private static final long FRESH_MS = 7L * 24 * 3600 * 1000;
    private static final long MAX_DISK_BYTES = 60L * 1024 * 1024;

    private final ConcurrentHashMap<Long, byte[]> memory = new ConcurrentHashMap<>();
    private final File dir; // may be null: no disk cache
    private int writes;

    public OnlineTileStore(File cacheDir) {
        this.dir = cacheDir;
        if (dir != null) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
    }

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

        File f = dir == null ? null : new File(dir, z + "_" + x + "_" + y + ".png");
        boolean onDisk = f != null && f.isFile() && f.length() > 0;
        if (onDisk && System.currentTimeMillis() - f.lastModified() < FRESH_MS) {
            byte[] d = read(f);
            if (d != null) return remember(key, d);
        }

        byte[] data = download(z, x, y);
        if (data == null) return onDisk ? read(f) : null; // offline: an old copy is better than a blank tile
        if (f != null) write(f, data);
        return remember(key, data);
    }

    private byte[] remember(long key, byte[] data) {
        if (memory.size() < MAX_CACHE) memory.put(key, data);
        return data;
    }

    private static byte[] download(int z, int x, int y) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png").openConnection();
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
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
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
