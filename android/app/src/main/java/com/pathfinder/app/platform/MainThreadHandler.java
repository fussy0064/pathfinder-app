package com.pathfinder.app.platform;

import android.os.Handler;
import android.os.Looper;

import com.pathfinder.app.core.Ports;

public final class MainThreadHandler implements Ports.MainThread {
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void post(Runnable r) {
        handler.post(r);
    }
}
