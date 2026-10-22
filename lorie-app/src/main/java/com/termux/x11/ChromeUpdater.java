// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import java.io.File;

/**
 * Chrome updates, as set in Preferences → Browser → Chrome updates. Linux Chrome has no
 * self-updater; Google publishes updates through its signed APT repository. While Chrome
 * runs, the app checks the repository and downloads a newer package in the background;
 * the package is installed before Chrome next starts, because replacing it under a
 * running Chrome breaks its newly started processes. "Restart now" in the notification
 * restarts the app at once; Chrome reopens its tabs.
 */
public final class ChromeUpdater {
    private static final String TAG = "ChromeDeck";
    private static final String CHANNEL = "chrome-updates";
    private static final int NOTIFICATION_ID = 7;
    private static final long DAY_MS = 20L * 60 * 60 * 1000;
    private static final long WEEK_MS = 6L * 24 * 60 * 60 * 1000 + DAY_MS;
    private static volatile boolean busy;

    private ChromeUpdater() {}

    private static File runtime(Context context, String name) {
        return new File(context.getFilesDir(), "runtime/" + name);
    }

    /** The downloaded version waiting to be installed before Chrome starts. */
    static File ready(Context context) { return runtime(context, "chrome-update-ready"); }
    /** The newest version the repository offered at the last check, shown in Preferences. */
    static File available(Context context) { return runtime(context, "chrome-update-available"); }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    /** auto, ask or off; installations that had turned updates off before keep them off. */
    static String mode(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.contains("chromeUpdateMode") && RuntimeInstaller.chromeUpdateOptOut(context).exists())
            return "off";
        return prefs.getString("chromeUpdateMode", "auto");
    }

    /**
     * Checks for an update in the background. Scheduled checks follow the settings;
     * a manual check from Preferences runs at once and reports its result.
     */
    public static void check(Context context, boolean manual) {
        Context app = context.getApplicationContext();
        if (busy || (!manual && "off".equals(mode(app))))
            return;
        SharedPreferences prefs = prefs(app);
        File stamp = runtime(app, "chrome-update-check");
        long interval = "weekly".equals(prefs.getString("chromeUpdateInterval", "daily")) ? WEEK_MS : DAY_MS;
        if (!manual && System.currentTimeMillis() - stamp.lastModified() < interval)
            return;
        ConnectivityManager network = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (!manual && prefs.getBoolean("chromeUpdateWifiOnly", true) && network.isActiveNetworkMetered())
            return;
        busy = true;
        if (manual)
            toast(app, app.getString(R.string.chrome_update_checking));
        new Thread(() -> {
            try {
                LinuxRuntime.writeText(stamp, "");
                String candidate = newerVersion(app);
                if (candidate == null) {
                    if (manual)
                        toast(app, app.getString(R.string.chrome_update_none));
                } else if (candidate.equals(readTrimmed(ready(app)))) {
                    notifyReady(app, candidate);
                } else if (!manual && candidate.equals(prefs.getString("chromeUpdateSkipped", ""))) {
                    Log.i(TAG, "Chrome " + candidate + " was skipped");
                } else if (manual || "auto".equals(mode(app))) {
                    download(app, candidate);
                } else {
                    notifyAvailable(app, candidate);
                }
            } catch (Exception error) {
                Log.w(TAG, "Chrome update check failed", error);
                if (manual)
                    toast(app, app.getString(R.string.chrome_update_failed));
            } finally {
                busy = false;
            }
        }, "chrome-update").start();
    }

    /** The repository's version if it is newer than the installed one, otherwise null. */
    private static String newerVersion(Context context) throws Exception {
        // Refresh only Google's repository list; keep the other package lists.
        LinuxRuntime.runChecked(context, 120, "/usr/bin/env", "DEBIAN_FRONTEND=noninteractive",
                "/usr/bin/apt-get", "-o", "APT::Sandbox::User=root",
                "-o", "Dir::Etc::SourceList=/etc/apt/sources.list.d/google-chrome.sources",
                "-o", "Dir::Etc::SourceParts=/nonexistent",
                "-o", "APT::Get::List-Cleanup=0", "update");
        String installed = "", candidate = "";
        for (String line : LinuxRuntime.runChecked(context, 60, "/usr/bin/apt-cache", "policy",
                "google-chrome-stable").split("\n")) {
            line = line.trim();
            if (line.startsWith("Installed:"))
                installed = line.substring(10).trim();
            else if (line.startsWith("Candidate:"))
                candidate = line.substring(10).trim();
        }
        if (candidate.isEmpty() || candidate.equals("(none)"))
            return null;
        LinuxRuntime.writeText(available(context), candidate + "\n");
        String newer = LinuxRuntime.runChecked(context, 60, "/bin/sh", "-c",
                "dpkg --compare-versions \"$1\" gt \"$2\" && echo newer || true", "sh", candidate, installed);
        return newer.contains("newer") ? candidate : null;
    }

    /** Downloads the package into APT's cache, for installation before Chrome next starts. */
    private static void download(Context context, String version) throws Exception {
        LinuxRuntime.runChecked(context, 1800, "/usr/bin/env", "DEBIAN_FRONTEND=noninteractive",
                "/usr/bin/apt-get", "-o", "APT::Sandbox::User=root", "install", "--yes", "--download-only",
                "--only-upgrade", "--no-install-recommends", "google-chrome-stable");
        LinuxRuntime.writeText(ready(context), version + "\n");
        notifyReady(context, version);
    }

    /** Before Chrome starts: installs a downloaded update from APT's cache, without the network. */
    static void installReady(Context context, RuntimeInstaller.Progress progress) {
        File ready = ready(context);
        if (!ready.isFile())
            return;
        progress.update("Updating Google Chrome", -1);
        try {
            LinuxRuntime.runChecked(context, 900, "/usr/bin/env", "DEBIAN_FRONTEND=noninteractive",
                    "/usr/bin/apt-get", "-o", "APT::Sandbox::User=root", "install", "--yes", "--no-download",
                    "--only-upgrade", "--no-install-recommends", "google-chrome-stable");
        } catch (Exception error) {
            Log.w(TAG, "Installing the downloaded Chrome update failed; starting the installed version.", error);
        }
        ready.delete();
        cancel(context);
    }

    private static String readTrimmed(File file) {
        return LinuxRuntime.readTail(file, 256).trim();
    }

    private static void toast(Context context, String text) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, text, Toast.LENGTH_LONG).show());
    }

    private static Notification.Builder notification(Context context, String title, String text) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.chrome_update_channel), NotificationManager.IMPORTANCE_DEFAULT));
        return new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_x11_icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true);
    }

    private static Notification.Action action(Context context, int code, int title, String what) {
        PendingIntent intent = PendingIntent.getBroadcast(context, code,
                new Intent(context, Receiver.class).putExtra("what", what),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(null, context.getString(title), intent).build();
    }

    private static void notifyAvailable(Context context, String version) {
        context.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(context,
                context.getString(R.string.chrome_update_available_title, shortVersion(version)),
                context.getString(R.string.chrome_update_available_text))
                .addAction(action(context, 11, R.string.chrome_update_update, "update"))
                .addAction(action(context, 12, R.string.chrome_update_later, "later"))
                .addAction(action(context, 13, R.string.chrome_update_skip, "skip:" + version))
                .build());
    }

    private static void notifyReady(Context context, String version) {
        context.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(context,
                context.getString(R.string.chrome_update_ready_title, shortVersion(version)),
                context.getString(R.string.chrome_update_ready_text))
                .addAction(action(context, 14, R.string.chrome_update_restart, "restart"))
                .addAction(action(context, 12, R.string.chrome_update_later, "later"))
                .build());
    }

    private static void cancel(Context context) {
        context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
    }

    /** 157.0.7451.86-1 as 157.0.7451.86. */
    private static String shortVersion(String version) {
        int dash = version.lastIndexOf('-');
        return dash > 0 ? version.substring(0, dash) : version;
    }

    /** The notifications' buttons, and the manual check from Preferences. */
    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String what = String.valueOf(intent.getStringExtra("what"));
            if (what.equals("check")) {
                check(context, true);
            } else if (what.equals("update")) {
                cancel(context);
                busy = true;
                new Thread(() -> {
                    try {
                        String version = LinuxRuntime.readTail(available(context), 256).trim();
                        download(context.getApplicationContext(), version);
                    } catch (Exception error) {
                        Log.w(TAG, "Chrome update download failed", error);
                        toast(context, context.getString(R.string.chrome_update_failed));
                    } finally {
                        busy = false;
                    }
                }, "chrome-update").start();
            } else if (what.startsWith("skip:")) {
                prefs(context).edit().putString("chromeUpdateSkipped", what.substring(5)).apply();
                cancel(context);
            } else if (what.equals("restart")) {
                cancel(context);
                ((DesktopChromeApp) context.getApplicationContext()).restartBrowser();
            } else {
                cancel(context);
            }
        }
    }
}
