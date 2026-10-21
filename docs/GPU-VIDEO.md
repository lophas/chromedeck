# Video playback and GPU rendering

Measured on the test phone (Vivo X300, MediaTek MT6993, Mali-G1-Ultra MC12,
Android 16) with Google Chrome 155, a warmed-up session, the screen on and the
app in the foreground. Each figure is a 25–30 s sample of a looping, muted
`<video>` element, read through `getVideoPlaybackQuality()` and
`requestVideoFrameCallback()`.

## Result: PRoot syscall acceleration

The browser session previously ran with `PROOT_NO_SECCOMP=1`. Every system call
of every Chrome thread then stops in PRoot's single ptrace tracer. During video
playback the phone had more than five idle cores while PRoot was the busiest
thread and Chrome's decoder threads were mostly waiting.

With PRoot's seccomp filter, calls that need no path translation bypass the
tracer:

| Video (60 fps VP9 unless noted) | Without seccomp | With seccomp |
| --- | --- | --- |
| 1080p | 10.5 fps shown, 68% dropped, plays at 0.63× | 58.1 fps, 0.2% dropped, 1.00× |
| 1440p | — | 55.9 fps, 1.5% dropped, 1.00× |
| 2160p | 4.8 fps shown, 80% dropped, plays at 0.45× | 45.5 fps, 6.4% dropped, 1.00× |
| 1080p H.264, 30 fps | 24.7 fps, 17% dropped | 27.4 fps, 7% dropped |

Frames already reach the embedded X server through MIT-SHM 1.2 file
descriptors (memfd); the X socket carried only about 11 MB during a full test,
so frame transfer was not the bottleneck. Android's kernel has no SysV IPC.

The browser session uses the accelerated path by default.
Package installation still uses the conservative path. If an accelerated
session exits with an error within 20 seconds, the app writes
`files/runtime/disable-proot-seccomp`, retries once without acceleration, and
keeps that choice. **Preferences → Browser → Fast system calls (PRoot
acceleration)** shows that state and turns acceleration off or back on; the
change applies after restarting the app. The switch stores the same
`files/runtime/disable-proot-seccomp` marker.

## Hardware video decoding

Not available. Linux Chrome decodes in hardware only through VA-API or V4L2
devices, which the Android app sandbox does not expose. Chrome's software
decoders (FFmpeg H.264, libvpx, dav1d) are used; with the change above they
are sufficient for 1080p60 and usable for 1440p60 on the test phone.

## YouTube Ambient mode

YouTube's Ambient mode draws a blurred copy of every video frame behind the
player. In software rendering this keeps Chrome's single-threaded compositor
fully busy. A 1080p30 AV1 video on youtube.com dropped 13–44% of its frames
with Ambient mode (compositor thread at about 84% of a core) and 1.2% without
it; the AV1 decoder threads used about 7% each. VP9 instead of AV1 did not
help (29%). Turn Ambient mode off in the player settings.

## GPU rendering status

GPU rendering remains disabled. Findings from earlier experiments (a VirGL 1.3.0
server on Android with ANGLE/Vulkan or GLES, Mesa `virpipe` in Debian) and from
direct device tests:

- The VirGL server works: `glxgears` reports `virgl (Mali-G1-Ultra MC12)` at
  roughly 250–350 fps.
- Chrome's ANGLE GL backend could not create its backing desktop GL context on
  `virpipe` (`EGL_NOT_INITIALIZED`). With `--use-gl=desktop`, the GPU process
  failed on an incomplete multisample framebuffer (`0x8D56`), followed by
  `GL_INVALID_FRAMEBUFFER_OPERATION` and fence export errors (`EBADF`).
- `glxinfo` fails with `BadMatch` on `X_GetImage`; Mesa's software-presented
  `virpipe` path reads every frame back to the CPU and sends it through X11,
  so it cannot improve on the current MIT-SHM software path for video.
- Chrome's GPU process also crashes (`SIGSEGV`, exit code 11) with its bundled
  SwiftShader backend, with or without PRoot acceleration. WebGL therefore
  stays unavailable.

The Mali Vulkan/GLES drivers are Android (bionic) libraries and cannot be
loaded by Debian's glibc Chrome directly. A zero-copy path would need a
glibc-to-bionic Vulkan bridge plus DRI3 buffers backed by `AHardwareBuffer`.
That is a separate research project, not a configuration change.
