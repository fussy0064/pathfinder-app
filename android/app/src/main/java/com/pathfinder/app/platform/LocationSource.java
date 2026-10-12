package com.pathfinder.app.platform;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;

import com.pathfinder.app.core.Controller;
import com.pathfinder.app.core.Fix;

/** GPS from the phone's LocationManager (no Google Play Services needed, nothing leaves the phone). */
public final class LocationSource implements LocationListener {
    private final Context context;
    private final LocationManager manager;
    private final Controller controller;
    private boolean running;

    public LocationSource(Context context, Controller controller) {
        this.context = context;
        this.controller = controller;
        this.manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
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
        running = true;
        if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) controller.setLocationState(Controller.LocationState.READY);
        Location last = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (last != null) onLocationChanged(last);
    }

    public void stop() {
        if (!running) return;
        manager.removeUpdates(this);
        running = false;
    }

    @Override
    public void onLocationChanged(Location l) {
        controller.onFix(new Fix(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 999f,
                l.hasSpeed() ? l.getSpeed() : 0f, l.getBearing(), l.hasBearing()));
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
