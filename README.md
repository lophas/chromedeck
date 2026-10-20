# Desktop Chrome Android

A standalone ARM64 Android app that runs the Linux desktop version of Google Chrome
inside its own Debian 13 environment, with an embedded Termux:X11 display server.

- App name: **Desktop Chrome Android**
- Application ID: `hu.lophas.desktopchromeandroid`
- No separate Termux app, root access, or Shizuku service is required.
- Debian and Chrome are installed on first launch; the APK contains the display
  server and the statically linked PRoot runtime.
- The Chrome profile persists between launches.

## Development status

The embedded X11 display, PRoot, Debian installer, official desktop Chrome, extensions,
and on-screen keyboard have been verified on Android 16. Long-running stability with
Android's default child-process limit is under investigation.

Current prototype limits:

- ARM64 only; Android 8.0 or newer.
- Software rendering, with Chrome's `--disable-gpu` flag.
- Chrome runs with `--no-sandbox` because this PRoot setup does not provide the
  normal Linux browser sandbox. Android's app sandbox is a separate boundary.
- Audio output has not been integrated yet.
- Target SDK 28 is used for Linux executable compatibility; newer Android
  installers may show an old-target warning.
- Development APKs currently use the upstream public debug signing key.

## Extra key bar

- Three-finger swipe down toggles the extra key bar (the upstream default gesture).
  On devices where this triggers a system screenshot, use Preferences > Other >
  Configure response to user actions > Volume down > toggle additional key bar.
- Preferences > Keyboard > Show additional keyboard enables or disables it.
- Hide extra key bar with external keyboard is enabled by default. It hides the
  bar when Android detects an external alphabetic keyboard, including USB and
  Bluetooth keyboards, and restores the saved manual visibility on disconnect.
- A manual toggle can reveal the bar while the keyboard remains connected. In
  this auto-hide mode the bar overlays the desktop, even when the normal
  Prevent extra keys bar from covering the display setting is enabled. Revealing
  or hiding it does not change the desktop content insets.
- The manual override lasts until the keyboard connection or the auto-hide
  preference changes. A manually hidden bar stays hidden on disconnect.
- Preferences can still be opened through the Android notification while the bar
  is hidden. Software keyboard behavior is controlled separately by Show IME with
  external keyboard.

## Android child-process limits

Android can terminate native child processes with the reason
`Trimming phantom processes`. On the tested Vivo, the configured shared limit is 32.
A baseline snapshot contained 23 native children for this app. The partial baseline
recording confirmed a subsequent PRoot/session termination; it was not a complete
five-minute time series.

The current launcher starts the Chrome binary directly (avoiding two wrapper
`cat` processes), uses `--no-zygote`, and requests
`--renderer-process-limit=4`. The renderer limit is soft and does not cap all
browser children or other apps' children and does not guarantee immunity from
system process trimming.

A five-minute device test of `chrome-test-005` on 2026-10-20 completed successfully
(140 samples, 14:15:37-14:20:35 local time). The global monitor override was absent
and the configured phantom-process limit was 32. No Chrome termination or phantom
trimming appeared in the captured events. The Android-tracked global child-process
count reached 32. Raw app-native process counts reached 39 briefly during startup;
the final count was 28, including 15 renderers, 6 of which were extension renderers.
The renderer peak was 20. This is evidence of improved stability in that session,
not a hard process-count bound or proof of long-running stability.

A later `chrome-test-006` session was terminated after about eight and a half
minutes: Android logged `Trimming phantom processes` for `libproot.so` at
14:42:28.979. The five-minute pass therefore did not establish long-term stability.

`chrome-test-007` also disabled Crashpad initialization, but failed immediately on
the device with repeated FD ownership violations, GPU/network child crashes, and
exit code 133. The next test restores Crashpad while retaining the two renderer
options, to isolate the suspected regression. This is separate from the earlier
Android phantom-process termination (exit code 137). Startup recovery and process
counts still require device validation.

The follow-up test after restoring Crashpad also failed with the same FD ownership
violations and exit code 133. Restoring Crashpad alone therefore did not resolve
the startup failure. The launcher now also removes `--process-per-site` and
`--disable-features=SpareRendererForSitePerProcess`, returning to the launch options
from the successful `chrome-test-005` baseline. This rollback requires device
validation; the cause of the FD ownership failure is not yet established, and the
earlier phantom-process limit issue remains unresolved.

For a longer measurement:

```bash
MEASURE_SECONDS=900 bash scripts/measure-android-processes.sh
```

Source references:
[process model](https://chromium.googlesource.com/chromium/src/+/main/docs/process_model_and_site_isolation.md),
[spare renderer feature](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/content/public/common/content_features.cc),
[Crashpad initialization](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/chrome/app/chrome_main_delegate.cc).

The app does not change the system child-process monitor. To measure on a connected
computer with ADB, run:

```bash
bash scripts/measure-android-processes.sh
```

The measurement script removes the previously used global monitor override to
restore the system default, records process snapshots and Android trimming events,
and saves a ZIP in the current directory, including partial data if it stops early.
Renderer classification also checks `--renderer-client-id`, since Chrome may
rewrite its visible command line and omit `--type`. Extension renderer counts
are a subset of all renderers.

## Build the APK

On a Debian x86_64 build host, install:

```bash
apt-get install -y build-essential git curl unzip python3 file bison flex gawk xkb-data
```

Use JDK 21 and an Android SDK with accepted SDK licenses. The project pins NDK
29.0.14206865. The Gradle build prepares the static PRoot runtime and keyboard assets.

```bash
git clone --recurse-submodules https://github.com/lophas/desktop-chrome-android.git
cd desktop-chrome-android
git switch desktop-chrome
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew :lorie-app:assembleStandaloneDebug
```

Output:

```text
lorie-app/build/outputs/apk/standalone/debug/desktop-chrome-debug.apk
```

## Runtime image

`scripts/build-debian-rootfs.sh` prepares the ARM64 Debian image using a pinned
official Debian Docker rootfs, host PRoot, and QEMU user-mode emulation. It installs
Chrome's real dependency list through a local dependency metapackage, plus Openbox,
D-Bus, and fonts. It does not redistribute Google Chrome.

```bash
apt-get install -y --no-install-recommends proot qemu-user-static xz-utils
bash scripts/build-debian-rootfs.sh
```

Publish the generated image and manifest to the configured GitHub release, and
copy the generated manifest into `lorie-app/src/main/assets/runtime-manifest.json`
before building the APK.

The app verifies both the image and the official Google Chrome package against the
SHA-256 hashes in its bundled manifest. Partial HTTPS downloads can be resumed.
Extraction occurs in a staging directory, and the installed profile is not replaced
on later launches.

## Runtime layout

- `files/debian`: persistent Debian installation
- `files/debian/root/.config/desktop-chrome-android`: Chrome profile
- `files/runtime/tmp`: shared X11 and PRoot temporary directory
- `files/runtime/setup.log`: first-run package installation output
- `files/runtime/chrome-session.log`: latest browser session output

Startup errors offer **Copy details** for device-side diagnostics.

## Upstream and licenses

This is a fork of [Termux:X11](https://github.com/termux/termux-x11).
The original README is retained in [UPSTREAM_TERMUX_X11.md](UPSTREAM_TERMUX_X11.md).

PRoot comes from [termux/proot](https://github.com/termux/proot), and talloc comes from
[Samba](https://www.samba.org/ftp/talloc/). Their pinned source URLs, checksums, build
steps, and license copies are included in the runtime build process.
TAR support uses [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/).
Google Chrome is downloaded directly from Google's official Linux package repository
and remains subject to Google's terms.
