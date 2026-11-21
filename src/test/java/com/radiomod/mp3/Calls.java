/*
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.radiomod.mp3;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The sequence of {@code decodeFrame} calls on a whole file, as written by {@code minimp3_ref.c} or
 * produced by the Java decoder with the same loop.
 */
final class Calls {
    /** One call: its result, the frame information after it and the samples it wrote. */
    record Call(int samples, int frameBytes, int frameOffset, int channels, int hz, int layer, int bitrateKbps,
            float[] pcm) {
        String info() {
            return "samples=" + samples + " frameBytes=" + frameBytes + " frameOffset=" + frameOffset
                    + " channels=" + channels + " hz=" + hz + " layer=" + layer + " bitrateKbps=" + bitrateKbps;
        }

        boolean sameInfo(Call o) {
            return samples == o.samples && frameBytes == o.frameBytes && frameOffset == o.frameOffset
                    && channels == o.channels && hz == o.hz && layer == o.layer && bitrateKbps == o.bitrateKbps;
        }
    }

    private Calls() {
    }

    /** Reads a file written by minimp3_ref. */
    static List<Call> read(Path file) {
        ByteBuffer b = ByteBuffer.wrap(TestData.read(file)).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[8];
        b.get(magic);
        String m = new String(magic, StandardCharsets.US_ASCII);
        boolean withPcm = m.equals("MP3REF01");
        if (!withPcm && !m.equals("MP3REFH1")) {
            throw new AssertionError(file + ": not a minimp3_ref output");
        }
        List<Call> calls = new ArrayList<>();
        while (b.hasRemaining()) {
            int samples = b.getInt();
            int frameBytes = b.getInt();
            int frameOffset = b.getInt();
            int channels = b.getInt();
            int hz = b.getInt();
            int layer = b.getInt();
            int bitrate = b.getInt();
            float[] pcm = null;
            if (withPcm && samples > 0) {
                pcm = new float[samples * channels];
                b.asFloatBuffer().get(pcm);
                b.position(b.position() + 4 * pcm.length);
            }
            calls.add(new Call(samples, frameBytes, frameOffset, channels, hz, layer, bitrate, pcm));
        }
        return calls;
    }

    /** Decodes a whole buffer with the loop of minimp3_ref.c. */
    static List<Call> decode(byte[] mp3, boolean withPcm) {
        Mp3Decoder dec = new Mp3Decoder();
        Mp3FrameInfo info = new Mp3FrameInfo();
        float[] pcm = withPcm ? new float[Mp3Decoder.MAX_SAMPLES_PER_FRAME] : null;
        List<Call> calls = new ArrayList<>();
        int pos = 0;
        for (;;) {
            int samples = dec.decodeFrame(mp3, pos, mp3.length - pos, pcm, info);
            float[] out = withPcm && samples > 0 ? Arrays.copyOf(pcm, samples * info.channels) : null;
            calls.add(new Call(samples, info.frameBytes, info.frameOffset, info.channels, info.hz, info.layer,
                    info.bitrateKbps, out));
            if (info.frameBytes <= 0) {
                break;
            }
            pos += info.frameBytes;
        }
        return calls;
    }

    /** {@code mp3dec_f32_to_s16} of one sample. */
    static short toPcm16(float sample) {
        float[] in = {sample};
        short[] out = new short[1];
        Mp3Decoder.toPcm16(in, out, 1);
        return out[0];
    }
}
