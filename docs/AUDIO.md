# Audio output experiment

Branch: `audio-output`. APK version code: **19**. The first device test produced
audible playback but stuttered; this update's tuning still needs device testing.
It is separate from the frozen v0.1.0 release. No microphone permission is added;
microphone input and calls are outside this change.

## Design

Chrome connects to a private PulseAudio server inside Debian. A software sink
mixes the output; `parec` reads its monitor as 48 kHz, stereo, signed little-endian
PCM16. An app-private FIFO carries those bytes into the existing X11 service's
Android AudioTrack. No TCP listener or external Termux service is used.

The bridge adds two persistent Linux children (`pulseaudio` and `parec`) and one
Android thread, rather than another Android process. Chrome may create its own
audio utility process too. The existing shared Android child-process limitation
still applies; use the documented Developer option for many-tab testing.

The current tuning uses a 250 ms Android buffer and queues about 125 ms before
starting playback. PulseAudio capture requests 120 ms latency with 40 ms processing
intervals, rather than the original 40/10 ms configuration. This favors continuity
under CPU scheduling delays over minimal latency; audio/video synchronization must
be checked on the device. Volume is updated only when it changes. Android's
underrun counter and cumulative PCM byte counts are logged every ten seconds.

Audio focus is requested when non-silent samples arrive, released after two
seconds of silence, and respected during transient focus loss. The receiver
drains incoming audio while playback is paused so stale sound is not queued for
later. Android chooses the media output route. Speaker, headphones, Bluetooth
and external-display routes still need device validation.

If the Android receiver or PulseAudio startup fails, the browser can still start
without audio and logs the failure. A failure after playback starts is logged;
automatic recovery of a dead audio bridge is not implemented in this first test.
Session shutdown stops the PulseAudio and capture children and closes AudioTrack.

## First launch after updating

The existing Debian installation and Chrome profile are preserved. One-time audio
setup uses Debian's configured, signed APT repositories to install `pulseaudio`,
`pulseaudio-utils` and `libasound2-plugins`; it requires an internet connection.
These packages are not included in the APK or pinned by the Chrome download
manifest. Package-install failures show the setup error and require Retry.

The installer also initializes an empty/invalid Debian machine-id with a random
per-install ID. Audio configuration lives in `/usr/local/lib/desktop-chrome-audio`;
the PulseAudio and ALSA overrides apply only to the experimental browser session.

## Build and upload from the LXC host

From the existing checkout, with any local work committed or saved:

```bash
git fetch origin
git switch --track origin/audio-output
export ANDROID_HOME=/opt/koreader/base/toolchain/android-sdk-linux
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew :lorie-app:assembleStandaloneDebug --console=plain
```

On subsequent builds use `git switch audio-output` and `git pull --ff-only`.

Publish the test APK after a successful build:

```bash
gh release create audio-test-002 \
  lorie-app/build/outputs/apk/standalone/debug/desktop-chrome-debug.apk \
  --repo lophas/desktop-chrome-android \
  --target "$(git rev-parse HEAD)" \
  --title "Desktop Chrome Android - Audio test 002" \
  --prerelease \
  --notes "Audio buffering and priming improvements, fewer volume updates, underrun/CPU diagnostics, and an opt-in PRoot fast-path comparison. Device validation required."
```

Install over the current app without uninstalling or clearing its data.

## Device checks

1. Play a short web video with sound; verify intelligible audio, stereo playback,
   pause/resume and approximate audio/video synchronization.
2. Play two tabs simultaneously; confirm their output mixes, then close one.
3. Pause both for several seconds and resume; confirm there is no stale audio.
4. Start another media app and return; check focus loss, restoration and volume.
5. Test the output devices you use, including Bluetooth or an external display.
6. Close and reopen Desktop Chrome; confirm audio returns and no old server remains.
7. Record a process measurement while playing audio. The CSV now includes
   `pulse_servers` and `pcm_readers`, and events include `DesktopChromeAudio` logs.
   The recording also includes filtered `dumpsys cpuinfo` snapshots in `cpuinfo.txt`.

Diagnostic command on the connected computer:

```bash
adb logcat -b all -d -v threadtime -s DesktopChromeAudio:V DesktopChrome:V
```

## Optional PRoot syscall acceleration comparison

The original runtime forces `PROOT_NO_SECCOMP=1` as a compatibility precaution.
That prevents PRoot's syscall-filtering acceleration, when compiled in and
supported by the kernel. The default remains unchanged in this audio update.
The effect on this phone has not been benchmarked.

After testing the new buffer settings by themselves, an ADB-connected computer
can opt this debug APK into the accelerated path for a separate comparison:

```bash
adb shell am force-stop hu.lophas.desktopchromeandroid
adb shell run-as hu.lophas.desktopchromeandroid touch files/runtime/enable-proot-seccomp
adb shell am start -n hu.lophas.desktopchromeandroid/com.termux.x11.MainActivity
```

This removes the forced opt-out for this app; it does not guarantee that PRoot
actually uses filtering on every build or device. Compare the same pages and
video with a process/CPU recording. It is PRoot syscall tracing acceleration,
not a change to Chrome's `--no-sandbox` setting or Android's child-process switch.

To return to the conservative baseline, including if the experiment fails to start:

```bash
adb shell am force-stop hu.lophas.desktopchromeandroid
adb shell run-as hu.lophas.desktopchromeandroid rm -f files/runtime/enable-proot-seccomp
adb shell am start -n hu.lophas.desktopchromeandroid/com.termux.x11.MainActivity
```

See [PRoot's syscall filter implementation](https://github.com/termux/proot/blob/v5.1.107.96/src/syscall/seccomp.c).

To roll back the APK with ADB, the public-debug-key builds allow a version-code
downgrade: `adb install -r -d desktop-chrome-debug.apk` using the **v0.1.0 APK**.
The installed audio packages remain, but v0.1.0 does not start the bridge.

## Validation available before device testing

- Changed Android classes compile against the verified official SDK 34 API JAR;
  generated/project-only collaborators were stubbed for this API compilation.
  This is not a full Gradle APK build.
- `PcmFramesTest` checks byte-for-byte reconstruction across arbitrary FIFO
  fragments, reconnection boundaries and signed PCM16 silence detection.
- Shell syntax checks cover the launcher and audio lifecycle script.
- `test-audio-session.py` exercises a real Linux PulseAudio monitor, a missing
  receiver and child cleanup on a host with PulseAudio tools installed. In the
  current coding environment its real-server step was blocked by a Unix socket
  permission error; successful transport or audible output is not claimed.

To run the portable PCM test on a JDK-equipped host:

```bash
mkdir -p build/audio-tests
javac -d build/audio-tests \
  lorie-app/src/main/java/com/termux/x11/PcmFrames.java tests/PcmFramesTest.java
java -cp build/audio-tests com.termux.x11.PcmFramesTest
```

References: [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack),
[PulseAudio modules](https://www.freedesktop.org/wiki/Software/PulseAudio/Documentation/User/Modules/),
[parec options](https://manpages.debian.org/testing/pulseaudio-utils/parec.1.en.html).
