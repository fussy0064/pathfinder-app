package com.pathfinder.app.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

/**
 * Downloads map packs from a catalog.json. Blocking: call from a background thread.
 *
 * Security rules (all enforced here):
 *  - https only (http is allowed only in tests), no redirects, no compression
 *  - file names come from a fixed list; paths cannot leave the base URL
 *  - every file must match the declared size and SHA-256 before it is installed
 *  - files are downloaded to a temp folder, then moved into place (no half-installed data)
 */
public final class PackManager {
    public interface Progress {
        void onProgress(long doneBytes, long totalBytes, String currentFile);
    }

    public static final class CancelToken {
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final String[] MOVE_ORDER = {"map_graph.bin.ch", "places.db", "tiles.mbtiles", "map_graph.bin"};

    private final URI base;
    private final File dataDir;

    /** allowInsecureForTests lets unit tests use a local http server. Never use it in the app. */
    public PackManager(String baseUrl, File dataDir, boolean allowInsecureForTests) throws PackException {
        URI u;
        try {
            u = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        } catch (IllegalArgumentException e) {
            throw new PackException("Pack server must be an https address");
        }
        boolean okScheme = "https".equals(u.getScheme()) || (allowInsecureForTests && "http".equals(u.getScheme()));
        if (!okScheme || u.getHost() == null || u.getHost().isEmpty()) throw new PackException("Pack server must be an https address");
        this.base = u;
        this.dataDir = dataDir;
    }

    private URL urlFor(String path) throws PackException {
        URI u = base.resolve(path);
        if (!java.util.Objects.equals(u.getScheme(), base.getScheme()) || !java.util.Objects.equals(u.getHost(), base.getHost())
                || u.getPort() != base.getPort() || !u.getPath().startsWith(base.getPath())) {
            throw new PackException("Pack file is outside the pack server");
        }
        try {
            return u.toURL();
        } catch (IOException e) {
            throw new PackException("Bad pack address");
        }
    }

    private HttpURLConnection open(URL url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("Accept-Encoding", "identity"); // sizes in the catalog are of the raw file
        return c;
    }

    private static PackException mapIo(IOException e) {
        if (e instanceof java.net.SocketTimeoutException) return new PackException("The server did not answer in time");
        if (e instanceof javax.net.ssl.SSLException) return new PackException("Secure connection failed");
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException) {
            return new PackException("No connection to the pack server");
        }
        return new PackException("Download failed");
    }

    public List<PackInfo> fetchCatalog() throws PackException {
        HttpURLConnection c = null;
        try {
            c = open(urlFor("catalog.json"));
            int code = c.getResponseCode();
            if (code != 200) throw new PackException("Server returned " + code);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    b.write(buf, 0, n);
                    if (b.size() > Catalog.MAX_CATALOG_BYTES) throw new PackException("Catalog is too large");
                }
            }
            return Catalog.parse(new String(b.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw mapIo(e);
        } catch (SecurityException e) {
            throw new PackException("This build has no internet permission");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Downloads and installs a pack. Throws PackException on any problem; nothing is installed then. */
    public void install(PackInfo pack, Progress progress, CancelToken cancel) throws PackException {
        File tmp = new File(dataDir, ".download");
        try {
            deleteTree(tmp);
            if (!tmp.mkdirs() && !tmp.isDirectory()) throw new PackException("Could not write to storage (is it full?)");
            long total = pack.totalBytes();
            long[] done = {0};
            for (PackFile f : pack.files) {
                download(f, new File(tmp, f.name), total, done, progress, cancel); // f.name is from the fixed list
            }
            for (String name : MOVE_ORDER) {
                File staged = new File(tmp, name);
                if (staged.exists()) {
                    File target = new File(dataDir, name);
                    if (target.exists() && !target.delete()) throw new PackException("Could not replace " + name);
                    if (!staged.renameTo(target)) throw new PackException("Could not install " + name);
                }
            }
            if (!new File(tmp, "map_graph.bin.ch").exists()) {
                File stale = new File(dataDir, "map_graph.bin.ch"); // an old index would not match the new graph
                if (stale.exists() && !stale.delete()) throw new PackException("Could not remove the old index");
            }
        } finally {
            deleteTree(tmp);
        }
    }

    private void download(PackFile f, File target, long total, long[] done, Progress progress, CancelToken cancel)
            throws PackException {
        HttpURLConnection c = null;
        try {
            c = open(urlFor(f.path));
            int code = c.getResponseCode();
            if (code != 200) throw new PackException("Server returned " + code + " for " + f.name);
            long declared = c.getContentLengthLong();
            if (declared >= 0 && declared != f.size) throw new PackException("Unexpected size for " + f.name);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long got = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel != null && cancel.isCancelled()) throw new PackException("Cancelled");
                    got += n;
                    if (got > f.size) throw new PackException("Server sent more data than declared");
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                    done[0] += n;
                    if (progress != null) progress.onProgress(done[0], total, f.name);
                }
            }
            if (got != f.size) throw new PackException("Download of " + f.name + " is incomplete");
            if (!hex(sha.digest()).equals(f.sha256)) throw new PackException("Checksum mismatch for " + f.name);
        } catch (IOException e) {
            throw mapIo(e);
        } catch (SecurityException e) {
            throw new PackException("This build has no internet permission");
        } catch (NoSuchAlgorithmException e) {
            throw new PackException("SHA-256 is not available");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Deletes all installed map data (graph, index, search, tiles). */
    public void removeAll() {
        for (String name : Catalog.ALLOWED_FILES) {
            File f = new File(dataDir, name);
            if (f.exists()) f.delete();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder();
        for (byte x : bytes) b.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return b.toString();
    }

    private static void deleteTree(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
