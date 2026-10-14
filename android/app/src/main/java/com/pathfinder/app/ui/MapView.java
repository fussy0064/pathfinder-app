package com.pathfinder.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.pathfinder.app.core.Guidance;
import com.pathfinder.app.core.Mercator;
import com.pathfinder.app.core.Tanzania;
import com.pathfinder.app.platform.TileSource;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Offline map: draws raster tiles from an .mbtiles file, the route line, the destination pin and the user.
 * rotation is in degrees clockwise (heading-up uses rotation = -heading, so the travel direction points up).
 */
final class MapView extends View {
    interface Listener {
        void onLongPress(double lat, double lon);
    }

    private static final double MAX_ZOOM = 19;
    private static final double MIN_ZOOM = 6; // whole Tanzania fits on screen
    private static final int MAX_TILES_PER_DRAW = 400;

    private volatile TileSource tiles;
    private final LruCache<Long, Bitmap> cache;
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final Set<Long> missing = ConcurrentHashMap.newKeySet();
    /** Newest request first: tiles you look at now load before tiles you scrolled past. */
    private static final class NewestFirstQueue extends LinkedBlockingDeque<Runnable> {
        @Override
        public boolean offer(Runnable r) {
            return offerFirst(r);
        }
    }

    private final ExecutorService loader = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, new NewestFirstQueue(), r -> {
        Thread t = new Thread(r, "pathfinder-tiles");
        t.setDaemon(true);
        return t;
    });
    private final Rect srcRect = new Rect();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator camAnim;

    private double centerLat = Tanzania.CENTER_LAT, centerLon = Tanzania.CENTER_LON, zoom = 6;
    private float rotation = 0;
    private double[] routeLats, routeLons;
    private boolean hasDest, hasUser, userArrow, hasPickup;
    private double pickupLat, pickupLon;
    private double destLat, destLon, userLat, userLon;
    private float userHeading;
    private boolean interactive = true;
    private Listener listener;

    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint attrPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        if (Ui.dark) { // dim and invert the light map tiles for dark mode
            tilePaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[] {
                    -0.8f, 0, 0, 0, 204,
                    0, -0.8f, 0, 0, 204,
                    0, 0, -0.8f, 0, 204,
                    0, 0, 0, 1, 0}));
        }

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

    void setTiles(TileSource store) {
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

    void setPickup(boolean has, double lat, double lon) {
        hasPickup = has;
        pickupLat = lat;
        pickupLon = lon;
        invalidate();
    }

    /** Map centre {lat, lon}. */
    double[] center() {
        return new double[] {centerLat, centerLon};
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
        if (camAnim != null) camAnim.cancel();
        centerLat = Tanzania.clampLat(lat);
        centerLon = Tanzania.clampLon(lon);
        zoom = clampZoom(zoomLevel);
        rotation = rotationDeg;
        invalidate();
    }

    /**
     * Navigation camera: follows the user smoothly and keeps the user in the lower part of the screen,
     * so more of the road (and the destination) ahead is visible.
     */
    void setNavCamera(double userLat, double userLon, double zoomLevel, float rotationDeg) {
        double z = clampZoom(zoomLevel);
        double off = getHeight() * 0.22; // pixels the map centre is ahead of the user
        double rad = Math.toRadians(rotationDeg);
        double cx = Mercator.x(userLon, z) - Math.sin(rad) * off;
        double cy = Mercator.y(userLat, z) - Math.cos(rad) * off;
        animateCamera(Mercator.lat(cy, z), Mercator.lon(cx, z), z, rotationDeg);
    }

    private void animateCamera(double lat, double lon, double z, float rotDeg) {
        final double toLat = Tanzania.clampLat(lat), toLon = Tanzania.clampLon(lon);
        final double sLat = centerLat, sLon = centerLon, sZoom = zoom, toZoom = z;
        final float sRot = rotation;
        final float dRot = ((rotDeg - sRot) % 360f + 540f) % 360f - 180f; // shortest way round
        if (camAnim != null) camAnim.cancel();
        boolean far = Math.abs(toLat - sLat) + Math.abs(toLon - sLon) > 0.01 || Math.abs(toZoom - sZoom) > 1.2;
        if (far || getWidth() == 0) { // big jump (first frame of navigation): no animation
            centerLat = toLat;
            centerLon = toLon;
            zoom = toZoom;
            rotation = rotDeg;
            invalidate();
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(950); // a little less than the 1 second between GPS fixes
        a.setInterpolator(new android.view.animation.LinearInterpolator());
        a.addUpdateListener(an -> {
            float t = (Float) an.getAnimatedValue();
            centerLat = sLat + (toLat - sLat) * t;
            centerLon = sLon + (toLon - sLon) * t;
            zoom = sZoom + (toZoom - sZoom) * t;
            rotation = sRot + dRot * t;
            invalidate();
        });
        camAnim = a;
        a.start();
    }

    void setRotation360(float rotationDeg) {
        if (camAnim != null) camAnim.cancel();
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
        centerLon = Tanzania.clampLon(Mercator.lon(cx - ox, z));
        centerLat = Tanzania.clampLat(Mercator.lat(cy - oy, z));
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
        TileSource t = tiles;
        double min = t != null ? Math.max(MIN_ZOOM, t.minZoom() - 1) : MIN_ZOOM;
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
        centerLon = Tanzania.clampLon(Mercator.lon(cx, zoom));
        centerLat = Tanzania.clampLat(Mercator.lat(cy, zoom));
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
        drawAttribution(canvas, h);
    }

    /** Small credit line for the map data owner (required by the map data licenses). */
    private void drawAttribution(Canvas canvas, int h) {
        TileSource store = tiles;
        String text = store == null ? null : store.attribution();
        if (text == null) return;
        float d = getResources().getDisplayMetrics().density;
        attrPaint.setTextSize(10 * d);
        attrPaint.setColor(0xFF222222);
        float w = attrPaint.measureText(text) + 8 * d;
        float x = 4 * d, y = h - 4 * d;
        fill.setColor(0xB3FFFFFF);
        canvas.drawRect(x, y - 14 * d, x + w, y, fill);
        canvas.drawText(text, x + 4 * d, y - 3.5f * d, attrPaint);
    }

    private void drawTiles(Canvas canvas, int w, int h) {
        final TileSource store = tiles;
        if (store == null) return;
        // On sharp (high density) screens use tiles one level lower: bigger, easier to read text and 4x fewer tiles to load.
        int shift = getResources().getDisplayMetrics().density >= 2f ? 1 : 0;
        int z = (int) Math.max(store.minZoom(), Math.min(store.maxZoom(), Math.round(zoom) - shift));
        double scale = Math.pow(2, zoom - z);
        double cxz = Mercator.x(centerLon, z), cyz = Mercator.y(centerLat, z);
        boolean upright = Math.abs(rotation) < 0.5f;
        double radiusX = (upright ? w : Math.hypot(w, h)) / 2.0 / scale, radiusY = (upright ? h : Math.hypot(w, h)) / 2.0 / scale;
        int n = 1 << z;
        int tx0 = Math.max(0, (int) Math.floor((cxz - radiusX) / Mercator.TILE)), tx1 = Math.min(n - 1, (int) Math.floor((cxz + radiusX) / Mercator.TILE));
        int ty0 = Math.max(0, (int) Math.floor((cyz - radiusY) / Mercator.TILE)), ty1 = Math.min(n - 1, (int) Math.floor((cyz + radiusY) / Mercator.TILE));
        if ((long) (tx1 - tx0 + 1) * (ty1 - ty0 + 1) > MAX_TILES_PER_DRAW) return;
        float size = (float) (Mercator.TILE * scale);
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                if (!store.covers(z, tx, ty)) continue;
                long key = ((long) z << 48) | ((long) tx << 24) | ty;
                Bitmap b = cache.get(key);
                if (b != null) {
                    float left = (float) ((tx * (double) Mercator.TILE - cxz) * scale), top = (float) ((ty * (double) Mercator.TILE - cyz) * scale);
                    rect.set(left, top, left + size, top + size);
                    canvas.drawBitmap(b, null, rect, tilePaint);
                } else {
                    float left = (float) ((tx * (double) Mercator.TILE - cxz) * scale), top = (float) ((ty * (double) Mercator.TILE - cyz) * scale);
                    rect.set(left, top, left + size, top + size);
                    drawCoarser(canvas, store, z, tx, ty);
                    if (!missing.contains(key)) {
                        request(store, key, z, tx, ty);
                        // also fetch a much bigger, coarser tile: it covers the screen with one small download
                        int up = Math.min(3, z - store.minZoom());
                        if (up > 0) {
                            int pz = z - up, ptx = tx >> up, pty = ty >> up;
                            long pkey = ((long) pz << 48) | ((long) ptx << 24) | pty;
                            if (cache.get(pkey) == null && !missing.contains(pkey) && store.covers(pz, ptx, pty)) request(store, pkey, pz, ptx, pty);
                        }
                    }
                }
            }
        }
    }

    /** While a tile loads, show the blurry part of a bigger tile we already have (into the current rect). */
    private void drawCoarser(Canvas canvas, TileSource store, int z, int tx, int ty) {
        for (int up = 1; up <= 4; up++) {
            int pz = z - up;
            if (pz < store.minZoom()) return;
            Bitmap pb = cache.get(((long) pz << 48) | ((long) (tx >> up) << 24) | (ty >> up));
            if (pb == null) continue;
            int part = Math.max(1, pb.getWidth() >> up), partH = Math.max(1, pb.getHeight() >> up);
            int sx = (tx & ((1 << up) - 1)) * part, sy = (ty & ((1 << up) - 1)) * partH;
            srcRect.set(sx, sy, sx + part, sy + partH);
            canvas.drawBitmap(pb, srcRect, rect, tilePaint);
            return;
        }
    }

    private void request(final TileSource store, final long key, final int z, final int x, final int y) {
        if (!pending.add(key)) return;
        try {
            loader.execute(() -> {
                try {
                    byte[] data = store.tile(z, x, y);
                    Bitmap bmp = data == null ? null : BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bmp != null) cache.put(key, bmp);
                    else {
                        missing.add(key);
                        postDelayed(() -> { // try again later (network may come back)
                            missing.remove(key);
                            invalidate();
                        }, 15000);
                    }
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

    /** While navigating and the destination is off screen: a red arrow at the screen edge pointing to it, with the distance. */
    private void drawDestinationPointer(Canvas canvas, float[] dest, int w, int h, float d) {
        float m = 28 * d;
        if (dest[0] >= m && dest[0] <= w - m && dest[1] >= m && dest[1] <= h - m) return; // visible: the pin is enough
        float[] u = toScreen(userLat, userLon, Mercator.x(centerLon, zoom), Mercator.y(centerLat, zoom), w, h);
        float dx = dest[0] - u[0], dy = dest[1] - u[1];
        float len = (float) Math.hypot(dx, dy);
        if (len < 1f) return;
        float tx = dx > 0 ? (w - m - u[0]) / dx : dx < 0 ? (m - u[0]) / dx : Float.MAX_VALUE;
        float ty = dy > 0 ? (h - m - u[1]) / dy : dy < 0 ? (m - u[1]) / dy : Float.MAX_VALUE;
        float t = Math.min(tx, ty);
        float qx = u[0] + dx * t, qy = u[1] + dy * t;
        float ux = dx / len, uy = dy / len;
        fill.setColor(0xFFC62828);
        canvas.drawCircle(qx, qy, 15 * d, fill);
        path.rewind();
        path.moveTo(qx + ux * 9 * d, qy + uy * 9 * d);
        path.lineTo(qx - ux * 5 * d - uy * 7 * d, qy - uy * 5 * d + ux * 7 * d);
        path.lineTo(qx - ux * 5 * d + uy * 7 * d, qy - uy * 5 * d - ux * 7 * d);
        path.close();
        fill.setColor(0xFFFFFFFF);
        canvas.drawPath(path, fill);
        double cos = Math.cos(Math.toRadians(userLat));
        double meters = 111320.0 * Math.hypot((destLat - userLat), (destLon - userLon) * cos);
        String text = Guidance.formatDistance(meters);
        labelPaint.setTextSize(12 * d);
        labelPaint.setFakeBoldText(true);
        labelPaint.setColor(0xFF222222);
        float tw = labelPaint.measureText(text);
        float lx = Math.max(4 * d, Math.min(w - tw - 12 * d, qx - ux * 30 * d - tw / 2)), ly = qy - uy * 30 * d;
        fill.setColor(0xE6FFFFFF);
        canvas.drawRoundRect(lx - 4 * d, ly - 13 * d, lx + tw + 8 * d, ly + 5 * d, 6 * d, 6 * d, fill);
        canvas.drawText(text, lx + 2 * d, ly, labelPaint);
    }

    private void drawMarkers(Canvas canvas, double cxw, double cyw, int w, int h) {
        float d = getResources().getDisplayMetrics().density;
        if (hasPickup) {
            float[] q = toScreen(pickupLat, pickupLon, cxw, cyw, w, h);
            fill.setColor(0xFF2E7D32);
            canvas.drawCircle(q[0], q[1], 9 * d, fill);
            fill.setColor(0xFFFFFFFF);
            canvas.drawCircle(q[0], q[1], 3.6f * d, fill);
        }
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
            if (hasUser && userArrow) drawDestinationPointer(canvas, p, w, h, d);
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
