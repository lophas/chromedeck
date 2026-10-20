#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
export HOME=/root
export XDG_CONFIG_HOME=/root/.config
export CHROME_VERSION_EXTRA=stable
export GNOME_DISABLE_CRASH_DIALOG=SET_BY_GOOGLE_CHROME
xdg_app_dir="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$xdg_app_dir"
[ -f "$xdg_app_dir/mimeapps.list" ] || touch "$xdg_app_dir/mimeapps.list"
mkdir -p "$XDG_CONFIG_HOME" /root/Downloads
openbox --sm-disable &
wm_pid=$!
trap 'kill "$wm_pid" 2>/dev/null || true' EXIT HUP INT TERM
# Skip the official wrapper's two persistent cat processes.
# Keep the launch options from the last successful device baseline.
# The renderer limit is soft: site isolation can require additional processes.
/opt/google/chrome/chrome \
    --no-sandbox \
    --no-zygote \
    --renderer-process-limit=4 \
    --disable-dev-shm-usage \
    --disable-gpu \
    --disable-background-mode \
    --ozone-platform=x11 \
    --password-store=basic \
    --user-data-dir=/root/.config/desktop-chrome-android \
    --no-first-run \
    --no-default-browser-check \
    --start-maximized \
    "$@" < /dev/null
