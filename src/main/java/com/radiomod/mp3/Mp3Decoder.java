/*
 * minimp3-java: a Java port of minimp3 by lieff, https://github.com/lieff/minimp3
 * Translated from minimp3.h at commit 142823796ab8efa55e1bf11406028f3be77ecceb.
 *
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.radiomod.mp3;

import java.util.Arrays;
import java.util.Objects;

import static com.radiomod.mp3.Tables.G_AA;
import static com.radiomod.mp3.Tables.G_EXPFRAC;
import static com.radiomod.mp3.Tables.G_HZ;
import static com.radiomod.mp3.Tables.G_LINBITS;
import static com.radiomod.mp3.Tables.G_MDCT_WINDOW;
import static com.radiomod.mp3.Tables.G_MOD;
import static com.radiomod.mp3.Tables.G_PAN;
import static com.radiomod.mp3.Tables.G_POW43;
import static com.radiomod.mp3.Tables.G_PREAMP;
import static com.radiomod.mp3.Tables.G_SCFC_DECODE;
import static com.radiomod.mp3.Tables.G_SCF_LONG;
import static com.radiomod.mp3.Tables.G_SCF_MIXED;
import static com.radiomod.mp3.Tables.G_SCF_PARTITIONS;
import static com.radiomod.mp3.Tables.G_SCF_SHORT;
import static com.radiomod.mp3.Tables.G_SEC;
import static com.radiomod.mp3.Tables.G_TWID3;
import static com.radiomod.mp3.Tables.G_TWID9;
import static com.radiomod.mp3.Tables.G_WIN;
import static com.radiomod.mp3.Tables.HALFRATE;
import static com.radiomod.mp3.Tables.TAB32;
import static com.radiomod.mp3.Tables.TAB33;
import static com.radiomod.mp3.Tables.TABINDEX;
import static com.radiomod.mp3.Tables.TABS;

/**
 * An MPEG-1, MPEG-2 and MPEG-2.5 Layer III decoder: a faithful port of
 * <a href="https://github.com/lieff/minimp3">minimp3</a> by lieff, compiled with {@code MINIMP3_ONLY_MP3},
 * {@code MINIMP3_NO_SIMD} and {@code MINIMP3_FLOAT_OUTPUT}.
 *
 * <p>An instance holds the decoding state of one stream ({@code mp3dec_t}). Instances share no mutable
 * state, so several can decode in parallel on different threads; a single instance is not thread-safe.
 * All working buffers are allocated by the constructor: {@link #decodeFrame} allocates nothing.
 *
 * <p>Typical use, with the whole file in memory:
 *
 * <pre>{@code
 * Mp3Decoder decoder = new Mp3Decoder();
 * Mp3FrameInfo info = new Mp3FrameInfo();
 * float[] pcm = new float[Mp3Decoder.MAX_SAMPLES_PER_FRAME];
 * int pos = 0;
 * while (true) {
 *     int samples = decoder.decodeFrame(mp3, pos, mp3.length - pos, pcm, info);
 *     if (info.frameBytes == 0) {
 *         break;              // end of the data
 *     }
 *     pos += info.frameBytes;
 *     if (samples > 0) {
 *         // pcm[0 .. samples * info.channels) holds the interleaved samples
 *     }
 * }
 * }</pre>
 *
 * <p>The decoder accepts any input, including corrupted or hostile data: it never throws for the content
 * of the data, never reads outside the range it is given and runs in time proportional to the length
 * of that range.
 */
public final class Mp3Decoder {
    /**
     * {@code MINIMP3_MAX_SAMPLES_PER_FRAME}: the largest number of samples (all channels together) that
     * one call can write, and so the minimum length of the {@code pcm} array.
     */
    public static final int MAX_SAMPLES_PER_FRAME = 1152 * 2;

    private static final int MAX_FREE_FORMAT_FRAME_SIZE = 2304; /* more than ISO spec's */
    private static final int MAX_FRAME_SYNC_MATCHES = 10;
    private static final int MAX_L3_FRAME_PAYLOAD_BYTES = MAX_FREE_FORMAT_FRAME_SIZE;
    private static final int MAX_BITRESERVOIR_BYTES = 511;
    private static final int SHORT_BLOCK_TYPE = 2;
    private static final int STOP_BLOCK_TYPE = 3;
    static final int HDR_SIZE = 4;
    private static final int BITS_DEQUANTIZER_OUT = -1;
    private static final int MAX_SCF = 255 + BITS_DEQUANTIZER_OUT * 4 - 210;
    private static final int MAX_SCFI = (MAX_SCF + 3) & ~3;

    // float grbuf[2][576], scf[40], syn[18 + 15][2*32] of mp3dec_scratch_t, kept in one array in the
    // same order as in the C struct (see l3Reorder for why the layout matters).
    private static final int GRBUF = 0;
    private static final int SCF = GRBUF + 2 * 576;
    private static final int SYN = SCF + 40;
    private static final int SCRATCH_FLOATS = SYN + (18 + 15) * 2 * 32;
    private static final int IST_POS_LEN = 39;

    // mp3dec_t
    private final float[] mdctOverlap = new float[2 * 9 * 32];
    private final float[] qmfState = new float[15 * 2 * 32];
    private int reserv;
    private int freeFormatBytes;
    private final byte[] header = new byte[HDR_SIZE];
    private final byte[] reservBuf = new byte[MAX_BITRESERVOIR_BYTES];

    // mp3dec_scratch_t: a local of mp3dec_decode_frame in C, allocated once here.
    private final BitStream scratchBs = new BitStream();
    private final byte[] maindata = new byte[MAX_BITRESERVOIR_BYTES + MAX_L3_FRAME_PAYLOAD_BYTES];
    private final GrInfo[] grInfo = {new GrInfo(), new GrInfo(), new GrInfo(), new GrInfo()};
    private final float[] scratch = new float[SCRATCH_FLOATS];
    private final int[] istPos = new int[2 * IST_POS_LEN];

    // Other locals of the C functions, allocated once.
    private final BitStream bsFrame = new BitStream();
    private final int[] findFrameOut = new int[2];
    private final int[] scfSize = new int[4];
    private final int[] iscf = new int[40];
    private final int[] maxBand = new int[3];
    private final float[] co = new float[9];
    private final float[] si = new float[9];
    private final float[] tmp = new float[18];
    private final float[] dctT = new float[4 * 8];
    private final float[] synA = new float[4];
    private final float[] synB = new float[4];

    /** Creates a decoder in the initial state ({@code mp3dec_init}). */
    public Mp3Decoder() {
        mp3decInit();
    }

    /**
     * Returns the decoder to the initial state ({@code mp3dec_init}), for example before decoding from
     * another position of the stream. The next call to {@link #decodeFrame} searches for a frame from
     * scratch, as the first call does.
     */
    public void reset() {
        mp3decInit();
    }

    // HDR_IS_MONO
    private static boolean hdrIsMono(byte[] h, int o) {
        return (h[o + 3] & 0xC0) == 0xC0;
    }

    // HDR_IS_MS_STEREO
    private static boolean hdrIsMsStereo(byte[] h, int o) {
        return (h[o + 3] & 0xE0) == 0x60;
    }

    // HDR_IS_FREE_FORMAT
    private static boolean hdrIsFreeFormat(byte[] h, int o) {
        return (h[o + 2] & 0xF0) == 0;
    }

    // HDR_IS_CRC
    private static boolean hdrIsCrc(byte[] h, int o) {
        return (h[o + 1] & 1) == 0;
    }

    // HDR_TEST_PADDING
    private static boolean hdrTestPadding(byte[] h, int o) {
        return (h[o + 2] & 0x2) != 0;
    }

    // HDR_TEST_MPEG1
    private static boolean hdrTestMpeg1(byte[] h, int o) {
        return (h[o + 1] & 0x8) != 0;
    }

    // HDR_TEST_NOT_MPEG25
    private static boolean hdrTestNotMpeg25(byte[] h, int o) {
        return (h[o + 1] & 0x10) != 0;
    }

    // HDR_TEST_I_STEREO
    private static boolean hdrTestIStereo(byte[] h, int o) {
        return (h[o + 3] & 0x10) != 0;
    }

