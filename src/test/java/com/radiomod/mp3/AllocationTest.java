/*
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.radiomod.mp3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Section 7.6: {@code decodeFrame} allocates nothing. The bytes allocated by the test thread are measured
 * with {@code com.sun.management.ThreadMXBean} while whole files are decoded, after a warm-up that lets
 * the JIT compile the decoder. The test runs alone, so that other tests do not slow the JIT down.
 */
@Isolated
class AllocationTest {
    private static long decodeFile(Mp3Decoder dec, byte[] mp3, float[] pcm, Mp3FrameInfo info) {
        long samples = 0;
        int pos = 0;
        dec.reset();
        do {
            samples += dec.decodeFrame(mp3, pos, mp3.length - pos, pcm, info);
            pos += info.frameBytes;
        } while (info.frameBytes != 0);
        return samples;
    }

    @Test
    void decodeFrameDoesNotAllocate() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean, "allocation counters not available");
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(mx.isThreadAllocatedMemorySupported(), "allocation counters not supported");
        mx.setThreadAllocatedMemoryEnabled(true);

        String[] names = {"44100_joint_cbr320", "48000_mono_vbr0", "22050_stereo_cbr8", "8000_joint_vbr9",
            "tag_id3v2big_id3v1_44100_joint_cbr128", "32000_stereo_cbr128"};
        byte[][] files = new byte[names.length][];
        for (int i = 0; i < names.length; i++) {
            files[i] = TestData.entry(names[i]).bytes();
        }
        Mp3Decoder dec = new Mp3Decoder();
        Mp3FrameInfo info = new Mp3FrameInfo();
        float[] pcm = new float[Mp3Decoder.MAX_SAMPLES_PER_FRAME];
        long thread = Thread.currentThread().threadId();

        for (int warm = 0; warm < 60; warm++) {
            for (byte[] f : files) {
                decodeFile(dec, f, pcm, info);
                decodeFile(dec, f, null, info);
            }
            mx.getThreadAllocatedBytes(thread);
        }

        long before = mx.getThreadAllocatedBytes(thread);
        long samples = 0;
        for (int round = 0; round < 10; round++) {
            for (byte[] f : files) {
                samples += decodeFile(dec, f, pcm, info);
                samples += decodeFile(dec, f, null, info);
            }
        }
        long allocated = mx.getThreadAllocatedBytes(thread) - before;
        TestData.report("allocation", "# Allocation (section 7.6)\n\nBytes allocated by the decoding thread while "
                + "decoding " + samples + " samples (" + files.length + " files, 10 times, with and without pcm): "
                + allocated + "\n");
        assertEquals(0, allocated, "bytes allocated by decodeFrame");
    }
}
