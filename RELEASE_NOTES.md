# ChromeDeck 0.3.0 — Preview

## New in 0.3.0

- **Phone storage in Chrome:** the phone's shared storage (Download, Documents and
  so on) appears to Chrome as `/root/Phone`, so files can be opened and saved there
  and unpacked extensions can be loaded (`chrome://extensions` → Developer mode →
  Load unpacked). On by default: the app asks for the storage permission on its
  first start, and declining turns the setting off. **Preferences → Browser →
  Phone storage** turns it on or off; changes apply after restarting the app.

Install over 0.2.x without uninstalling; the browser profile is kept.

## 0.2.3

Desktop Chrome Android is now **ChromeDeck**. Install over the previous version;
the browser profile is kept.

### New in 0.2.3

- **Full-screen monitor mode:** in desktop mode Chrome fills the monitor without a
  window caption, and moves between the monitor and the phone without restarting.
  The monitor must be connected after the phone has started.
- **Phone as touchpad:** while Chrome is on the monitor, the dark phone screen
  works as a touchpad and the keyboard and mouse control Chrome there.
- **Phone screen on/off:** the app's notification and the Quick Settings tile
  switch between the touchpad and using other apps on the phone; Chrome stays on
  the monitor.
- **Touchpads and mice:** **Preferences → Pointer → Mouse wheel scroll speed**
  (50% by default) and **Touchpad pinch zooms** (off by default), which ignores
  the Ctrl+wheel pinches that some USB touchpads send.

## 0.2.2

### New in 0.2.2

- **External monitor in Android's desktop mode:** with **Developer options →
  Force desktop mode** and **Enable freeform windows** on, Chrome moves to a
  connected monitor automatically, maximized at its full 16:9 resolution, and
  returns to the phone when the monitor disconnects (**Preferences → Browser →
  Open on external display automatically**). Tapping the app icon returns the
  keyboard and mouse to the monitor after using the phone.
- **Mouse on the monitor:** the window captures the mouse there, so it keeps
  working on the monitor while the phone is used by touch.
- **Display scale:** separate settings for the phone screen and external displays
  render Chrome larger at full sharpness, like a HiDPI display.
- **Phone screen off:** the notification (tap it) or a Quick Settings tile darkens
  the phone screen while Chrome is on the monitor; tap the black screen to return.
- The phone stays awake while Chrome is on the monitor, because the monitor sleeps
  together with the phone screen. Chrome starts below the desktop-mode window
  caption, which the app cannot hide.

Install over 0.2.1 or 0.2.0 without uninstalling; the browser profile is kept.
The app icon is a new launcher entry, so re-add it to the home screen if it
disappears.

## 0.2.1

### New in 0.2.1

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
