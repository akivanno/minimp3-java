/*
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.radiomod.mp3;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Section 7.5: starting at a random byte with a reset decoder, as the mod does to join a song in the
 * middle, the output must converge to the decoding from the start of the file (within the tolerance of
 * 7.1).
 *
 * <p>Frames are numbered from the first one found: frame 0 is the first, frame 3 the third after it.
 * A frame can be decoded only once the bit reservoir holds the {@code main_data_begin} bytes it refers to;
 * let k0 be the first such frame. Its output is still wrong (the MDCT overlap and the synthesis filter
 * start from zero), and so is the next one for MPEG-2 and 2.5, whose frames have a single granule. So the
 * test requires the output to match from frame k0 + 1 (MPEG-1) or k0 + 2 (MPEG-2, 2.5), and reports how
 * many restarts match from frame 3, the target of the specification. With a deep bit reservoir and small
 * frames k0 can exceed 2, for the original as well (see NOTES.md).
 */
@Execution(ExecutionMode.CONCURRENT)
class ResyncTest {
    static final int POINTS = 100;
    static final int TARGET_FROM = 3;
    /** How many frames are compared after the one from which the output must match. */
    static final int COMPARED_AFTER = 8;
    /**
     * Start points leave at least this many frames before the end of the file. The bit reservoir can need
     * many earlier frames before a frame can be decoded (up to 255 bytes of reservoir over MPEG-2 frames
     * that carry 11 bytes of main data each, in VBR silence), so the window must be wide.
     */
    static final int TAIL = 40;

    record Frame(int offset, int samples, float[] pcm) {
    }

    record Result(String version, int points, int maxFirstMatching, int exactFrom, int fromTarget, int maxK0,
            int skipped, Map<Integer, Integer> histogram) {
    }

    private static final Map<String, Result> RESULTS = new ConcurrentHashMap<>();

    static Stream<String> files() {
        return Stream.of("44100_joint_cbr128", "48000_stereo_vbr0", "32000_mono_cbr32", "22050_joint_cbr160",
                "16000_stereo_vbr9", "24000_mono_vbr0", "8000_joint_cbr8", "11025_stereo_vbr0", "12000_mono_cbr160",
                "tag_id3v2big_44100_joint_cbr128");
    }

    /** Decodes from pos with the decoder as it is; returns the frames found, with their absolute offsets. */
    static List<Frame> decodeFrom(byte[] mp3, int pos, int maxFrames) {
        return decodeFrom(mp3, pos, maxFrames, -1);
    }

    /**
     * As above; with lag >= 0, stops COMPARED_AFTER frames after frame k0 + lag, where k0 is the first
     * frame that returned samples.
     */
    static List<Frame> decodeFrom(byte[] mp3, int pos, int maxFrames, int lag) {
        Mp3Decoder dec = new Mp3Decoder();
        Mp3FrameInfo info = new Mp3FrameInfo();
        float[] pcm = new float[Mp3Decoder.MAX_SAMPLES_PER_FRAME];
        List<Frame> frames = new ArrayList<>();
        int k0 = -1;
        while (frames.size() < maxFrames && (k0 < 0 || lag < 0 || frames.size() < k0 + lag + COMPARED_AFTER)) {
            info.frameOffset = -1;
            int samples = dec.decodeFrame(mp3, pos, mp3.length - pos, pcm, info);
            if (info.frameBytes == 0) {
                break;
            }
            if (info.frameOffset >= 0) { // a frame header was found
                if (samples > 0 && k0 < 0) {
                    k0 = frames.size();
                }
                frames.add(new Frame(pos + info.frameOffset, samples,
                        samples > 0 ? Arrays.copyOf(pcm, samples * info.channels) : new float[0]));
            }
            pos += info.frameBytes;
        }
        return frames;
    }

    static boolean same(Frame a, Frame b, boolean exact) {
        if (a.offset != b.offset || a.samples != b.samples || a.pcm.length != b.pcm.length) {
            return false;
        }
        for (int i = 0; i < a.pcm.length; i++) {
            if (exact ? Float.floatToRawIntBits(a.pcm[i]) != Float.floatToRawIntBits(b.pcm[i])
                    : Math.abs(Calls.toPcm16(a.pcm[i]) - Calls.toPcm16(b.pcm[i])) > 1) {
                return false;
            }
        }
        return true;
    }

