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
        for (String script : new String[] { "window-visibility.py", "display-scale.py" })
            try (InputStream input = context.getAssets().open(script)) {
                LinuxRuntime.writeText(new File(root, "usr/local/lib/desktop-chrome/" + script), read(input));
            }
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
            // The pinned package is a minimum: never replace a newer Chrome that
            // automatic updates already installed.
            if (!browserReady && installedChromeAtLeast(context, chrome.getString("version"))) {
                LinuxRuntime.writeText(chromeMarker, chromeSha);
                browserReady = true;
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
            writeChromeVersion(context, version);
            packageFile.delete();
        }
        updateChrome(context, progress);
        prepareAudio(context, progress, root);
        progress.update("Starting Chrome", -1);
    }

    /** Installed Chrome version reported by the binary, shown in Preferences. */
    public static File chromeVersion(Context context) { return new File(context.getFilesDir(), "runtime/chrome-version"); }
    public static File chromeUpdateOptOut(Context context) { return new File(context.getFilesDir(), "runtime/disable-chrome-update"); }
    private static final long CHROME_UPDATE_INTERVAL_MS = 20L * 60 * 60 * 1000;

    /** Keeps only the "Google Chrome x.y" line; Chrome also logs warnings to the same output. */
    private static void writeChromeVersion(Context context, String output) throws IOException {
        for (String line : output.split("\n")) {
            if (line.startsWith("Google Chrome")) {
                LinuxRuntime.writeText(chromeVersion(context), line.trim() + "\n");
                return;
            }
        }
    }

    private static boolean installedChromeAtLeast(Context context, String version) {
        try {
            String installed = LinuxRuntime.runChecked(context, 60, "/usr/bin/dpkg-query",
                    "-W", "-f=${Version}", "google-chrome-stable");
            LinuxRuntime.runChecked(context, 60, "/usr/bin/dpkg", "--compare-versions",
                    installed, "ge", version);
            return true;
        } catch (Exception notNewer) {
            return false;
        }
    }

    /**
     * Linux Chrome has no self-updater; Google publishes updates only through its
     * signed APT repository. Upgrade from it at most about once a day, before Chrome
     * starts. Failures are logged and never prevent the installed Chrome from starting.
     */
    private static void updateChrome(Context context, Progress progress) {
        if (!chromeVersion(context).isFile()) {
            try {
                String version = LinuxRuntime.runChecked(context, 60, "/opt/google/chrome/chrome", "--version");
                writeChromeVersion(context, version);
            } catch (Exception error) {
                android.util.Log.w("DesktopChrome", "Cannot read the installed Chrome version.", error);
            }
        }
        if (chromeUpdateOptOut(context).exists())
            return;
        File stamp = new File(context.getFilesDir(), "runtime/chrome-update-check");
        if (System.currentTimeMillis() - stamp.lastModified() < CHROME_UPDATE_INTERVAL_MS)
            return;
        progress.update("Checking for Google Chrome updates", -1);
        try {
            LinuxRuntime.writeText(stamp, "");
            // Refresh only Google's repository list; keep the other package lists.
            LinuxRuntime.runChecked(context, 120, "/usr/bin/env", "DEBIAN_FRONTEND=noninteractive",
                    "/usr/bin/apt-get", "-o", "APT::Sandbox::User=root",
                    "-o", "Dir::Etc::SourceList=/etc/apt/sources.list.d/google-chrome.sources",
                    "-o", "Dir::Etc::SourceParts=/nonexistent",
                    "-o", "APT::Get::List-Cleanup=0", "update");
            progress.update("Updating Google Chrome", -1);
            LinuxRuntime.runChecked(context, 900, "/usr/bin/env", "DEBIAN_FRONTEND=noninteractive",
                    "/usr/bin/apt-get", "-o", "APT::Sandbox::User=root", "install", "--yes",
                    "--only-upgrade", "--no-install-recommends", "google-chrome-stable");
            String version = LinuxRuntime.runChecked(context, 60, "/opt/google/chrome/chrome", "--version");
            writeChromeVersion(context, version);
        } catch (Exception error) {
            android.util.Log.w("DesktopChrome", "Chrome update failed; starting the installed version.", error);
        }
    }

    private static void prepareAudio(Context context, Progress progress, File root) throws Exception {
        File marker = new File(root, ".desktop-chrome-audio-v1");
        if (!marker.isFile() || !new File(root, "usr/bin/pulseaudio").isFile()
                || !new File(root, "usr/bin/parec").isFile()
                || !new File(root, "usr/bin/pactl").isFile()) {
            progress.update("Preparing audio packages", -1);
            LinuxRuntime.runChecked(context, 300, "/usr/bin/env", "DEBIAN_FRONTEND=noninteractive",
                    "/usr/bin/apt-get", "-o", "APT::Sandbox::User=root", "update");
            LinuxRuntime.runChecked(context, 900, "/usr/bin/env", "DEBIAN_FRONTEND=noninteractive",
                    "/usr/bin/apt-get", "-o", "APT::Sandbox::User=root", "install", "--yes",
                    "--no-install-recommends", "pulseaudio", "pulseaudio-utils", "libasound2-plugins");
            LinuxRuntime.writeText(marker, "Debian PulseAudio output bridge v1\n");
        }
        File audio = new File(root, "usr/local/lib/desktop-chrome-audio");
        for (String name : new String[] { "default.pa", "client.conf", "asound.conf", "session.sh" }) {
            try (InputStream input = context.getAssets().open("audio/" + name)) {
                LinuxRuntime.writeText(new File(audio, name), read(input));
            }
        }
        // The base image has an empty machine-id. PulseAudio needs a stable,
        // valid per-install ID. Replace a guest symlink without following it.
        File machineId = new File(root, "etc/machine-id");
        String id = "";
        if (!java.nio.file.Files.isSymbolicLink(machineId.toPath()) && machineId.isFile()) {
            try (InputStream input = new FileInputStream(machineId)) { id = read(input).trim(); }
        }
        if (!id.matches("[0-9a-fA-F]{32}")) {
            java.nio.file.Files.deleteIfExists(machineId.toPath());
            LinuxRuntime.writeText(machineId, java.util.UUID.randomUUID().toString().replace("-", "") + "\n");
        }
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
