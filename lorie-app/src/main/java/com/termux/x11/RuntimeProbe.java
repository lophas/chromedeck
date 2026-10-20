// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** A bounded smoke test of the bundled tracer before a Debian image is installed. */
public final class RuntimeProbe {
    private RuntimeProbe() {}

    public static String check(Context context) throws Exception {
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File proot = new File(nativeDir, "libproot.so");
        File loader = new File(nativeDir, "libproot_loader.so");
        if (!proot.canExecute() || !loader.canExecute())
            throw new IOException("Bundled PRoot executables were not extracted: " + nativeDir);

        String version = run(context, nativeDir, proot.getAbsolutePath(), "--version");
        String result = run(context, nativeDir, proot.getAbsolutePath(),
                "-0", "-r", "/", "-w", context.getFilesDir().getAbsolutePath(),
                "/system/bin/sh", "-c",
                "printf 'PROOT_OK\\n'; /system/bin/id; /system/bin/uname -m");
        if (!result.contains("PROOT_OK") || !result.contains("uid=0"))
            throw new IOException("Unexpected PRoot smoke test result:\n" + result);

        File report = new File(context.getFilesDir(), "runtime/proot-probe.log");
        try (FileOutputStream output = new FileOutputStream(report)) {
            output.write((version + "\n" + result).getBytes(StandardCharsets.UTF_8));
        }
        Log.i("DesktopChrome", "PRoot smoke test passed: " + result);
        return "PRoot test passed\n\n" + result
                + "\nThe reported root identity is emulated by PRoot.";
    }

    private static String run(Context context, File nativeDir, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        File tmp = new File(context.getFilesDir(), "runtime/tmp");
        builder.directory(context.getFilesDir());
        Map<String, String> env = builder.environment();
        env.put("HOME", context.getFilesDir().getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("PROOT_TMP_DIR", tmp.getAbsolutePath());
        env.put("PROOT_LOADER", new File(nativeDir, "libproot_loader.so").getAbsolutePath());
        File loader32 = new File(nativeDir, "libproot_loader32.so");
        if (loader32.isFile())
            env.put("PROOT_LOADER_32", loader32.getAbsolutePath());
        // Start conservatively; optimize seccomp use after the device smoke test.
        env.put("PROOT_NO_SECCOMP", "1");
        env.remove("LD_PRELOAD");

        Process process = builder.start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    synchronized (captured) {
                        if (captured.size() < 65536)
                            captured.write(buffer, 0, Math.min(count, 65536 - captured.size()));
                    }
                }
            } catch (IOException ignored) {}
        }, "desktop-chrome-proot-output");
        reader.setDaemon(true);
        reader.start();

        long deadline = SystemClock.elapsedRealtime() + 30000;
        Integer status = null;
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                try {
                    status = process.exitValue();
                    break;
                } catch (IllegalThreadStateException running) {
                    Thread.sleep(50);
                }
            }
            if (status == null) {
                process.destroy();
                throw new IOException("PRoot smoke test timed out after 30 seconds.");
            }
            reader.join(1000);
            String text;
            synchronized (captured) {
                text = new String(captured.toByteArray(), StandardCharsets.UTF_8);
            }
            if (status != 0)
                throw new IOException("PRoot exited with status " + status + ":\n" + text);
            return text.trim();
        } finally {
            process.destroy();
        }
    }
}