    // HDR_TEST_MS_STEREO
    private static boolean hdrTestMsStereo(byte[] h, int o) {
        return (h[o + 3] & 0x20) != 0;
    }

    // HDR_GET_LAYER
    private static int hdrGetLayer(byte[] h, int o) {
        return ((h[o + 1] & 0xFF) >> 1) & 3;
    }

    // HDR_GET_BITRATE
    private static int hdrGetBitrate(byte[] h, int o) {
        return (h[o + 2] & 0xFF) >> 4;
    }

    // HDR_GET_SAMPLE_RATE
    private static int hdrGetSampleRate(byte[] h, int o) {
        return ((h[o + 2] & 0xFF) >> 2) & 3;
    }

    // HDR_GET_MY_SAMPLE_RATE
    private static int hdrGetMySampleRate(byte[] h, int o) {
        int h1 = h[o + 1] & 0xFF;
        return hdrGetSampleRate(h, o) + (((h1 >> 3) & 1) + ((h1 >> 4) & 1)) * 3;
    }

    // HDR_IS_FRAME_576
    private static boolean hdrIsFrame576(byte[] h, int o) {
        return (h[o + 1] & 14) == 2;
    }

    // HDR_IS_LAYER_1
    private static boolean hdrIsLayer1(byte[] h, int o) {
        return (h[o + 1] & 6) == 6;
    }

    // MINIMP3_MIN
    private static int min(int a, int b) {
        return a > b ? b : a;
    }

    // MINIMP3_MAX
    private static int max(int a, int b) {
        return a < b ? b : a;
    }

    // hdr_valid
    private static boolean hdrValid(byte[] h, int o) {
        return (h[o] & 0xFF) == 0xff
                && ((h[o + 1] & 0xF0) == 0xf0 || (h[o + 1] & 0xFE) == 0xe2)
                && (hdrGetLayer(h, o) != 0)
                && (hdrGetBitrate(h, o) != 15)
                && (hdrGetSampleRate(h, o) != 3);
    }

    // hdr_compare
    private static boolean hdrCompare(byte[] b1, int h1, byte[] b2, int h2) {
        return hdrValid(b2, h2)
                && ((b1[h1 + 1] ^ b2[h2 + 1]) & 0xFE) == 0
                && ((b1[h1 + 2] ^ b2[h2 + 2]) & 0x0C) == 0
                && !(hdrIsFreeFormat(b1, h1) ^ hdrIsFreeFormat(b2, h2));
    }

    // hdr_bitrate_kbps
    private static int hdrBitrateKbps(byte[] h, int o) {
        return 2 * HALFRATE[hdrTestMpeg1(h, o) ? 1 : 0][hdrGetLayer(h, o) - 1][hdrGetBitrate(h, o)];
    }

    // hdr_sample_rate_hz
    private static int hdrSampleRateHz(byte[] h, int o) {
        return G_HZ[hdrGetSampleRate(h, o)] >> (hdrTestMpeg1(h, o) ? 0 : 1) >> (hdrTestNotMpeg25(h, o) ? 0 : 1);
    }

    // hdr_frame_samples
    private static int hdrFrameSamples(byte[] h, int o) {
        return hdrIsLayer1(h, o) ? 384 : (1152 >> (hdrIsFrame576(h, o) ? 1 : 0));
    }

    // hdr_frame_bytes
    private static int hdrFrameBytes(byte[] h, int o, int freeFormatSize) {
        int frameBytes = hdrFrameSamples(h, o) * hdrBitrateKbps(h, o) * 125 / hdrSampleRateHz(h, o);
        if (hdrIsLayer1(h, o)) {
            frameBytes &= ~3; /* slot align */
        }
        return frameBytes != 0 ? frameBytes : freeFormatSize;
    }

    // hdr_padding
    private static int hdrPadding(byte[] h, int o) {
        return hdrTestPadding(h, o) ? (hdrIsLayer1(h, o) ? 4 : 1) : 0;
    }

    // L3_read_side_info
    // Package-private so that the tests can reproduce mp3dec_check_vbrtag of minimp3_ex.h.
    static int l3ReadSideInfo(BitStream bs, GrInfo[] grs, int gr, byte[] hdr, int h) {
        int tables, scfsi = 0;
        int mainDataBegin, part23Sum = 0;
        int grCount = hdrIsMono(hdr, h) ? 1 : 2;
        int srIdx = hdrGetMySampleRate(hdr, h);
        srIdx -= (srIdx != 0) ? 1 : 0;

        if (hdrTestMpeg1(hdr, h)) {
            grCount *= 2;
            mainDataBegin = bs.getBits(9);
            scfsi = bs.getBits(7 + grCount);
        } else {
            mainDataBegin = bs.getBits(8 + grCount) >>> grCount;
        }

        do {
            GrInfo g = grs[gr];
            if (hdrIsMono(hdr, h)) {
                scfsi <<= 4;
            }
            g.part23Length = bs.getBits(12);
            part23Sum += g.part23Length;
            g.bigValues = bs.getBits(9);
            if (g.bigValues > 288) {
                return -1;
            }
            g.globalGain = bs.getBits(8);
            g.scalefacCompress = bs.getBits(hdrTestMpeg1(hdr, h) ? 4 : 9);
            g.sfbtab = G_SCF_LONG[srIdx];
            g.nLongSfb = 22;
            g.nShortSfb = 0;
            if (bs.getBits(1) != 0) {
                g.blockType = bs.getBits(2);
                if (g.blockType == 0) {
                    return -1;
                }
                g.mixedBlockFlag = bs.getBits(1);
                g.regionCount[0] = 7;
                g.regionCount[1] = 255;
                if (g.blockType == SHORT_BLOCK_TYPE) {
                    scfsi &= 0x0F0F;
                    if (g.mixedBlockFlag == 0) {
                        g.regionCount[0] = 8;
                        g.sfbtab = G_SCF_SHORT[srIdx];
                        g.nLongSfb = 0;
                        g.nShortSfb = 39;
                    } else {
                        g.sfbtab = G_SCF_MIXED[srIdx];
                        g.nLongSfb = hdrTestMpeg1(hdr, h) ? 8 : 6;
                        g.nShortSfb = 30;
                    }
                }
                tables = bs.getBits(10);
                tables <<= 5;
                g.subblockGain[0] = bs.getBits(3);
                g.subblockGain[1] = bs.getBits(3);
                g.subblockGain[2] = bs.getBits(3);
            } else {
                g.blockType = 0;
                g.mixedBlockFlag = 0;
                tables = bs.getBits(15);
                g.regionCount[0] = bs.getBits(4);
                g.regionCount[1] = bs.getBits(3);
                g.regionCount[2] = 255;
            }
            g.tableSelect[0] = tables >>> 10;
            g.tableSelect[1] = (tables >>> 5) & 31;
            g.tableSelect[2] = tables & 31;
            g.preflag = hdrTestMpeg1(hdr, h) ? bs.getBits(1) : (g.scalefacCompress >= 500 ? 1 : 0);
            g.scalefacScale = bs.getBits(1);
            g.count1Table = bs.getBits(1);
            g.scfsi = (scfsi >>> 12) & 15;
            scfsi <<= 4;
            gr++;
        } while (--grCount != 0);

        if (part23Sum + bs.pos > bs.limit + mainDataBegin * 8) {
            return -1;
        }

        return mainDataBegin;
    }

    // L3_read_scalefactors
    // scf and ist_pos are uint8_t in C: the values stored here are masked to 0..255 like a uint8_t store.
    private static void l3ReadScalefactors(int[] scf, int[] istPos, int ist, int[] scfSize, int[] scfCount,
            int cnt0, BitStream bitbuf, int scfsi) {
        int i, k;
        int s = 0;
        for (i = 0; i < 4 && scfCount[cnt0 + i] != 0; i++, scfsi *= 2) {
            int cnt = scfCount[cnt0 + i];
            if ((scfsi & 8) != 0) {
                System.arraycopy(istPos, ist, scf, s, cnt);
            } else {
                int bits = scfSize[i];
                if (bits == 0) {
                    Arrays.fill(scf, s, s + cnt, 0);
                    Arrays.fill(istPos, ist, ist + cnt, 0);
                } else {
                    int maxScf = (scfsi < 0) ? (1 << bits) - 1 : -1;
                    for (k = 0; k < cnt; k++) {
                        int v = bitbuf.getBits(bits);
                        istPos[ist + k] = (v == maxScf ? -1 : v) & 0xFF;
                        scf[s + k] = v & 0xFF;
                    }
                }
            }
            ist += cnt;
            s += cnt;
        }
        scf[s] = scf[s + 1] = scf[s + 2] = 0;
    }

