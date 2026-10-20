// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Keeps an embedded X server bound while at least one X11 window exists. */
public class DesktopChromeApp extends LorieApp implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "DesktopChrome";
    private final Handler main = new Handler(Looper.getMainLooper());
    private int windows;
    private boolean preparing;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "Embedded X server service connected");
            // The server announces readiness through the upstream ACTION_START protocol.
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            Log.e(TAG, "Embedded X server process disconnected");
        }
        @Override public void onNullBinding(ComponentName name) {
            showError(new IOException("The X server service returned no connection."));
            releaseServer();
        }
        @Override public void onBindingDied(ComponentName name) {
            showError(new IOException("The X server service stopped."));
            releaseServer();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        if (!(activity instanceof MainActivity))
            return;
        windows++;
        prepareAndBind();
    }

    private void prepareAndBind() {
        if (bound || preparing)
            return;
        preparing = true;
        new Thread(() -> {
            Exception failure = null;
            try {
                File runtime = new File(getFilesDir(), "runtime");
                File marker = new File(runtime, "xkb-ready-v1");
                if (!marker.isFile()) {
                    copyAssetTree("xkb", new File(runtime, "xkb"));
                    File rules = new File(runtime, "xkb/rules/evdev");
                    if (!rules.isFile())
                        throw new IOException("Bundled XKB rules are missing.");
                    if (!marker.createNewFile() && !marker.isFile())
                        throw new IOException("Cannot save XKB installation state.");
                }
                File tmp = new File(runtime, "tmp");
                if (!tmp.isDirectory() && !tmp.mkdirs())
                    throw new IOException("Cannot create the X11 runtime directory.");
            } catch (Exception e) {
                failure = e;
            }
            final Exception error = failure;
            main.post(() -> {
                preparing = false;
                if (windows == 0)
                    return;
                if (error != null) {
                    showError(error);
                    return;
                }
                try {
                    bound = bindService(new Intent(this, EmbeddedX11Service.class),
                            connection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
                    if (!bound)
                        showError(new IOException("Cannot bind the X server service."));
                } catch (Exception e) {
                    showError(e);
                }
            });
        }, "desktop-chrome-prepare").start();
    }

    private void copyAssetTree(String asset, File destination) throws IOException {
        String[] entries = getAssets().list(asset);
        if (entries != null && entries.length > 0) {
            if (!destination.isDirectory() && !destination.mkdirs())
                throw new IOException("Cannot create " + destination);
            for (String entry : entries)
                copyAssetTree(asset + "/" + entry, new File(destination, entry));
            return;
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create " + parent);
        try (InputStream input = getAssets().open(asset);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1)
                output.write(buffer, 0, count);
        }
    }

    private void showError(Exception error) {
        Log.e(TAG, "Embedded X11 startup failed", error);
        MainActivity activity = MainActivity.getFocusedOrAnyInstance();
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Desktop Chrome Android")
                    .setMessage("X11 startup failed:\n" + error)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void releaseServer() {
        if (bound) {
            bound = false;
            unbindService(connection);
        }
    }

    @Override public void onActivityDestroyed(Activity activity) {
        if (activity instanceof MainActivity && --windows == 0)
            releaseServer();
    }
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
}
