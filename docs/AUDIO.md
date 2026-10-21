# Audio output

Desktop Chrome plays audio through Android. No microphone permission is
requested; microphone input and calls are not supported.

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
the audio bridge is not restarted automatically.
Session shutdown stops the PulseAudio and capture children and closes AudioTrack.

## Installation

One-time audio
setup uses Debian's configured, signed APT repositories to install `pulseaudio`,
`pulseaudio-utils` and `libasound2-plugins`; it requires an internet connection.
These packages are not included in the APK or pinned by the Chrome download
manifest. Package-install failures show the setup error and require Retry.

The installer also initializes an empty/invalid Debian machine-id with a random
per-install ID. Audio configuration lives in `/usr/local/lib/desktop-chrome-audio`;
the PulseAudio and ALSA overrides apply only to the browser session.

## Diagnostics

Diagnostic command on the connected computer:

```bash
adb logcat -b all -d -v threadtime -s DesktopChromeAudio:V DesktopChrome:V
```

## Tests

`tests/test-audio-session.py` exercises a real Linux PulseAudio monitor, a
missing receiver and child cleanup on a host with PulseAudio tools installed.
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