    // L3_ldexp_q2
    private static float l3LdexpQ2(float y, int expQ2) {
        int e;
        do {
            e = min(30 * 4, expQ2);
            y *= G_EXPFRAC[e & 3] * (1 << 30 >> (e >> 2));
        } while ((expQ2 -= e) > 0);
        return y;
    }

    // L3_decode_scalefactors
    // ist_pos is istPos[istOff ..]; scf is scratch[SCF ..].
    private void l3DecodeScalefactors(byte[] hdr, int h, int istOff, BitStream bs, GrInfo gr, int ch) {
        final int[] scfPartition = G_SCF_PARTITIONS[(gr.nShortSfb != 0 ? 1 : 0) + (gr.nLongSfb == 0 ? 1 : 0)];
        int partOff = 0;
        final int[] scfSize = this.scfSize;
        final int[] iscf = this.iscf;
        int i, scfShift = gr.scalefacScale + 1, gainExp, scfsi = gr.scfsi;
        float gain;

        if (hdrTestMpeg1(hdr, h)) {
            int part = G_SCFC_DECODE[gr.scalefacCompress];
            scfSize[1] = scfSize[0] = part >> 2;
            scfSize[3] = scfSize[2] = part & 3;
        } else {
            int k, modprod, sfc, ist = (hdrTestIStereo(hdr, h) && ch != 0) ? 1 : 0;
            sfc = gr.scalefacCompress >> ist;
            for (k = ist * 3 * 4; sfc >= 0; sfc -= modprod, k += 4) {
                for (modprod = 1, i = 3; i >= 0; i--) {
                    scfSize[i] = sfc / modprod % G_MOD[k + i];
                    modprod *= G_MOD[k + i];
                }
            }
            partOff += k;
            scfsi = -16;
        }
        l3ReadScalefactors(iscf, istPos, istOff, scfSize, scfPartition, partOff, bs, scfsi);

        if (gr.nShortSfb != 0) {
            int sh = 3 - scfShift;
            for (i = 0; i < gr.nShortSfb; i += 3) {
                iscf[gr.nLongSfb + i + 0] = (iscf[gr.nLongSfb + i + 0] + (gr.subblockGain[0] << sh)) & 0xFF;
                iscf[gr.nLongSfb + i + 1] = (iscf[gr.nLongSfb + i + 1] + (gr.subblockGain[1] << sh)) & 0xFF;
                iscf[gr.nLongSfb + i + 2] = (iscf[gr.nLongSfb + i + 2] + (gr.subblockGain[2] << sh)) & 0xFF;
            }
        } else if (gr.preflag != 0) {
            for (i = 0; i < 10; i++) {
                iscf[11 + i] = (iscf[11 + i] + G_PREAMP[i]) & 0xFF;
            }
        }

        gainExp = gr.globalGain + BITS_DEQUANTIZER_OUT * 4 - 210 - (hdrIsMsStereo(hdr, h) ? 2 : 0);
        gain = l3LdexpQ2(1 << (MAX_SCFI / 4), MAX_SCFI - gainExp);
        for (i = 0; i < gr.nLongSfb + gr.nShortSfb; i++) {
            scratch[SCF + i] = l3LdexpQ2(gain, iscf[i] << scfShift);
        }
    }

    // L3_pow_43
    private static float l3Pow43(int x) {
        float frac;
        int sign, mult = 256;

        if (x < 129) {
            return G_POW43[16 + x];
        }

        if (x < 1024) {
            mult = 16;
            x <<= 3;
        }

        sign = 2 * x & 64;
        frac = (float) ((x & 63) - sign) / ((x & ~63) + sign);
        return G_POW43[16 + ((x + sign) >> 6)] * (1.f + frac * ((4.f / 3) + frac * (2.f / 9))) * mult;
    }

    // *bs_next_ptr in L3_huffman, with a bound check that C does not have: a byte past the end of the
    // array reads as 0, where C would read the bytes that follow maindata in mp3dec_scratch_t. Only a
    // corrupted granule can make the reader run that far (see NOTES.md).
    private static int rd(byte[] buf, int i) {
        return i < buf.length ? buf[i] & 0xFF : 0;
    }

    // L3_huffman
    // dst is scratch[dst ..]; scf is scratch[SCF ..]. The macros PEEK_BITS, FLUSH_BITS, CHECK_BITS and BSPOS
    // are written out in place, each one marked with its name.
    private void l3Huffman(int dst, BitStream bs, GrInfo grInfo, int layer3grLimit) {
        final float[] f = scratch;
        final short[] tabs = TABS;
        final byte[] buf = bs.buf;
        final int base = bs.base;
        final int[] sfbTab = grInfo.sfbtab;
        int sfb = 0;
        int scf = SCF;

        float one = 0.0f;
        int ireg = 0, bigValCnt = grInfo.bigValues;
        int bsNextPtr = base + bs.pos / 8;
        int bsCache = (((rd(buf, bsNextPtr) * 256 + rd(buf, bsNextPtr + 1)) * 256 + rd(buf, bsNextPtr + 2)) * 256
                + rd(buf, bsNextPtr + 3)) << (bs.pos & 7);
        int pairsToDecode, np, bsSh = (bs.pos & 7) - 8;
        bsNextPtr += 4;

        while (bigValCnt > 0) {
            int tabNum = grInfo.tableSelect[ireg];
            int sfbCnt = grInfo.regionCount[ireg++];
            int codebook = TABINDEX[tabNum];
            int linbits = G_LINBITS[tabNum];
            if (linbits != 0) {
                do {
                    np = sfbTab[sfb++] / 2;
                    pairsToDecode = min(bigValCnt, np);
                    one = f[scf++];
                    do {
                        int j, w = 5;
                        int leaf = tabs[codebook + (bsCache >>> (32 - w))]; // PEEK_BITS(w)
                        while (leaf < 0) {
                            bsCache <<= w; // FLUSH_BITS(w)
                            bsSh += w;
                            w = leaf & 7;
                            leaf = tabs[codebook + (bsCache >>> (32 - w)) - (leaf >> 3)]; // PEEK_BITS(w)
                        }
                        bsCache <<= leaf >> 8; // FLUSH_BITS(leaf >> 8)
                        bsSh += leaf >> 8;

                        for (j = 0; j < 2; j++, dst++, leaf >>= 4) {
                            int lsb = leaf & 0x0F;
                            if (lsb == 15) {
                                lsb += bsCache >>> (32 - linbits); // PEEK_BITS(linbits)
                                bsCache <<= linbits; // FLUSH_BITS(linbits)
                                bsSh += linbits;
                                while (bsSh >= 0) { // CHECK_BITS
                                    bsCache |= rd(buf, bsNextPtr++) << bsSh;
                                    bsSh -= 8;
                                }
                                f[dst] = one * l3Pow43(lsb) * (bsCache < 0 ? -1 : 1);
                            } else {
                                f[dst] = G_POW43[16 + lsb - 16 * (bsCache >>> 31)] * one;
                            }
                            int n = lsb != 0 ? 1 : 0; // FLUSH_BITS(lsb ? 1 : 0)
                            bsCache <<= n;
                            bsSh += n;
                        }
                        while (bsSh >= 0) { // CHECK_BITS
                            bsCache |= rd(buf, bsNextPtr++) << bsSh;
                            bsSh -= 8;
                        }
                    } while (--pairsToDecode != 0);
                } while ((bigValCnt -= np) > 0 && --sfbCnt >= 0);
            } else {
                do {
                    np = sfbTab[sfb++] / 2;
                    pairsToDecode = min(bigValCnt, np);
                    one = f[scf++];
                    do {
                        int j, w = 5;
                        int leaf = tabs[codebook + (bsCache >>> (32 - w))]; // PEEK_BITS(w)
                        while (leaf < 0) {
                            bsCache <<= w; // FLUSH_BITS(w)
                            bsSh += w;
                            w = leaf & 7;
                            leaf = tabs[codebook + (bsCache >>> (32 - w)) - (leaf >> 3)]; // PEEK_BITS(w)
                        }
                        bsCache <<= leaf >> 8; // FLUSH_BITS(leaf >> 8)
                        bsSh += leaf >> 8;

                        for (j = 0; j < 2; j++, dst++, leaf >>= 4) {
                            int lsb = leaf & 0x0F;
                            f[dst] = G_POW43[16 + lsb - 16 * (bsCache >>> 31)] * one;
                            int n = lsb != 0 ? 1 : 0; // FLUSH_BITS(lsb ? 1 : 0)
                            bsCache <<= n;
                            bsSh += n;
                        }
                        while (bsSh >= 0) { // CHECK_BITS
                            bsCache |= rd(buf, bsNextPtr++) << bsSh;
                            bsSh -= 8;
                        }
                    } while (--pairsToDecode != 0);
                } while ((bigValCnt -= np) > 0 && --sfbCnt >= 0);
            }
        }

        for (np = 1 - bigValCnt;; dst += 4) {
            final int[] codebookCount1 = (grInfo.count1Table != 0) ? TAB33 : TAB32;
            int leaf = codebookCount1[bsCache >>> (32 - 4)]; // PEEK_BITS(4)
            if ((leaf & 8) == 0) {
                leaf = codebookCount1[(leaf >> 3) + ((bsCache << 4) >>> (32 - (leaf & 3)))];
            }
            bsCache <<= leaf & 7; // FLUSH_BITS(leaf & 7)
            bsSh += leaf & 7;
            if ((bsNextPtr - base) * 8 - 24 + bsSh > layer3grLimit) { // BSPOS > layer3gr_limit
                break;
            }
            if (--np == 0) { // RELOAD_SCALEFACTOR
                np = sfbTab[sfb++] / 2;
                if (np == 0) {
                    break;
                }
                one = f[scf++];
            }
            if ((leaf & (128 >> 0)) != 0) { // DEQ_COUNT1(0)
                f[dst + 0] = (bsCache < 0) ? -one : one;
                bsCache <<= 1;
                bsSh += 1;
            }
            if ((leaf & (128 >> 1)) != 0) { // DEQ_COUNT1(1)
                f[dst + 1] = (bsCache < 0) ? -one : one;
                bsCache <<= 1;
                bsSh += 1;
            }
            if (--np == 0) { // RELOAD_SCALEFACTOR
                np = sfbTab[sfb++] / 2;
                if (np == 0) {
                    break;
                }
                one = f[scf++];
            }
            if ((leaf & (128 >> 2)) != 0) { // DEQ_COUNT1(2)
                f[dst + 2] = (bsCache < 0) ? -one : one;
                bsCache <<= 1;
                bsSh += 1;
            }
            if ((leaf & (128 >> 3)) != 0) { // DEQ_COUNT1(3)
                f[dst + 3] = (bsCache < 0) ? -one : one;
                bsCache <<= 1;
                bsSh += 1;
            }
            while (bsSh >= 0) { // CHECK_BITS
                bsCache |= rd(buf, bsNextPtr++) << bsSh;
                bsSh -= 8;
            }
        }

        bs.pos = layer3grLimit;
    }