    /** The first index from which every compared frame matches (frames.size() if none does). */
    static int firstMatching(List<Frame> after, Map<Integer, Frame> full, boolean exact) {
        int first = after.size();
        for (int k = after.size() - 1; k >= 0; k--) {
            Frame f = full.get(after.get(k).offset);
            if (f == null || !same(after.get(k), f, exact)) {
                break;
            }
            first = k;
        }
        return first;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    void resynchronizes(String name) {
        byte[] mp3 = TestData.entry(name).bytes();
        Map<Integer, Frame> full = new HashMap<>();
        List<Frame> all = decodeFrom(mp3, 0, Integer.MAX_VALUE);
        for (Frame f : all) {
            full.put(f.offset, f);
        }
        // start points that leave TAIL frames before the end of the file (a quarter of it in short files)
        int limit = all.get(all.size() > 2 * TAIL ? all.size() - TAIL : all.size() / 4).offset;
        SplittableRandom r = new SplittableRandom(0x5E5E ^ name.hashCode());
        String version = TestData.entry(name).attr("version");
        int lag = version.equals("MPEG-1") ? 1 : 2;
        int worst = 0;
        int worstExact = 0;
        int worstK0 = 0;
        int fromTarget = 0;
        int skipped = 0;
        Map<Integer, Integer> histogram = new TreeMap<>();
        for (int p = 0; p < POINTS; p++) {
            int start = r.nextInt(limit);
            List<Frame> after = decodeFrom(mp3, start, Integer.MAX_VALUE, lag);
            int k0 = 0;
            while (k0 < after.size() && after.get(k0).samples == 0) {
                k0++;
            }
            if (after.size() < k0 + lag + 2) {
                skipped++; // the file ended before the reservoir filled: nothing to judge
                continue;
            }
            int first = firstMatching(after, full, false);
            int firstExact = firstMatching(after, full, true);
            histogram.merge(first, 1, Integer::sum);
            worst = Math.max(worst, first);
            worstExact = Math.max(worstExact, firstExact);
            worstK0 = Math.max(worstK0, k0);
            fromTarget += first <= TARGET_FROM ? 1 : 0;
            // within the tolerance a frame can match earlier, when what is still wrong is too small to show
            assertTrue(firstExact <= k0 + lag, name + " from byte " + start + ": first decodable frame " + k0
                    + ", but the output is bit-exact only from frame " + firstExact);
            assertTrue(first <= firstExact);
        }
        assertTrue(skipped <= POINTS / 2, name + ": " + skipped + " start points could not be judged");
        RESULTS.put(name, new Result(version, POINTS - skipped, worst, worstExact, fromTarget, worstK0, skipped,
                histogram));
    }

    @AfterAll
    static void writeReport() {
        if (RESULTS.isEmpty()) {
            return;
        }
        final String nl = "\n";
        StringBuilder sb = new StringBuilder();
        sb.append("# Restart in the middle of a file (section 7.5)").append(nl).append(nl);
        sb.append(POINTS).append(" random start points per file. Frame 0 is the first frame found after the start; ")
                .append("k0 is the first frame the bit reservoir lets the decoder decode. 'Matches from' is the first ")
                .append("frame from which every frame compared (up to ").append(COMPARED_AFTER)
                .append(" frames after k0 + 1 or k0 + 2) matches the decoding from the start of the file ")
                .append("(16-bit samples within ±1). Start points after which the file ends before the reservoir ")
                .append("fills are not judged.").append(nl).append(nl);
        sb.append("| file | version | worst k0 | worst: matches from frame | restarts matching from frame ")
                .append(TARGET_FROM).append(" | not judged | distribution of 'matches from' |").append(nl);
        sb.append("|---|---|---:|---:|---:|---:|---|").append(nl);
        int total = 0;
        int ok = 0;
        int worstAll = 0;
        for (Map.Entry<String, Result> e : new TreeMap<>(RESULTS).entrySet()) {
            Result v = e.getValue();
            total += v.points;
            ok += v.fromTarget;
            worstAll = Math.max(worstAll, v.maxFirstMatching);
            sb.append("| ").append(e.getKey()).append(" | ").append(v.version).append(" | ").append(v.maxK0)
                    .append(" | ").append(v.maxFirstMatching).append(" | ").append(v.fromTarget).append('/')
                    .append(v.points).append(" | ").append(v.skipped).append(" | ").append(v.histogram).append(" |")
                    .append(nl);
        }
        sb.append(nl).append("All files: ").append(ok).append('/').append(total).append(" restarts match from frame ")
                .append(TARGET_FROM).append("; the worst matches from frame ").append(worstAll).append('.').append(nl);
        TestData.report("resync", sb.toString());
    }
}
