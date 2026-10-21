// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.ProgressDialog;
import android.content.*;
import android.os.*;
import android.util.Log;
import android.widget.Toast;
import java.io.*;

/** Prepares the persistent browser installation and keeps its display service bound. */
@SuppressWarnings("deprecation")
public class DesktopChromeApp extends LorieApp implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "DesktopChrome";
    /** Height of the desktop-mode caption bar, measured as 56 px at 1.33 density. */
    private static final int DESKTOP_CAPTION_HEIGHT_DP = 42;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int windows;
    private boolean preparing, starting, bound;
    private ProgressDialog progressDialog;
    private Activity progressOwner;
    private AlertDialog errorDialog;
    private volatile String progressText = "Preparing browser";
    private volatile int progressPercent = -1;
    private long lastProgressPost;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "Embedded display service connected");
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (bound && windows > 0) {
                releaseServer();
                showError(new IOException("The display process stopped."));
            }
        }
        @Override public void onNullBinding(ComponentName name) {
            releaseServer();
            showError(new IOException("The display service returned no connection."));
        }
        @Override public void onBindingDied(ComponentName name) {
            releaseServer();
            showError(new IOException("The display service stopped."));
        }
    };

    private final BroadcastReceiver sessionState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (windows == 0) return;
            String state = intent.getStringExtra("state");
            if ("started".equals(state)) {
                starting = false;
                dismissProgress();
            } else if ("exited".equals(state)) {
                starting = false;
                dismissProgress();
                int code = intent.getIntExtra("code", -1);
                if (code == 0) {
                    for (MainActivity activity : MainActivity.getInstances()) activity.finish();
                } else {
                    releaseServer();
                    showError(new IOException("Chrome stopped (" + code + ").\n"
                            + intent.getStringExtra("details")));
                }
            } else if ("error".equals(state)) {
                releaseServer();
                showError(new IOException(intent.getStringExtra("details")));
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        MainActivity.browserVisibilityListener = visible -> LinuxRuntime.writeBrowserVisibility(this, visible);
        IntentFilter filter = new IntentFilter(EmbeddedX11Service.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(sessionState, filter, Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(sessionState, filter);
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {
        if (!(activity instanceof MainActivity)) return;
        // The browser session picks the display scale of the display it starts on.
        boolean onExternal = ((MainActivity) activity).isOnExternalDisplay();
        boolean external = onExternal || MainActivity.presentationLikely(this, builtInPrefs);
        // Android's desktop mode draws a window caption bar over the top of the window
        // on external displays without reporting it to this app; keep Chrome below it.
        int captionHeight = onExternal
                ? Math.round(DESKTOP_CAPTION_HEIGHT_DP * activity.getResources().getDisplayMetrics().density) : 0;
        if (!external)
            PhoneScreenOffActivity.dismiss();
        try {
            LinuxRuntime.writeText(LinuxRuntime.displayKind(this),
                    (external ? "external " : "builtin ") + captionHeight + "\n");
        } catch (IOException error) {
            Log.w(TAG, "Cannot record the window's display", error);
        }
        windows++;
        prepareAndBind();
    }

    private void prepareAndBind() {
        if (bound) return;
        if (preparing) {
            showProgress();
            return;
        }
        preparing = true;
        progressText = "Preparing browser";
        progressPercent = -1;
        showProgress();
        new Thread(() -> {
            Exception failure = null;
            try {
                prepareKeyboard();
                RuntimeInstaller.ensureInstalled(this, this::reportProgress);
            } catch (Exception e) {
                failure = e;
            }
            final Exception error = failure;
            main.post(() -> {
                preparing = false;
                if (windows == 0) {
                    dismissProgress();
                    return;
                }
                if (error != null) {
                    showError(error);
                    return;
                }
                try {
                    starting = true;
                    bound = bindService(new Intent(this, EmbeddedX11Service.class),
                            connection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
                    if (!bound)
                        showError(new IOException("Cannot start the browser display service."));
                } catch (Exception e) { showError(e); }
            });
        }, "desktop-chrome-setup").start();
    }

    private void prepareKeyboard() throws IOException {
        File runtime = new File(getFilesDir(), "runtime");
        File marker = new File(runtime, "xkb-ready-v1");
        if (!marker.isFile()) {
            copyAssetTree("xkb", new File(runtime, "xkb"));
            if (!new File(runtime, "xkb/rules/evdev").isFile())
                throw new IOException("Bundled keyboard data is missing.");
            if (!marker.createNewFile() && !marker.isFile())
                throw new IOException("Cannot record keyboard installation.");
        }
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
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private synchronized void reportProgress(String phase, int percent) {
        boolean changedPhase = !phase.equals(progressText);
        progressText = phase;
        progressPercent = percent;
        long now = SystemClock.elapsedRealtime();
        if (!changedPhase && percent != 100 && now - lastProgressPost < 150) return;
        lastProgressPost = now;
        main.post(this::refreshProgress);
    }

    private void showProgress() {
        MainActivity activity = MainActivity.getFocusedOrAnyInstance();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (progressDialog != null && progressOwner == activity) {
            refreshProgress();
            return;
        }
        dismissProgress();
        progressOwner = activity;
        progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle("ChromeDeck");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setCancelable(false);
        progressDialog.setCanceledOnTouchOutside(false);
        refreshProgress();
        progressDialog.show();
    }

    private void refreshProgress() {
        if (progressDialog == null) return;
        progressDialog.setMessage(progressText);
        progressDialog.setIndeterminate(progressPercent < 0);
        if (progressPercent >= 0) progressDialog.setProgress(progressPercent);
    }

    private void dismissProgress() {
        if (progressDialog != null) progressDialog.dismiss();
        progressDialog = null;
        progressOwner = null;
    }

    private void showError(Exception error) {
        starting = false;
        dismissProgress();
        Log.e(TAG, "Browser startup failed", error);
        MainActivity activity = MainActivity.getFocusedOrAnyInstance();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (errorDialog != null) errorDialog.dismiss();
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        String details = trace + "\n\nSetup log:\n"
                + LinuxRuntime.readTail(new File(getFilesDir(), "runtime/setup.log"), 8192)
                + "\n\nSession log:\n" + LinuxRuntime.readTail(LinuxRuntime.log(this), 8192);
        errorDialog = new AlertDialog.Builder(activity)
                .setTitle("ChromeDeck")
                .setMessage(error.getMessage() == null ? error.toString() : error.getMessage())
                .setPositiveButton("Retry", (dialog, which) -> {
                    releaseServer();
                    main.postDelayed(this::prepareAndBind, 500);
                })
                .setNegativeButton("Close", (dialog, which) -> {
                    for (MainActivity window : MainActivity.getInstances()) window.finish();
                })
                .setNeutralButton("Copy details", null)
                .setCancelable(false)
                .show();
        // Keep Retry visible after copying the diagnostic report.
        errorDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("ChromeDeck", details));
            Toast.makeText(this, "Details copied", Toast.LENGTH_SHORT).show();
        });
    }

    private void releaseServer() {
        starting = false;
        if (bound) {
            bound = false;
            unbindService(connection);
        }
    }

    @Override public void onActivityDestroyed(Activity activity) {
        if (progressOwner == activity) dismissProgress();
        if (activity instanceof MainActivity && --windows == 0) {
            releaseServer();
            if (errorDialog != null) errorDialog.dismiss();
            errorDialog = null;
        }
    }
    @Override public void onActivityResumed(Activity activity) {
        if (activity instanceof MainActivity && (preparing || starting)) showProgress();
    }
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
}