    // L3_midside_stereo
    private static void l3MidsideStereo(float[] f, int left, int n) {
        int i = 0;
        int right = left + 576;
        for (; i < n; i++) {
            float a = f[left + i];
            float b = f[right + i];
            f[left + i] = a + b;
            f[right + i] = a - b;
        }
    }

    // L3_intensity_stereo_band
    private static void l3IntensityStereoBand(float[] f, int left, int n, float kl, float kr) {
        int i;
        for (i = 0; i < n; i++) {
            f[left + i + 576] = f[left + i] * kr;
            f[left + i] = f[left + i] * kl;
        }
    }

    // L3_stereo_top_band
    private static void l3StereoTopBand(float[] f, int right, int[] sfb, int nbands, int[] maxBand) {
        int i, k;

        maxBand[0] = maxBand[1] = maxBand[2] = -1;

        for (i = 0; i < nbands; i++) {
            for (k = 0; k < sfb[i]; k += 2) {
                if (f[right + k] != 0 || f[right + k + 1] != 0) {
                    maxBand[i % 3] = i;
                    break;
                }
            }
            right += sfb[i];
        }
    }

    // L3_stereo_process
    private static void l3StereoProcess(float[] f, int left, int[] istPos, int ist, int[] sfb, byte[] hdr,
            int h, int[] maxBand, int mpeg2Sh) {
        final boolean mpeg1 = hdrTestMpeg1(hdr, h);
        int i, maxPos = mpeg1 ? 7 : 64;

        for (i = 0; sfb[i] != 0; i++) {
            int ipos = istPos[ist + i];
            if (i > maxBand[i % 3] && ipos < maxPos) {
                float kl, kr, s = hdrTestMsStereo(hdr, h) ? 1.41421356f : 1;
                if (mpeg1) {
                    kl = G_PAN[2 * ipos];
                    kr = G_PAN[2 * ipos + 1];
                } else {
                    kl = 1;
                    kr = l3LdexpQ2(1, (ipos + 1) >> 1 << mpeg2Sh);
                    if ((ipos & 1) != 0) {
                        kl = kr;
                        kr = 1;
                    }
                }
                l3IntensityStereoBand(f, left, sfb[i], kl * s, kr * s);
            } else if (hdrTestMsStereo(hdr, h)) {
                l3MidsideStereo(f, left, sfb[i]);
            }
            left += sfb[i];
        }
    }

    // L3_intensity_stereo
    // ist_pos is istPos[ist ..]; gr is grInfo[gr], so gr[1] of the C code is grInfo[gr + 1].
    private void l3IntensityStereo(int left, int ist, int gr) {
        final GrInfo g = grInfo[gr];
        final int[] maxBand = this.maxBand;
        int nSfb = g.nLongSfb + g.nShortSfb;
        int i, maxBlocks = g.nShortSfb != 0 ? 3 : 1;

        l3StereoTopBand(scratch, left + 576, g.sfbtab, nSfb, maxBand);
        if (g.nLongSfb != 0) {
            maxBand[0] = maxBand[1] = maxBand[2] = max(max(maxBand[0], maxBand[1]), maxBand[2]);
        }
        for (i = 0; i < maxBlocks; i++) {
            int defaultPos = hdrTestMpeg1(header, 0) ? 3 : 0;
            int itop = nSfb - maxBlocks + i;
            int prev = itop - maxBlocks;
            istPos[ist + itop] = maxBand[i] >= prev ? defaultPos : istPos[ist + prev];
        }
        l3StereoProcess(scratch, left, istPos, ist, g.sfbtab, header, 0, maxBand, grInfo[gr + 1].scalefacCompress & 1);
    }

    // L3_reorder
    // grbuf is scratch[grbuf ..], scratch (the C parameter) is scratch[dst0 ..], sfb is sfbTab[sfb ..].
    // For MPEG-2.5 at 8 kHz with mixed blocks the C code reads and writes 24 floats past the end of the
    // granule (g_scf_mixed[1] describes 528 short-block lines, but they start at line 72 instead of 48).
    // Keeping grbuf, scf and syn in one array, as in the C struct, makes those accesses land on the same
    // data as in C instead of failing.
    private static void l3Reorder(float[] f, int grbuf, int dst0, int[] sfbTab, int sfb) {
        int i, len;
        int src = grbuf, dst = dst0;

        for (; 0 != (len = sfbTab[sfb]); sfb += 3, src += 2 * len) {
            for (i = 0; i < len; i++, src++) {
                f[dst++] = f[src + 0 * len];
                f[dst++] = f[src + 1 * len];
                f[dst++] = f[src + 2 * len];
            }
        }
        System.arraycopy(f, dst0, f, grbuf, dst - dst0);
    }

