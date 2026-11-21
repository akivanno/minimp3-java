/*
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.radiomod.mp3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Corrupted and generated streams decoded by both the C reference and the Java port: the output must be
 * identical there too, not only on valid files. On a few malformed streams minimp3 reads memory that it
 * has not initialized or that does not belong to the buffer it decodes (see NOTES.md): there the outputs
 * may legitimately differ, and the test counts those streams instead of failing, as long as the
 * sequence of frames is the same.
 *
 * <p>The number of streams is set by {@code -Dminimp3.difffuzz=N} (default 600).
 */
class DifferentialFuzzTest {
    @Test
    @Timeout(value = 900, unit = TimeUnit.SECONDS)
    void cAndJavaAgreeOnCorruptedInput() throws IOException {
        int count = Integer.getInteger("minimp3.difffuzz", 600);
        Path ref = TestData.dir().resolve("bin")
                .resolve(System.getProperty("os.name").toLowerCase().contains("win") ? "minimp3_ref.exe" : "minimp3_ref");
        assertTrue(Files.isExecutable(ref), ref + " is missing");
        Path work = TestData.dir().resolve("difffuzz");
        Files.createDirectories(work);
        byte[][] seeds = new byte[RobustnessTest.FUZZ_FILES.length][];
        for (int i = 0; i < seeds.length; i++) {
            byte[] mp3 = TestData.entry(RobustnessTest.FUZZ_FILES[i]).bytes();
            seeds[i] = Arrays.copyOf(mp3, Math.min(mp3.length, 12 * 1024));
        }
        AtomicInteger identical = new AtomicInteger();
        AtomicInteger samplesDiffer = new AtomicInteger();
        List<String> differ = java.util.Collections.synchronizedList(new ArrayList<>());
        IntStream.range(0, count).parallel().forEach(v -> {
            SplittableRandom r = new SplittableRandom(0xD1FFL * 1_000_003 + v);
            byte[] data = (v & 1) == 0 ? RobustnessTest.mutate(seeds[v % seeds.length], r)
                    : RobustnessTest.StreamGenerator.stream(r, 4 + r.nextInt(28));
            Path in = work.resolve("case" + v + ".mp3");
            Path out = work.resolve("case" + v + ".ref");
            try {
                Files.write(in, data);
                Process p = new ProcessBuilder(ref.toString(), in.toString(), out.toString())
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                assertEquals(0, p.waitFor(), "minimp3_ref failed on case " + v);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            List<Calls.Call> c = Calls.read(out);
            List<Calls.Call> j = Calls.decode(data, true);
            assertEquals(c.size(), j.size(), "case " + v + ": number of calls");
            boolean same = true;
            for (int i = 0; i < c.size(); i++) {
                assertTrue(c.get(i).sameInfo(j.get(i)), "case " + v + " call " + i + "\n  C:    " + c.get(i).info()
                        + "\n  Java: " + j.get(i).info());
                float[] a = c.get(i).pcm();
                float[] b = j.get(i).pcm();
                for (int k = 0; a != null && k < a.length; k++) {
                    if (Float.floatToRawIntBits(a[k]) != Float.floatToRawIntBits(b[k])) {
                        same = false;
                        break;
                    }
                }
            }
            if (same) {
                identical.incrementAndGet();
                try {
                    Files.delete(in);
                    Files.delete(out);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                samplesDiffer.incrementAndGet();
                differ.add("case" + v);
            }
        });
        TestData.report("differential-fuzz", "# C and Java on corrupted input\n\n" + count + " streams (half mutated "
                + "corpus files, half generated frames). Identical output: " + identical.get()
                + ". Same frames but different samples: " + samplesDiffer.get() + (differ.isEmpty() ? "" : " ("
                + String.join(", ", differ.subList(0, Math.min(20, differ.size()))) + "; kept in "
                + work.toAbsolutePath() + ")") + ".\n");
        assertTrue(samplesDiffer.get() <= count / 50, samplesDiffer.get() + " streams decode differently: " + differ);
    }
}
