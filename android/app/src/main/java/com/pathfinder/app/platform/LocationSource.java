package com.pathfinder.app.platform;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.os.SystemClock;

import com.pathfinder.app.core.Controller;
import com.pathfinder.app.core.Fix;

/**
 * Position from the phone's LocationManager (no Google Play Services library).
 * Uses GPS plus the phone's fast Wi-Fi/cell/fused location, so the first position appears quickly.
 * Works with "Approximate" location too (Wi-Fi/cell only).
 */
public final class LocationSource implements LocationListener {
    private final Context context;
    private final LocationManager manager;
    private final Controller controller;
    private boolean running;
    private boolean receiverOn;
    /** The user switched Location on or off while the app is open: start (or restart) the search at once. */
    private final BroadcastReceiver modeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (running) restartSearch();
        }
    };
    private long lastGpsMs = -100000;
    private static final long STALE_MS = 6 * 3600 * 1000L; // a position from the last 6 hours is shown at once

    public LocationSource(Context context, Controller controller) {
        this.context = context;
        this.controller = controller;
        this.manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    /** True when the user allowed Precise or Approximate location. */
    public boolean hasPermission() {
        return fine() || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean fine() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isOn(String provider) {
        try {
            return manager != null && manager.isProviderEnabled(provider);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Wi-Fi/cell and (Android 12+) fused providers: fast first position, no GPS needed. */
    private static String[] fastProviders() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            return new String[] {LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER};
        }
        return new String[] {LocationManager.NETWORK_PROVIDER};
    }

    private void refreshState() {
        boolean any = fine() && isOn(LocationManager.GPS_PROVIDER);
        for (String name : fastProviders()) any |= isOn(name);
        controller.setLocationState(any ? Controller.LocationState.READY : Controller.LocationState.SERVICE_OFF);
    }

    @SuppressLint("MissingPermission")
    private void listen(String provider) {
        try {
            // also for a provider that is OFF now: Android calls us as soon as the user switches it on
            manager.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper());
        } catch (RuntimeException ignored) {
            // provider not on this phone: skip it, the others still work
        }
    }

    public void start() {
        if (!hasPermission()) {
            controller.setLocationState(Controller.LocationState.DENIED);
            return;
        }
        if (manager == null) {
            controller.setLocationState(Controller.LocationState.SERVICE_OFF);
            return;
        }
        if (running) { // called again (app back on screen): make sure every provider is registered, even if GPS was off before
            restartSearch();
            return;
        }
        running = true;
        if (!receiverOn) {
            try {
                IntentFilter f = new IntentFilter(LocationManager.MODE_CHANGED_ACTION);
                f.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
                context.registerReceiver(modeReceiver, f);
                receiverOn = true;
            } catch (RuntimeException ignored) {
                // the provider callbacks below still work
            }
        }
        restartSearch();
    }

    /** Safe to call many times: re-registers all providers, refreshes the state, shows the last position, asks for a fresh fix. */
    private void restartSearch() {
        if (fine()) listen(LocationManager.GPS_PROVIDER);
        for (String name : fastProviders()) listen(name);
        if (fine()) helpGpsColdStart();
        refreshState();
        pushLastKnown();
        requestQuickFix();
    }

    /** Ask the GPS chip to refresh its satellite help data (many phones support this; others ignore it). */
    private void helpGpsColdStart() {
        try {
            manager.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_time_injection", null);
            manager.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_xtra_injection", null);
        } catch (RuntimeException ignored) {
            // not supported on this phone
        }
    }

    /** Android 11+: ask once for a fix right now instead of waiting for the next update. */
    @SuppressLint("MissingPermission")
    private void requestQuickFix() {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        java.util.List<String> providers = new java.util.ArrayList<>(java.util.Arrays.asList(fastProviders()));
        if (fine()) providers.add(LocationManager.GPS_PROVIDER);
        for (String name : providers) {
            try {
                if (!isOn(name)) continue;
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
        String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, "fused"};
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
                l.hasSpeed() ? l.getSpeed() : -1f, l.getBearing(), l.hasBearing()));
    }

    public void stop() {
        if (!running) return;
        manager.removeUpdates(this);
        if (receiverOn) {
            try {
                context.unregisterReceiver(modeReceiver);
            } catch (RuntimeException ignored) {
                // already removed
            }
            receiverOn = false;
        }
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
        if (running) restartSearch(); // GPS or Wi-Fi location was just switched on: find the position now
    }

    @Override
    public void onProviderDisabled(String provider) {
        refreshState(); // one provider off (for example Wi-Fi location) must not hide a working GPS
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, android.os.Bundle extras) {
        // deprecated, not needed
    }
}
