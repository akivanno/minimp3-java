/*
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.radiomod.mp3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

/** The semantics of the public API that the other tests do not cover directly. */
class ApiTest {
    @Test
    void toPcm16RoundsAndClipsLikeTheOriginal() {
        float k = 1f / 32768f;
        float[] in = {0f, -0f, 1f, -1f, 2f, -2f, 0.4f * k, 0.5f * k, 0.6f * k, 1.5f * k, -0.4f * k, -0.5f * k,
            -0.7f * k, -1.2f * k, -1.5f * k, -1.6f * k, -2.5f * k, 32766.4f * k, 32766.5f * k, -32767.4f * k,
            -32767.5f * k, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1e30f, -1e30f};
        short[] expected = {0, 0, 32767, -32768, 32767, -32768, 0, 1, 1, 2, 0, 0,
            // mp3dec_f32_to_s16 truncates sample + 0.5 and then subtracts 1 from negative results, so
            // samples in (-1.5, 0.5) become 0 and -1 is never produced (see NOTES.md)
            0, 0, -2, -2, -3, 32766, 32767, -32767, -32768, 0, 32767, -32768, 32767, -32768};
        short[] out = new short[in.length];
        Mp3Decoder.toPcm16(in, out, in.length);
        assertArrayEquals(expected, out);
        // only the first samples are converted
        short[] partial = {7, 7, 7};
        Mp3Decoder.toPcm16(new float[] {1f, 1f, 1f}, partial, 2);
        assertArrayEquals(new short[] {32767, 32767, 7}, partial);
    }

    @Test
    void frameInfoKeepsOldValuesWhenNoFrameIsFound() {
        Mp3FrameInfo info = new Mp3FrameInfo();
        info.frameOffset = 11;
        info.channels = 22;
        info.hz = 33;
        info.layer = 44;
        info.bitrateKbps = 55;
        new Mp3Decoder().decodeFrame(new byte[100], 0, 100, new float[Mp3Decoder.MAX_SAMPLES_PER_FRAME], info);
        assertEquals(100, info.frameBytes); // nothing found: skip everything
        assertEquals(11, info.frameOffset);
        assertEquals(22, info.channels);
        assertEquals(33, info.hz);
        assertEquals(44, info.layer);
        assertEquals(55, info.bitrateKbps);
    }

    @Test
    void resetRestartsTheSearch() {
        byte[] mp3 = TestData.entry("44100_joint_cbr128").bytes();
        List<Calls.Call> fresh = Calls.decode(mp3, true);
        // decode something else first, then reset: the result must be the same as with a new decoder
        Mp3Decoder dec = new Mp3Decoder();
        Mp3FrameInfo info = new Mp3FrameInfo();
        float[] pcm = new float[Mp3Decoder.MAX_SAMPLES_PER_FRAME];
        byte[] other = TestData.entry("22050_stereo_vbr0").bytes();
        for (int pos = 0, n = 0; n < 20; n++) {
            dec.decodeFrame(other, pos, other.length - pos, pcm, info);
            pos += info.frameBytes;
        }
        dec.reset();
        int pos = 0;
        for (Calls.Call c : fresh) {
            int samples = dec.decodeFrame(mp3, pos, mp3.length - pos, pcm, info);
            assertEquals(c.samples(), samples);
            assertEquals(c.frameBytes(), info.frameBytes);
            for (int i = 0; c.pcm() != null && i < c.pcm().length; i++) {
                assertEquals(Float.floatToRawIntBits(c.pcm()[i]), Float.floatToRawIntBits(pcm[i]));
            }
            pos += info.frameBytes;
        }
    }

    @Test
    void decodersOnDifferentThreadsAreIndependent() throws Exception {
        String[] names = {"44100_joint_cbr320", "16000_mono_vbr0", "8000_stereo_cbr8", "48000_stereo_vbr9",
            "tag_id3v2big_44100_joint_cbr128", "24000_joint_cbr160"};
        List<List<Calls.Call>> expected = new ArrayList<>();
        List<byte[]> files = new ArrayList<>();
        for (String n : names) {
            byte[] mp3 = TestData.entry(n).bytes();
            files.add(mp3);
            expected.add(Calls.decode(mp3, true));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < 48; t++) {
                int i = t % names.length;
                results.add(pool.submit(() -> same(expected.get(i), Calls.decode(files.get(i), true))));
            }
            for (Future<Boolean> f : results) {
                assertTrue(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean same(List<Calls.Call> a, List<Calls.Call> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).sameInfo(b.get(i))) {
                return false;
            }
            float[] x = a.get(i).pcm();
            float[] y = b.get(i).pcm();
            if (x != null) {
                for (int k = 0; k < x.length; k++) {
                    if (Float.floatToRawIntBits(x[k]) != Float.floatToRawIntBits(y[k])) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Test
    void samplesAreNotClipped() {
        // full-scale white noise: the decoded peaks go past 1.0 and must be returned as they are
        byte[] mp3 = TestData.entry("special_44100_stereo_cbr320_noise").bytes();
        float peak = 0;
        for (Calls.Call c : Calls.decode(mp3, true)) {
            for (int i = 0; c.pcm() != null && i < c.pcm().length; i++) {
                peak = Math.max(peak, Math.abs(c.pcm()[i]));
            }
        }
        assertTrue(peak > 1f, "peak " + peak);
    }
}
