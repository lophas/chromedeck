// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.system.Os;
import android.util.Log;
import java.io.File;

/** Dedicated process: a fatal native X server error must not terminate the UI. */
public class EmbeddedX11Service extends Service {
    private static final String TAG = "DesktopChrome";
    private final Binder lifetime = new Binder();
    private CmdEntryPoint server;

    @Override public void onCreate() {
        super.onCreate();
        try {
            File runtime = new File(getFilesDir(), "runtime");
            File tmp = new File(runtime, "tmp");
            File xkb = new File(runtime, "xkb");
            if (!tmp.isDirectory() || !new File(xkb, "rules/evdev").isFile())
                throw new IllegalStateException("X11 runtime assets were not prepared.");

            Os.setenv("TMPDIR", tmp.getAbsolutePath(), true);
            Os.setenv("XDG_RUNTIME_DIR", tmp.getAbsolutePath(), true);
            Os.setenv("XKB_CONFIG_ROOT", xkb.getAbsolutePath(), true);
            // Never inherit a preload or startup command from an external Termux session.
            Os.unsetenv("LD_PRELOAD");
            Os.unsetenv("TERMUX_X11_XSTARTUP");

            System.loadLibrary("Xlorie");
            CmdEntryPoint.ctx = this;
            server = new CmdEntryPoint(new String[] {
                    ":1", "-nolisten", "tcp", "-check-drawing"
            });
            Log.i(TAG, "Embedded X server started in process " + Process.myPid());
        } catch (Exception | LinkageError error) {
            Log.e(TAG, "Cannot start embedded X server", error);
            throw new IllegalStateException("Embedded X11 startup failed", error);
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return lifetime;
    }

    @Override public void onDestroy() {
        super.onDestroy();
        // Xorg owns native threads and process-global state; stop the isolated process.
        Process.killProcess(Process.myPid());
    }
}
