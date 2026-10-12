package com.pathfinder.app.nativeapi;

/**
 * JNI declarations for the C++ routing engine (core/jni/pathfinder_jni.cpp).
 * All functions are safe to call with stale handles: they return an error / null instead of crashing.
 * Not thread-safe per route: use EngineClient, which runs everything on one thread.
 */
public final class NativeEngine {
    static {
        System.loadLibrary("pathfinder_jni");
    }

    private NativeEngine() {}

    public static native String version();

    /** Returns an engine handle, or 0 (then status[0] has the error code). */
    public static native long open(String graphPath, int[] status);
    public static native void close(long engine);
    public static native int nodeCount(long engine);
    public static native boolean usesCh(long engine);

    /** Returns a route handle, or 0 (then status[0] has the error code). Free it with routeFree. */
    public static native long route(long engine, double startLat, double startLon, double targetLat, double targetLon, int[] status);
    public static native void routeFree(long route);
    /** {distance_m, time_s} */
    public static native double[] routeInfo(long route);
    public static native double[] routeLats(long route);
    public static native double[] routeLons(long route);
    public static native int[] routeStepTypes(long route);
    public static native int[] routeStepPoints(long route);
    public static native double[] routeStepDists(long route);

    /** out (length >= 7): along_m, off_route_m, remaining_m, to_next_m, bearing_deg, next_step, announce. Returns a status code. */
    public static native int progress(long route, double lat, double lon, double[] out);
}
