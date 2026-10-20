// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.content.Context;
import android.os.StatFs;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Installs the pinned Debian image once, then the pinned official Chrome package. */
public final class RuntimeInstaller {
    private RuntimeInstaller() {}
    public interface Progress { void update(String phase, int percent); }

    public static void ensureInstalled(Context context, Progress progress) throws Exception {
        JSONObject manifest;
        try (InputStream input = context.getAssets().open("runtime-manifest.json")) {
            manifest = new JSONObject(read(input));
        }
        if (manifest.getInt("schema") != 1 || !"arm64".equals(manifest.getString("architecture")))
            throw new IOException("Unsupported runtime manifest.");
        File runtime = new File(context.getFilesDir(), "runtime");
        if (!runtime.isDirectory() && !runtime.mkdirs())
            throw new IOException("Cannot create runtime storage.");
        if (!LinuxRuntime.tmp(context).isDirectory() && !LinuxRuntime.tmp(context).mkdirs())
            throw new IOException("Cannot create shared temporary storage.");

        String imageSha = manifest.getString("image_sha256");
        File root = LinuxRuntime.rootfs(context);
        File baseMarker = new File(root, ".desktop-chrome-base");
        if (!baseMarker.isFile()) {
            if (root.exists())
                throw new IOException("An incomplete Debian directory already exists. Installation stopped to preserve it.");
            long needed = manifest.getLong("image_size") + manifest.getLong("unpacked_size")
                    + 512L * 1024 * 1024;
            if (new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes() < needed)
                throw new IOException("Not enough free storage to install Debian and Chrome.");
            File downloads = new File(context.getCacheDir(), "runtime-downloads");
            File archive = new File(downloads, "debian-" + imageSha.substring(0, 16) + ".tar.gz");
            CheckedDownload.fetch(manifest.getString("image_url"), imageSha,
                    manifest.getLong("image_size"), archive,
                    percent -> progress.update("Downloading Debian", percent));
            progress.update("Installing Debian", 0);
            File staging = new File(context.getFilesDir(), "debian-staging-v1");
            RootfsArchive.deleteStaging(staging);
            RootfsArchive.unpack(archive, staging, manifest.getLong("unpacked_size"),
                    bytes -> progress.update("Installing Debian",
                            (int) Math.min(99, 100 * bytes / Math.max(1, manifest.optLong("unpacked_size", 1)))));
            if (!new File(staging, "usr/bin/dpkg").isFile()
                    || !new File(staging, "usr/local/bin/desktop-chrome-session").isFile())
                throw new IOException("Debian image is missing required files.");
            LinuxRuntime.writeText(new File(staging, ".desktop-chrome-base"), imageSha);
            if (!staging.renameTo(root))
                throw new IOException("Cannot activate the completed Debian installation.");
            // Keep only the installed system once the verified archive has served its purpose.
            archive.delete();
        } else {
            try (InputStream input = new FileInputStream(baseMarker)) {
                if (!imageSha.equals(read(input).trim()))
                    throw new IOException("This APK requires a different Debian image. Existing browser data was preserved.");
            }
        }

        File launcher = new File(root, "usr/local/bin/desktop-chrome-session");
        try (InputStream input = context.getAssets().open("desktop-chrome-session.sh")) {
            LinuxRuntime.writeText(launcher, read(input));
        }
        android.system.Os.chmod(launcher.getAbsolutePath(), 0755);
        LinuxRuntime.configureDns(context);
        JSONObject chrome = manifest.getJSONObject("chrome");
        String chromeSha = chrome.getString("sha256");
        File chromeMarker = new File(root, ".desktop-chrome-browser");
        File chromeBinary = new File(root, "opt/google/chrome/chrome");
        boolean browserReady = false;
        if (chromeMarker.isFile() && chromeBinary.isFile()) {
            try (InputStream input = new FileInputStream(chromeMarker)) {
                browserReady = chromeSha.equals(read(input).trim());
            }
        }
        if (!browserReady) {
            File packageFile = new File(LinuxRuntime.tmp(context), "google-chrome-" + chromeSha.substring(0, 16) + ".deb");
            CheckedDownload.fetch(chrome.getString("url"), chromeSha, -1, packageFile,
                    percent -> progress.update("Downloading Google Chrome", percent));
            progress.update("Installing Google Chrome", -1);
            LinuxRuntime.writeText(new File(runtime, "setup.log"), "Installing " + chrome.getString("version") + "\n");
            LinuxRuntime.runChecked(context, 900, "/usr/bin/dpkg", "--install", "/tmp/" + packageFile.getName());
            String version = LinuxRuntime.runChecked(context, 60, "/opt/google/chrome/chrome", "--version");
            if (!version.contains("Google Chrome") || !chromeBinary.isFile())
                throw new IOException("Google Chrome installation did not complete:\n" + version);
            LinuxRuntime.writeText(chromeMarker, chromeSha);
            packageFile.delete();
        }
        progress.update("Starting Chrome", -1);
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
            if (output.size() > 1024 * 1024)
                throw new IOException("Runtime metadata is unexpectedly large.");
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
