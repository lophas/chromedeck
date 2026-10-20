#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Sourced by desktop-chrome-session; both children belong to its PRoot tree.
audio_pid=
capture_pid=

audio_stop() {
    if [ -n "$capture_pid" ]; then kill "$capture_pid" 2>/dev/null || true; fi
    if [ -n "$audio_pid" ]; then kill "$audio_pid" 2>/dev/null || true; fi
    capture_pid=
    audio_pid=
}

audio_start() {
    [ "${DESKTOP_CHROME_AUDIO:-0}" = 1 ] || return 0
    if [ ! -p /tmp/desktop-chrome-audio/output.pcm ]; then
        echo 'DesktopChrome audio: Android PCM receiver is unavailable.' >&2
        return 1
    fi

    export PULSE_SERVER=unix:/tmp/desktop-chrome-audio/native
    export PULSE_CLIENTCONFIG=/usr/local/lib/desktop-chrome-audio/client.conf
    pulseaudio --daemonize=no --use-pid-file=no --exit-idle-time=-1 \
        --disable-shm=yes --realtime=no --high-priority=no --log-target=stderr \
        -n --file=/usr/local/lib/desktop-chrome-audio/default.pa < /dev/null &
    audio_pid=$!

    audio_attempt=0
    while ! pactl info > /dev/null 2>&1; do
        if ! kill -0 "$audio_pid" 2>/dev/null || [ "$audio_attempt" -ge 100 ]; then
            echo 'DesktopChrome audio: PulseAudio did not become ready.' >&2
            return 1
        fi
        audio_attempt=$((audio_attempt + 1))
        sleep 0.1
    done

    # One monitor stream mixes all browser outputs. Fixed PCM format matches
    # AndroidAudioOutput: signed 16-bit little-endian, stereo, 48 kHz.
    parec --raw --device=android.monitor --format=s16le --rate=48000 \
        --channels=2 --latency-msec=120 --process-time-msec=40 \
        > /tmp/desktop-chrome-audio/output.pcm &
    capture_pid=$!
    export PULSE_SINK=android
    export ALSA_CONFIG_PATH=/usr/local/lib/desktop-chrome-audio/asound.conf
    echo 'DesktopChrome audio: PCM output bridge started.' >&2
}

if ! audio_start; then
    audio_stop
    unset PULSE_SERVER PULSE_CLIENTCONFIG PULSE_SINK ALSA_CONFIG_PATH
    echo 'DesktopChrome audio: continuing without audio.' >&2
fi
