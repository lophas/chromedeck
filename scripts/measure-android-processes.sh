#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Baseline measurement on the host computer; no APK changes required.
set -euo pipefail

pkg="hu.lophas.desktopchromeandroid"
out="desktop-chrome-baseline-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
adb get-state >/dev/null

adb shell -n settings delete global settings_enable_monitor_phantom_procs
adb shell -n settings get global settings_enable_monitor_phantom_procs | tee "$out/monitor-setting.txt"

app_uid="$(adb shell -n cmd package list packages -U "$pkg" | tr -d '\r' | awk -v p="package:$pkg" '$1==p {sub(/^uid:/,"",$2); print $2; exit}')"
if [[ ! "$app_uid" =~ ^[0-9]+$ ]]; then
    echo "Cannot determine app UID."
    exit 1
fi

adb shell -n dumpsys activity settings > "$out/activity-settings.txt"
adb logcat -b all -v threadtime -T 1 -s ActivityManager:I DesktopChrome:V lmkd:V am_kill:I am_proc_died:I > "$out/events.txt" 2>&1 </dev/null &
log_pid=$!
trap 'kill "$log_pid" 2>/dev/null || true' EXIT
trap 'exit 130' INT TERM

adb shell -n am start -n "$pkg/com.termux.x11.MainActivity" >/dev/null
printf 'time,app_processes,native_processes,renderers,zygotes\n' > "$out/counts.csv"
echo "Recording for about 5 minutes. Use Chrome on the phone."

for ((sample=0; sample<150; sample++)); do
    stamp="$(date +%H:%M:%S)"
    adb shell -n ps -A -o UID,PID,PPID,NAME,ARGS | tr -d '\r' | awk -v uid="$app_uid" 'NR==1 || $1==uid' > "$out/current-processes.txt"
    awk -v t="$stamp" -v pkg="$pkg" '
        NR>1 {
            total++
            if (index($5,pkg)!=1) native++
            if ($0 ~ /--type=renderer([[:space:]]|$)/) renderer++
            if ($0 ~ /--type=zygote([[:space:]]|$)/) zygote++
        }
        END {printf "%s,%d,%d,%d,%d\n",t,total,native,renderer,zygote}
    ' "$out/current-processes.txt" | tee -a "$out/counts.csv"
    {
        printf '\n=== %s ===\n' "$stamp"
        cat "$out/current-processes.txt"
    } >> "$out/processes.txt"
    if (( sample % 5 == 0 )); then
        {
            printf '\n=== %s ===\n' "$stamp"
            adb shell -n dumpsys activity processes | tr -d '\r' | awk '
                /All Active App Child Processes:/ {active=1}
                active && /All Zombie App Child Processes:/ {exit}
                active && /^[[:space:]]*$/ {if (seen) exit; next}
                active {print; seen=1}
            '
        } >> "$out/phantom-processes.txt"
    fi
    sleep 2
done

kill "$log_pid" 2>/dev/null || true
wait "$log_pid" 2>/dev/null || true
trap - EXIT INT TERM
rm "$out/current-processes.txt"
zip -qr "$out.zip" "$out"
echo "Done: $out.zip"
