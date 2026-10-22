#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
export HOME=/root
export XDG_CONFIG_HOME=/root/.config
export CHROME_VERSION_EXTRA=stable
export GNOME_DISABLE_CRASH_DIALOG=SET_BY_GOOGLE_CHROME
umask 077
xdg_app_dir="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$xdg_app_dir"
[ -f "$xdg_app_dir/mimeapps.list" ] || touch "$xdg_app_dir/mimeapps.list"
mkdir -p "$XDG_CONFIG_HOME" /root/Downloads
# On an external display Android's desktop caption bar covers the top of the screen;
# an Openbox margin keeps maximized Chrome windows below it.
openbox_config=/etc/xdg/openbox/rc.xml
if [ "${DESKTOP_CHROME_TOP_MARGIN:-0}" -gt 0 ] 2>/dev/null; then
    sed "/<margins>/,/<\/margins>/ s#<top>[0-9]*</top>#<top>$DESKTOP_CHROME_TOP_MARGIN</top>#" \
        /etc/xdg/openbox/rc.xml > /tmp/desktop-chrome-openbox.xml && openbox_config=/tmp/desktop-chrome-openbox.xml
fi
openbox --sm-disable --config-file "$openbox_config" &
wm_pid=$!
# ChromeDeck writes "hidden" or "visible" to this pipe when it leaves or returns to the
# screen. Minimized Chrome stops drawing pages and slows down timers, as Chrome on a
# phone does in the background; audio keeps playing.
visibility_pipe=/tmp/desktop-chrome-visibility
rm -f "$visibility_pipe"
mkfifo -m 600 "$visibility_pipe"
while :; do
    while read -r state; do
        python3 /usr/local/lib/desktop-chrome/window-visibility.py "$state" \
            || echo "Cannot change Chrome's window visibility" >&2
    done < "$visibility_pipe"
done &
visibility_pid=$!
chrome_pid=
# The Android service reads this PID to stop Chrome gracefully.
pid_file=/tmp/desktop-chrome-browser.pid
trap 'audio_stop; kill "$wm_pid" "$visibility_pid" "${scale_pid:-}" 2>/dev/null || true; rm -f "$pid_file" "$visibility_pipe" "${scale_pipe:-}"' EXIT
# Let Chrome save its session before the session ends.
trap 'if [ -n "$chrome_pid" ]; then kill -TERM "$chrome_pid" 2>/dev/null; wait "$chrome_pid"; fi; exit 143' HUP INT TERM
. /usr/local/lib/desktop-chrome-audio/session.sh
chrome_profile=/root/.config/desktop-chrome-android
# Android may stop the app without warning. Mark every profile as cleanly
# closed so Chrome reopens the last session instead of a crash prompt, and
# default each profile once to "Continue where you left off".
python3 - "$chrome_profile" <<'PY' || echo "Profile startup preparation failed" >&2
import json, os, sys
root = sys.argv[1]
for name in sorted(os.listdir(root)) if os.path.isdir(root) else []:
    path = os.path.join(root, name, "Preferences")
    if not os.path.isfile(path):
        continue
    with open(path, encoding="utf-8") as f:
        prefs = json.load(f)
    profile = prefs.setdefault("profile", {})
    profile["exit_type"] = "Normal"
    profile["exited_cleanly"] = True
    marker = os.path.join(root, name, ".desktop-chrome-restore-default")
    if not os.path.exists(marker):
        prefs.setdefault("session", {}).setdefault("restore_on_startup", 1)
        open(marker, "w").close()
    tmp = path + ".desktop-chrome-tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(prefs, f, separators=(",", ":"))
    os.replace(tmp, path)
PY
# Skip the official wrapper's two persistent cat processes.
# Keep the launch options from the last successful device baseline.
# The renderer limit is soft: site isolation can require additional processes.
# PRoot provides no Linux browser sandbox, so --no-sandbox is required; Android's
# app sandbox still applies. --test-type hides the resulting warning bar.
# Preferences → Browser → Display scale renders Chrome's interface and pages larger
# at full resolution, like a HiDPI display. Chrome takes it from the Xft/DPI desktop
# setting, which follows when ChromeDeck moves between the phone and a monitor.
scale_pipe=/tmp/desktop-chrome-scale
rm -f "$scale_pipe"
mkfifo -m 600 "$scale_pipe"
case "${DESKTOP_CHROME_SCALE:-auto}" in
    auto) initial_scale=1 ;;
    *) initial_scale=$DESKTOP_CHROME_SCALE ;;
esac
python3 /usr/local/lib/desktop-chrome/display-scale.py "$scale_pipe" "$initial_scale" &
scale_pid=$!
/opt/google/chrome/chrome \
    --no-sandbox \
    --test-type \
    --no-zygote \
    --renderer-process-limit=4 \
    --disable-dev-shm-usage \
    --disable-gpu \
    --disable-background-mode \
    --ozone-platform=x11 \
    --password-store=basic \
    --user-data-dir="$chrome_profile" \
    --no-first-run \
    --no-default-browser-check \
    --start-maximized \
    "$@" < /dev/null &
chrome_pid=$!
echo "$chrome_pid" > "$pid_file"
status=0
wait "$chrome_pid" || status=$?
exit "$status"
