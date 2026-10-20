// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.file.Files;

/** Private FIFO -> Android media output, inside the existing X11 service process. */
final class AndroidAudioOutput implements AutoCloseable {
    private static final String TAG = "DesktopChromeAudio";
    private static final int RATE = 48000;
    private static final long SILENCE_MS = 2000;
    private final File directory;
    private final File fifo;
    private final AudioManager manager;
    private volatile boolean stopping;
    private volatile boolean playbackAllowed;
    private volatile float volume = 1f;
    private volatile AudioTrack track;
    private AudioFocusRequest focus;
    private FileDescriptor descriptor;
    private Thread worker;
    // Accessed only by the PCM worker.
    private volatile boolean focusAttempted;
    private boolean playing;
    private long lastAudible;

    AndroidAudioOutput(Context context) {
        directory = new File(LinuxRuntime.tmp(context), "desktop-chrome-audio");
        fifo = new File(directory, "output.pcm");
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    void start() throws Exception {
        try {
            if (manager == null) throw new IOException("Android audio service is unavailable.");
            if (!directory.isDirectory() && !directory.mkdirs())
                throw new IOException("Cannot create private audio directory.");
            Os.chmod(directory.getAbsolutePath(), 0700);
            // No old session is running in this service. Never reuse a stale
            // socket or a FIFO whose unread bytes belong to an earlier session.
            Files.deleteIfExists(new File(directory, "native").toPath());
            Files.deleteIfExists(fifo.toPath());
            Os.mkfifo(fifo.getAbsolutePath(), 0600);
            descriptor = Os.open(fifo.getAbsolutePath(),
                    OsConstants.O_RDONLY | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0);

            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            int minimum = AudioTrack.getMinBufferSize(RATE,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("Unsupported Android PCM output: " + minimum);
            track = new AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(minimum, RATE * PcmFrames.FRAME_BYTES / 10))
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED)
                throw new IOException("Android AudioTrack initialization failed.");
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(change -> {
                        if (stopping || !focusAttempted) return;
                        playbackAllowed = change == AudioManager.AUDIOFOCUS_GAIN
                                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK;
                        volume = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ? 0.2f : 1f;
                    }, new Handler(Looper.getMainLooper())).build();
            worker = new Thread(this::pump, "desktop-chrome-audio");
            worker.start();
            Log.i(TAG, "Android PCM receiver ready: 48000 Hz, stereo, PCM16.");
        } catch (Exception error) {
            release();
            throw error;
        }
    }

    private void pump() {
        byte[] input = new byte[8192];
        byte[] output = new byte[input.length + PcmFrames.FRAME_BYTES];
        PcmFrames frames = new PcmFrames();
        StructPollfd poll = new StructPollfd();
        poll.fd = descriptor;
        poll.events = (short) OsConstants.POLLIN;
        StructPollfd[] polls = { poll };
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            while (!stopping) {
                expireSilence();
                Os.poll(polls, 250);
                if (stopping) break;
                if ((poll.revents & OsConstants.POLLIN) == 0) {
                    if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLNVAL)) != 0)
                        throw new IOException("PCM pipe poll failed: " + poll.revents);
                    if ((poll.revents & OsConstants.POLLHUP) != 0) {
                        frames.reset();
                        pause();
                        // With no writer, poll can return HUP immediately.
                        Thread.sleep(100);
                    }
                    continue;
                }
                int count;
                try { count = Os.read(descriptor, input, 0, input.length); }
                catch (ErrnoException error) {
                    if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue;
                    throw error;
                }
                if (count <= 0) { frames.reset(); continue; }
                int aligned = frames.append(input, count, output);
                if (aligned == 0) continue;
                if (PcmFrames.audible(output, aligned)) {
                    lastAudible = SystemClock.elapsedRealtime();
                    if (!focusAttempted) {
                        focusAttempted = true;
                        playbackAllowed = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                    }
                }
                if (!playbackAllowed) {
                    pause();
                    continue; // Always drain the FIFO, including during calls / focus loss.
                }
                if (!playing) { track.play(); playing = true; }
                track.setVolume(volume);
                int offset = 0;
                while (!stopping && playbackAllowed && offset < aligned) {
                    int written = track.write(output, offset, aligned - offset, AudioTrack.WRITE_BLOCKING);
                    if (written < 0) throw new IOException("AudioTrack write failed: " + written);
                    if (written == 0) { Thread.sleep(5); continue; }
                    offset += written;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (!stopping) Log.e(TAG, "Audio output stopped; browser session remains available.", error);
        } finally {
            release();
        }
    }

    private void expireSilence() {
        if (focusAttempted && SystemClock.elapsedRealtime() - lastAudible > SILENCE_MS) {
            playbackAllowed = false;
            focusAttempted = false;
            pause();
            manager.abandonAudioFocusRequest(focus);
        }
    }

    private void pause() {
        if (!playing) return;
        track.pause();
        track.flush();
        playing = false;
    }

    private void release() {
        stopping = true;
        if (focus != null && manager != null) manager.abandonAudioFocusRequest(focus);
        AudioTrack current = track;
        track = null;
        if (current != null) {
            try { current.pause(); } catch (IllegalStateException ignored) {}
            current.release();
        }
        if (descriptor != null) {
            try { Os.close(descriptor); } catch (ErrnoException ignored) {}
            descriptor = null;
        }
        try { Files.deleteIfExists(fifo.toPath()); } catch (IOException ignored) {}
    }

    @Override public void close() {
        stopping = true;
        if (worker == null) { release(); return; }
        worker.interrupt();
        // Unblock a pending blocking write; only the worker releases the track.
        AudioTrack current = track;
        if (current != null) {
            try { current.pause(); } catch (IllegalStateException ignored) {}
        }
        if (Thread.currentThread() != worker) {
            try { worker.join(1000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
