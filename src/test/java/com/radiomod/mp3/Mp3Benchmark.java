/*
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.radiomod.mp3;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Section 7.6: decoding speed on one core, as a multiple of real time, on the 4-minute synthetic files
 * (44.1 kHz stereo, 128 and 320 kbps). Run with {@code gradlew benchmark}; it is not part of the tests.
 *
 * <p>Arguments: the test data directory and the directory for the report.
 */
public final class Mp3Benchmark {
    private Mp3Benchmark() {
    }

    private static long decodeFile(Mp3Decoder dec, byte[] mp3, float[] pcm, Mp3FrameInfo info) {
        long samples = 0;
        int pos = 0;
        dec.reset();
        do {
            samples += dec.decodeFrame(mp3, pos, mp3.length - pos, pcm, info);
            pos += info.frameBytes;
        } while (info.frameBytes != 0);
        return samples;
    }

    public static void main(String[] args) throws IOException {
        Path data = Path.of(args.length > 0 ? args[0] : "build/testdata");
        Path reports = Path.of(args.length > 1 ? args[1] : "build/reports/minimp3");
        StringBuilder sb = new StringBuilder();
        sb.append("# Decoding speed (section 7.6)\n\n");
        sb.append("CPU: ").append(cpuName()).append("  \n");
        sb.append("JVM: ").append(System.getProperty("java.vm.name")).append(' ')
                .append(System.getProperty("java.runtime.version")).append("  \n");
        sb.append("OS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch"))
                .append("  \n\n");
        sb.append("One thread; best of 7 runs after 5 warm-up runs. On CPUs with performance and efficiency cores ")
                .append("the OS may move the thread to an efficiency core during the run, which is several times ")
                .append("slower: for stable numbers pin the JVM to one core (Windows: start /affinity 4 ..., ")
                .append("Linux: taskset -c 2 ...).\n\n");
        sb.append("| file | audio | decoding time | speed |\n|---|---:|---:|---:|\n");
        for (int kbps : new int[] {128, 320}) {
            String name = "bench_44100_joint_cbr" + kbps + "_240s";
            byte[] mp3 = Files.readAllBytes(data.resolve("corpus").resolve(name + ".mp3"));
            Mp3Decoder dec = new Mp3Decoder();
            Mp3FrameInfo info = new Mp3FrameInfo();
            float[] pcm = new float[Mp3Decoder.MAX_SAMPLES_PER_FRAME];
            long samples = 0;
            for (int i = 0; i < 5; i++) {
                samples = decodeFile(dec, mp3, pcm, info);
            }
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 7; i++) {
                long t0 = System.nanoTime();
                decodeFile(dec, mp3, pcm, info);
                best = Math.min(best, System.nanoTime() - t0);
            }
            double audio = samples / 44100.0;
            double secs = best / 1e9;
            String line = String.format(Locale.ROOT, "| %s | %.1f s | %.3f s | %.0fx real time |%n", name, audio, secs,
                    audio / secs);
            sb.append(line);
        }
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("benchmark.md"), sb.toString(), StandardCharsets.UTF_8);
        // the benchmark is a command-line tool: printing is its output
        System.out.print(sb);
    }

    private static String cpuName() {
        try {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            if (os.contains("linux")) {
                for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                    if (line.startsWith("model name")) {
                        return line.substring(line.indexOf(':') + 1).trim();
                    }
                }
            } else if (os.contains("windows")) {
                String out = run("reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "/v",
                        "ProcessorNameString");
                int i = out.indexOf("REG_SZ");
                if (i >= 0) {
                    return out.substring(i + 6).trim();
                }
            } else if (os.contains("mac")) {
                return run("sysctl", "-n", "machdep.cpu.brand_string").trim();
            }
        } catch (IOException | RuntimeException e) {
            // fall through
        }
        return System.getProperty("os.arch") + ", " + Runtime.getRuntime().availableProcessors() + " threads";
    }

    private static String run(String... cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            for (String line; (line = r.readLine()) != null;) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }
}