    // L3_antialias
    private static void l3Antialias(float[] f, int grbuf, int nbands) {
        for (; nbands > 0; nbands--, grbuf += 18) {
            int i = 0;
            for (; i < 8; i++) {
                float u = f[grbuf + 18 + i];
                float d = f[grbuf + 17 - i];
                f[grbuf + 18 + i] = u * G_AA[0][i] - d * G_AA[1][i];
                f[grbuf + 17 - i] = u * G_AA[1][i] + d * G_AA[0][i];
            }
        }
    }

    // L3_dct3_9
    private static void l3Dct39(float[] y) {
        float s0, s1, s2, s3, s4, s5, s6, s7, s8, t0, t2, t4;

        s0 = y[0];
        s2 = y[2];
        s4 = y[4];
        s6 = y[6];
        s8 = y[8];
        t0 = s0 + s6 * 0.5f;
        s0 -= s6;
        t4 = (s4 + s2) * 0.93969262f;
        t2 = (s8 + s2) * 0.76604444f;
        s6 = (s4 - s8) * 0.17364818f;
        s4 += s8 - s2;

        s2 = s0 - s4 * 0.5f;
        y[4] = s4 + s0;
        s8 = t0 - t2 + s6;
        s0 = t0 - t4 + t2;
        s4 = t0 + t4 - s6;

        s1 = y[1];
        s3 = y[3];
        s5 = y[5];
        s7 = y[7];

        s3 *= 0.86602540f;
        t0 = (s5 + s1) * 0.98480775f;
        t4 = (s5 - s7) * 0.34202014f;
        t2 = (s1 + s7) * 0.64278761f;
        s1 = (s1 - s5 - s7) * 0.86602540f;

        s5 = t0 - s3 - t2;
        s7 = t4 - s3 - t0;
        s3 = t4 + s3 - t2;

        y[0] = s4 - s7;
        y[1] = s2 + s1;
        y[2] = s0 - s3;
        y[3] = s8 + s5;
        y[5] = s8 - s5;
        y[6] = s0 + s3;
        y[7] = s2 - s1;
        y[8] = s4 + s7;
    }

    // L3_imdct36
    // grbuf is scratch[grbuf ..], overlap is mdctOverlap[overlap ..].
    private void l3Imdct36(int grbuf, int overlap, float[] window, int nbands) {
        final float[] f = scratch;
        final float[] ov = mdctOverlap;
        final float[] co = this.co;
        final float[] si = this.si;
        int i, j;

        for (j = 0; j < nbands; j++, grbuf += 18, overlap += 9) {
            co[0] = -f[grbuf + 0];
            si[0] = f[grbuf + 17];
            for (i = 0; i < 4; i++) {
                si[8 - 2 * i] = f[grbuf + 4 * i + 1] - f[grbuf + 4 * i + 2];
                co[1 + 2 * i] = f[grbuf + 4 * i + 1] + f[grbuf + 4 * i + 2];
                si[7 - 2 * i] = f[grbuf + 4 * i + 4] - f[grbuf + 4 * i + 3];
                co[2 + 2 * i] = -(f[grbuf + 4 * i + 3] + f[grbuf + 4 * i + 4]);
            }
            l3Dct39(co);
            l3Dct39(si);

            si[1] = -si[1];
            si[3] = -si[3];
            si[5] = -si[5];
            si[7] = -si[7];

            i = 0;

            for (; i < 9; i++) {
                float ovl = ov[overlap + i];
                float sum = co[i] * G_TWID9[9 + i] + si[i] * G_TWID9[0 + i];
                ov[overlap + i] = co[i] * G_TWID9[0 + i] - si[i] * G_TWID9[9 + i];
                f[grbuf + i] = ovl * window[0 + i] - sum * window[9 + i];
                f[grbuf + 17 - i] = ovl * window[9 + i] + sum * window[0 + i];
            }
        }
    }

    // L3_idct3
    private static void l3Idct3(float x0, float x1, float x2, float[] dst) {
        float m1 = x1 * 0.86602540f;
        float a1 = x0 - x2 * 0.5f;
        dst[1] = x0 + x2;
        dst[0] = a1 + m1;
        dst[2] = a1 - m1;
    }

    // L3_imdct12
    // x is tmp[x ..], dst is dstArr[dst ..], overlap is mdctOverlap[overlap ..].
    private void l3Imdct12(float[] tmp, int x, float[] dstArr, int dst, int overlap) {
        final float[] ov = mdctOverlap;
        final float[] co = this.co;
        final float[] si = this.si;
        int i;

        l3Idct3(-tmp[x + 0], tmp[x + 6] + tmp[x + 3], tmp[x + 12] + tmp[x + 9], co);
        l3Idct3(tmp[x + 15], tmp[x + 12] - tmp[x + 9], tmp[x + 6] - tmp[x + 3], si);
        si[1] = -si[1];

        for (i = 0; i < 3; i++) {
            float ovl = ov[overlap + i];
            float sum = co[i] * G_TWID3[3 + i] + si[i] * G_TWID3[0 + i];
            ov[overlap + i] = co[i] * G_TWID3[0 + i] - si[i] * G_TWID3[3 + i];
            dstArr[dst + i] = ovl * G_TWID3[2 - i] - sum * G_TWID3[5 - i];
            dstArr[dst + 5 - i] = ovl * G_TWID3[5 - i] + sum * G_TWID3[2 - i];
        }
    }

    // L3_imdct_short
    private void l3ImdctShort(int grbuf, int overlap, int nbands) {
        final float[] f = scratch;
        final float[] ov = mdctOverlap;
        final float[] tmp = this.tmp;
        for (; nbands > 0; nbands--, overlap += 9, grbuf += 18) {
            System.arraycopy(f, grbuf, tmp, 0, 18);
            System.arraycopy(ov, overlap, f, grbuf, 6);
            l3Imdct12(tmp, 0, f, grbuf + 6, overlap + 6);
            l3Imdct12(tmp, 1, f, grbuf + 12, overlap + 6);
            l3Imdct12(tmp, 2, ov, overlap, overlap + 6);
        }
    }

    // L3_change_sign
    private static void l3ChangeSign(float[] f, int grbuf) {
        int b, i;
        for (b = 0, grbuf += 18; b < 32; b += 2, grbuf += 36) {
            for (i = 1; i < 18; i += 2) {
                f[grbuf + i] = -f[grbuf + i];
            }
        }
    }

    // L3_imdct_gr
    private void l3ImdctGr(int grbuf, int overlap, int blockType, int nLongBands) {
        if (nLongBands != 0) {
            l3Imdct36(grbuf, overlap, G_MDCT_WINDOW[0], nLongBands);
            grbuf += 18 * nLongBands;
            overlap += 9 * nLongBands;
        }
        if (blockType == SHORT_BLOCK_TYPE) {
            l3ImdctShort(grbuf, overlap, 32 - nLongBands);
        } else {
            l3Imdct36(grbuf, overlap, G_MDCT_WINDOW[blockType == STOP_BLOCK_TYPE ? 1 : 0], 32 - nLongBands);
        }
    }

    // L3_save_reservoir
    // bs.pos and bs.limit are never negative, so the unsigned divisions of the C code are plain divisions.
    private void l3SaveReservoir() {
        final BitStream bs = scratchBs;
        int pos = (bs.pos + 7) / 8;
        int remains = bs.limit / 8 - pos;
        if (remains > MAX_BITRESERVOIR_BYTES) {
            pos += remains - MAX_BITRESERVOIR_BYTES;
            remains = MAX_BITRESERVOIR_BYTES;
        }
        if (remains > 0) {
            System.arraycopy(maindata, pos, reservBuf, 0, remains);
        }
        reserv = remains;
    }

    // L3_restore_reservoir
    private boolean l3RestoreReservoir(BitStream bs, int mainDataBegin) {
        int frameBytes = (bs.limit - bs.pos) / 8;
        int bytesHave = min(reserv, mainDataBegin);
        System.arraycopy(reservBuf, max(0, reserv - mainDataBegin), maindata, 0, min(reserv, mainDataBegin));
        System.arraycopy(bs.buf, bs.base + bs.pos / 8, maindata, bytesHave, frameBytes);
        scratchBs.init(maindata, 0, bytesHave + frameBytes);
        return reserv >= mainDataBegin;
    }

