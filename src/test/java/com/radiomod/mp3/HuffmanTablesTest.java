/*
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.radiomod.mp3;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The Huffman lookups of {@code L3_huffman} index the tables with bits read from the stream. These
 * tests walk every path of every table for every possible bit sequence and check that the indices stay
 * inside the tables, so that no input can make the decoder read outside them.
 */
class HuffmanTablesTest {
    private int maxIndex;
    private int maxBits;

    /** Follows the codebook from an entry point, trying every value of the next w bits. */
    private void walk(int codebook, int offset, int w, int bits, int depth) {
        assertTrue(depth < 32, "the tree is too deep");
        assertTrue(w >= 1 && w <= 7, "window of " + w + " bits");
        for (int v = 0; v < (1 << w); v++) {
            int idx = codebook + v + offset;
            assertTrue(idx >= 0 && idx < Tables.TABS.length, "index " + idx + " outside tabs");
            maxIndex = Math.max(maxIndex, idx);
            int leaf = Tables.TABS[idx];
            if (leaf < 0) {
                walk(codebook, -(leaf >> 3), leaf & 7, bits + w, depth + 1);
            } else {
                assertTrue((leaf >> 8) <= w, "code longer than the bits peeked");
                maxBits = Math.max(maxBits, bits + (leaf >> 8));
            }
        }
    }

    @Test
    void bigValueTablesStayInBounds() {
        for (int tab = 0; tab < 32; tab++) {
            walk(Tables.TABINDEX[tab], 0, 5, 0, 0);
        }
        assertTrue(maxIndex < Tables.TABS.length);
        assertTrue(maxBits <= 19, "longest code: " + maxBits + " bits");
    }

    @Test
    void count1TablesStayInBounds() {
        for (int[] cb : new int[][] {Tables.TAB32, Tables.TAB33}) {
            for (int peek = 0; peek < 16; peek++) {
                int leaf = cb[peek];
                if ((leaf & 8) == 0) {
                    int n = leaf & 3;
                    assertTrue(n != 0, "second lookup of 0 bits");
                    for (int v = 0; v < (1 << n); v++) {
                        int idx = (leaf >> 3) + v;
                        assertTrue(idx < cb.length, "index " + idx + " outside the count1 table");
                        assertTrue((cb[idx] & 7) != 0);
                    }
                } else {
                    assertTrue((leaf & 7) != 0);
                }
            }
        }
    }

    @Test
    void escapeValuesStayInsidePow43() {
        // the largest value of L3_pow_43 is 15 + (2^13 - 1) (linbits = 13)
        for (int x = 0; x <= 15 + 8191; x++) {
            int y = x;
            if (y >= 129) {
                if (y < 1024) {
                    y <<= 3;
                }
                int sign = 2 * y & 64;
                int idx = 16 + ((y + sign) >> 6);
                assertTrue(idx < Tables.G_POW43.length, "g_pow43 index " + idx + " for " + x);
            }
        }
    }
}
