/*
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.radiomod.mp3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Every table of {@link Tables} has the values of minimp3.h at the pinned commit (section 6: tables are
 * copied value by value). The C source is parsed from the minimp3 checkout of the test data.
 */
class TablesTest {
    private static String source() {
        return new String(TestData.read(TestData.minimp3().resolve("minimp3.h")), StandardCharsets.UTF_8);
    }

    /** The initializer of a static const table, as nested lists of literal tokens. */
    private static List<Object> table(String src, String name) {
        Matcher m = Pattern.compile("static const \\w+ " + Pattern.quote(name) + "((?:\\[[^\\]]*\\])+)\\s*=\\s*\\{")
                .matcher(src);
        assertTrue(m.find(), name + " not found in minimp3.h");
        int i = m.end() - 1;
        List<List<Object>> stack = new ArrayList<>();
        stack.add(new ArrayList<>());
        StringBuilder tok = new StringBuilder();
        for (;; i++) {
            char c = src.charAt(i);
            if (c == '{' || c == '}' || c == ',' || Character.isWhitespace(c)) {
                if (tok.length() > 0) {
                    stack.get(stack.size() - 1).add(tok.toString());
                    tok.setLength(0);
                }
                if (c == '{') {
                    stack.add(new ArrayList<>());
                } else if (c == '}') {
                    List<Object> done = stack.remove(stack.size() - 1);
                    stack.get(stack.size() - 1).add(done);
                    if (stack.size() == 1) {
                        break;
                    }
                }
            } else {
                tok.append(c);
            }
        }
        @SuppressWarnings("unchecked")
        List<Object> t = (List<Object>) stack.get(0).get(0);
        return t;
    }

    private static double value(String token) {
        String t = token.endsWith("f") ? token.substring(0, token.length() - 1) : token;
        return t.contains(".") || t.contains("e") ? Float.parseFloat(t) : Integer.parseInt(t);
    }

    /** Compares a Java table with the C initializer; missing C elements are 0, as in C. */
    private static void compare(String name, Object table, List<Object> c) {
        Object[] rows = switch (table) {
            case int[][][] a -> a;
            case int[][] a -> a;
            case float[][] a -> a;
            default -> null;
        };
        if (rows != null) {
            assertTrue(rows.length >= c.size(), name + ": the Java table is shorter than the C one");
            for (int i = 0; i < rows.length; i++) {
                @SuppressWarnings("unchecked")
                List<Object> sub = i < c.size() ? (List<Object>) c.get(i) : List.of();
                compare(name + "[" + i + "]", rows[i], sub);
            }
            return;
        }
        double[] values = switch (table) {
            case int[] a -> Arrays.stream(a).asDoubleStream().toArray();
            case short[] a -> {
                double[] d = new double[a.length];
                for (int i = 0; i < a.length; i++) {
                    d[i] = a[i];
                }
                yield d;
            }
            case float[] a -> {
                double[] d = new double[a.length];
                for (int i = 0; i < a.length; i++) {
                    d[i] = a[i];
                }
                yield d;
            }
            default -> throw new AssertionError(name + ": unexpected type " + table.getClass());
        };
        boolean isFloat = table instanceof float[];
        assertTrue(values.length >= c.size(), name + ": the Java table is shorter than the C one");
        for (int i = 0; i < values.length; i++) {
            double expected = i < c.size() ? value((String) c.get(i)) : 0;
            if (isFloat) {
                assertEquals(Float.floatToIntBits((float) expected), Float.floatToIntBits((float) values[i]),
                        name + "[" + i + "]");
            } else if (expected != values[i]) {
                fail(name + "[" + i + "]: C " + expected + ", Java " + values[i]);
            }
        }
    }

    @Test
    void tablesMatchMinimp3() {
        String src = source();
        Object[][] tables = {
            {"halfrate", Tables.HALFRATE}, {"g_hz", Tables.G_HZ}, {"g_scf_long", Tables.G_SCF_LONG},
            {"g_scf_short", Tables.G_SCF_SHORT}, {"g_scf_mixed", Tables.G_SCF_MIXED},
            {"g_scf_partitions", Tables.G_SCF_PARTITIONS}, {"g_scfc_decode", Tables.G_SCFC_DECODE},
            {"g_mod", Tables.G_MOD}, {"g_preamp", Tables.G_PREAMP}, {"g_expfrac", Tables.G_EXPFRAC},
            {"g_pow43", Tables.G_POW43}, {"tabs", Tables.TABS}, {"tab32", Tables.TAB32}, {"tab33", Tables.TAB33},
            {"tabindex", Tables.TABINDEX}, {"g_linbits", Tables.G_LINBITS}, {"g_pan", Tables.G_PAN},
            {"g_aa", Tables.G_AA}, {"g_twid9", Tables.G_TWID9}, {"g_twid3", Tables.G_TWID3},
            {"g_mdct_window", Tables.G_MDCT_WINDOW}, {"g_sec", Tables.G_SEC}, {"g_win", Tables.G_WIN},
        };
        for (Object[] t : tables) {
            List<Object> c = table(src, (String) t[0]);
            compare((String) t[0], t[1], c);
        }
        // the sizes declared in C
        assertEquals(129 + 16, Tables.G_POW43.length);
        assertEquals(23, Tables.G_SCF_LONG[0].length);
        assertEquals(40, Tables.G_SCF_SHORT[0].length);
        assertEquals(40, Tables.G_SCF_MIXED[0].length);
        assertEquals(15 * 16, Tables.G_WIN.length);
    }
}
