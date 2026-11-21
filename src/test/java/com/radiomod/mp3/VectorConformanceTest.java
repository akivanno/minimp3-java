/*
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.radiomod.mp3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Section 7.2: the ISO conformance vectors of the minimp3 repository, scored as minimp3_test.c does
 * (mp3dec_load_buf, conversion to 16 bits, PSNR over all samples).
 *
 * <p>Every Layer III vector must reach more than 96 dB and exactly the PSNR that minimp3_test.c, built with
 * the options of the port, prints for it. The PSNR table of the minimp3 README is reported for comparison
 * but is not a pass criterion: it was measured with the SIMD code, which rounds differently from the scalar
 * code the port translates (see NOTES.md).
 */
@Execution(ExecutionMode.CONCURRENT)
class VectorConformanceTest {
    /** The PSNR table of README.md of minimp3 at the pinned commit. */
    static final Map<String, Double> README = Map.ofEntries(
            Map.entry("l3-compl", 124.22), Map.entry("l3-he_32khz", 139.67), Map.entry("l3-he_44khz", 144.04),
            Map.entry("l3-he_48khz", 139.67), Map.entry("l3-hecommon", 133.93), Map.entry("l3-he_free", 137.48),
            Map.entry("l3-he_mode", 118.00), Map.entry("l3-si", 120.30), Map.entry("l3-si_block", 125.18),
            Map.entry("l3-si_huff", 107.98), Map.entry("l3-sin1k0db", 111.03));

    record Row(String status, int samples, int maxDiff, String psnr, String cPsnr) {
    }

    private static final Map<String, Row> ROWS = new ConcurrentHashMap<>();
    private static volatile Map<String, String> cPsnr;

    static Stream<TestData.Entry> vectors() {
        return TestData.vectors();
    }

    private static Map<String, String> cPsnr() {
        if (cPsnr == null) {
            Map<String, String> m = new HashMap<>();
            for (String line : TestData.readLines(TestData.dir().resolve("vectors_c.tsv"))) {
                if (!line.startsWith("#")) {
                    String[] f = line.split("\t", -1);
                    m.put(f[0], f[2]);
                }
            }
            cPsnr = m;
        }
        return cPsnr;
    }

    /** The layer of the first frame header found, or 0 if there is none. */
    private static int firstLayer(byte[] mp3) {
        Mp3Decoder dec = new Mp3Decoder();
        Mp3FrameInfo info = new Mp3FrameInfo();
        int pos = 0;
        while (pos < mp3.length) {
            int samples = dec.decodeFrame(mp3, pos, mp3.length - pos, null, info);
            if (samples > 0) {
                return info.layer;
            }
            if (info.frameBytes == 0) {
                break;
            }
            pos += info.frameBytes;
        }
        return 0;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void psnr(TestData.Entry e) {
        byte[] mp3 = e.bytes();
        int layer = firstLayer(mp3);
        if (layer == 1 || layer == 2) {
            ROWS.put(e.name(), new Row("skipped: Layer " + (layer == 1 ? "I" : "II"), 0, 0, "", cPsnr().get(e.name())));
            Assumptions.abort("Layer " + layer + " is not decoded with MINIMP3_ONLY_MP3");
        }
        LoadBuf.FileInfo info = LoadBuf.load(mp3);
        short[] out = new short[info.samples()];
        Mp3Decoder.toPcm16(info.buffer(), out, info.samples());
        // minimp3_test.c compares only when the reference exists (l3-nonstandard-big-iscf has none)
        byte[] ref = e.attr("pcm").isEmpty() ? null : TestData.read(Path.of(e.attr("pcm")));
        if (ref == null) {
            ROWS.put(e.name(), new Row("decoded, no reference", info.samples(), 0, "99.000000", cPsnr().get(e.name())));
            assertEquals(cPsnr().get(e.name()), "99.000000");
            return;
        }

        // decode_file of minimp3_test.c, MODE_LOAD
        int refSamples = ref.length / 2;
        boolean noStdVec = e.name().contains("nonstandard") || e.name().contains("ILL");
        boolean lenMatch = refSamples == info.samples();
        boolean relaxedLenMatch = lenMatch || refSamples + 1152 == info.samples() || refSamples + 2304 == info.samples();
        boolean seekLenMatch = refSamples <= info.samples() || refSamples + 2304 >= info.samples();
        if (((!relaxedLenMatch || !seekLenMatch) && (3 == info.layer() || 0 == info.layer()) && !noStdVec)
                || (noStdVec && !lenMatch)) {
            fail(e.name() + ": reference and produced number of samples do not match (" + refSamples + "/"
                    + info.samples() + ")");
        }
        int maxSamples = Math.min(refSamples, info.samples());
        int maxDiff = 0;
        double mse = 0.0;
        for (int i = 0; i < maxSamples; i++) {
            short r = (short) ((ref[2 * i] & 0xFF) | (ref[2 * i + 1] << 8));
            int d = Math.abs(out[i] - r);
            maxDiff = Math.max(maxDiff, d);
            mse += (float) d * (float) d;
        }
        mse /= info.samples() != 0 ? info.samples() : 1;
        double psnr = mse == 0 ? 99.0 : 10.0 * Math.log10(((double) 0x7fff * 0x7fff) / mse);
        String psnrText = String.format(Locale.ROOT, "%f", psnr);
        String c = cPsnr().get(e.name());
        ROWS.put(e.name(), new Row("decoded", info.samples(), maxDiff, psnrText, c));

        assertTrue(psnr >= 96, e.name() + ": PSNR compliance failed: " + psnrText);
        assertEquals(c, psnrText, e.name() + ": PSNR differs from minimp3_test.c built with the same options");
    }

    @AfterAll
    static void writeReport() {
        if (ROWS.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# ISO conformance vectors (section 7.2)\n\n");
        sb.append("PSNR computed as minimp3_test.c does. C = minimp3_test.c built with MINIMP3_ONLY_MP3, ")
                .append("MINIMP3_NO_SIMD, MINIMP3_FLOAT_OUTPUT. README = table of the minimp3 README (SIMD build).\n\n");
        sb.append("| vector | result | samples | max diff | PSNR Java | PSNR C | README |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|\n");
        for (Map.Entry<String, Row> r : new TreeMap<>(ROWS).entrySet()) {
            Row v = r.getValue();
            Double readme = README.get(r.getKey());
            sb.append("| ").append(r.getKey()).append(" | ").append(v.status).append(" | ")
                    .append(v.status.startsWith("decoded") ? String.valueOf(v.samples) : "").append(" | ")
                    .append(v.status.startsWith("decoded") ? String.valueOf(v.maxDiff) : "").append(" | ")
                    .append(v.psnr).append(" | ").append(v.status.startsWith("decoded") ? v.cPsnr : "").append(" | ")
                    .append(readme == null ? "" : String.format(Locale.ROOT, "%.2f", readme)).append(" |\n");
        }
        TestData.report("vectors", sb.toString());
    }
}
