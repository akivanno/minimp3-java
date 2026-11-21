/*
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.radiomod.mp3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Section 7.4: whatever the input, {@code decodeFrame} must not throw, must not read outside the range it
 * is given, must not write outside {@code pcm} and must terminate.
 *
 * <p>Every input is decoded by {@link #check}: once at the very end of an array of exactly its size, so
 * that reading past the range would throw {@link ArrayIndexOutOfBoundsException}, and once in the middle
 * of a larger array surrounded by other data, which must give the same output; both with and without
 * {@code pcm}. The loop is the one of the mod: advance by {@code frameBytes} until it is 0.
 */
@Execution(ExecutionMode.CONCURRENT)
class RobustnessTest {
    private static final long SEED = 0x6D696E696D7033L; // "minimp3"

    /** Decodes [off, off + len) to the end and returns a fingerprint of every call and every sample. */
    static long decodeAll(byte[] buf, int off, int len, boolean withPcm) {
        Mp3Decoder dec = new Mp3Decoder();
        Mp3FrameInfo info = new Mp3FrameInfo();
        // pcm is longer than needed: the decoder must not write past MAX_SAMPLES_PER_FRAME
        float[] pcm = withPcm ? new float[Mp3Decoder.MAX_SAMPLES_PER_FRAME + 64] : null;
        if (pcm != null) {
            Arrays.fill(pcm, Float.NaN);
        }
        long h = 0xcbf29ce484222325L;
        int pos = 0;
        long calls = 0;
        for (;;) {
            int samples = dec.decodeFrame(buf, off + pos, len - pos, pcm, info);
            calls++;
            h = mix(h, samples, info.frameBytes, info.frameOffset, info.channels, info.hz, info.layer, info.bitrateKbps);
            if (withPcm && samples > 0) {
                assertTrue(samples * info.channels <= Mp3Decoder.MAX_SAMPLES_PER_FRAME);
                for (int k = 0; k < samples * info.channels; k++) {
                    h = mix(h, Float.floatToRawIntBits(pcm[k]));
                }
            }
            if (pcm != null) {
                for (int k = Mp3Decoder.MAX_SAMPLES_PER_FRAME; k < pcm.length; k++) {
                    assertTrue(Float.isNaN(pcm[k]), "write past MAX_SAMPLES_PER_FRAME");
                }
            }
            if (info.frameBytes == 0) {
                return h;
            }
            assertTrue(info.frameBytes > 0 && info.frameBytes <= len - pos,
                    "frameBytes " + info.frameBytes + " with " + (len - pos) + " bytes left");
            pos += info.frameBytes;
            assertTrue(calls <= (long) len + 1, "no progress");
        }
    }

    private static long mix(long h, int... values) {
        for (int v : values) {
            h = (h ^ v) * 0x100000001b3L;
        }
        return h;
    }

    /** Decodes data alone and embedded in other data; the results must agree. */
    static void check(byte[] data, String what) {
        try {
            long alone = decodeAll(data, 0, data.length, true);
            long aloneInfo = decodeAll(data, 0, data.length, false);
            for (int variant = 0; variant < 2; variant++) {
                int pre = 37 + variant * 1000;
                int post = 4099;
                byte[] big = new byte[pre + data.length + post];
                fillSurroundings(big, variant);
                System.arraycopy(data, 0, big, pre, data.length);
                assertEquals(alone, decodeAll(big, pre, data.length, true), what + ": output depends on bytes outside the range");
                assertEquals(aloneInfo, decodeAll(big, pre, data.length, false), what + ": output depends on bytes outside the range");
            }
        } catch (Throwable t) {
            saveFailure(data, what);
            throw new AssertionError(what + ": " + t, t);
        }
    }

    /** The cheaper version of check: only the array of exact size. */
    static void checkQuick(byte[] data, String what) {
        try {
            decodeAll(data, 0, data.length, true);
            decodeAll(data, 0, data.length, false);
        } catch (Throwable t) {
            saveFailure(data, what);
            throw new AssertionError(what + ": " + t, t);
        }
    }

