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
 * {@code bs_t}: a big-endian bit reader over a byte array.
 *
 * <p>The C pointer {@code buf} becomes the pair ({@link #buf}, {@link #base}): bit position {@code pos}
 * refers to {@code buf[base + pos / 8]}.
 */
final class BitStream {
    /** {@code const uint8_t *buf}: the array that holds the data. */
    byte[] buf;
    /** Index in {@link #buf} of the byte the C pointer {@code buf} points to. */
    int base;
    /** {@code int pos}: the next bit to read. */
    int pos;
    /** {@code int limit}: the number of readable bits. */
    int limit;

    // bs_init
    void init(byte[] data, int offset, int bytes) {
        buf = data;
        base = offset;
        pos = 0;
        limit = bytes * 8;
    }

    // get_bits
    // Reads only bytes below base + limit / 8: the position is checked against the limit before
    // the first read, exactly as in C.
    int getBits(int n) {
        int next, cache = 0, s = pos & 7;
        int shl = n + s;
        int p = base + (pos >> 3);
        if ((pos += n) > limit) {
            return 0;
        }
        next = buf[p++] & (255 >> s);
        while ((shl -= 8) > 0) {
            cache |= next << shl;
            next = buf[p++] & 0xFF;
        }
        return cache | (next >>> -shl);
    }
}
