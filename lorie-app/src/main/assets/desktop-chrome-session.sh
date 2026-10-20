#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
export HOME=/root
export XDG_CONFIG_HOME=/root/.config
mkdir -p "$XDG_CONFIG_HOME" /root/Downloads
openbox --sm-disable &
wm_pid=$!
trap 'kill "$wm_pid" 2>/dev/null || true' EXIT HUP INT TERM
/usr/bin/google-chrome-stable \
    --no-sandbox \
    --disable-dev-shm-usage \
    --disable-gpu \
    --disable-background-mode \
    --ozone-platform=x11 \
    --password-store=basic \
    --user-data-dir=/root/.config/desktop-chrome-android \
    --no-first-run \
    --no-default-browser-check \
    --start-maximized \
    "$@"
