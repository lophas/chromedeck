# Android child-process limit measurements — 2026-10-20

Device: Vivo X300, Android 16. Runtime: Debian 13 ARM64 and Google Chrome
155.0.8059.84 with `--no-zygote` and `--renderer-process-limit=4`, without PRoot
acceleration. These are observations from individual sessions, not a general
stability guarantee.

## Measurements

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