    // L3_decode
    // gr is the index in grInfo of gr_info[0] of the C code.
    private void l3Decode(int gr, int nch) {
        final float[] f = scratch;
        final BitStream bs = scratchBs;
        int ch;

        for (ch = 0; ch < nch; ch++) {
            int layer3grLimit = bs.pos + grInfo[gr + ch].part23Length;
            l3DecodeScalefactors(header, 0, IST_POS_LEN * ch, bs, grInfo[gr + ch], ch);
            l3Huffman(GRBUF + 576 * ch, bs, grInfo[gr + ch], layer3grLimit);
        }

        if (hdrTestIStereo(header, 0)) {
            l3IntensityStereo(GRBUF, IST_POS_LEN, gr);
        } else if (hdrIsMsStereo(header, 0)) {
            l3MidsideStereo(f, GRBUF, 576);
        }

        for (ch = 0; ch < nch; ch++, gr++) {
            final GrInfo g = grInfo[gr];
            int aaBands = 31;
            int nLongBands = (g.mixedBlockFlag != 0 ? 2 : 0) << (hdrGetMySampleRate(header, 0) == 2 ? 1 : 0);

            if (g.nShortSfb != 0) {
                aaBands = nLongBands - 1;
                l3Reorder(f, GRBUF + 576 * ch + nLongBands * 18, SYN, g.sfbtab, g.nLongSfb);
            }

            l3Antialias(f, GRBUF + 576 * ch, aaBands);
            l3ImdctGr(GRBUF + 576 * ch, 9 * 32 * ch, g.blockType, nLongBands);
            l3ChangeSign(f, GRBUF + 576 * ch);
        }
    }

    // mp3d_DCT_II
    private void mp3dDctII(int grbuf, int n) {
        final float[] f = scratch;
        final float[] t = dctT; // float t[4][8]: t[a][b] is t[a * 8 + b]
        int i, k = 0;
        for (; k < n; k++) {
            int x, y = grbuf + k;

            for (x = 0, i = 0; i < 8; i++, x++) {
                float x0 = f[y + i * 18];
                float x1 = f[y + (15 - i) * 18];
                float x2 = f[y + (16 + i) * 18];
                float x3 = f[y + (31 - i) * 18];
                float t0 = x0 + x3;
                float t1 = x1 + x2;
                float t2 = (x1 - x2) * G_SEC[3 * i + 0];
                float t3 = (x0 - x3) * G_SEC[3 * i + 1];
                t[x + 0] = t0 + t1;
                t[x + 8] = (t0 - t1) * G_SEC[3 * i + 2];
                t[x + 16] = t3 + t2;
                t[x + 24] = (t3 - t2) * G_SEC[3 * i + 2];
            }
            for (x = 0, i = 0; i < 4; i++, x += 8) {
                float x0 = t[x + 0], x1 = t[x + 1], x2 = t[x + 2], x3 = t[x + 3];
                float x4 = t[x + 4], x5 = t[x + 5], x6 = t[x + 6], x7 = t[x + 7], xt;
                xt = x0 - x7;
                x0 += x7;
                x7 = x1 - x6;
                x1 += x6;
                x6 = x2 - x5;
                x2 += x5;
                x5 = x3 - x4;
                x3 += x4;
                x4 = x0 - x3;
                x0 += x3;
                x3 = x1 - x2;
                x1 += x2;
                t[x + 0] = x0 + x1;
                t[x + 4] = (x0 - x1) * 0.70710677f;
                x5 = x5 + x6;
                x6 = (x6 + x7) * 0.70710677f;
                x7 = x7 + xt;
                x3 = (x3 + x4) * 0.70710677f;
                x5 -= x7 * 0.198912367f; /* rotate by PI/8 */
                x7 += x5 * 0.382683432f;
                x5 -= x7 * 0.198912367f;
                x0 = xt - x6;
                xt += x6;
                t[x + 1] = (xt + x7) * 0.50979561f;
                t[x + 2] = (x4 + x3) * 0.54119611f;
                t[x + 3] = (x0 - x5) * 0.60134488f;
                t[x + 5] = (x0 + x5) * 0.89997619f;
                t[x + 6] = (x4 - x3) * 1.30656302f;
                t[x + 7] = (xt - x7) * 2.56291556f;
            }
            for (i = 0; i < 7; i++, y += 4 * 18) {
                f[y + 0 * 18] = t[0 * 8 + i];
                f[y + 1 * 18] = t[2 * 8 + i] + t[3 * 8 + i] + t[3 * 8 + i + 1];
                f[y + 2 * 18] = t[1 * 8 + i] + t[1 * 8 + i + 1];
                f[y + 3 * 18] = t[2 * 8 + i + 1] + t[3 * 8 + i] + t[3 * 8 + i + 1];
            }
            f[y + 0 * 18] = t[0 * 8 + 7];
            f[y + 1 * 18] = t[2 * 8 + 7] + t[3 * 8 + 7];
            f[y + 2 * 18] = t[1 * 8 + 7];
            f[y + 3 * 18] = t[3 * 8 + 7];
        }
    }

    // mp3d_scale_pcm (MINIMP3_FLOAT_OUTPUT)
    private static float mp3dScalePcm(float sample) {
        return sample * (1.f / 32768.f);
    }

    // mp3d_synth_pair
    // pcm is pcm[p ..], z is f[z ..].
    private static void mp3dSynthPair(float[] pcm, int p, int nch, float[] f, int z) {
        float a;
        a = (f[z + 14 * 64] - f[z + 0]) * 29;
        a += (f[z + 1 * 64] + f[z + 13 * 64]) * 213;
        a += (f[z + 12 * 64] - f[z + 2 * 64]) * 459;
        a += (f[z + 3 * 64] + f[z + 11 * 64]) * 2037;
        a += (f[z + 10 * 64] - f[z + 4 * 64]) * 5153;
        a += (f[z + 5 * 64] + f[z + 9 * 64]) * 6574;
        a += (f[z + 8 * 64] - f[z + 6 * 64]) * 37489;
        a += f[z + 7 * 64] * 75038;
        pcm[p + 0] = mp3dScalePcm(a);

        z += 2;
        a = f[z + 14 * 64] * 104;
        a += f[z + 12 * 64] * 1567;
        a += f[z + 10 * 64] * 9727;
        a += f[z + 8 * 64] * 64019;
        a += f[z + 6 * 64] * -9975;
        a += f[z + 4 * 64] * -45;
        a += f[z + 2 * 64] * 146;
        a += f[z + 0 * 64] * -5;
        pcm[p + 16 * nch] = mp3dScalePcm(a);
    }

    // LOAD(k) followed by S0(k) of mp3d_synth. Returns w advanced past the two window coefficients.
    private static int synthS0(float[] f, int zlin, int i, int k, int w, float[] a, float[] b) {
        float w0 = G_WIN[w++];
        float w1 = G_WIN[w++];
        int vz = zlin + 4 * i - k * 64;
        int vy = zlin + 4 * i - (15 - k) * 64;
        for (int j = 0; j < 4; j++) {
            b[j] = f[vz + j] * w1 + f[vy + j] * w0;
            a[j] = f[vz + j] * w0 - f[vy + j] * w1;
        }
        return w;
    }

    // LOAD(k) followed by S1(k) of mp3d_synth.
    private static int synthS1(float[] f, int zlin, int i, int k, int w, float[] a, float[] b) {
        float w0 = G_WIN[w++];
        float w1 = G_WIN[w++];
        int vz = zlin + 4 * i - k * 64;
        int vy = zlin + 4 * i - (15 - k) * 64;
        for (int j = 0; j < 4; j++) {
            b[j] += f[vz + j] * w1 + f[vy + j] * w0;
            a[j] += f[vz + j] * w0 - f[vy + j] * w1;
        }
        return w;
    }

    // LOAD(k) followed by S2(k) of mp3d_synth.
    private static int synthS2(float[] f, int zlin, int i, int k, int w, float[] a, float[] b) {
        float w0 = G_WIN[w++];
        float w1 = G_WIN[w++];
        int vz = zlin + 4 * i - k * 64;
        int vy = zlin + 4 * i - (15 - k) * 64;
        for (int j = 0; j < 4; j++) {
            b[j] += f[vz + j] * w1 + f[vy + j] * w0;
            a[j] += f[vy + j] * w1 - f[vz + j] * w0;
        }
        return w;
    }

