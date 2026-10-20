// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Shared PRoot command construction for setup and the browser session. */
public final class LinuxRuntime {
    private LinuxRuntime() {}
    public static File rootfs(Context context) { return new File(context.getFilesDir(), "debian"); }
    public static File tmp(Context context) { return new File(context.getFilesDir(), "runtime/tmp"); }
    public static File log(Context context) { return new File(context.getFilesDir(), "runtime/chrome-session.log"); }

    public static ProcessBuilder command(Context context, String... guestCommand) throws IOException {
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File proot = new File(nativeDir, "libproot.so");
        if (!proot.canExecute())
            throw new IOException("Bundled PRoot is not executable: " + proot);
        if (!tmp(context).isDirectory() && !tmp(context).mkdirs())
            throw new IOException("Cannot create the shared runtime directory.");

        List<String> args = new ArrayList<>(Arrays.asList(
                proot.getAbsolutePath(), "-0", "--kill-on-exit", "--link2symlink",
                "-r", rootfs(context).getAbsolutePath(),
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", tmp(context).getAbsolutePath() + ":/tmp", "-w", "/root",
                "/usr/bin/env", "-i",
                "HOME=/root", "USER=root", "LOGNAME=root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LANG=C.UTF-8", "LC_ALL=C.UTF-8", "TMPDIR=/tmp",
                "DISPLAY=:1", "XDG_RUNTIME_DIR=/tmp", "XDG_SESSION_TYPE=x11",
                "TZ=" + TimeZone.getDefault().getID()));
        args.addAll(Arrays.asList(guestCommand));
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(rootfs(context));
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.remove("LD_PRELOAD");
        env.remove("LD_LIBRARY_PATH");
        env.put("HOME", context.getFilesDir().getAbsolutePath());
        env.put("TMPDIR", tmp(context).getAbsolutePath());
        env.put("PROOT_TMP_DIR", tmp(context).getAbsolutePath());
        env.put("PROOT_LOADER", new File(nativeDir, "libproot_loader.so").getAbsolutePath());
        File loader32 = new File(nativeDir, "libproot_loader32.so");
        if (loader32.isFile())
            env.put("PROOT_LOADER_32", loader32.getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        return builder;
    }

    public static void configureDns(Context context) throws Exception {
        StringBuilder config = new StringBuilder();
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties properties = manager == null ? null : manager.getLinkProperties(manager.getActiveNetwork());
        if (properties != null) {
            for (InetAddress address : properties.getDnsServers())
                config.append("nameserver ").append(address.getHostAddress()).append('\n');
        }
        if (config.length() == 0)
            config.append("nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        config.append("options timeout:2 attempts:2\n");
        File resolv = new File(rootfs(context), "etc/resolv.conf");
        // Replace the directory entry itself, never follow an absolute guest symlink.
        java.nio.file.Files.deleteIfExists(resolv.toPath());
        writeText(resolv, config.toString());
    }

    public static String runChecked(Context context, long timeoutSeconds, String... command) throws Exception {
        File report = new File(context.getFilesDir(), "runtime/setup.log");
        Process process = command(context, command).start();
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        final IOException[] streamFailure = {null};
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream();
                 FileOutputStream output = new FileOutputStream(report, true)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    synchronized (tail) {
                        tail.write(buffer, 0, count);
                        if (tail.size() > 32768) {
                            byte[] bytes = tail.toByteArray();
                            tail.reset();
                            tail.write(bytes, bytes.length - 16384, 16384);
                        }
                    }
                }
            } catch (IOException e) { streamFailure[0] = e; }
        }, "desktop-chrome-setup-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Setup command timed out: " + command[0]);
            }
            reader.join(3000);
            String text;
            synchronized (tail) {
                text = new String(tail.toByteArray(), StandardCharsets.UTF_8);
            }
            if (process.exitValue() != 0)
                throw new IOException("Setup command failed (" + process.exitValue() + "):\n" + text);
            if (streamFailure[0] != null)
                throw streamFailure[0];
            return text.trim();
        } finally {
            process.destroy();
        }
    }

    public static Process startSession(Context context, boolean audioEnabled) throws Exception {
        configureDns(context);
        ProcessBuilder builder = command(context,
                "/usr/bin/env", "DESKTOP_CHROME_AUDIO=" + (audioEnabled ? "1" : "0"),
                "/usr/bin/dbus-run-session", "--", "/usr/local/bin/desktop-chrome-session");
        builder.redirectOutput(ProcessBuilder.Redirect.to(log(context)));
        return builder.start();
    }

    public static String readTail(File file, int maxBytes) {
        if (!file.isFile()) return "";
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(Math.max(0, input.length() - maxBytes));
            byte[] bytes = new byte[(int) Math.min(input.length(), maxBytes)];
            input.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) { return e.toString(); }
    }

    public static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create " + parent);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
