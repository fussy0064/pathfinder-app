package com.pathfinder.app.platform;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.os.SystemClock;

import com.pathfinder.app.core.Controller;
import com.pathfinder.app.core.Fix;

/** GPS from the phone's LocationManager (no Google Play Services needed, nothing leaves the phone). */
public final class LocationSource implements LocationListener {
    private final Context context;
    private final LocationManager manager;
    private final Controller controller;
    private boolean running;
    private long lastGpsMs = -100000;
    /** Online build only: Wi-Fi/cell location gives a fast first position while GPS warms up. */
    private final boolean useNetwork;
    private static final long STALE_MS = 30 * 60 * 1000L;

    public LocationSource(Context context, Controller controller) {
        this.context = context;
        this.controller = controller;
        this.manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.useNetwork = context.getPackageName().endsWith(".online");
    }

    public boolean hasPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        if (running) return;
        if (!hasPermission()) {
            controller.setLocationState(Controller.LocationState.DENIED);
            return;
        }
        if (manager == null || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            controller.setLocationState(Controller.LocationState.SERVICE_OFF);
            // keep listening: the user may switch GPS on
        }
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 3f, this, Looper.getMainLooper());
        } catch (RuntimeException e) {
            controller.setLocationState(Controller.LocationState.SERVICE_OFF);
            return;
        }
        if (useNetwork) {
            try {
                if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 10f, this, Looper.getMainLooper());
                }
            } catch (RuntimeException ignored) {
                // no network provider: GPS only
            }
        }
        running = true;
        if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) controller.setLocationState(Controller.LocationState.READY);
        pushLastKnown();
        requestQuickFix();
    }

    /** Android 11+: ask once for a fix right now instead of waiting for the next update. */
    @SuppressLint("MissingPermission")
    private void requestQuickFix() {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        String[] providers = useNetwork
                ? new String[] {LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}
                : new String[] {LocationManager.GPS_PROVIDER};
        for (String name : providers) {
            try {
                if (!manager.isProviderEnabled(name)) continue;
                manager.getCurrentLocation(name, null, context.getMainExecutor(), l -> {
                    if (l != null && running) onLocationChanged(l);
                });
            } catch (RuntimeException ignored) {
                // not available: normal updates still work
            }
        }
    }

    /** Show the newest recent position at once, before the first live fix arrives. */
    @SuppressLint("MissingPermission")
    private void pushLastKnown() {
        Location best = null;
        String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        for (String name : providers) {
            try {
                Location l = manager.getLastKnownLocation(name);
                if (l == null) continue;
                long ageMs = (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1000000L;
                if (ageMs > STALE_MS) continue;
                if (best == null || l.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos()) best = l;
            } catch (RuntimeException ignored) {
                // provider missing: skip
            }
        }
        if (best != null) push(best);
    }

    private void push(Location l) {
        controller.onFix(new Fix(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 999f,
                l.hasSpeed() ? l.getSpeed() : 0f, l.getBearing(), l.hasBearing()));
    }

    public void stop() {
        if (!running) return;
        manager.removeUpdates(this);
        running = false;
    }

    @Override
    public void onLocationChanged(Location l) {
        long now = SystemClock.elapsedRealtime();
        if (LocationManager.GPS_PROVIDER.equals(l.getProvider())) lastGpsMs = now;
        else if (now - lastGpsMs < 10000L) return; // a fresh GPS fix is better than Wi-Fi/cell
        push(l);
    }

    @Override
    public void onProviderEnabled(String provider) {
        controller.setLocationState(Controller.LocationState.READY);
    }

    @Override
    public void onProviderDisabled(String provider) {
        controller.setLocationState(Controller.LocationState.SERVICE_OFF);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, android.os.Bundle extras) {
        // deprecated, not needed
    }
}
