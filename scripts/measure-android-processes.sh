#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Baseline measurement on the host computer; no APK changes required.
set -euo pipefail

duration="${MEASURE_SECONDS:-300}"
if [[ ! "$duration" =~ ^[1-9][0-9]*$ ]]; then
    echo "MEASURE_SECONDS must be a positive integer."
    exit 1
fi

pkg="hu.lophas.desktopchromeandroid"
out="desktop-chrome-baseline-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
log_pid=""
finish() {
    status=$?
    trap - EXIT INT TERM
    if [[ -n "$log_pid" ]]; then
        kill "$log_pid" 2>/dev/null || true
        wait "$log_pid" 2>/dev/null || true
    fi
    printf 'Exit status: %s\n' "$status" > "$out/measurement-status.txt"
    if zip -qr "$out.zip" "$out"; then
        echo "Saved: $out.zip"
    else
        echo "ZIP failed; measurement files remain in: $out"
    fi
    if (( status != 0 )); then
        echo "Measurement stopped early (exit $status). Partial data was preserved."
    fi
    exit "$status"
}
trap finish EXIT
trap 'exit 130' INT TERM
adb get-state >/dev/null

adb shell -n settings delete global settings_enable_monitor_phantom_procs
adb shell -n settings get global settings_enable_monitor_phantom_procs | tee "$out/monitor-setting.txt"

app_uid="$(adb shell -n cmd package list packages -U "$pkg" | tr -d '\r' | awk -v p="package:$pkg" '$1==p {sub(/^uid:/,"",$2); print $2}')"
if [[ ! "$app_uid" =~ ^[0-9]+$ ]]; then
    echo "Cannot determine app UID."
    exit 1
fi

adb shell -n dumpsys activity settings > "$out/activity-settings.txt"
adb shell -n dumpsys package "$pkg" | awk '/versionCode=|versionName=/ {print}' > "$out/app-version.txt"
adb logcat -b all -v threadtime -T 1 -s ActivityManager:I DesktopChrome:V lmkd:V am_kill:I am_proc_died:I > "$out/events.txt" 2>&1 </dev/null &
log_pid=$!

adb shell -n am start -n "$pkg/com.termux.x11.MainActivity" >/dev/null
printf 'time,app_processes,native_processes,renderers,extension_renderers,gpu_processes,crashpad_handlers\n' > "$out/counts.csv"
echo "Recording for $duration seconds. Use Chrome on the phone."

sample=0
started=$SECONDS
while (( SECONDS - started < duration )); do
    stamp="$(date +%H:%M:%S)"
    adb shell -n ps -A -o UID,PID,PPID,NAME,ARGS | tr -d '\r' | awk -v uid="$app_uid" 'NR==1 || $1==uid' > "$out/current-processes.txt"
    awk -v t="$stamp" -v pkg="$pkg" '
        NR>1 {
            total++
            if ($4 == "chrome_crashpad_handler") crashpad++
            if (index($5,pkg)!=1) native++
            if ($0 ~ /--type=renderer([[:space:]]|$)/ || $0 ~ /--renderer-client-id=/) {
                renderer++
                if ($0 ~ /--extension-process([[:space:]]|$)/) extension++
            }
            if ($0 ~ /--type=gpu-process([[:space:]]|$)/ || $0 ~ /--gpu-preferences=/) gpu++
        }
        END {printf "%s,%d,%d,%d,%d,%d,%d\n",t,total,native,renderer,extension,gpu,crashpad}
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
                active && /All Zombie App Child Processes:/ {active=0}
                active && /^[[:space:]]*$/ {if (seen) active=0; next}
                active {print; seen=1}
            '
        } >> "$out/phantom-processes.txt"
    fi
    sample=$((sample + 1))
    sleep 2
done

echo "Measurement complete."
