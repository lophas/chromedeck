# ChromeDeck 0.5.1 — Preview

## New in 0.5.1

- **Chrome update settings:** **Preferences → Browser → Chrome updates** offers
  **Install automatically** (default), **Ask before installing** and **Off**,
  daily or weekly checks, and **Only on Wi-Fi** (on by default). Updates are
  checked and downloaded in the background while Chrome runs, so startup no longer
  waits for them; a downloaded update installs before Chrome next starts. A
  notification offers **Restart now**, or in ask mode **Update**, **Later** and
  **Skip this version**. **Check for Chrome updates now** shows the installed and
  the latest version.
- **Chrome windows:** new browser windows open maximized; pop-up windows such as
  sign-in and payment windows keep their size. With more than one window the
  toolbar shows **Windows**, a list of the windows by title; tapping one brings it
  to the front. While ChromeDeck is on screen, windows no longer minimize, and
  Chrome's restore button does not shrink a browser window.
- **Toolbar place:** the toolbar's place is its handle's. Unfolding shifts it only
  as far as the screen edge needs, and folding returns the handle to its place; a
  moved toolbar keeps its new place.

### Fixes in 0.5.1

- After the screen turned off and on, ChromeDeck could stay black with only the
  mouse pointer: Chrome had been minimized and the message to restore it was lost.
  Minimizing and restoring now run in the session's long-running X11 helper, and
  every return to ChromeDeck restores Chrome.

Install over 0.5.0 without uninstalling; the browser profile is kept. Updates
turned off with the earlier switch stay off.

## 0.5.0

### New in 0.5.0

- **Automatic display scale:** **Automatic** in **Preferences → Browser → Display
  scale** now picks a scale for the display: from Android's display density on the
  phone (250% on a 480 dpi phone), from the resolution on a monitor (125% at 1080p,
  150% at 1440p, 200% at 2160p). 25% steps up to 300%; the settings show the
  scale that Automatic picks.
- **Display scale follows the display:** Chrome rescales at once, without a
  restart, when it moves between the phone and a monitor and when the scale
  setting changes. ChromeDeck now gives Chrome its scale as the desktop's Xft/DPI
  setting instead of a launch option. With a monitor connected, Chrome starts
  after the monitor question.
- **Toolbar on the phone too:** the touchpad's toolbar is now also on the phone
  screen over Chrome: **Settings**, **Keyboard**, **Zoom**, **Monitor** when a
  monitor is connected, and **Exit**. **Zoom** steps the display scale of the
  current display with − / +, or returns it to **Auto**. Drag it by its handle
  (touch, or a mouse that Chrome has not captured) and tap the handle to fold it;
  its place is kept per mode.
  **Preferences → Browser → Toolbar** hides it.
- **Notification** buttons match the toolbar: **Settings**, **Keyboard**, **Exit**
  or **Monitor** on the phone; **Settings**, **Mirroring**, **Use phone** /
  **Use touchpad** while Chrome is on the monitor.
- The interface calls the external display **Monitor**: **Switch to monitor** /
  **Keep mirroring** in the question, **Monitor** / **Mirroring** in the
  notification.
- At a large display scale on the phone, Chrome's window now stays as wide as the
  screen instead of reaching past its right edge.

Install over 0.4.x without uninstalling; the browser profile is kept.

## 0.4.0

### New in 0.4.0

- **Monitor choice:** when ChromeDeck starts with a monitor connected, or a monitor
  connects while it runs, it asks whether to **Switch to desktop** or
  **Keep mirroring**. **Remember this choice** saves the answer, **Preferences →
  Browser → Show Chrome on a connected monitor** (Ask / Always / Never) changes it,
  and **Desktop** / **Mirroring** in the app's notification switch for the
  current connection.
- **No Developer options needed** on the test phone: Chrome also takes over a
  monitor in normal mirroring mode, and mirroring returns when Chrome goes back to
  the phone.
- The monitor shows black instead of white while it switches over to Chrome.
- **Touchpad toolbar:** while Chrome is on the monitor, faint buttons at the top
  of the dark phone screen open Settings, the on-screen keyboard, the phone for
  other apps, and Exit (after a confirmation).
- **Simpler notification:** **Exit** and **Desktop** while the monitor mirrors the
  phone; **Exit**, **Mirroring** and **Use touchpad** / **Use phone** while Chrome
  is on the monitor. Settings open from the touchpad toolbar or the app icon's
  **Preferences** shortcut.
- **No key bar:** Termux:X11's extra key bar is gone; the back gesture opens the
  on-screen keyboard.
- **Full on-screen keyboard with a hardware keyboard:** Android then shows only a
  small keyboard bar; the touchpad's keyboard button offers to open Android's
  physical keyboard settings, where **Use on-screen keyboard** allows the full
  keyboard.
- Exit removes the app's notification also while Chrome is on a monitor.

Install over 0.3.x without uninstalling; the browser profile is kept. An
**Open on external display automatically** setting that was turned off becomes
**Never**.

## 0.3.4

### New in 0.3.4

- **Lower CPU use in the background:** Chrome's window is minimized while
  ChromeDeck cannot be seen — another app is in front on the phone (after 3
  seconds) or the screens are off — and restored when it returns. A minimized
  Chrome stops drawing pages; audio keeps playing. A playing YouTube video drops
  from about 150% to about 50% of a CPU core. While Chrome is on a monitor it
  stays visible until the screens turn off.

Install over 0.3.x without uninstalling; the browser profile is kept.

## 0.3.3

### New in 0.3.3

- **Battery and clock height:** **Preferences → Browser → Battery and clock
  height** sets the size of the box in the monitor's corner, so that it covers
  Chrome's window buttons at any external display scale. Changes apply at once.

Install over 0.3.x without uninstalling; the browser profile is kept.

## 0.3.2

### New in 0.3.2

- **Battery and clock on the monitor:** while Chrome is on an external display,
  its top right corner shows the phone's battery level and the time, with ⚡ while
  charging; the level turns orange below 20% and red below 10%. The box covers
  Chrome's window buttons, which have no use there. **Preferences → Browser →
  Battery and clock on external display** turns it off.

Install over 0.3.x without uninstalling; the browser profile is kept.

## 0.3.1

### Fixes in 0.3.1

- While Chrome is on a monitor, the key bar stays visible on the phone touchpad;
  a three-finger swipe on the touchpad could hide it.
- The on-screen keyboard opens on the phone while Chrome is on a monitor, and its
  input goes to Chrome there. With a hardware keyboard connected, Android shows
  only a compact keyboard unless **Physical keyboard → Use on-screen keyboard**
  is on.

Known: in desktop mode Android shows the recent-apps switcher on the monitor, so
swipe up and hold does not open it on the phone while a monitor is connected.

## 0.3.0

### New in 0.3.0

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
