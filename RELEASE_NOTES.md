# Desktop Chrome Android 0.2.1 — Preview

## New in 0.2.1

- **Battery-friendly rendering:** a new **Preferences → Browser → Frame rate**
  setting, 30 fps by default (also 60 fps or the display maximum). Chrome renders
  in software, and 30 fps uses 20–40% less CPU than the 120 Hz display maximum on
  the test phone. The app also asks Android to run the display at that rate.
  At 30 fps, 60 fps videos show every second frame. Changes apply after
  restarting the app.
- **Security:** the preference and command receivers inherited from Termux:X11 are
  no longer reachable by other apps.

Install over 0.2.0 without uninstalling; the browser profile is kept. Upgrading
from 0.1.0 still requires uninstalling it once (see below).

## 0.2.0

Official Linux ARM64 desktop Google Chrome on Android in one app, with Debian 13,
an embedded X11 display and a static PRoot runtime. No Termux, root access or
Shizuku service is required.

### New in 0.2.0

- **Smooth video:** fast PRoot system calls. On the test phone 1080p60 VP9 plays
  at 58 fps instead of 10 fps. Chrome still renders and decodes in software.
- **Audio playback** through Android's media output.
- **Automatic Chrome updates** from Google's signed package repository, at most
  about once a day before Chrome starts. New installations start with Chrome
  156.0.8078.47.
- **Tabs reopen after a restart** without the "Chrome didn't shut down correctly"
  prompt.
- **Preferences → Browser** with switches for PRoot acceleration and automatic
  updates, and the installed Chrome version.
- A new launcher icon, and no warning bar about `--no-sandbox`.

### Installing

Google Play Protect blocks a fresh installation ("Unsafe app blocked"); choose
**More details → Install anyway**. The app targets Android 9 to run Linux programs
from its own storage, and it downloads Debian and Chrome after installation.

**0.2.0 is signed with a new key.** Uninstall 0.1.0 once before installing 0.2.0;
this deletes the old browser profile. Later versions install over 0.2.0 and keep
the profile.

### Known issues

- Many tabs can exceed Android's shared child-process limit; enable **Developer
  options → Disable child process restrictions** where available.
- Turn off YouTube's **Ambient mode** to avoid dropped frames.
- No GPU acceleration or WebGL.

Tested on a Vivo X300 with Android 16, including a fresh installation.