    private static void fillSurroundings(byte[] big, int variant) {
        if (variant == 0) {
            new SplittableRandom(SEED).nextBytes(big);
        } else {
            // valid-looking MPEG-1 Layer III headers everywhere, to attract any read outside the range
            byte[] hdr = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, (byte) 0x64};
            for (int i = 0; i < big.length; i++) {
                big[i] = hdr[i & 3];
            }
        }
    }

    private static void saveFailure(byte[] data, String what) {
        try {
            Path dir = Path.of("build", "robustness-failures");
            Files.createDirectories(dir);
            Files.write(dir.resolve(what.replaceAll("[^A-Za-z0-9_.-]", "_") + ".bin"), data);
        } catch (IOException ignored) {
            // the assertion that follows reports the failure anyway
        }
    }

    /** The frame headers found by a full decode: their offsets in the file. */
    static List<Integer> frameStarts(byte[] mp3) {
        Mp3Decoder dec = new Mp3Decoder();
        Mp3FrameInfo info = new Mp3FrameInfo();
        List<Integer> starts = new ArrayList<>();
        int pos = 0;
        for (;;) {
            info.frameOffset = -1;
            dec.decodeFrame(mp3, pos, mp3.length - pos, null, info);
            if (info.frameBytes == 0) {
                return starts;
            }
            if (info.frameOffset >= 0) {
                starts.add(pos + info.frameOffset);
            }
            pos += info.frameBytes;
        }
    }

    // --- argument checks ----------------------------------------------------------------------------

    @Test
    void argumentsAreChecked() {
        Mp3Decoder d = new Mp3Decoder();
        Mp3FrameInfo info = new Mp3FrameInfo();
        float[] pcm = new float[Mp3Decoder.MAX_SAMPLES_PER_FRAME];
        byte[] mp3 = new byte[10];
        assertThrows(NullPointerException.class, () -> d.decodeFrame(null, 0, 0, pcm, info));
        assertThrows(NullPointerException.class, () -> d.decodeFrame(mp3, 0, 10, pcm, null));
        assertThrows(IllegalArgumentException.class, () -> d.decodeFrame(mp3, -1, 5, pcm, info));
        assertThrows(IllegalArgumentException.class, () -> d.decodeFrame(mp3, 0, -1, pcm, info));
        assertThrows(IllegalArgumentException.class, () -> d.decodeFrame(mp3, 0, 11, pcm, info));
        assertThrows(IllegalArgumentException.class, () -> d.decodeFrame(mp3, 5, 6, pcm, info));
        assertThrows(IllegalArgumentException.class, () -> d.decodeFrame(mp3, 11, 0, pcm, info));
        assertThrows(IllegalArgumentException.class, () -> d.decodeFrame(mp3, 1, Integer.MAX_VALUE, pcm, info));
        assertThrows(IllegalArgumentException.class, () -> d.decodeFrame(mp3, Integer.MAX_VALUE, 1, pcm, info));
        assertThrows(IllegalArgumentException.class,
                () -> d.decodeFrame(mp3, 0, 10, new float[Mp3Decoder.MAX_SAMPLES_PER_FRAME - 1], info));
        assertDoesNotThrow(() -> d.decodeFrame(mp3, 10, 0, pcm, info));
        assertDoesNotThrow(() -> d.decodeFrame(mp3, 0, 10, null, info));
        assertThrows(NullPointerException.class, () -> Mp3Decoder.toPcm16(null, new short[1], 0));
        assertThrows(NullPointerException.class, () -> Mp3Decoder.toPcm16(new float[1], null, 0));
        assertThrows(IllegalArgumentException.class, () -> Mp3Decoder.toPcm16(new float[1], new short[2], 2));
        assertThrows(IllegalArgumentException.class, () -> Mp3Decoder.toPcm16(new float[2], new short[1], 2));
        assertThrows(IllegalArgumentException.class, () -> Mp3Decoder.toPcm16(new float[1], new short[1], -1));
    }

    // --- tiny and random inputs -----------------------------------------------------------------------

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void emptyAndTinyInputs() {
        check(new byte[0], "empty");
        byte[] values = {0x00, (byte) 0xFF, (byte) 0xFB, (byte) 0xF3, (byte) 0xE2, (byte) 0x90, 0x44, 0x49, 0x44, 0x33};
        for (int len = 1; len <= 4; len++) {
            int combos = (int) Math.pow(values.length, len);
            for (int c = 0; c < combos; c++) {
                byte[] data = new byte[len];
                for (int k = 0, x = c; k < len; k++, x /= values.length) {
                    data[k] = values[x % values.length];
                }
                checkQuick(data, "tiny-" + len + "-" + c);
                // the original skips 1 to 4 bytes at once: frame_bytes is the whole input
                Mp3FrameInfo info = new Mp3FrameInfo();
                new Mp3Decoder().decodeFrame(data, 0, len, new float[Mp3Decoder.MAX_SAMPLES_PER_FRAME], info);
                assertEquals(len, info.frameBytes);
            }
        }
        for (int len = 1; len <= 4; len++) {
            check(new byte[len], "zeros-" + len);
            check(Arrays.copyOf(new byte[] {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64}, len), "header-prefix-" + len);
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void oneMegabyteOfRandomBytes() {
        byte[] data = new byte[1 << 20];
        new SplittableRandom(SEED).nextBytes(data);
        check(data, "random-1MB");
        // the same with many fake frame headers in it, to keep the frame search busy
        SplittableRandom r = new SplittableRandom(SEED + 1);
        byte[][] headers = {{(byte) 0xFF, (byte) 0xFB}, {(byte) 0xFF, (byte) 0xF3}, {(byte) 0xFF, (byte) 0xE3},
            {(byte) 0xFF, (byte) 0xFA}, {(byte) 0xFF, (byte) 0xE2}};
        for (int i = 0; i < data.length - 4; i += 1 + r.nextInt(64)) {
            byte[] h = headers[r.nextInt(headers.length)];
            data[i] = h[0];
            data[i + 1] = h[1];
        }
        check(data, "random-1MB-with-syncs");
    }

    // --- truncated files ------------------------------------------------------------------------------

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void truncatedFiles() {
        for (String name : new String[] {"tag_id3v2big_id3v1_44100_joint_cbr128", "8000_joint_vbr0"}) {
            byte[] mp3 = TestData.entry(name).bytes();
            SplittableRandom r = new SplittableRandom(SEED ^ name.hashCode());
            TreeSet<Integer> cuts = new TreeSet<>();
            while (cuts.size() < 100) {
                cuts.add(r.nextInt(mp3.length + 1));
            }
            // 100 cuts at frame boundaries and one byte before or after them
            List<Integer> starts = new ArrayList<>(frameStarts(mp3));
            java.util.Collections.shuffle(starts, new java.util.Random(SEED));
            TreeSet<Integer> boundary = new TreeSet<>();
            for (int k = 0; boundary.size() < 100 && k < starts.size(); k++) {
                for (int d = -1; d <= 1 && boundary.size() < 100; d++) {
                    int c = starts.get(k) + d;
                    if (c >= 0 && c <= mp3.length && !cuts.contains(c)) {
                        boundary.add(c);
                    }
                }
            }
            cuts.addAll(boundary);
            cuts.add(mp3.length - 1);
            cuts.add(mp3.length - 128); // in the ID3v1 tag
            cuts.add(mp3.length - 129);
            assertTrue(cuts.size() >= 200);
            cuts.parallelStream().forEach(cut -> check(Arrays.copyOf(mp3, cut), name + "-cut-" + cut));
        }
    }

    // --- fuzzing --------------------------------------------------------------------------------------

    static final String[] FUZZ_FILES = {
        "44100_joint_cbr128", "22050_stereo_vbr0", "8000_mono_cbr8", "tag_id3v2small_16000_mono_vbr0",
    };
    static final int VARIANTS = 10_000;

    @Test
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    void fuzzing() {
        byte[][] seeds = new byte[FUZZ_FILES.length][];
        for (int i = 0; i < seeds.length; i++) {
            byte[] mp3 = TestData.entry(FUZZ_FILES[i]).bytes();
            seeds[i] = Arrays.copyOf(mp3, Math.min(mp3.length, 12 * 1024)); // about 30 frames
        }
        AtomicLong decoded = new AtomicLong();
        IntStream.range(0, VARIANTS).parallel().forEach(v -> {
            byte[] base = seeds[v % seeds.length];
            byte[] data = mutate(base, new SplittableRandom(SEED + v));
            String what = "fuzz-" + FUZZ_FILES[v % seeds.length] + "-" + v;
            if (v % 10 == 0) {
                check(data, what);
            } else {
                checkQuick(data, what);
            }
            decoded.incrementAndGet();
        });
        assertEquals(VARIANTS, decoded.get());
    }

    /** Bit flips, byte substitutions, insertions and deletions, alone or combined. */
    static byte[] mutate(byte[] base, SplittableRandom r) {
        byte[] d = base.clone();
        int ops = 1 + (r.nextInt(4) == 0 ? r.nextInt(4) : 0);
        for (int op = 0; op < ops; op++) {
            switch (r.nextInt(6)) {
                case 0 -> { // bit flips
                    for (int n = 1 + r.nextInt(16); n > 0; n--) {
                        int p = r.nextInt(d.length * 8);
                        d[p >> 3] ^= (byte) (1 << (p & 7));
                    }
                }
                case 1 -> { // byte substitutions
                    for (int n = 1 + r.nextInt(16); n > 0; n--) {
                        d[r.nextInt(d.length)] = (byte) r.nextInt(256);
                    }
                }
                case 2 -> { // insertion
                    int p = r.nextInt(d.length + 1);
                    byte[] ins = new byte[1 + r.nextInt(r.nextBoolean() ? 4 : 600)];
                    r.nextBytes(ins);
                    byte[] n = new byte[d.length + ins.length];
                    System.arraycopy(d, 0, n, 0, p);
                    System.arraycopy(ins, 0, n, p, ins.length);
                    System.arraycopy(d, p, n, p + ins.length, d.length - p);
                    d = n;
                }
                case 3 -> { // deletion
                    int len = Math.min(d.length - 1, 1 + r.nextInt(r.nextBoolean() ? 4 : 600));
                    int p = r.nextInt(d.length - len + 1);
                    byte[] n = new byte[d.length - len];
                    System.arraycopy(d, 0, n, 0, p);
                    System.arraycopy(d, p + len, n, p, d.length - p - len);
                    d = n;
                }
                case 4 -> { // overwrite a block with random bytes
                    int len = Math.min(d.length, 1 + r.nextInt(64));
                    int p = r.nextInt(d.length - len + 1);
                    for (int k = 0; k < len; k++) {
                        d[p + k] = (byte) r.nextInt(256);
                    }
                }
                default -> { // change fields of frame headers (or of anything that looks like one)
                    for (int i = 0; i + 3 < d.length; i++) {
                        if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xE0) == 0xE0 && r.nextInt(4) == 0) {
                            int b = 1 + r.nextInt(3);
                            d[i + b] ^= (byte) (1 << r.nextInt(8));
                        }
                    }
                }
            }
            if (d.length == 0) {
                d = new byte[] {0};
            }
        }
        return d;
    }

    // --- reserved header values -----------------------------------------------------------------------

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void reservedHeaderFields() {
        byte[] mp3 = TestData.entry("44100_joint_cbr128").bytes();
        List<Integer> starts = frameStarts(mp3);
        // {byte index in the header, mask, value}: bitrate 15, sample rate 3, layer 0, MPEG version "01"
        int[][] fields = {{2, 0xF0, 0xF0}, {2, 0x0C, 0x0C}, {1, 0x06, 0x00}, {1, 0x18, 0x08}};
        String[] names = {"bitrate15", "samplerate3", "layer0", "version01"};
        for (int f = 0; f < fields.length; f++) {
            for (String which : new String[] {"first", "all", "every-other", "random"}) {
                byte[] d = mp3.clone();
                SplittableRandom r = new SplittableRandom(SEED + f);
                for (int k = 0; k < starts.size(); k++) {
                    boolean change = switch (which) {
                        case "first" -> k == 0;
                        case "all" -> true;
                        case "every-other" -> (k & 1) == 0;
                        default -> r.nextInt(5) == 0;
                    };
                    if (change) {
                        int p = starts.get(k) + fields[f][0];
                        d[p] = (byte) ((d[p] & ~fields[f][1]) | fields[f][2]);
                    }
                }
                check(d, names[f] + "-" + which);
                if (which.equals("all")) {
                    assertEquals(0, totalSamples(d), names[f] + " in every header: nothing may be decoded");
                }
            }
        }
    }

    static long totalSamples(byte[] mp3) {
        Mp3Decoder dec = new Mp3Decoder();
        Mp3FrameInfo info = new Mp3FrameInfo();
        float[] pcm = new float[Mp3Decoder.MAX_SAMPLES_PER_FRAME];
        long total = 0;
        int pos = 0;
        do {
            total += dec.decodeFrame(mp3, pos, mp3.length - pos, pcm, info);
            pos += info.frameBytes;
        } while (info.frameBytes != 0);
        return total;
    }

    // --- the Huffman reader past the end of maindata ----------------------------------------------------

    /**
     * A crafted stream on which minimp3 reads past the end of {@code maindata}: free-format MPEG-1 frames of
     * 2303 bytes that use the whole bit reservoir (main_data_begin = 511), whose last granule starts 12359
     * bits into the main data and holds 288 pairs coded as (15, 15) with 13 linbits (table 23), 36 bits per
     * pair. L3_huffman decodes big_values pairs without looking at part2_3_length, so it reads up to bit
     * 22727, past the 2815 bytes of maindata; in C those bytes are the following members of
     * mp3dec_scratch_t. The port reads them as 0 (see NOTES.md) and must not fail.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void huffmanReaderPastTheEndOfMainData() {
        final int frameSize = 2303;
        BitWriter w = new BitWriter(frameSize);
        w.put(0xFFFB0000L, 32); // MPEG-1 Layer III, no CRC, free format, 44.1 kHz, no padding, stereo
        w.put(511, 9); // main_data_begin
        w.put(0, 3); // private bits
        w.put(0, 8); // scfsi
        for (int gr = 0; gr < 2; gr++) {
            for (int ch = 0; ch < 2; ch++) {
                boolean last = gr == 1 && ch == 1;
                w.put(4095, 12); // part2_3_length: 4 * 4095 = 16380 bits fit in the 22224 of main data
                w.put(last ? 288 : 0, 9); // big_values
                w.put(100, 8); // global_gain
                w.put(last ? 15 : 0, 4); // scalefac_compress: 11 * 4 + 10 * 3 = 74 bits of scale factors
                w.put(0, 1); // no window switching
                w.put(23, 5); // table_select: table 16 tree, linbits 13
                w.put(23, 5);
                w.put(23, 5);
                w.put(15, 4); // region0_count
                w.put(7, 3); // region1_count
                w.put(0, 1); // preflag
                w.put(0, 1); // scalefac_scale
                w.put(0, 1); // count1table
            }
        }
        // main data: the last granule starts at bit 3 * 4095 + 74 of maindata, which begins with the 511
        // bytes of the reservoir, so at bit 12359 - 4088 of this frame's main data
        int mainStart = w.bitPos;
        w.bitPos = mainStart + 3 * 4095 + 74 - 511 * 8;
        while (w.bitPos + 36 <= frameSize * 8) {
            w.put(0b00000011, 8); // (15, 15) in the table 16 tree
            w.put(8191, 13); // linbits
            w.put(0, 1); // sign
            w.put(8191, 13);
            w.put(0, 1);
        }
        byte[] frame = w.buf;
        byte[] stream = new byte[frameSize * 8];
        for (int i = 0; i < 8; i++) {
            System.arraycopy(frame, 0, stream, i * frameSize, frameSize);
        }
        check(stream, "huffman-past-maindata");

        Mp3Decoder dec = new Mp3Decoder();
        Mp3FrameInfo info = new Mp3FrameInfo();
        float[] pcm = new float[Mp3Decoder.MAX_SAMPLES_PER_FRAME];
        int decoded = 0;
        for (int pos = 0; pos < stream.length; pos += info.frameBytes) {
            int samples = dec.decodeFrame(stream, pos, stream.length - pos, pcm, info);
            assertEquals(frameSize, info.frameBytes, "free-format frames of " + frameSize + " bytes");
            if (samples > 0) {
                decoded++;
                for (int k = 0; k < samples * info.channels; k++) {
                    assertTrue(Float.isFinite(pcm[k]));
                }
            }
        }
        // the first frame has no reservoir; the following ones are decoded, reading past maindata
        assertEquals(7, decoded);
    }

    /** Writes bits MSB first. */
    static final class BitWriter {
        final byte[] buf;
        int bitPos;

        BitWriter(int size) {
            buf = new byte[size];
        }

        void put(long value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                if (((value >>> i) & 1) != 0) {
                    buf[bitPos >> 3] |= (byte) (0x80 >>> (bitPos & 7));
                }
                bitPos++;
            }
        }
    }

    // --- valid headers with random side information -----------------------------------------------------

    /**
     * Streams of frames with valid headers of every kind (all versions, bit rates, modes, CRC, padding,
     * free format) and side information with random values inside (and at the edges of) the legal ranges,
     * followed by random main data. These reach the deep parts of the decoder that random bytes rarely
     * reach: scale factors, Huffman decoding, intensity stereo, mixed blocks, the bit reservoir.
     */
    @Test
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    void validHeadersWithRandomSideInfo() {
        AtomicLong frames = new AtomicLong();
        IntStream.range(0, 4000).parallel().forEach(s -> {
            SplittableRandom r = new SplittableRandom(SEED * 31 + s);
            byte[] data = StreamGenerator.stream(r, 4 + r.nextInt(28));
            frames.addAndGet(data.length);
            if (s % 10 == 0) {
                check(data, "generated-" + s);
            } else {
                checkQuick(data, "generated-" + s);
            }
        });
        assertTrue(frames.get() > 0);
    }

    /** Writes plausible but random Layer III frames. */
    static final class StreamGenerator {
        private final byte[] buf;
        private int bitPos;

        private StreamGenerator(int size) {
            buf = new byte[size];
        }

        private void put(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                if (((value >>> i) & 1) != 0) {
                    buf[bitPos >> 3] |= (byte) (0x80 >>> (bitPos & 7));
                }
                bitPos++;
            }
        }

        private static int pick(SplittableRandom r, int max) { // 0..max, with the edges often
            return switch (r.nextInt(8)) {
                case 0 -> 0;
                case 1 -> max;
                default -> r.nextInt(max + 1);
            };
        }

        static byte[] stream(SplittableRandom r, int frames) {
            int version = r.nextInt(3); // 0 = MPEG-1, 1 = MPEG-2, 2 = MPEG-2.5
            int srIdx = r.nextInt(3);
            boolean free = r.nextInt(8) == 0;
            int brIdx = free ? 0 : 1 + r.nextInt(14);
            int mode = r.nextInt(4);
            int modeExt = r.nextInt(4);
            boolean crc = r.nextBoolean();
            int[][] kbpsTable = {{0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},
                {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}};
            int[][] hzTable = {{44100, 48000, 32000}, {22050, 24000, 16000}, {11025, 12000, 8000}};
            int hz = hzTable[version][srIdx];
            int freeSize = 48 + r.nextInt(2200);
            List<byte[]> out = new ArrayList<>();
            for (int f = 0; f < frames; f++) {
                boolean padding = r.nextBoolean();
                int size = free ? freeSize + (padding ? 1 : 0)
                        : (version == 0 ? 144000 : 72000) * kbpsTable[version == 0 ? 0 : 1][brIdx] / hz + (padding ? 1 : 0);
                StreamGenerator g = new StreamGenerator(Math.max(size, 40));
                g.put(0xFFE, 11);
                g.put(version == 0 ? 3 : version == 1 ? 2 : 0, 2);
                g.put(1, 2); // Layer III
                g.put(crc ? 0 : 1, 1);
                g.put(brIdx, 4);
                g.put(srIdx, 2);
                g.put(padding ? 1 : 0, 1);
                g.put(r.nextInt(2), 1);
                g.put(r.nextInt(16) == 0 ? r.nextInt(4) : mode, 2); // occasional mono/stereo switches
                g.put(modeExt, 2);
                g.put(r.nextInt(16), 4);
                if (crc) {
                    g.put(r.nextInt(1 << 16), 16);
                }
                boolean mono = mode == 3;
                int nch = mono ? 1 : 2;
                int ngr = version == 0 ? 2 : 1;
                int budget = Math.max(1, (size - 4) * 8 / (ngr * nch));
                if (version == 0) {
                    g.put(pick(r, 511), 9);
                    g.put(r.nextInt(1 << (mono ? 5 : 3)), mono ? 5 : 3);
                    g.put(r.nextInt(1 << (4 * nch)), 4 * nch);
                } else {
                    g.put(pick(r, 255), 8);
                    g.put(r.nextInt(1 << nch), nch);
                }
                for (int gr = 0; gr < ngr; gr++) {
                    for (int ch = 0; ch < nch; ch++) {
                        g.put(r.nextInt(20) == 0 ? r.nextInt(4096) : r.nextInt(Math.min(4096, budget + 64)), 12);
                        g.put(r.nextInt(20) == 0 ? 289 + r.nextInt(223) : pick(r, 288), 9);
                        g.put(pick(r, 255), 8);
                        g.put(version == 0 ? pick(r, 15) : pick(r, 511), version == 0 ? 4 : 9);
                        boolean ws = r.nextBoolean();
                        g.put(ws ? 1 : 0, 1);
                        if (ws) {
                            g.put(r.nextInt(12) == 0 ? 0 : 1 + r.nextInt(3), 2);
                            g.put(r.nextInt(2), 1); // mixed block
                            g.put(r.nextInt(32), 5);
                            g.put(r.nextInt(32), 5);
                            g.put(pick(r, 7), 3);
                            g.put(pick(r, 7), 3);
                            g.put(pick(r, 7), 3);
                        } else {
                            g.put(r.nextInt(32), 5);
                            g.put(r.nextInt(32), 5);
                            g.put(r.nextInt(32), 5);
                            g.put(pick(r, 15), 4);
                            g.put(pick(r, 7), 3);
                        }
                        if (version == 0) {
                            g.put(r.nextInt(2), 1);
                        }
                        g.put(r.nextInt(2), 1);
                        g.put(r.nextInt(2), 1);
                    }
                }
                byte[] frame = g.buf;
                for (int i = (g.bitPos + 7) / 8; i < frame.length; i++) {
                    frame[i] = (byte) r.nextInt(256);
                }
                out.add(Arrays.copyOf(frame, size));
            }
            int total = out.stream().mapToInt(b -> b.length).sum();
            byte[] data = new byte[total];
            int p = 0;
            for (byte[] b : out) {
                System.arraycopy(b, 0, data, p, b.length);
                p += b.length;
            }
            return data;
        }
    }
}
