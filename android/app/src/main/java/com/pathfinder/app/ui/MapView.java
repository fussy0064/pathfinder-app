package com.pathfinder.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.pathfinder.app.core.Mercator;
import com.pathfinder.app.platform.MbtilesStore;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Offline map: draws raster tiles from an .mbtiles file, the route line, the destination pin and the user.
 * rotation is in degrees clockwise (heading-up uses rotation = -heading, so the travel direction points up).
 */
final class MapView extends View {
    interface Listener {
        void onLongPress(double lat, double lon);
    }

    private static final double MAX_ZOOM = 19;
    private static final int MAX_TILES_PER_DRAW = 400;

    private volatile MbtilesStore tiles;
    private final LruCache<Long, Bitmap> cache;
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final Set<Long> missing = ConcurrentHashMap.newKeySet();
    private final ExecutorService loader = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "pathfinder-tiles");
        t.setDaemon(true);
        return t;
    });

    private double centerLat = 0, centerLon = 0, zoom = 2;
    private float rotation = 0;
    private double[] routeLats, routeLons;
    private boolean hasDest, hasUser, userArrow;
    private double destLat, destLon, userLat, userLon;
    private float userHeading;
    private boolean interactive = true;
    private Listener listener;

    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint routeCasing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routeLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;

    MapView(Context context) {
        super(context);
        int bytes = (int) Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);
        cache = new LruCache<Long, Bitmap>(bytes) {
            @Override protected int sizeOf(Long key, Bitmap value) {
                return value.getByteCount();
            }
        };
        routeCasing.setStyle(Paint.Style.STROKE);
        routeCasing.setStrokeCap(Paint.Cap.ROUND);
        routeCasing.setStrokeJoin(Paint.Join.ROUND);
        routeCasing.setColor(0xFFFFFFFF);
        routeCasing.setStrokeWidth(Ui.dp(context, 9));
        routeLine.setStyle(Paint.Style.STROKE);
        routeLine.setStrokeCap(Paint.Cap.ROUND);
        routeLine.setStrokeJoin(Paint.Join.ROUND);
        routeLine.setColor(Ui.PRIMARY);
        routeLine.setStrokeWidth(Ui.dp(context, 5.5f));
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
        ring.setStrokeWidth(Ui.dp(context, 2.5f));
        setBackgroundColor(Ui.BG);

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                moveByScreen(dx, dy);
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                zoomBy(1);
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                if (listener == null) return;
                double[] ll = screenToLatLon(e.getX(), e.getY());
                listener.onLongPress(ll[0], ll[1]);
            }
        });
        scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomBy(Math.log(d.getScaleFactor()) / Math.log(2));
                return true;
            }
        });
    }

    // ---- API ----

    void setListener(Listener l) { listener = l; }

    void setInteractive(boolean on) { interactive = on; }

    void setTiles(MbtilesStore store) {
        tiles = store;
        cache.evictAll();
        pending.clear();
        missing.clear();
        invalidate();
    }

    void setRoute(double[] lats, double[] lons) {
        routeLats = lats;
        routeLons = lons;
        invalidate();
    }

    void setDestination(boolean has, double lat, double lon) {
        hasDest = has;
        destLat = lat;
        destLon = lon;
        invalidate();
    }

    void setUser(boolean has, double lat, double lon, float headingDeg, boolean arrow) {
        hasUser = has;
        userLat = lat;
        userLon = lon;
        userHeading = headingDeg;
        userArrow = arrow;
        invalidate();
    }

    void setCamera(double lat, double lon, double zoomLevel, float rotationDeg) {
        centerLat = Math.max(-Mercator.MAX_LAT, Math.min(Mercator.MAX_LAT, lat));
        centerLon = lon;
        zoom = clampZoom(zoomLevel);
        rotation = rotationDeg;
        invalidate();
    }

    void setRotation360(float rotationDeg) {
        rotation = rotationDeg;
        invalidate();
    }

    double zoomLevel() { return zoom; }

    /** Shows the whole box inside the free area left by the paddings (pixels). Needs the view to be laid out. */
    boolean fitBounds(double minLat, double minLon, double maxLat, double maxLon, int padL, int padT, int padR, int padB) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return false;
        double availW = Math.max(50, w - padL - padR), availH = Math.max(50, h - padT - padB);
        double boxW = Mercator.x(maxLon, 0) - Mercator.x(minLon, 0);
        double boxH = Mercator.y(minLat, 0) - Mercator.y(maxLat, 0);
        double z = 17;
        if (boxW > 1e-9) z = Math.min(z, Math.log(availW / boxW) / Math.log(2));
        if (boxH > 1e-9) z = Math.min(z, Math.log(availH / boxH) / Math.log(2));
        z = clampZoom(z);
        double cx = (Mercator.x(minLon, z) + Mercator.x(maxLon, z)) / 2, cy = (Mercator.y(minLat, z) + Mercator.y(maxLat, z)) / 2;
        double ox = padL + availW / 2 - w / 2.0, oy = padT + availH / 2 - h / 2.0;
        zoom = z;
        rotation = 0;
        centerLon = Mercator.lon(cx - ox, z);
        centerLat = Math.max(-Mercator.MAX_LAT, Math.min(Mercator.MAX_LAT, Mercator.lat(cy - oy, z)));
        invalidate();
        return true;
    }

    void shutdown() {
        loader.shutdownNow();
    }

    // ---- gestures ----

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!interactive) return false;
        scaler.onTouchEvent(ev);
        if (!scaler.isInProgress()) gestures.onTouchEvent(ev);
        return true;
    }

    private double clampZoom(double z) {
        MbtilesStore t = tiles;
        double min = t != null ? Math.max(1, t.minZoom - 1) : 1;
        return Math.max(min, Math.min(MAX_ZOOM, z));
    }

    private void zoomBy(double delta) {
        if (!interactive) return;
        zoom = clampZoom(zoom + delta);
        invalidate();
    }

    private void moveByScreen(float dx, float dy) {
        if (!interactive) return;
        double rad = Math.toRadians(-rotation);
        double wx = dx * Math.cos(rad) - dy * Math.sin(rad), wy = dx * Math.sin(rad) + dy * Math.cos(rad);
        double cx = Mercator.x(centerLon, zoom) + wx, cy = Mercator.y(centerLat, zoom) + wy;
        centerLon = Mercator.lon(cx, zoom);
        centerLat = Math.max(-Mercator.MAX_LAT, Math.min(Mercator.MAX_LAT, Mercator.lat(cy, zoom)));
        invalidate();
    }

    private double[] screenToLatLon(float sx, float sy) {
        double dx = sx - getWidth() / 2.0, dy = sy - getHeight() / 2.0;
        double rad = Math.toRadians(-rotation);
        double wx = dx * Math.cos(rad) - dy * Math.sin(rad), wy = dx * Math.sin(rad) + dy * Math.cos(rad);
        double lat = Mercator.lat(Mercator.y(centerLat, zoom) + wy, zoom);
        double lon = Mercator.lon(Mercator.x(centerLon, zoom) + wx, zoom);
        return new double[] {Math.max(-90, Math.min(90, lat)), lon};
    }

    private float[] toScreen(double lat, double lon, double cxw, double cyw, int w, int h) {
        double dx = Mercator.x(lon, zoom) - cxw, dy = Mercator.y(lat, zoom) - cyw;
        double rad = Math.toRadians(rotation);
        return new float[] {(float) (w / 2.0 + dx * Math.cos(rad) - dy * Math.sin(rad)), (float) (h / 2.0 + dx * Math.sin(rad) + dy * Math.cos(rad))};
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        double cxw = Mercator.x(centerLon, zoom), cyw = Mercator.y(centerLat, zoom);
        canvas.save();
        canvas.translate(w / 2f, h / 2f);
        canvas.rotate(rotation);
        drawTiles(canvas, w, h);
        drawRoute(canvas, cxw, cyw);
        canvas.restore();
        drawMarkers(canvas, cxw, cyw, w, h);
    }

    private void drawTiles(Canvas canvas, int w, int h) {
        final MbtilesStore store = tiles;
        if (store == null) return;
        int z = (int) Math.max(store.minZoom, Math.min(store.maxZoom, Math.round(zoom)));
        double scale = Math.pow(2, zoom - z);
        double cxz = Mercator.x(centerLon, z), cyz = Mercator.y(centerLat, z);
        double radius = Math.hypot(w, h) / 2.0 / scale;
        int n = 1 << z;
        int tx0 = Math.max(0, (int) Math.floor((cxz - radius) / Mercator.TILE)), tx1 = Math.min(n - 1, (int) Math.floor((cxz + radius) / Mercator.TILE));
        int ty0 = Math.max(0, (int) Math.floor((cyz - radius) / Mercator.TILE)), ty1 = Math.min(n - 1, (int) Math.floor((cyz + radius) / Mercator.TILE));
        if ((long) (tx1 - tx0 + 1) * (ty1 - ty0 + 1) > MAX_TILES_PER_DRAW) return;
        float size = (float) (Mercator.TILE * scale);
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                long key = ((long) z << 48) | ((long) tx << 24) | ty;
                Bitmap b = cache.get(key);
                if (b != null) {
                    float left = (float) ((tx * (double) Mercator.TILE - cxz) * scale), top = (float) ((ty * (double) Mercator.TILE - cyz) * scale);
                    rect.set(left, top, left + size, top + size);
                    canvas.drawBitmap(b, null, rect, tilePaint);
                } else if (!missing.contains(key)) {
                    request(store, key, z, tx, ty);
                }
            }
        }
    }

    private void request(final MbtilesStore store, final long key, final int z, final int x, final int y) {
        if (!pending.add(key)) return;
        try {
            loader.execute(() -> {
                try {
                    byte[] data = store.tile(z, x, y);
                    Bitmap bmp = data == null ? null : BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bmp != null) cache.put(key, bmp);
                    else missing.add(key);
                } finally {
                    pending.remove(key);
                }
                postInvalidate();
            });
        } catch (RuntimeException rejected) {
            pending.remove(key);
        }
    }

    private void drawRoute(Canvas canvas, double cxw, double cyw) {
        double[] lats = routeLats, lons = routeLons;
        if (lats == null || lons == null || lats.length < 2 || lats.length != lons.length) return;
        path.rewind();
        for (int i = 0; i < lats.length; i++) {
            float x = (float) (Mercator.x(lons[i], zoom) - cxw), y = (float) (Mercator.y(lats[i], zoom) - cyw);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, routeCasing);
        canvas.drawPath(path, routeLine);
    }

    private void drawMarkers(Canvas canvas, double cxw, double cyw, int w, int h) {
        float d = getResources().getDisplayMetrics().density;
        if (hasDest) {
            float[] p = toScreen(destLat, destLon, cxw, cyw, w, h);
            float r = 9 * d, headY = p[1] - 2.2f * r;
            fill.setColor(0xFFC62828);
            path.rewind();
            path.moveTo(p[0], p[1]);
            path.lineTo(p[0] - r * 0.85f, headY + r * 0.5f);
            path.lineTo(p[0] + r * 0.85f, headY + r * 0.5f);
            path.close();
            canvas.drawPath(path, fill);
            canvas.drawCircle(p[0], headY, r, fill);
            fill.setColor(0xFFFFFFFF);
            canvas.drawCircle(p[0], headY, r * 0.4f, fill);
        }
        if (hasUser) {
            float[] p = toScreen(userLat, userLon, cxw, cyw, w, h);
            if (userArrow) {
                canvas.save();
                canvas.translate(p[0], p[1]);
                canvas.rotate(userHeading + rotation); // heading-up: rotation = -heading, so the arrow points up
                path.rewind();
                path.moveTo(0, -17 * d);
                path.lineTo(12 * d, 14 * d);
                path.lineTo(0, 7 * d);
                path.lineTo(-12 * d, 14 * d);
                path.close();
                fill.setColor(Ui.PRIMARY);
                canvas.drawPath(path, fill);
                ring.setStrokeWidth(2 * d);
                canvas.drawPath(path, ring);
                canvas.restore();
            } else {
                fill.setColor(Ui.PRIMARY);
                canvas.drawCircle(p[0], p[1], 9 * d, fill);
                ring.setStrokeWidth(3 * d);
                canvas.drawCircle(p[0], p[1], 9 * d, ring);
            }
        }
    }
}
