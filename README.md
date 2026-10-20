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

## Android child-process limits

Android can terminate native child processes with the reason
`Trimming phantom processes`. On the tested Vivo, the configured shared limit is 32.
A baseline snapshot contained 23 native children for this app. The partial baseline
recording confirmed a subsequent PRoot/session termination; it was not a complete
five-minute time series.

The current launcher starts the Chrome binary directly (avoiding two wrapper
`cat` processes), uses `--no-zygote`, and requests
`--renderer-process-limit=4`. The renderer limit is soft and does not cap all
browser children or other apps' children. These changes require device validation
and do not guarantee immunity from system process trimming.

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
