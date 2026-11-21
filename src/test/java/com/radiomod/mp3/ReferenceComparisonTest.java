/*
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.radiomod.mp3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Sections 7.1 and 7.3: the Java decoder against minimp3.h compiled with the same options, on the
 * synthetic corpus and on the ISO vectors. The sequence of calls must be identical and the samples may
 * differ by at most one 16-bit unit (in practice they are bit-exact).
 */
@Execution(ExecutionMode.CONCURRENT)
class ReferenceComparisonTest {
    record Result(int calls, int frames, long samples, double maxFloatDiff, int maxPcm16Diff, boolean exact) {
    }

    private static final Map<String, Result> RESULTS = new ConcurrentHashMap<>();

    static Stream<TestData.Entry> files() {
        return TestData.withReference();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    void matchesReference(TestData.Entry e) {
        List<Calls.Call> expected = Calls.read(e.ref());
        List<Calls.Call> actual = Calls.decode(e.bytes(), true);
        double maxFloat = 0;
        int maxPcm16 = 0;
        int frames = 0;
        long samples = 0;
        for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            Calls.Call c = expected.get(i);
            Calls.Call j = actual.get(i);
            if (!c.sameInfo(j)) {
                fail(e.name() + ": call " + i + " differs\n  C:    " + c.info() + "\n  Java: " + j.info());
            }
            if (c.pcm() != null) {
                frames++;
                samples += c.pcm().length;
                for (int k = 0; k < c.pcm().length; k++) {
                    float a = c.pcm()[k];
                    float b = j.pcm()[k];
                    if (Float.floatToRawIntBits(a) != Float.floatToRawIntBits(b)) {
                        maxFloat = Math.max(maxFloat, Math.abs((double) a - b));
                        if (Float.isNaN(a) != Float.isNaN(b)) {
                            maxFloat = Double.POSITIVE_INFINITY;
                        }
                    }
                    maxPcm16 = Math.max(maxPcm16, Math.abs(Calls.toPcm16(a) - Calls.toPcm16(b)));
                }
            }
        }
        assertEquals(expected.size(), actual.size(), e.name() + ": number of calls");
        boolean exact = maxFloat == 0;
        RESULTS.put(e.name(), new Result(expected.size(), frames, samples, maxFloat, maxPcm16, exact));
        assertTrue(maxPcm16 <= 1, e.name() + ": 16-bit samples differ by " + maxPcm16);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    void matchesReferenceWithoutPcm(TestData.Entry e) {
        List<Calls.Call> expected = Calls.read(e.nopcmRef());
        List<Calls.Call> actual = Calls.decode(e.bytes(), false);
        for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            if (!expected.get(i).sameInfo(actual.get(i))) {
                fail(e.name() + ": call " + i + " with pcm == null differs\n  C:    " + expected.get(i).info()
                        + "\n  Java: " + actual.get(i).info());
            }
        }
        assertEquals(expected.size(), actual.size(), e.name() + ": number of calls with pcm == null");
    }

    @AfterAll
    static void writeReport() {
        if (RESULTS.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Java vs C reference (sections 7.1 and 7.3)\n\n");
        sb.append("JVM: ").append(System.getProperty("java.vm.name")).append(' ')
                .append(System.getProperty("java.version")).append("\n\n");
        long exact = RESULTS.values().stream().filter(Result::exact).count();
        sb.append("Files: ").append(RESULTS.size()).append(", bit-exact: ").append(exact).append("\n\n");
        sb.append("| file | calls | decoded frames | samples | max float diff | max 16-bit diff |\n");
        sb.append("|---|---:|---:|---:|---:|---:|\n");
        for (Map.Entry<String, Result> r : new TreeMap<>(RESULTS).entrySet()) {
            Result v = r.getValue();
            sb.append("| ").append(r.getKey()).append(" | ").append(v.calls).append(" | ").append(v.frames)
                    .append(" | ").append(v.samples).append(" | ").append(v.maxFloatDiff == 0 ? "0" : v.maxFloatDiff)
                    .append(" | ").append(v.maxPcm16Diff).append(" |\n");
        }
        TestData.report("reference-comparison", sb.toString());
    }
}
