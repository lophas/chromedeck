// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

/** Run without Android: java com.sun.tools.javac.Main -d ... PcmFrames.java PcmFramesTest.java. */
public class PcmFramesTest {
    public static void main(String[] args) {
        byte[] original = new byte[48000 * 4];
        new Random(42).nextBytes(original);
        PcmFrames frames = new PcmFrames();
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        byte[] output = new byte[8196];
        Random fragments = new Random(7);
        int offset = 0;
        while (offset < original.length) {
            int count = Math.min(1 + fragments.nextInt(8192), original.length - offset);
            byte[] piece = Arrays.copyOfRange(original, offset, offset + count);
            int aligned = frames.append(piece, count, output);
            check(aligned % 4 == 0, "A partial stereo frame escaped.");
            joined.write(output, 0, aligned);
            offset += count;
        }
        check(Arrays.equals(original, joined.toByteArray()), "Fragmented reads changed PCM bytes.");
        frames.append(new byte[] { 1, 2, 3 }, 3, output);
        frames.reset();
        check(frames.append(new byte[] { 4, 5, 6, 7 }, 4, output) == 4, "Reconnect retained stale bytes.");
        check(Arrays.equals(Arrays.copyOf(output, 4), new byte[] { 4, 5, 6, 7 }), "Reconnect data corrupted.");
        check(!PcmFrames.audible(new byte[] { 0, 0, 0, 0 }, 4), "Silence requested audio focus.");
        check(!PcmFrames.audible(new byte[] { 8, 0, -8, -1 }, 4), "Noise floor requested audio focus.");
        check(PcmFrames.audible(new byte[] { 9, 0, 0, 0 }, 4), "Positive PCM16 sample missed.");
        check(PcmFrames.audible(new byte[] { -9, -1, 0, 0 }, 4), "Negative PCM16 sample missed.");
        System.out.println("PCM fragmentation, reconnect and silence tests passed.");
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
