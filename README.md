# ChromeDeck

**The desktop edition of Google Chrome, running locally on Android.**

[Download the latest APK](https://github.com/lophas/chromedeck/releases/latest/download/chromedeck.apk)
· [Release notes](RELEASE_NOTES.md)
· [All releases](https://github.com/lophas/chromedeck/releases)

## Mission

Make a real desktop browser available on an Android phone or tablet in a
self-contained app: desktop Chrome's interface, desktop extensions and persistent
browser data, without setting up Termux, a separate X server or a full desktop
environment yourself.

Everything runs on the device. This is the official Linux desktop browser shown
through an embedded X11 display; it is not a remote browser or an Android WebView.

## Features

- **Official desktop Google Chrome for ARM64**, including desktop web extensions.
- **One Android app:** an embedded X11 display server and a static PRoot runtime.
  No separate Termux app, root access or Shizuku service is required.
- **Automatic setup:** on first launch the app installs Debian 13 and Google Chrome
  from verified, SHA-256-pinned downloads.
- **Automatic Chrome updates** from Google's signed package repository.
- **Smooth video:** fast PRoot system calls let Chrome play 1080p60 video on the
  test phone with software decoding.
- **Battery-friendly rendering:** a 30 fps default frame rate, adjustable in the
  app's settings.
- **Audio playback** through Android's media output.
- **Phone storage in Chrome** at `/root/Phone`, for files and unpacked extensions.
- **Persistent profile:** settings, sign-ins and extensions survive restarts and
  app updates, and the last session's tabs reopen when the app starts again.
- **Touch and keyboard controls:** a configurable extra key bar with Esc, Ctrl,
  Alt and navigation keys that hides itself when an external keyboard is connected.
- **Fullscreen mode** without Android's status and navigation bars.
- **External monitor:** Chrome fills a connected monitor in Android's desktop mode,
  with its own display scale and the phone's battery level and clock in the
  corner, while the phone screen works as a touchpad.
- **Copyable diagnostics** when startup or a browser session fails.

## Requirements

| Requirement | Support |
| --- | --- |
| CPU | ARM64 (`arm64-v8a`) |
| Android | 8.0 or newer; tested on Android 16 on a Vivo X300 |
| Storage | About 1.2 GB after installation, growing with the browser profile and cache |
| Memory | Plan for roughly twice the RAM of Android's own Chrome |
| Network | Required for installation and browsing |
| Root, Shizuku, Termux | Not required |

This is a **preview**. Other devices and Android versions may behave differently.

## Install

1. Download `chromedeck.apk` from the
   [latest release](https://github.com/lophas/chromedeck/releases/latest).
2. Open the APK and allow installation from that source when Android asks.
3. **Google Play Protect warns about the app** ("Unsafe app blocked"). Choose
   **More details → Install anyway**. See [Play Protect warning](#play-protect-warning).
4. Open **ChromeDeck** and keep it in the foreground while it installs
   Debian and Chrome. It downloads about 380 MB; on a fast connection Chrome
   appears after about a minute. Later launches start directly.

### Updating

Install the new APK over the existing app. **Do not uninstall the app or clear its
data**, or the browser profile and the Linux installation are deleted.

Version 0.2.0 is signed with a new key. Android cannot update a 0.1.0 installation
in place: uninstall 0.1.0 once, then install 0.2.0. This deletes the old profile.

With ADB:

```bash
adb install -r chromedeck.apk
```

## Getting started

- **Preferences** open from the app's Android notification or from the gear key
  on the extra key bar.
- **Fullscreen:** **Preferences → Output → Fullscreen** hides Android's status and
  navigation bars; swipe from the screen edge to show them briefly.
  **Hide cutout** also uses the area beside the camera cutout.
- **Extra key bar:** toggle it with a three-finger swipe down. If your phone uses
  that gesture for screenshots, map **Preferences → Other → Configure response to
  user actions → Volume down → toggle additional key bar**.
- **External keyboard:** **Preferences → Keyboard → Hide extra key bar with
  external keyboard** (on by default) hides the bar while a USB or Bluetooth
  keyboard is connected. **Show IME with external keyboard** controls Android's
  software keyboard separately.
- **Browser settings** in **Preferences → Browser**:
  - **Phone storage** — on by default. The phone's shared storage (Download,
    Documents and so on) appears to Chrome as `/root/Phone`, for example to load
    an unpacked extension (`chrome://extensions` → **Developer mode** → **Load
    unpacked**) or to open and save files. The app asks for the storage
    permission on its first start; declining turns the setting off, and it can be
    turned on again here. Changes apply after restarting the app.
  - **Fast system calls (PRoot acceleration)** — on by default; it makes video and
    browsing much faster. If Chrome fails to start with it, the app turns it off
    and says so here. Turn it off if Chrome crashes or misbehaves. Changes apply
    after restarting the app. Details: [video and GPU notes](docs/GPU-VIDEO.md).
  - **Update Chrome automatically** — on by default. Linux Chrome has no built-in
    updater, so before Chrome starts, at most about once a day, the app installs a
    newer `google-chrome-stable` from Google's repository. A failed check never
    blocks startup. The installed version is shown below the switch.
  - **Frame rate** — 30 fps by default to save battery. Chrome renders in
    software, and 30 fps uses 20–40% less CPU than the display's maximum.
    Choose 60 fps or **Display maximum** for smoother scrolling and animation.
    Applies after restarting the app.
- **External display:** turn on **Developer options → Force desktop mode** and
  **Enable freeform windows**, restart the phone, and connect the monitor
  **after** the phone has started (a monitor connected during startup stays
  disabled until it is reconnected). With **Preferences → Browser → Open on
  external display automatically** (on by default) Chrome then fills the monitor
  without a window caption, and returns to the phone when the monitor
  disconnects, without restarting. Meanwhile the phone screen is dark and works
  as a touchpad, and the keyboard and mouse control Chrome on the monitor.
  **Phone screen on** in the app's notification, or the **Phone screen off**
  Quick Settings tile, lets you use other apps on the phone while Chrome stays on
  the monitor; the mouse then belongs to the phone until you switch back. The
  phone stays awake while Chrome is on the monitor, because the monitor sleeps
  together with the phone screen; the power button still puts both to sleep.
  The top right corner of the monitor shows the phone's battery level and the
  time over Chrome's window buttons; **Preferences → Browser → Battery and clock
  on external display** turns it off.
- **Display scale:** **Preferences → Browser → Display scale — phone screen** and
  **— external display** make Chrome's interface and pages larger at full
  sharpness, like a HiDPI display. Chrome uses the scale of the display it starts
  on.
- **Tabs after a restart:** each profile starts set to Chrome's **On startup →
  Continue where you left off**, so the last session reopens without a crash
  prompt. You can change this in Chrome's settings.
- **Many tabs:** enable the Android developer option described under
  [Android child-process limit](#android-child-process-limit).

## Known issues

### Android child-process limit

Android can stop the browser with the reason `Trimming phantom processes`,
usually reported as **Chrome stopped (137)**. Android shares a limit of 32 such
processes among all apps, and Chrome tabs, cross-site frames and extensions each
add processes.

For many tabs, turn on **Settings → System → Developer options → Disable child
process restrictions**. The wording varies by manufacturer; search Settings for
"child process". Developer options appear after tapping **Build number** seven
times in the phone's About screen. Some manufacturers omit the switch. The setting
is system-wide, and the app does not change it.

Fewer tabs and Chrome's Memory Saver (`chrome://settings/performance`) reduce the
risk but do not remove it. Measurements: [process-limit notes](docs/TESTING.md).

### YouTube drops frames with Ambient mode

YouTube's **Ambient mode** draws a blurred copy of every video frame behind the
player. Without a GPU this saturates Chrome's compositor: on the test phone a
1080p30 video dropped 13–44% of its frames with Ambient mode and 1.2% without it.
Turn it off in the player's **Settings (gear) → Ambient mode**.

### No GPU acceleration or WebGL

Chrome renders in software (`--disable-gpu`), and WebGL is unavailable. Video is
decoded by Chrome's software decoders; Android's hardware decoders are not
reachable from Linux Chrome. Effects that redraw large areas every frame cost
CPU and battery. At the default 30 fps, 60 fps videos show every second frame;
choose a higher frame rate in **Preferences → Browser** if that matters.
Details: [video and GPU notes](docs/GPU-VIDEO.md).

### Play Protect warning

The app targets Android 9 (SDK 28), which it needs to run Linux programs from its
own storage, and it downloads and runs Debian and Chrome after installation. Google
Play Protect therefore blocks a fresh installation until you choose
**Install anyway**.

### Browser sandbox

Chrome runs with `--no-sandbox` because PRoot cannot provide the Linux browser
sandbox. Android's app sandbox still separates ChromeDeck from other apps.
Chrome's warning bar about the flag is hidden.

### Tabs after the app is swiped away

Many phones end the whole app at once when it is swiped away from recent apps.
Chrome reopens the tabs it had saved by then, which may miss the last few seconds
of browsing.

### External display needs desktop mode

In mirroring mode the phone's own HDMI mirroring owns the monitor and apps
cannot draw on it, so Developer options' desktop mode is required. The mouse
can only control Chrome on the monitor while ChromeDeck is in front on the
phone. In desktop mode Android opens the recent-apps switcher on the monitor,
so swiping up and holding on the phone does not show it there while a monitor
is connected, with or without ChromeDeck.

### Audio

Playback only; there is no microphone input. Details: [audio notes](docs/AUDIO.md).

### Log messages

D-Bus, udev and similar errors in `files/runtime/chrome-session.log` also appear in
working sessions. Use **Copy details** in the error dialog when reporting a problem.

Planned work and postponed ideas are listed in the [roadmap](docs/ROADMAP.md).

## Development

Builds use JDK 21, Android SDK 34 and NDK **29.0.14206865** on a Debian x86_64 host:

```bash
apt-get install -y build-essential git curl unzip python3 file bison flex gawk xkb-data
git clone --recurse-submodules https://github.com/lophas/chromedeck.git
cd chromedeck
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew :lorie-app:assembleStandaloneDebug
```

Accept the SDK licenses and install the SDK and NDK components first. The build
prepares the static PRoot runtime and keyboard data. Output:
`lorie-app/build/outputs/apk/standalone/debug/chromedeck.apk`.

### Signing

Published APKs are signed with a private key that is not in this repository. The
build reads `~/.config/desktop-chrome-android/signing.properties` (or the file
named by `DESKTOP_CHROME_SIGNING`):

```properties
storeFile=release.jks
storePassword=...
keyAlias=desktop-chrome
keyPassword=...
```

`storeFile` is relative to that file. Without it, the build falls back to the
public Termux:X11 test key; such APKs cannot update published installations.

### Runtime image

`scripts/build-debian-rootfs.sh` builds the ARM64 Debian image from a pinned
official Debian rootfs using host PRoot and QEMU. It installs Chrome's
dependencies, Openbox, D-Bus and fonts; Chrome itself is downloaded separately.

```bash
apt-get install -y --no-install-recommends proot qemu-user-static xz-utils
bash scripts/build-debian-rootfs.sh
```

Publish the image with its manifest, and copy the generated manifest to
`lorie-app/src/main/assets/runtime-manifest.json` before building the APK. The
manifest also pins the Chrome package that new installations start with.

### Process measurements

With the phone connected through ADB:

```bash
MEASURE_SECONDS=900 bash scripts/measure-android-processes.sh
```

The script records process snapshots and system kill events into a ZIP file;
Ctrl+C keeps a partial recording. It deletes the
`settings_enable_monitor_phantom_procs` global override at startup, so check your
child-process setting afterwards.

### Runtime layout

| App-private path | Contents |
| --- | --- |
| `files/debian` | Linux installation |
| `files/debian/root/.config/desktop-chrome-android` | Chrome profile |
| `files/runtime/tmp` | Shared X11 and PRoot temporary files |
| `files/runtime/setup.log` | Installation and Chrome update log |
| `files/runtime/chrome-session.log` | Latest browser session log |
| `files/runtime/disable-proot-seccomp` | Present when PRoot acceleration is off |
| `files/runtime/disable-chrome-update` | Present when automatic updates are off |
| `/root/Phone` (inside Debian) | The phone's shared storage, when Phone storage is on |

## Upstream and licenses

Based on [Termux:X11](https://github.com/termux/termux-x11); its original README is
preserved in [UPSTREAM_TERMUX_X11.md](UPSTREAM_TERMUX_X11.md).

PRoot comes from [termux/proot](https://github.com/termux/proot), talloc from
[Samba](https://www.samba.org/ftp/talloc/), and archive support from
[Apache Commons Compress](https://commons.apache.org/proper/commons-compress/).
Pinned sources, checksums and runtime license copies are part of the build. See
[LICENSE](LICENSE) and the component licenses for their terms.

Google Chrome is downloaded from Google's official package repository and remains
subject to Google's terms. This project is not affiliated with Google.
