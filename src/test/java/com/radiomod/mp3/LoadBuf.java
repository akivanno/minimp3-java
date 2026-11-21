/*
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.radiomod.mp3;

import java.util.Arrays;

/**
 * A translation of {@code mp3dec_load_buf} of minimp3_ex.h (with {@code MINIMP3_ALLOW_MONO_STEREO_TRANSITION},
 * as in minimp3_test.c), used only to compute the PSNR of the ISO vectors exactly as the original test
 * program does: ID3v2/ID3v1/APEv2 skipping, the Xing/LAME tag (delay and padding) and the decoding loop.
 * minimp3_ex.h is not part of the port; this lives in the tests.
 */
final class LoadBuf {
    /** mp3dec_file_info_t. */
    record FileInfo(float[] buffer, int samples, int channels, int hz, int layer) {
    }

    private LoadBuf() {
    }

    private static boolean tag(byte[] b, int o, String s) {
        for (int i = 0; i < s.length(); i++) {
            if (b[o + i] != (byte) s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int u8(byte[] b, int o) {
        return b[o] & 0xFF;
    }

    // mp3dec_skip_id3v1 (returns the new size)
    static int skipId3v1(byte[] buf, int off, int size) {
        if (size >= 128 && tag(buf, off + size - 128, "TAG")) {
            size -= 128;
            if (size >= 227 && tag(buf, off + size - 227, "TAG+")) {
                size -= 227;
            }
        }
        if (size > 32 && tag(buf, off + size - 32, "APETAGEX")) {
            size -= 32;
            int t = off + size + 8 + 4;
            long tagSize = ((long) u8(buf, t + 3) << 24) | (u8(buf, t + 2) << 16) | (u8(buf, t + 1) << 8) | u8(buf, t);
            if (size >= tagSize) {
                size -= (int) tagSize;
            }
        }
        return size;
    }

    // mp3dec_skip_id3v2
    static long skipId3v2(byte[] buf, int off, int size) {
        if (size >= 10 && tag(buf, off, "ID3") && !((u8(buf, off + 5) & 15) != 0 || (u8(buf, off + 6) & 0x80) != 0
                || (u8(buf, off + 7) & 0x80) != 0 || (u8(buf, off + 8) & 0x80) != 0 || (u8(buf, off + 9) & 0x80) != 0)) {
            long id3v2size = (((long) (u8(buf, off + 6) & 0x7f) << 21) | ((u8(buf, off + 7) & 0x7f) << 14)
                    | ((u8(buf, off + 8) & 0x7f) << 7) | (u8(buf, off + 9) & 0x7f)) + 10;
            if ((u8(buf, off + 5) & 16) != 0) {
                id3v2size += 10; /* footer */
            }
            return id3v2size;
        }
        return 0;
    }

    private static boolean isMono(byte[] h, int o) {
        return (h[o + 3] & 0xC0) == 0xC0;
    }

    private static int frameSamples(byte[] h, int o) {
        if ((h[o + 1] & 6) == 6) {
            return 384;
        }
        return 1152 >> ((h[o + 1] & 14) == 2 ? 1 : 0);
    }

    // mp3dec_check_vbrtag: returns {ret, frames, delay, padding}
    static long[] checkVbrtag(byte[] frame, int f, int frameSize) {
        BitStream bs = new BitStream();
        GrInfo[] grInfo = {new GrInfo(), new GrInfo(), new GrInfo(), new GrInfo()};
        long frames = 0;
        long delay = 0, padding = 0;
        if (frameSize < 4 + 8) {
            return new long[] {0, 0, 0, 0};
        }
        bs.init(frame, f + 4, frameSize - 4);
        if ((frame[f + 1] & 1) == 0) {
            bs.getBits(16);
        }
        if (Mp3Decoder.l3ReadSideInfo(bs, grInfo, 0, frame, f) < 0) {
            return new long[] {0, 0, 0, 0}; /* side info corrupted */
        }
        long tagOff = 4 + bs.pos / 8;
        if (tagOff > frameSize || frameSize - tagOff < 8) {
            return new long[] {0, 0, 0, 0};
        }
        int t = (int) (f + tagOff);
        if (!tag(frame, t, "Xing") && !tag(frame, t, "Info")) {
            return new long[] {0, 0, 0, 0};
        }
        int flags = u8(frame, t + 7);
        if ((flags & 1) == 0) {
            return new long[] {-1, 0, 0, 0};
        }
        tagOff += 8;
        if (frameSize - tagOff < 4) {
            return new long[] {0, 0, 0, 0};
        }
        t = (int) (f + tagOff);
        frames = ((long) u8(frame, t) << 24) | (u8(frame, t + 1) << 16) | (u8(frame, t + 2) << 8) | u8(frame, t + 3);
        tagOff += 4;
        if ((flags & 2) != 0) {
            if (frameSize - tagOff < 4) {
                return new long[] {0, frames, 0, 0};
            }
            tagOff += 4;
        }
        if ((flags & 4) != 0) {
            if (frameSize - tagOff < 100) {
                return new long[] {0, frames, 0, 0};
            }
            tagOff += 100;
        }
        if ((flags & 8) != 0) {
            if (frameSize - tagOff < 4) {
                return new long[] {0, frames, 0, 0};
            }
            tagOff += 4;
        }
        if (frameSize - tagOff < 1) {
            return new long[] {0, frames, 0, 0};
        }
        t = (int) (f + tagOff);
        if (frame[t] != 0) { /* extension, LAME, Lavc, etc. Should be the same structure. */
            if (frameSize - tagOff <= 35) {
                return new long[] {0, frames, 0, 0};
            }
            tagOff += 21;
            t = (int) (f + tagOff);
            delay = ((u8(frame, t) << 4) | (u8(frame, t + 1) >> 4)) + (528 + 1);
            padding = (((u8(frame, t + 1) & 0xF) << 8) | u8(frame, t + 2)) - (528 + 1);
        }
        return new long[] {1, frames, delay, padding};
    }

    // mp3dec_load_buf (io == NULL, no progress callback)
    static FileInfo load(byte[] data) {
        int off = 0, size = data.length;
        long detectedSamples = 0;
        int toSkip = 0;
        Mp3FrameInfo frameInfo = new Mp3FrameInfo();
        // mp3dec_skip_id3
        long id3v2size = skipId3v2(data, off, size);
        if (id3v2size != 0) {
            if (id3v2size >= size) {
                id3v2size = size;
            }
            off += (int) id3v2size;
            size -= (int) id3v2size;
        }
        size = skipId3v1(data, off, size);
        if (size == 0) {
            return new FileInfo(new float[0], 0, 0, 0, 0);
        }
        Mp3Decoder dec = new Mp3Decoder();
        int samples;
        int[] ff = new int[2];
        do {
            ff[0] = 0;
            ff[1] = 0;
            int i = Mp3Decoder.mp3dFindFrame(data, off, size, ff);
            off += i;
            size -= i;
            int frameSize = ff[1];
            if (i != 0 && frameSize == 0) {
                continue;
            }
            if (frameSize == 0) {
                return new FileInfo(new float[0], 0, 0, 0, 0);
            }
            int hdr = off;
            frameInfo.channels = isMono(data, hdr) ? 1 : 2;
            frameInfo.hz = Tables.G_HZ[((data[hdr + 2] & 0xFF) >> 2) & 3] >> ((data[hdr + 1] & 0x8) != 0 ? 0 : 1)
                    >> ((data[hdr + 1] & 0x10) != 0 ? 0 : 1);
            frameInfo.layer = 4 - (((data[hdr + 1] & 0xFF) >> 1) & 3);
            frameInfo.frameBytes = frameSize;
            samples = frameSamples(data, hdr) * frameInfo.channels;
            if (3 != frameInfo.layer) {
                break;
            }
            long[] v = checkVbrtag(data, hdr, frameSize);
            long ret = v[0];
            if (ret > 0) {
                long padding = v[3] * frameInfo.channels;
                toSkip = (int) (v[2] * frameInfo.channels);
                detectedSamples = samples * v[1];
                if (detectedSamples >= toSkip) {
                    detectedSamples -= toSkip;
                }
                if (padding > 0 && detectedSamples >= padding) {
                    detectedSamples -= padding;
                }
                if (detectedSamples == 0) {
                    return new FileInfo(new float[0], 0, 0, 0, 0);
                }
            }
            if (ret != 0) {
                off += frameSize;
                size -= frameSize;
            }
            break;
        } while (true);

        float[] buffer = new float[Mp3Decoder.MAX_SAMPLES_PER_FRAME * 4];
        int infoSamples = 0;
        int infoChannels = frameInfo.channels;
        int infoHz = frameInfo.hz;
        int infoLayer = frameInfo.layer;
        float[] pcm = new float[Mp3Decoder.MAX_SAMPLES_PER_FRAME];
        do {
            samples = dec.decodeFrame(data, off, size, pcm, frameInfo);
            off += frameInfo.frameBytes;
            size -= frameInfo.frameBytes;
            if (samples != 0) {
                if (infoHz != frameInfo.hz || infoLayer != frameInfo.layer) {
                    break; // MP3D_E_DECODE
                }
                if (infoChannels != 0 && infoChannels != frameInfo.channels) {
                    infoChannels = 0; /* mark file with mono-stereo transition */
                }
                samples *= frameInfo.channels;
                if (buffer.length < infoSamples + samples) {
                    buffer = Arrays.copyOf(buffer, buffer.length * 2);
                }
                System.arraycopy(pcm, 0, buffer, infoSamples, samples);
                if (toSkip != 0) {
                    int skip = Math.min(samples, toSkip);
                    toSkip -= skip;
                    samples -= skip;
                    System.arraycopy(buffer, skip, buffer, 0, samples);
                }
                infoSamples += samples;
            }
        } while (frameInfo.frameBytes != 0);
        if (detectedSamples != 0 && infoSamples > detectedSamples) {
            infoSamples = (int) detectedSamples; /* cut padding */
        }
        return new FileInfo(buffer, infoSamples, infoChannels, infoHz, infoLayer);
    }
}
