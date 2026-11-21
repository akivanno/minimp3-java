/*
 * minimp3-java: a Java port of minimp3 by lieff, https://github.com/lieff/minimp3
 * Translated from minimp3.h at commit 142823796ab8efa55e1bf11406028f3be77ecceb.
 *
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.radiomod.mp3;

/**
 * {@code L3_gr_info_t}: the side information of one granule of one channel.
 *
 * <p>The {@code uint8_t} and {@code uint16_t} fields become {@code int}; every value stored in them fits
 * the original C type, so no truncation is lost.
 */
final class GrInfo {
    /**
     * {@code const uint8_t *sfbtab}. In C it always points to the start of a row of
     * {@code g_scf_long}, {@code g_scf_short} or {@code g_scf_mixed}, so the row itself is enough.
     */
    int[] sfbtab;
    int part23Length;
    int bigValues;
    int scalefacCompress;
    int globalGain;
    int blockType;
    int mixedBlockFlag;
    int nLongSfb;
    int nShortSfb;
    final int[] tableSelect = new int[3];
    final int[] regionCount = new int[3];
    final int[] subblockGain = new int[3];
    int preflag;
    int scalefacScale;
    int count1Table;
    int scfsi;
}
