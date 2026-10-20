#!/usr/bin/env python3
"""Exercise the real PulseAudio -> PCM FIFO transport on a Linux test host."""
from pathlib import Path
import math
import os
import select
import shutil
import struct
import subprocess
import tempfile
import threading
import time

ASSETS = Path(__file__).resolve().parents[1] / 'lorie-app/src/main/assets/audio'


def main():
    for command in ('pulseaudio', 'pactl', 'parec', 'pacat'):
        if not shutil.which(command):
            raise SystemExit('Install pulseaudio and pulseaudio-utils to run this test.')
    with tempfile.TemporaryDirectory(prefix='dc-audio-') as temporary:
        base = Path(temporary)
        runtime, config = base / 'run', base / 'config'
        runtime.mkdir(mode=0o700)
        config.mkdir()
        for asset in ASSETS.iterdir():
            text = asset.read_text().replace('/tmp/desktop-chrome-audio', str(runtime))
            text = text.replace('/usr/local/lib/desktop-chrome-audio', str(config))
            (config / asset.name).write_text(text)
        if os.environ.get('PULSE_TEST_MODULE_DIR'):
            script = config / 'session.sh'
            script.write_text(script.read_text().replace('pulseaudio --daemonize',
                'pulseaudio --dl-search-path=' + os.environ['PULSE_TEST_MODULE_DIR'] + ' --daemonize'))

        env = dict(os.environ, DESKTOP_CHROME_AUDIO='1')
        # Missing receiver must degrade to a silent browser, without children.
        fallback = subprocess.run(['sh', '-c', '. "$1"; test -z "$audio_pid"; test -z "${PULSE_SERVER:-}"',
                                   'sh', str(config / 'session.sh')], env=env,
                                  capture_output=True, text=True, timeout=5)
        assert fallback.returncode == 0, fallback.stderr
        assert 'continuing without audio' in fallback.stderr

        fifo = runtime / 'output.pcm'
        os.mkfifo(fifo, 0o600)
        reader = os.open(fifo, os.O_RDONLY | os.O_NONBLOCK)
        stop = threading.Event()
        received = bytearray()

        def drain():
            while not stop.is_set():
                try:
                    data = os.read(reader, 8192)
                    if data:
                        received.extend(data)
                        continue
                except BlockingIOError:
                    pass
                time.sleep(0.005)

        thread = threading.Thread(target=drain)
        thread.start()
        with (base / 'session.log').open('w+') as log:
            process = subprocess.Popen(['sh', '-c',
                '. "$1"; printf "%s %s\\n" "$audio_pid" "$capture_pid"; '
                'trap "audio_stop; exit" TERM INT; trap audio_stop EXIT; '
                'while :; do sleep 0.2; done', 'sh', str(config / 'session.sh')],
                env=env, stdout=subprocess.PIPE, stderr=log, text=True)
            children = []
            try:
                assert select.select([process.stdout], [], [], 15)[0], 'Audio startup timed out.'
                line = process.stdout.readline().strip()
                assert len(line.split()) == 2, 'Audio server/capture failed to start.'
                children = [int(pid) for pid in line.split()]
                pulse_env = dict(env, PULSE_SERVER='unix:' + str(runtime / 'native'),
                                 PULSE_CLIENTCONFIG=str(config / 'client.conf'))
                tone = b''.join(struct.pack('<hh', int(10000 * math.sin(2 * math.pi * 440 * i / 48000)),
                                           int(10000 * math.sin(2 * math.pi * 440 * i / 48000)))
                                for i in range(48000))
                subprocess.run(['pacat', '--playback', '--raw', '--format=s16le',
                                '--rate=48000', '--channels=2', '--latency-msec=40'],
                               input=tone, env=pulse_env, timeout=10, check=True)
                time.sleep(0.2)
                assert len(received) >= len(tone) // 2, 'Too few PCM bytes reached the receiver.'
                samples = struct.unpack('<' + 'h' * (len(received) // 2), received[:len(received) // 2 * 2])
                assert max(samples) > 5000 and min(samples) < -5000, 'Tone did not reach the monitor.'
            except Exception:
                log.flush()
                log.seek(0)
                print(log.read())
                raise
            finally:
                process.terminate()
                try:
                    process.communicate(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.communicate()
                stop.set()
                thread.join()
                os.close(reader)
                for pid in children:
                    try:
                        os.kill(pid, 0)
                    except ProcessLookupError:
                        continue
                    # A short-lived zombie is already dead; fail only on live children.
                    state = Path(f'/proc/{pid}/stat')
                    assert not state.exists() or state.read_text().split()[2] == 'Z', 'Audio child survived cleanup.'
        print('Real PulseAudio tone, missing-receiver fallback and child cleanup passed.')


if __name__ == '__main__':
    main()
