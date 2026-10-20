# Desktop Chrome Android 0.1.0 — Preview

A self-contained Android app for running official Linux ARM64 desktop Google
Chrome locally, with Debian 13, embedded Termux:X11 and a static PRoot runtime.
No separate Termux installation, root access or Shizuku service is required.

## Included

- Automatic first-run Debian and Chrome installation with SHA-256 verification.
- Persistent Chrome profile and support for desktop extensions.
- Software rendering and a lightweight Openbox browser session.
- Configurable extra key bar and automatic hiding with an external keyboard.
- Rewritten installation, getting-started, keyboard and troubleshooting guide.

## Before using many tabs

Enable **Android Settings → System → Developer options → Disable child process
restrictions** where your device provides it. Turn the switch **ON** to disable
the restrictions. The setting is system-wide; the app does not change it.
Paths vary by manufacturer, and some devices omit the switch.

Default Android child-process restrictions caused confirmed session termination
in device tests. A 15-minute test with fewer tabs passed, but a later termination
also occurred. The soft renderer limit does not guarantee stability.

## Keyboard toolbar

To toggle the extra key bar with Volume down, configure:

**Preferences → Other → Configure response to user actions → Volume down →
toggle additional key bar**

The default three-finger swipe down also toggles it, unless intercepted by the
phone's screenshot gesture. External-keyboard auto-hide is enabled by default.

## Current limitations

ARM64 only; Android 8.0 or newer. Tested on Android 16 on a Vivo X300.
Audio output is not integrated. Chrome uses software rendering and
`--no-sandbox`; Android's app sandbox is a separate boundary.
The APK targets SDK 28 and uses the upstream public debug signing key.

## Download and update

Install `desktop-chrome-debug.apk` over the existing app to keep its profile.
Do not uninstall or clear app data. An already installed `chrome-test-009` APK
is identical to this release and does not need reinstalling.

This release promotes the exact tested LXC-built `chrome-test-009` APK:
- Runtime source commit: `b0aa61e9cd4da541d9098fa06808e28709d711a0`.
- APK version name: `1.03.01-b0aa61e-20.10.26`; version code: `17`.
- SHA-256: `2a8209144103801e593136b4d0c4cacc734701890af960418b7387adc31dbc8e`.

The 0.1.0 label identifies this documented preview release. The existing APK's
embedded version and signing identity are preserved. See `SHA256SUMS` and
`release.json` in the release assets.
