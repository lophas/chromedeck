// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.Service;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Owns the X server and one Debian browser session in an isolated Android process. */
public class EmbeddedX11Service extends Service {
    public static final String ACTION_STATE = BuildConfig.APPLICATION_ID + ".SESSION_STATE";
    private static final String TAG = "DesktopChrome";
    private final Binder lifetime = new Binder();
    private CmdEntryPoint server;
    private java.lang.Process browser;
    private AndroidAudioOutput audio;
    private Thread worker;
    private volatile boolean stopping;
    private static final long ACCELERATED_START_GRACE_MS = 20000;
    private boolean lastSessionAccelerated;
    private long lastSessionMillis;

    @Override public void onCreate() {
        super.onCreate();
        try {
            File runtime = new File(getFilesDir(), "runtime");
            File tmp = LinuxRuntime.tmp(this);
            File xkb = new File(runtime, "xkb");
            if (!tmp.isDirectory() || !new File(xkb, "rules/evdev").isFile()
                    || !new File(LinuxRuntime.rootfs(this), "opt/google/chrome/chrome").isFile())
                throw new IllegalStateException("Browser runtime was not prepared.");

            Os.setenv("TMPDIR", tmp.getAbsolutePath(), true);
            Os.setenv("XDG_RUNTIME_DIR", tmp.getAbsolutePath(), true);
            Os.setenv("XKB_CONFIG_ROOT", xkb.getAbsolutePath(), true);
            Os.unsetenv("LD_PRELOAD");
            Os.unsetenv("TERMUX_X11_XSTARTUP");

            System.loadLibrary("Xlorie");
            CmdEntryPoint.ctx = this;
            server = new CmdEntryPoint(new String[] { ":1", "-nolisten", "tcp", "-ac" });
            worker = new Thread(this::runBrowser, "desktop-chrome-session");
            worker.start();
            Log.i(TAG, "Embedded X server started in process " + Process.myPid());
        } catch (Exception | LinkageError error) {
            reportError(error);
            throw new IllegalStateException("Embedded browser startup failed", error);
        }
    }

    private void runBrowser() {
        try {
            File socketPath = new File(LinuxRuntime.tmp(this), ".X11-unix/X1");
            long deadline = SystemClock.elapsedRealtime() + 30000;
            boolean ready = false;
            while (!stopping && SystemClock.elapsedRealtime() < deadline) {
                if (socketPath.exists()) {
                    try (LocalSocket socket = new LocalSocket()) {
                        socket.connect(new LocalSocketAddress(socketPath.getAbsolutePath(),
                                LocalSocketAddress.Namespace.FILESYSTEM));
                        ready = true;
                        break;
                    } catch (IOException notReady) { /* A stale socket is not a readiness signal. */ }
                }
                Thread.sleep(100);
            }
            if (stopping) return;
            if (!ready) throw new IOException("The display server did not become ready.");
            int status = runSession();
            if (!stopping && status != 0 && lastSessionAccelerated
                    && lastSessionMillis < ACCELERATED_START_GRACE_MS) {
                // An accelerated session that dies during startup is retried once
                // without PRoot's seccomp filter, and that choice is remembered.
                Log.w(TAG, "Accelerated session exited (" + status + ") after "
                        + lastSessionMillis + " ms; retrying without PRoot seccomp.");
                LinuxRuntime.writeText(LinuxRuntime.seccompOptOut(this),
                        "Accelerated session exited with " + status + "\n");
                closeAudio();
                status = runSession();
            }
            if (!stopping)
                report("exited", status, LinuxRuntime.readTail(LinuxRuntime.log(this), 8192));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (!stopping) reportError(error);
        } finally {
            closeAudio();
        }
    }

    private int runSession() throws Exception {
        final java.lang.Process running;
        synchronized (this) {
            if (stopping) return 0;
            try {
                audio = new AndroidAudioOutput(this);
                audio.start();
            } catch (Exception error) {
                Log.e(TAG, "Android audio receiver unavailable; starting Chrome without audio.", error);
                if (audio != null) audio.close();
                audio = null;
            }
            lastSessionAccelerated = LinuxRuntime.seccompEnabled(this);
            browser = LinuxRuntime.startSession(this, audio != null);
            running = browser;
        }
        long started = SystemClock.elapsedRealtime();
        report("started", 0, "");
        int status = running.waitFor();
        lastSessionMillis = SystemClock.elapsedRealtime() - started;
        return status;
    }

    private synchronized void closeAudio() {
        if (audio != null) audio.close();
        audio = null;
    }

    private void reportError(Throwable error) {
        Log.e(TAG, "Browser runtime failed", error);
        StringWriter text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        try { LinuxRuntime.writeText(new File(getFilesDir(), "runtime/last-error.log"), text.toString()); }
        catch (IOException ignored) {}
        report("error", 1, text.toString());
    }

    private void report(String state, int code, String details) {
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName())
                .putExtra("state", state).putExtra("code", code).putExtra("details", details));
    }

    @Override public IBinder onBind(Intent intent) { return lifetime; }

    @Override public void onDestroy() {
        stopping = true;
        if (worker != null) worker.interrupt();
        synchronized (this) {
            if (browser != null) browser.destroy();
        }
        closeAudio();
        super.onDestroy();
        // Xorg owns process-global native state and cannot be restarted in this process.
        Process.killProcess(Process.myPid());
    }
}
