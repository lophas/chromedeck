# Device validation — 2026-10-20

Device: Vivo X300, Android 16. Runtime: Debian 13 ARM64 and Google Chrome
155.0.8059.84. These are observations from individual sessions, not a general
stability guarantee.

## Current frozen runtime

Source commit: `b0aa61e9cd4da541d9098fa06808e28709d711a0`.
APK: `chrome-test-009`, version code 17,
`1.03.01-b0aa61e-20.10.26`.

The launcher starts Chrome's binary directly, uses `--no-zygote` and requests
`--renderer-process-limit=4`. Crashpad remains enabled. The experimental
`--process-per-site` and `--disable-features=SpareRendererForSitePerProcess`
options are absent. It also uses software rendering and `--no-sandbox`.

## Measurements of this runtime

| Session | Observation |
| --- | --- |
| 16:01:31–16:07:30, many tabs | Android killed PRoot and browser children with `Trimming phantom processes`; Chrome reported exit 137. |
| 16:30:23–16:45:21, fewer tabs | 421 samples covering approximately 15 minutes; measurement exited normally. No Chrome termination or phantom trimming was captured. |
| 16:52:30, later in the session | Android again killed PRoot and D-Bus processes with `Trimming phantom processes`. No simultaneous process-count recording was supplied for this event. |

| Metric | Many-tab measurement | Fewer-tab measurement |
| --- | --- | --- |
| App-native process peak | 44 | 29 |
| Renderer peak, including extension renderers | 30 | 16 |
| Extension renderer peak | 8 | 8 |
| Native processes in final live sample | 43 | 27 |
| Android tracked phantom-process peak captured | Do not infer from native counts | 27 |
| Configured phantom-process limit | 32 | 32 |

The shared Android process limit remained configured at 32; the measurement's
global monitor-override field was `null`. Native counts from `ps` and
Android's tracked phantom-process list are not interchangeable. Tracking and
sampling can lag actual process creation.

The successful run supports reduced resource use with fewer tabs. It does not
establish permanent stability: the later 16:52:30 event confirms that trimming
can still terminate the same runtime.

## Earlier startup regression

An experimental launcher disabled Crashpad and changed renderer options.
It failed immediately with repeated FD ownership violations, GPU / network
process crashes and exit 133. Restoring Crashpad alone did not resolve that
startup failure; removing the remaining experimental renderer flags restored
successful startup in the subsequent tests. The exact FD ownership root cause
was not established.

## Interpreting a failure

- `Trimming phantom processes` for PRoot at the time of session termination is
  direct evidence of Android child-process trimming.
- Exit 137 alone does not identify why SIGKILL occurred.
- D-Bus, udev, empty machine-id and ALSA messages also appeared in running
  sessions; do not treat those messages alone as the cause of termination.
- The Developer-options workaround is documented, but no new long-running test
  with that workaround enabled is claimed here.

Use `scripts/measure-android-processes.sh` for further recordings. It saves
partial results on Ctrl+C and deletes the previous global monitor override;
check the desired system setting after diagnostic use.
