# Roadmap and open ideas

Ideas that were discussed and deliberately postponed, with what is known about
them. Estimates assume the existing build host and the Vivo X300 test phone.

## Phone touchpad while Chrome is on a monitor

Done for the Presentation monitor mode: the dark phone screen works as a touchpad
through Termux:X11's trackpad gestures. The notes below remain for reference.

Already available: Termux:X11's trackpad gesture engine
(`InputStrategyInterface.TrackpadInputStrategy`, used when the phone screen is
set to act as a touchpad) handles one-finger movement, tap to click, two-finger
tap for the right button, two-finger scrolling and tap-and-drag.

Work needed:

1. Route touch events from `PhoneScreenOffActivity` to the trackpad handler of the
   browser window on the external display, scaled to that X screen.
2. Input focus. The cover currently takes focus on every touch and hands it back
   to the external window, which cancels gestures. A touchpad cover must not be
   focusable; focus has to return to the external window another way after the
   notification shade closes, for example on the first touchpad touch. Whether
   Vivo's input routing allows this without interrupting gestures is the main
   risk and needs a device test.
3. Leaving the touchpad: tap is now a click, so close it with Android's back
   gesture and a small close button, as DeX offers back and home buttons.
4. Optional: a button that opens Android's on-screen keyboard.

Estimate: about half a day for 1–3 including monitor testing, about an hour more
for 4.

## Monitor without Developer options

Done in 0.4.0: the test phone hands a mirrored monitor over to the Presentation
window, so desktop mode is not needed there; ChromeDeck asks before taking the
monitor. Behavior on Samsung DeX, Pixel desktop mode and other phones is untested.

## WebGL through SwiftShader

Chrome's GPU process crashes (SIGSEGV) with its bundled SwiftShader backend, with
or without PRoot acceleration, so WebGL is unavailable. Investigating the crash
could enable WebGL for sites that require it, rendered on the CPU. It would not
make rendering faster. Proposed time box: one day.

## GPU rendering on Adreno phones

Mesa's Turnip Vulkan driver runs in glibc environments on Snapdragon (Adreno)
phones and could give Chrome real GPU rendering there. It does not apply to the
Mali GPU of the test phone; it needs a Snapdragon device to try. GPU rendering on
Mali would need a glibc-to-bionic Vulkan bridge and is not planned. Details:
[video and GPU notes](GPU-VIDEO.md).

## Smaller open items

- **Graceful Chrome shutdown:** the SIGTERM path before the session stops is
  untested, because the test phone ends the whole app when it is swiped away.
- **Gboard language bubble:** with a hardware keyboard, Gboard may show a floating
  language indicator over the browser. Check whether turning off **Preferences →
  Keyboard → Show IME with external keyboard** hides it; if so, consider making
  that the default.
- **App notification on Vivo:** the notification is posted, but Vivo's shade does
  not always show it prominently; the Quick Settings tile is the reliable path.
- **Play Protect:** fresh installations are blocked until **Install anyway**.
  Google's developer verification for sideloaded apps may become the way to
  avoid the warning.
