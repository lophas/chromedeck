// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

/** Joins arbitrary pipe reads into complete stereo PCM16 frames. */
final class PcmFrames {
    static final int FRAME_BYTES = 4;
    private final byte[] tail = new byte[FRAME_BYTES];
    private int pending;

    int append(byte[] input, int count, byte[] output) {
        int total = pending + count;
        int aligned = total - total % FRAME_BYTES;
        System.arraycopy(tail, 0, output, 0, pending);
        System.arraycopy(input, 0, output, pending, count);
        pending = total - aligned;
        System.arraycopy(output, aligned, tail, 0, pending);
        return aligned;
    }

    void reset() { pending = 0; }

    static boolean audible(byte[] data, int count) {
        for (int i = 0; i + 1 < count; i += 2) {
            int sample = (data[i] & 255) | (data[i + 1] << 8);
            if (sample > 8 || sample < -8) return true;
        }
        return false;
    }
}