    // mp3d_synth
    // xl is scratch[xl ..], dstl is pcm[dstl ..], lins is scratch[lins ..].
    private void mp3dSynth(int xl, float[] pcm, int dstl, int nch, int lins) {
        final float[] f = scratch;
        final float[] a = synA;
        final float[] b = synB;
        int i;
        int xr = xl + 576 * (nch - 1);
        int dstr = dstl + (nch - 1);

        int zlin = lins + 15 * 64;
        int w = 0;

        f[zlin + 4 * 15] = f[xl + 18 * 16];
        f[zlin + 4 * 15 + 1] = f[xr + 18 * 16];
        f[zlin + 4 * 15 + 2] = f[xl + 0];
        f[zlin + 4 * 15 + 3] = f[xr + 0];

        f[zlin + 4 * 31] = f[xl + 1 + 18 * 16];
        f[zlin + 4 * 31 + 1] = f[xr + 1 + 18 * 16];
        f[zlin + 4 * 31 + 2] = f[xl + 1];
        f[zlin + 4 * 31 + 3] = f[xr + 1];

        mp3dSynthPair(pcm, dstr, nch, f, lins + 4 * 15 + 1);
        mp3dSynthPair(pcm, dstr + 32 * nch, nch, f, lins + 4 * 15 + 64 + 1);
        mp3dSynthPair(pcm, dstl, nch, f, lins + 4 * 15);
        mp3dSynthPair(pcm, dstl + 32 * nch, nch, f, lins + 4 * 15 + 64);

        for (i = 14; i >= 0; i--) {
            f[zlin + 4 * i] = f[xl + 18 * (31 - i)];
            f[zlin + 4 * i + 1] = f[xr + 18 * (31 - i)];
            f[zlin + 4 * i + 2] = f[xl + 1 + 18 * (31 - i)];
            f[zlin + 4 * i + 3] = f[xr + 1 + 18 * (31 - i)];
            f[zlin + 4 * (i + 16)] = f[xl + 1 + 18 * (1 + i)];
            f[zlin + 4 * (i + 16) + 1] = f[xr + 1 + 18 * (1 + i)];
            f[zlin + 4 * (i - 16) + 2] = f[xl + 18 * (1 + i)];
            f[zlin + 4 * (i - 16) + 3] = f[xr + 18 * (1 + i)];

            // S0(0) S2(1) S1(2) S2(3) S1(4) S2(5) S1(6) S2(7)
            w = synthS0(f, zlin, i, 0, w, a, b);
            w = synthS2(f, zlin, i, 1, w, a, b);
            w = synthS1(f, zlin, i, 2, w, a, b);
            w = synthS2(f, zlin, i, 3, w, a, b);
            w = synthS1(f, zlin, i, 4, w, a, b);
            w = synthS2(f, zlin, i, 5, w, a, b);
            w = synthS1(f, zlin, i, 6, w, a, b);
            w = synthS2(f, zlin, i, 7, w, a, b);

            pcm[dstr + (15 - i) * nch] = mp3dScalePcm(a[1]);
            pcm[dstr + (17 + i) * nch] = mp3dScalePcm(b[1]);
            pcm[dstl + (15 - i) * nch] = mp3dScalePcm(a[0]);
            pcm[dstl + (17 + i) * nch] = mp3dScalePcm(b[0]);
            pcm[dstr + (47 - i) * nch] = mp3dScalePcm(a[3]);
            pcm[dstr + (49 + i) * nch] = mp3dScalePcm(b[3]);
            pcm[dstl + (47 - i) * nch] = mp3dScalePcm(a[2]);
            pcm[dstl + (49 + i) * nch] = mp3dScalePcm(b[2]);
        }
    }

    // mp3d_synth_granule
    // grbuf is scratch[grbuf ..], pcm is pcm[pcmOff ..], lins is scratch[lins ..].
    private void mp3dSynthGranule(int grbuf, int nbands, int nch, float[] pcm, int pcmOff, int lins) {
        final float[] f = scratch;
        int i;
        for (i = 0; i < nch; i++) {
            mp3dDctII(grbuf + 576 * i, nbands);
        }

        System.arraycopy(qmfState, 0, f, lins, 15 * 64);
        /* zero workspace so no uninitialized floats reach qmf_state */
        Arrays.fill(f, lins + 15 * 64, lins + 15 * 64 + 64 * nbands, 0f);

        for (i = 0; i < nbands; i += 2) {
            mp3dSynth(grbuf + i, pcm, pcmOff + 32 * nch * i, nch, lins + i * 64);
        }
        // MINIMP3_NONSTANDARD_BUT_LOGICAL is not defined
        if (nch == 1) {
            for (i = 0; i < 15 * 64; i += 2) {
                qmfState[i] = f[lins + nbands * 64 + i];
            }
        } else {
            System.arraycopy(f, lins + nbands * 64, qmfState, 0, 15 * 64);
        }
    }

    // hdr_is_null
    private static boolean hdrIsNull(byte[] h, int o) {
        return h[o] == 0 && h[o + 1] == 0 && h[o + 2] == 0 && h[o + 3] == 0;
    }

    // hdr_is_id3v1
    /* same signature mp3dec_skip_id3v1() uses; pinning the size to a whole 128-byte tag
       keeps a random "TAG" in audio data from being mistaken for end-of-stream */
    private static boolean hdrIsId3v1(byte[] h, int o, int bytesLeft) {
        return 128 == bytesLeft && h[o] == 'T' && h[o + 1] == 'A' && h[o + 2] == 'G';
    }

    // hdr_is_null_or_tag
    private static boolean hdrIsNullOrTag(byte[] h, int o, int bytesLeft) {
        return hdrIsNull(h, o) || hdrIsId3v1(h, o, bytesLeft);
    }

    // mp3d_match_frame
    // hdr is buf[hdr ..].
    private static boolean mp3dMatchFrame(byte[] buf, int hdr, int mp3Bytes, int frameBytes) {
        int i, nmatch;
        for (i = 0, nmatch = 0; nmatch < MAX_FRAME_SYNC_MATCHES; nmatch++) {
            /* advance to the start of the next frame */
            i += hdrFrameBytes(buf, hdr + i, frameBytes) + hdrPadding(buf, hdr + i);
            /* end of buffer, or trailing null/ID3v1 data: succeed if we already
               matched at least one header */
            if (i + HDR_SIZE > mp3Bytes) {
                return nmatch > 0;
            }
            if (hdrIsNullOrTag(buf, hdr + i, mp3Bytes - i)) {
                return nmatch > 0;
            }
            /* a following header differs: the first header was not a real sync */
            if (!hdrCompare(buf, hdr, buf, hdr + i)) {
                return false;
            }
        }
        return true;
    }

    // mp3d_find_frame
    // mp3 is buf[mp3 ..]. The two pointer arguments become ffAndFb: ffAndFb[0] is *free_format_bytes (in
    // and out) and ffAndFb[1] is *ptr_frame_bytes (out). Package-private for the tests.
    // Two sums of the C code are rearranged so that they cannot overflow (see NOTES.md); for every input
    // on which the C sums do not overflow the result is the same.
    static int mp3dFindFrame(byte[] buf, int mp3, int mp3Bytes, int[] ffAndFb) {
        int i, k;
        for (i = 0; i < mp3Bytes - HDR_SIZE; i++, mp3++) {
            if (hdrValid(buf, mp3)) {
                int frameBytes = hdrFrameBytes(buf, mp3, ffAndFb[0]);
                int frameAndPadding = frameBytes + hdrPadding(buf, mp3);

                /* free-format frame: the header carries no length, so look ahead
                   for two more matching headers to derive the frame size */
                // C: i + 2*k < mp3_bytes - HDR_SIZE
                for (k = HDR_SIZE; frameBytes == 0 && k < MAX_FREE_FORMAT_FRAME_SIZE
                        && 2 * k < mp3Bytes - HDR_SIZE - i; k++) {
                    if (hdrCompare(buf, mp3, buf, mp3 + k)) {
                        int fb = k - hdrPadding(buf, mp3);
                        int nextfb = fb + hdrPadding(buf, mp3 + k);
                        if (i + k + nextfb + HDR_SIZE > mp3Bytes || !hdrCompare(buf, mp3, buf, mp3 + k + nextfb)) {
                            continue;
                        }
                        frameAndPadding = k;
                        frameBytes = fb;
                        ffAndFb[0] = fb;
                    }
                }
                // C: i + frame_and_padding <= mp3_bytes
                if ((frameBytes != 0 && frameAndPadding <= mp3Bytes - i
                        && mp3dMatchFrame(buf, mp3, mp3Bytes - i, frameBytes))
                        || (i == 0 && frameAndPadding == mp3Bytes)) {
                    ffAndFb[1] = frameAndPadding;
                    return i;
                }
                ffAndFb[0] = 0;
            }
        }
        ffAndFb[1] = 0;
        return mp3Bytes;
    }

