# Desktop Chrome Android

**The desktop edition of Google Chrome, running locally on Android.**

> **Audio experiment — `audio-output` branch:** this branch adds a PulseAudio →
> Android AudioTrack playback bridge. Its current tuning still needs device testing and
> is separate from the frozen v0.1.0 preview below. See [audio test instructions](docs/AUDIO.md).

Desktop Chrome Android packages an embedded X11 display and a Linux runtime into
one ARM64 Android app. On first launch it installs its own Debian 13 environment
and downloads the official Linux ARM64 Google Chrome package from Google.

[Download the APK](https://github.com/lophas/desktop-chrome-android/releases/download/v0.1.0/desktop-chrome-debug.apk)
· [Release notes](RELEASE_NOTES.md)
· [All releases](https://github.com/lophas/desktop-chrome-android/releases)

> **Many tabs? Enable “Disable child process restrictions” in Android Developer
> options, if your device provides it.** Android can otherwise terminate the
> browser's Linux runtime when the shared child-process limit is exceeded.
> Fewer tabs can help, but do not guarantee long-running stability. See
> [Android process limits](#android-process-limits).

## Mission

Make a real desktop browser available on an Android phone or tablet in a
self-contained app: desktop Chrome's interface, desktop extensions and persistent
browser data, without setting up Termux, a separate X server or a full desktop
environment yourself.

Everything runs on the device. This is the Linux desktop browser displayed through
an embedded X11 server; it is not a remote browser or an Android WebView.

## Features

- **Official desktop Google Chrome for ARM64**, including desktop web extensions.
- **One Android application:** embedded Termux:X11 display server and static PRoot
  runtime; no separate Termux app, root access or Shizuku service required.
- **Automatic first-run setup:** Debian 13, Chrome dependencies and Google Chrome.
- **Persistent browser profile:** settings, sign-ins and installed extensions
  survive normal app restarts and updates.
- **Touch and keyboard controls:** on-screen keyboard support and a configurable
  extra key bar with desktop keys such as Ctrl, Alt, Escape and navigation keys.
- **External keyboard support:** the extra key bar can hide automatically when
  a USB or Bluetooth alphabetic keyboard is connected.
- **Direct browser session:** Openbox manages the Chrome window without a full
  desktop shell.
- **Verified runtime downloads:** the bundled manifest pins Debian and Chrome
  downloads by SHA-256; interrupted downloads can resume.
- **Copyable diagnostics** when startup or a browser session fails.

## Requirements

| Requirement | Current support |
| --- | --- |
| CPU | ARM64 / `arm64-v8a` |
| Android | Android 8.0 or newer; tested on Android 16 on a Vivo X300 |
| Network | Required for first-run installation and ordinary web browsing |
| Storage | Free internal storage for Debian, Chrome and the browser profile; setup checks available space |
| Root / Shizuku / Termux | Not required |
| Many tabs or extensions | Developer-options child-process setting recommended where available |

This is an **early preview release**. Other devices and Android builds may behave
differently.

## Installation

1. Download `desktop-chrome-debug.apk` from the
   [v0.1.0 release](https://github.com/lophas/desktop-chrome-android/releases/tag/v0.1.0).
2. Allow your browser or file manager to install apps from that source when
   Android asks, then install the APK.
3. Open **Desktop Chrome Android** and keep it open while it downloads and
   prepares Debian and Google Chrome.
4. Wait for the browser window to appear. Later launches reuse the installation.

The small APK does **not** contain Debian or Google Chrome. The pinned Debian
archive is about 240 MB; Chrome is downloaded separately from Google.

The app targets Android SDK 28 for Linux executable compatibility, so Android may
show an older-target warning. The APK currently uses the upstream public debug
signing key; this is a development distribution, not a hardened production build.

### Updating an existing installation

Install the new APK over the existing app. **Do not uninstall it or clear its app
data** if you want to keep the browser profile and Linux installation. Updates
must use a compatible signing key.

With ADB, installation or updating is also possible:

```bash
adb install -r desktop-chrome-debug.apk
```

## Getting started

1. Complete the first-run installation and use Chrome as you would on a desktop.
2. Install any desktop extensions you need. Extensions and embedded page content
   can each add browser processes.
3. For many tabs or longer sessions, review
   [Android process limits](#android-process-limits) before relying on the app.
4. Configure the [keyboard toolbar](#keyboard-toolbar) to suit touch or external
   keyboard use.
5. Open **Preferences** through the app's Android notification, including when
   the extra key bar is hidden.

Chrome's Memory Saver at `chrome://settings/performance` can deactivate unused
tabs. It can reduce resource usage but is not a hard process cap.

## Browser settings

**Preferences → Browser** contains the Desktop Chrome runtime options:

- **Fast system calls (PRoot acceleration)** — on by default. It makes video
  playback and browsing much faster. If Chrome fails to start with it, the app
  turns it off automatically and says so here. Turn it off manually if Chrome
  crashes or misbehaves. Changes apply after restarting the app. See
  [video and GPU notes](docs/GPU-VIDEO.md).
- **Update Chrome automatically** — on by default. Linux Chrome has no built-in
  updater; Google publishes updates through its signed APT repository. Before
  Chrome starts, at most about once a day, the app installs a newer
  `google-chrome-stable` from that repository. A failed check is logged in
  `files/runtime/setup.log` and the installed Chrome starts normally. The
  installed version is shown below the switch. A Chrome version pinned by a
  later APK is treated as a minimum and never downgrades a newer installation.

## Keyboard toolbar

The **extra key bar** is the toolbar containing Escape, Ctrl, Alt and navigation
keys. It is separate from Android's software keyboard.

### Toggle it with Volume down

In the app's **Preferences**, select:

**Other → Configure response to user actions → Volume down →
toggle additional key bar**

After configuring this, pressing **Volume down** shows or hides the toolbar.
This is a configurable action, not the default Volume down behavior.

The upstream default gesture is a **three-finger swipe down**. On phones where
that gesture takes a system screenshot, use the Volume down mapping instead.

### Hide it from Preferences

**Preferences → Keyboard → Show additional keyboard** controls whether the
extra key bar is shown.

### External keyboards

**Preferences → Keyboard → Hide extra key bar with external keyboard** is
enabled by default. The app hides the toolbar when it detects a USB or Bluetooth
alphabetic keyboard and restores the saved manual visibility on disconnect.

You can manually reveal the toolbar while an external keyboard is connected. In
this mode it overlays the browser without resizing the desktop. The manual
override lasts until the keyboard connection or this preference changes; a bar
you manually hid stays hidden on disconnect.

**Show IME with external keyboard** controls Android's software keyboard
separately.

## Android process limits

Android can kill native child processes with the reason
`Trimming phantom processes`. If it kills PRoot, the entire Linux browser session
ends, often reported as **Chrome stopped (137)**.

The tested phone has a **shared limit of 32 phantom processes**. This is not an
allowance of 32 processes exclusively for Chrome: other applications also use
the shared pool. Chrome tabs, cross-site frames, extensions and helper services
can create more processes than the visible tab count suggests.

**For many tabs, enable this setting where available:**

**Android Settings → System → Developer options →
Disable child process restrictions → ON**

The path and wording vary by manufacturer; search Settings for “child process”
or “Developer options.” To expose Developer options, Android normally requires
tapping the Build number seven times in the device's About / software information
screen. Some manufacturers omit the child-process switch.

**ON means the restrictions are disabled.** This is a system-wide setting.
The app does not change it automatically. It addresses phantom-process
restrictions, not every possible cause of termination such as memory pressure
or vendor battery management. Turning Developer options off can restore the
restrictions.

The launcher requests `--renderer-process-limit=4`, but Chromium treats this as
a **soft** limit. It does not cap all Chrome processes or prevent Android from
terminating the session.

Device testing confirmed both a successful 15-minute session with fewer tabs and
later phantom-process termination. This release does not claim unrestricted
many-tab stability with Android's default restrictions. See the
[validation notes](docs/TESTING.md) for the measured results.

References:
[Android preference controller](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/src/com/android/settings/development/PhantomProcessPreferenceController.java),
[Android phantom-process management](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/PhantomProcessList.java),
[Chromium process model](https://chromium.googlesource.com/chromium/src/+/main/docs/process_model_and_site_isolation.md).

## Known limitations and troubleshooting

| Symptom / limitation | What to know |
| --- | --- |
| Chrome stops after a few minutes, often with code 137 | Check Android's system log for `Trimming phantom processes`; use the Developer option above when available. Code 137 alone does not identify the killer. |
| Audio output | Experimental on the `audio-output` branch; see [audio tests](docs/AUDIO.md). The v0.1.0 release remains without audio. |
| Graphics performance | Chrome uses software rendering with `--disable-gpu`; WebGL is unavailable. Video decoding is software-only but plays 1080p60 VP9 smoothly on the test phone. See [video and GPU notes](docs/GPU-VIDEO.md). |
| Browser sandbox | Chrome runs with `--no-sandbox` because this PRoot environment does not provide the normal Linux browser sandbox. Android's app sandbox remains a separate boundary. |
| Startup fails | Use **Copy details** in the error dialog when reporting the problem. |
| Empty machine-id, D-Bus or udev messages | These appear in the current environment, including successful sessions; their presence alone does not identify a crash cause. |
| Data loss after uninstall / clearing data | Debian and the browser profile live inside app storage. Normal APK updates preserve them. |

## Development

The development branch is **`desktop-chrome`**. Builds use JDK 21, Android SDK 34
and NDK **29.0.14206865**.

On a Debian x86_64 build host:

```bash
apt-get install -y build-essential git curl unzip python3 file bison flex gawk xkb-data
git clone --branch desktop-chrome --recurse-submodules https://github.com/lophas/desktop-chrome-android.git
cd desktop-chrome-android
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew :lorie-app:assembleStandaloneDebug
```

Accept SDK licenses and install the required SDK / NDK components before building.
The Gradle build prepares the static PRoot runtime and bundled keyboard data.

Output: `lorie-app/build/outputs/apk/standalone/debug/desktop-chrome-debug.apk`.

### Process measurements

On a computer connected to the phone through ADB:

```bash
MEASURE_SECONDS=900 bash scripts/measure-android-processes.sh
```

The script records process snapshots and system kill events and saves a ZIP in
the current directory. Ctrl+C preserves a partial recording. It deletes the
previously used `settings_enable_monitor_phantom_procs` global override at startup;
check your desired child-process setting after diagnostic runs.

Native process counts from `ps` and Android's tracked phantom-process counts are
different measurements. Extension renderer counts are a subset of all renderers.

### Runtime image and release provenance

`scripts/build-debian-rootfs.sh` prepares the ARM64 Debian image from a pinned
official Debian Docker rootfs, using host PRoot and QEMU. It installs Chrome's
dependencies, Openbox, D-Bus and fonts; Google Chrome is downloaded separately.

```bash
apt-get install -y --no-install-recommends proot qemu-user-static xz-utils
bash scripts/build-debian-rootfs.sh
```

The image and its manifest must be published together, and the generated manifest
copied to `lorie-app/src/main/assets/runtime-manifest.json` before an APK build.

Version 0.1.0 preserves the exact LXC-built APK tested as `chrome-test-009`.
The release workflow verifies its SHA-256 and checks that runtime sources have
not changed, then publishes it with release notes, a checksum and
`release.json` provenance. It does not rebuild or silently alter the tested APK.

### Runtime layout

| App-private path | Contents |
| --- | --- |
| `files/debian` | Persistent Linux installation |
| `files/debian/root/.config/desktop-chrome-android` | Chrome profile |
| `files/runtime/tmp` | Shared X11 / PRoot temporary files |
| `files/runtime/setup.log` | First-run setup log |
| `files/runtime/chrome-session.log` | Latest browser session log |

## Upstream and licenses

Based on [Termux:X11](https://github.com/termux/termux-x11); its original README is
preserved in [UPSTREAM_TERMUX_X11.md](UPSTREAM_TERMUX_X11.md).

PRoot comes from [termux/proot](https://github.com/termux/proot), talloc from
[Samba](https://www.samba.org/ftp/talloc/), and archive support from
[Apache Commons Compress](https://commons.apache.org/proper/commons-compress/).
Pinned sources, checksums and runtime license copies are included in the build
process. See [LICENSE](LICENSE) and component licenses for their terms.

Google Chrome is downloaded from Google's official package repository and remains
subject to Google's terms.
