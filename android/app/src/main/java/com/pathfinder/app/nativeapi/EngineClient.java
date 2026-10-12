package com.pathfinder.app.nativeapi;

import com.pathfinder.app.core.Ports;
import com.pathfinder.app.core.RouteProgress;
import com.pathfinder.app.core.RouteResult;
import com.pathfinder.app.core.RouteStep;
import com.pathfinder.app.core.TurnType;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs every native engine call on ONE background thread and delivers results on the main thread.
 * Status codes match core/include/pathfinder.h (8 = the native library could not be loaded).
 */
public final class EngineClient {
    public static final int STATUS_BAD_ARGS = 1;
    public static final int STATUS_INTERNAL = 7;
    public static final int STATUS_LIBRARY = 8;

    public interface Callback<T> {
        void onResult(T value);
        void onError(int status);
    }

    private final Ports.MainThread main;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pathfinder-engine");
        t.setDaemon(true);
        return t;
    });
    private long engine; // touched only on the engine thread
    private volatile boolean closed;
    private volatile boolean usesCh;
    private volatile int nodeCount;

    public EngineClient(Ports.MainThread main) {
        this.main = main;
    }

    public boolean usesCh() {
        return usesCh;
    }

    public int nodeCount() {
        return nodeCount;
    }

    private void run(Runnable task, Runnable onRejected) {
        try {
            exec.execute(task);
        } catch (RejectedExecutionException e) {
            if (onRejected != null) main.post(onRejected);
        }
    }

    public void open(String graphPath, Callback<EngineClient> cb) {
        run(() -> {
            try {
                int[] st = new int[1];
                long e = NativeEngine.open(graphPath, st);
                if (e == 0) {
                    main.post(() -> cb.onError(st[0] == 0 ? STATUS_INTERNAL : st[0]));
                    return;
                }
                engine = e;
                nodeCount = NativeEngine.nodeCount(e);
                usesCh = NativeEngine.usesCh(e);
                main.post(() -> cb.onResult(this));
            } catch (LinkageError e) {
                main.post(() -> cb.onError(STATUS_LIBRARY));
            }
        }, () -> cb.onError(STATUS_INTERNAL));
    }

    public void route(double slat, double slon, double tlat, double tlon, Callback<RouteResult> cb) {
        run(() -> {
            if (engine == 0) {
                main.post(() -> cb.onError(STATUS_BAD_ARGS));
                return;
            }
            int[] st = new int[1];
            long h = NativeEngine.route(engine, slat, slon, tlat, tlon, st);
            if (h == 0) {
                main.post(() -> cb.onError(st[0] == 0 ? STATUS_INTERNAL : st[0]));
                return;
            }
            double[] info = NativeEngine.routeInfo(h);
            double[] lats = NativeEngine.routeLats(h);
            double[] lons = NativeEngine.routeLons(h);
            int[] types = NativeEngine.routeStepTypes(h);
            int[] points = NativeEngine.routeStepPoints(h);
            double[] dists = NativeEngine.routeStepDists(h);
            if (info == null || lats == null || lons == null || types == null || points == null || dists == null
                    || lats.length != lons.length || types.length != points.length || types.length != dists.length) {
                NativeEngine.routeFree(h);
                main.post(() -> cb.onError(STATUS_INTERNAL));
                return;
            }
            RouteStep[] steps = new RouteStep[types.length];
            for (int i = 0; i < steps.length; i++) steps[i] = new RouteStep(TurnType.fromCode(types[i]), points[i], dists[i]);
            RouteResult r = new RouteResult(h, info[0], info[1], lats, lons, steps);
            main.post(() -> cb.onResult(r));
        }, () -> cb.onError(STATUS_BAD_ARGS));
    }

    public void progress(RouteResult route, double lat, double lon, Callback<RouteProgress> cb) {
        run(() -> {
            double[] out = new double[7];
            int st = NativeEngine.progress(route.handle, lat, lon, out);
            if (st != 0) {
                main.post(() -> cb.onError(st));
                return;
            }
            RouteProgress p = new RouteProgress(out[0], out[1], out[2], out[3], out[4], (int) out[5], (int) out[6]);
            main.post(() -> cb.onResult(p));
        }, () -> cb.onError(STATUS_BAD_ARGS));
    }

    public void release(RouteResult route) {
        run(() -> NativeEngine.routeFree(route.handle), null);
    }

    /** Frees the engine and stops the thread. done runs on the main thread. */
    public void close(Runnable done) {
        if (closed) {
            if (done != null) main.post(done);
            return;
        }
        closed = true;
        run(() -> {
            if (engine != 0) NativeEngine.close(engine);
            engine = 0;
            exec.shutdown();
            if (done != null) main.post(done);
        }, done == null ? null : done);
    }
}