    // mp3dec_init
    private void mp3decInit() {
        header[0] = 0;
    }

    /**
     * Finds and decodes the next frame in {@code mp3[offset, offset + length)}: the equivalent of
     * {@code mp3dec_decode_frame}, with the same semantics.
     *
     * <ul>
     *   <li>{@code info.frameBytes == 0}: more data is needed (or the data is over).</li>
     *   <li>{@code info.frameBytes > 0} and the result is 0: data was skipped (an ID3 tag, invalid bytes) or
     *       the frame could not be decoded (for example because the bit reservoir it refers to is
     *       missing). Advance by {@code info.frameBytes} and call again.</li>
     *   <li>Otherwise the frame starts at {@code offset + info.frameOffset}; advance by
     *       {@code info.frameBytes} to reach the next one.</li>
     * </ul>
     *
     * <p>As in the original, only {@code info.frameBytes} is written when no frame header is found; the
     * other fields of {@code info} keep their previous values.
     *
     * <p>The samples are not clipped: values outside [-1, 1] are returned as they are, on the same scale
     * as the original with {@code MINIMP3_FLOAT_OUTPUT}.
     *
     * @param mp3    the data; it is only read
     * @param offset the index of the first byte to examine
     * @param length the number of bytes available from {@code offset}
     * @param pcm    where to write the samples, interleaved by channel (L, R, L, R...), at least
     *               {@link #MAX_SAMPLES_PER_FRAME} elements long; or {@code null} to only read the
     *               information of the next frame without decoding it, exactly as the original does with
     *               {@code pcm == NULL}
     * @param info   receives the information about the frame, as {@code mp3dec_frame_info_t}
     * @return the number of samples per channel written to {@code pcm}: 0, 576 or 1152. With {@code pcm}
     *         {@code null}, the number of samples per channel of the frame found, as in the original: 384 or
     *         1152 for Layer I and II frames too, although those are never decoded.
     * @throws NullPointerException     if {@code mp3} or {@code info} is {@code null}
     * @throws IllegalArgumentException if {@code offset} or {@code length} is negative or the range does not
     *                                  fit in {@code mp3}, or if {@code pcm} is shorter than
     *                                  {@link #MAX_SAMPLES_PER_FRAME}
     */
    public int decodeFrame(byte[] mp3, int offset, int length, float[] pcm, Mp3FrameInfo info) {
        Objects.requireNonNull(mp3, "mp3");
        Objects.requireNonNull(info, "info");
        if (offset < 0 || length < 0 || length > mp3.length - offset) {
            throw new IllegalArgumentException(
                    "offset " + offset + " and length " + length + " do not fit an array of " + mp3.length);
        }
        if (pcm != null && pcm.length < MAX_SAMPLES_PER_FRAME) {
            throw new IllegalArgumentException(
                    "pcm has " + pcm.length + " elements, at least " + MAX_SAMPLES_PER_FRAME + " are needed");
        }
        final int mp3Bytes = length;
        int i = 0, igr, frameSize = 0, success = 1;
        int hdr;
        final BitStream bsFrame = this.bsFrame;
        Arrays.fill(istPos, 0); /* avoid use-of-uninitialized-value (scfsi reuse) */

        if (mp3Bytes > 4 && (header[0] & 0xFF) == 0xff && hdrCompare(header, 0, mp3, offset)) {
            frameSize = hdrFrameBytes(mp3, offset, freeFormatBytes) + hdrPadding(mp3, offset);
            if (frameSize != mp3Bytes && (frameSize + HDR_SIZE > mp3Bytes
                    || !hdrCompare(mp3, offset, mp3, offset + frameSize))) {
                frameSize = 0;
            }
        }
        if (frameSize == 0) {
            // memset(dec, 0, sizeof(mp3dec_t))
            Arrays.fill(mdctOverlap, 0f);
            Arrays.fill(qmfState, 0f);
            reserv = 0;
            freeFormatBytes = 0;
            Arrays.fill(header, (byte) 0);
            Arrays.fill(reservBuf, (byte) 0);
            findFrameOut[0] = freeFormatBytes;
            i = mp3dFindFrame(mp3, offset, mp3Bytes, findFrameOut);
            freeFormatBytes = findFrameOut[0];
            frameSize = findFrameOut[1];
            // C: i + frame_size > mp3_bytes
            if (frameSize == 0 || frameSize > mp3Bytes - i) {
                info.frameBytes = i;
                return 0;
            }
        }

        hdr = offset + i;
        System.arraycopy(mp3, hdr, header, 0, HDR_SIZE);
        info.frameBytes = i + frameSize;
        info.frameOffset = i;
        info.channels = hdrIsMono(mp3, hdr) ? 1 : 2;
        info.hz = hdrSampleRateHz(mp3, hdr);
        info.layer = 4 - hdrGetLayer(mp3, hdr);
        info.bitrateKbps = hdrBitrateKbps(mp3, hdr);
        final int channels = info.channels;
        final int layer = info.layer;

        if (pcm == null) {
            return hdrFrameSamples(mp3, hdr);
        }

        bsFrame.init(mp3, hdr + HDR_SIZE, frameSize - HDR_SIZE);
        if (hdrIsCrc(mp3, hdr)) {
            bsFrame.getBits(16);
        }

        if (layer == 3) {
            int mainDataBegin = l3ReadSideInfo(bsFrame, grInfo, 0, mp3, hdr);
            if (mainDataBegin < 0 || bsFrame.pos > bsFrame.limit) {
                mp3decInit();
                return 0;
            }
            success = l3RestoreReservoir(bsFrame, mainDataBegin) ? 1 : 0;
            if (success != 0) {
                int pcmOff = 0;
                for (igr = 0; igr < (hdrTestMpeg1(mp3, hdr) ? 2 : 1); igr++, pcmOff += 576 * channels) {
                    Arrays.fill(scratch, GRBUF, GRBUF + 2 * 576, 0f);
                    l3Decode(igr * channels, channels);
                    mp3dSynthGranule(GRBUF, 18, channels, pcm, pcmOff, SYN);
                }
            }
            l3SaveReservoir();
        } else {
            // MINIMP3_ONLY_MP3
            return 0;
        }
        return success * hdrFrameSamples(header, 0);
    }

    /**
     * Converts float samples to 16-bit integers: the equivalent of {@code mp3dec_f32_to_s16}. Values
     * are scaled by 32768, rounded half away from zero and clipped to [-32768, 32767]; NaN becomes 0.
     *
     * @param in      the samples to convert
     * @param out     where to write the converted samples
     * @param samples how many samples to convert, from index 0 of both arrays
     * @throws NullPointerException     if {@code in} or {@code out} is {@code null}
     * @throws IllegalArgumentException if {@code samples} is negative or longer than either array
     */
    public static void toPcm16(float[] in, short[] out, int samples) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        if (samples < 0 || samples > in.length || samples > out.length) {
            throw new IllegalArgumentException("samples " + samples + " does not fit arrays of " + in.length
                    + " and " + out.length + " elements");
        }
        int i = 0;
        for (; i < samples; i++) {
            float sample = in[i] * 32768.0f;
            if (sample >= 32766.5) {
                out[i] = (short) 32767;
            } else if (sample <= -32767.5) {
                out[i] = (short) -32768;
            } else {
                short s = (short) (int) (sample + .5f);
                s -= (short) (s < 0 ? 1 : 0); /* away from zero, to be compliant */
                out[i] = s;
            }
        }
    }
}
