# Notes on the port

These are the delivery notes of the Java port of minimp3: what was translated, every deviation from the
C code, the test results and the parts of the original that look wrong. Line numbers `C:n` refer to
`minimp3.h` at the commit below; `J:n` to `src/main/java/com/radiomod/mp3/Mp3Decoder.java` unless another
file is named.

The port was written against a specification that is not part of this repository. Its requirements are
summarized where they matter, and its section numbers are kept as headings: 5 (untrusted input),
6 (fidelity of the translation), 7.1 to 7.6 (comparison with the C code, ISO vectors, synthetic corpus,
robustness, restart in the middle of a file, speed). When the specification and the C code disagree,
the C code wins.

## Source

| | |
|---|---|
| Repository | <https://github.com/lieff/minimp3>, branch `master` |
| Commit | **`142823796ab8efa55e1bf11406028f3be77ecceb`** (2026-11-18, "c90 compatibility, Issue #159") |
| File | `minimp3.h`, SHA-256 `f9592be69e8b94044e9da6f45f993c9708000abae38cf6f44b70c699de55b509` (LF line endings) |
| Configuration | `MINIMP3_ONLY_MP3`, `MINIMP3_NO_SIMD`, `MINIMP3_FLOAT_OUTPUT`; `MINIMP3_NONSTANDARD_BUT_LOGICAL` not defined |
| License | CC0 1.0, like the original; every source file starts with the dedication and the commit |

`minimp3_ex.h` is not ported. The tests contain a translation of `mp3dec_load_buf` (`LoadBuf.java`) only
to score the ISO vectors exactly as `minimp3_test.c` does.

## Summary

| Requirement | Result |
|---|---|
| 7.1 / 7.3 Java vs C, 240 files (157 synthetic + 83 ISO vectors) | **identical call sequence; every float sample bit-exact** (max float difference 0, max 16-bit difference 0), JDK 21 and 25; also with `pcm == null` |
| 7.1 extra: corrupted and generated streams | 600 per test run, 6000 in a one-off run: **all bit-exact** with C |
| 7.2 ISO vectors | 38 Layer III vectors: PSNR identical to `minimp3_test.c` with the same options, all > 96 dB (min 100.08). 45 Layer I/II vectors skipped. The README thresholds are not reached, by the C code either (see 7.2) |
| 7.4 robustness | no exception, no read outside the range, termination: all cases pass; the robustness tests alone execute 98% of the lines, the whole suite 100% |
| 7.5 restart | converges exactly as the bit reservoir allows; 730 of 1000 restarts match from frame 3, the worst from frame 8 (see 7.5) |
| 7.6 speed | 1108× real time (128 kbps), 883× (320 kbps) on one P-core of an Intel Core i7-12700H, JDK 21; about 1/5 of that on an E-core |
| 7.6 allocation | 0 bytes allocated by `decodeFrame` over 14 million samples, JDK 21 and 25 |
| Java 21 / 25 | compiled with `--release 21` (class files version 65); all tests pass on JDK 21.0.7 and 25.0.5 |

## 1. Structure of the translation

The functions are translated one by one and kept in the order of `minimp3.h`. Each Java method has the
name of its C function in a comment above it (`// L3_huffman`); each macro is a private method or code
in place, marked with the macro name.

| C | Java |
|---|---|
| `mp3dec_t` (C:22) | the fields of `Mp3Decoder` (J:96) |
| `mp3dec_frame_info_t` (C:17) | `Mp3FrameInfo` |
| `bs_t`, `bs_init`, `get_bits` (C:206, 241, 248) | `BitStream` |
| `L3_gr_info_t` (C:223) | `GrInfo` |
| the `static const` tables | `Tables`, copied by a script from the C source and checked value by value by `TablesTest` |
| everything else, from `hdr_valid` (C:264) to `mp3dec_f32_to_s16` (C:1899) | methods of `Mp3Decoder`, same order |
| `mp3dec_init`, `mp3dec_decode_frame`, `mp3dec_f32_to_s16` | `new Mp3Decoder()` and `reset()`, `decodeFrame`, `toPcm16` (names of the specification) |

The API is exactly the one of the specification. Two internal methods, `l3ReadSideInfo` and
`mp3dFindFrame`, are package-private instead of private because the test translation of
`mp3dec_load_buf` calls them (as `minimp3_ex.h` calls `L3_read_side_info` and `mp3d_find_frame`); they are
not part of the public API.

## 2. Deviations from the C code

### 2.1 Representation (section 6)

These change how the data is held, not what is computed.

| C | Java | Why |
|---|---|---|
| `mp3dec_scratch_t scratch`, a local of `mp3dec_decode_frame` (C:1807), uninitialized except `ist_pos` | fields of the decoder, allocated by the constructor | no allocation per frame. Where C reads uninitialized scratch memory Java reads the previous call's data: see 4.1 and 4.3 |
| `float grbuf[2][576], scf[40], syn[33][64]` (C:237) | one `float[]` in the same order (J:88-93) | `L3_reorder` writes past `grbuf[ch]` in one case (4.1); with the same layout the extra accesses land on the same data as in C |
| locals of other functions: `co`, `si` (C:1121), `tmp` (C:1202), `t` (C:1308/1395), `a`, `b` (C:1630), `scf_size`, `iscf` (C:677), `max_band` (C:1002), `bs_frame` (C:1806) | preallocated fields | no allocation per frame |
| pointers into arrays | array + index | |
| `mp3d_find_frame(..., int *free_format_bytes, int *ptr_frame_bytes)` (C:1747) | `int[2]` in/out argument (J:1225) | no out parameters in Java |
| `memset(dec, 0, sizeof(mp3dec_t))` (C:1820) | `Arrays.fill` on every field of `mp3dec_t` (J:1327) | |
| `uint8_t` tables | `int[]` | values unchanged, no masking when read |
| `int16_t tabs[]` | `short[]`; `tabindex` as `int[]` | values unchanged |
| `uint8_t` variables: `ist_pos`, `iscf`, the scale factors | `int`, masked with `& 0xFF` on every store where C truncates | same values |
| `uint32_t bs_cache`, `cache`, `next` | `int`, with `>>>` for the unsigned shifts | same bits |
| unsigned divisions of `L3_save_reservoir` (C:1239-1240) | plain divisions (J:898) | the operands are never negative |
| `LOAD`/`S0`/`S1`/`S2` of `mp3d_synth` (C:1626-1629) | `synthS0`, `synthS1`, `synthS2` (J:1057) | same operations in the same order |

All arithmetic is in `float`, in the order of the C code; every literal keeps its `f` suffix; there is no
`Math.fma`. Java evaluates `float` expressions in strict IEEE single precision, and the C reference is
built with `-ffp-contract=off` so that the compiler does not fuse multiply-adds: this is why the
results are bit-exact. The shifts by 32 that are undefined in C (`PEEK_BITS(0)` would be one) never
happen: `HuffmanTablesTest` walks every path of every Huffman table and shows that all windows are 1 to
7 bits wide.

### 2.2 Checks added for untrusted input (section 5)

Where the C code can be shown to stay inside its arrays, nothing was added. Three changes were needed;
none of them changes the output of a valid stream.

| C | Java | What | Valid streams |
|---|---|---|---|
| C:791 (`CHECK_BITS`), C:797-800 (first 4 bytes) in `L3_huffman` | `rd()`, J:465 | a byte past the end of `maindata` (2815 bytes) reads as 0. C reads the members of `mp3dec_scratch_t` that follow `maindata`. `L3_huffman` decodes `big_values` pairs without looking at `part2_3_length`, so a crafted granule can make it read up to 26 bytes past `maindata` (test `RobustnessTest.huffmanReaderPastTheEndOfMainData`, see 4.2) | never reached: valid main data is at most 511 + 2291 bytes and the reader looks at most 4 bytes ahead |
| C:1759 `i + 2*k < mp3_bytes - HDR_SIZE` | J:1234 `2 * k < mp3Bytes - HDR_SIZE - i` | rearranged so that it cannot overflow | identical result whenever the C sum does not overflow, that is for any range shorter than about 2^31 − 4600 bytes |
| C:1772 `i + frame_and_padding <= mp3_bytes` | J:1248 `frameAndPadding <= mp3Bytes - i` | same | same |
| C:1822 `i + frame_size > mp3_bytes` | J:1338 `frameSize > mp3Bytes - i` | same | same |

Plus the argument checks of the public methods (J:1302-1312 for `decodeFrame`, J:1398 for `toPcm16`):
`NullPointerException` for null `mp3`/`info`/arrays, `IllegalArgumentException` for an offset or length
outside the array, a `pcm` shorter than `MAX_SAMPLES_PER_FRAME`, or a sample count longer than the arrays.

Why the rest needs no check:

- `get_bits` (C:248) checks the position against the limit before reading, and every frame it reads lies
  inside `[offset, offset + length)`: `mp3d_find_frame` and the fast path of `mp3dec_decode_frame` only
  accept frames that fit (C:1772-1774, C:1813).
- The Huffman lookups index the tables with stream bits; `HuffmanTablesTest` proves that every index any
  bit sequence can produce is inside the tables, and that the `g_pow43` index of an escape value stays
  below 145.
- Scale factor band tables always end with 0 before the end of the row, and `big_values` (at most 288,
  C:556) never needs more pairs than the 288 of a granule, so `sfb`, `scf` and `dst` stay inside their
  arrays; `ist_pos` indices stay below 39; the frame payload copied into `maindata` is at most 2291
  bytes plus a reservoir of 511, below 2815.
- The only access past an array in C (`L3_reorder`, 4.1) stays inside `mp3dec_scratch_t`, and the Java
  layout reproduces it.

## 3. Results

### 7.1 and 7.3: comparison with the C code

`reference/minimp3_ref.c` decodes every file with `minimp3.h` built with the three defines
(gcc 16.2.0, MSYS2 UCRT64, `-O2 -ffp-contract=off -fno-fast-math`), calling `mp3dec_decode_frame` on the
remaining bytes and advancing by `frame_bytes`, with one `mp3dec_frame_info_t` reused for all the calls.
`ReferenceComparisonTest` does the same in Java and compares, call by call, the result, `frameBytes`,
`frameOffset`, `channels`, `hz`, `layer` and `bitrateKbps`, then the samples.

- 240 files: the 157 synthetic files that are not benchmark files and the 83 ISO vectors (Layer I/II
  vectors included: their frames must be skipped the same way).
- Every call identical, every sample bit-exact: **maximum float difference 0 and maximum 16-bit
  difference 0 for every file**, on JDK 21 and JDK 25. The table per file is in the appendix.
- The same with `pcm == null` (`minimp3_ref --nopcm`): identical call sequence for every file.
- `DifferentialFuzzTest` decodes corrupted corpus files and generated streams (see 7.4) with both: 600
  streams per run, and 6000 in a one-off run with `-PdiffFuzz=6000`. All bit-exact.

The corpus (section 7.3) is generated by `reference/make_corpus.py`: every sample rate × CBR minimum, CBR
maximum, CBR 128 kbps, VBR `-V0` and `-V9` (with Xing header) × mono, stereo, joint stereo (135 files),
each holding a 20 Hz-20 kHz sweep, full-scale white noise, impulses and silence; 6 special files (silence
only, full-scale noise at 320 kbps, impulses only, VBR without Xing header, no bit reservoir, MPEG-2.5
silence); 16 tagged files (large ID3v2 tag with a 117 KB cover image containing fake frame headers,
small ID3v2 tag, ID3v1 tag, both) and the 2 benchmark files. LAME caps MPEG-2.5 at 64 kbps, so the
MPEG-2.5 "160 kbps" (CBR maximum) and "128 kbps" files are 64 kbps files; `corpus.tsv` records the bit
rate actually written.

### 7.2: ISO conformance vectors

PSNR computed as `minimp3_test.c` does (`mp3dec_load_buf`, `mp3dec_f32_to_s16`, MSE over all samples,
the same length checks). "C" is `minimp3_test.c` itself, built with the three defines and run on the same
vectors; "README" is the table in the minimp3 README.

| Vector | Samples | Max diff | PSNR Java | PSNR C | README |
|---|---:|---:|---:|---:|---:|
| l3-compl | 248832 | 1 | 120.843569 | 120.843569 | 124.22 |
| l3-he_32khz | 172800 | 1 | 127.770554 | 127.770554 | 139.67 |
| l3-he_44khz | 472320 | 1 | 132.137480 | 132.137480 | 144.04 |
| l3-he_48khz | 172800 | 1 | 127.770554 | 127.770554 | 139.67 |
| l3-he_free | 156672 | 1 | 127.345031 | 127.345031 | 137.48 |
| l3-he_mode | 262656 | 1 | 116.905928 | 116.905928 | 118.00 |
| l3-hecommon | 69120 | 1 | 123.791154 | 123.791154 | 133.93 |
| l3-si | 135936 | 1 | 107.794961 | 107.794961 | 120.30 |
| l3-si_block | 73728 | 1 | 111.693410 | 111.693410 | 125.18 |
| l3-si_huff | 86400 | 1 | 100.082980 | 100.082980 | 107.98 |
| l3-sin1k0db | 725760 | 1 | 111.005374 | 111.005374 | 111.03 |
| l3-test45 | 946944 | 1 | 115.839518 | 115.839518 | |
| l3-test46 | 288000 | 1 | 115.948433 | 115.948433 | |
| l3-nonstandard-compl-sideinfo-bigvalues | 244224 | 1 | 122.091467 | 122.091467 | |
| l3-nonstandard-compl-sideinfo-blocktype | 244224 | 1 | 121.806156 | 121.806156 | |
| l3-nonstandard-compl-sideinfo-size | 244224 | 1 | 122.145417 | 122.145417 | |
| l3-nonstandard-he_44_48khz | 471168 | 1 | 132.416512 | 132.416512 | |
| l3-nonstandard-sin1k0db_lame_vbrtag | 725760 | 1 | 108.195268 | 108.195268 | |
| l3-nonstandard-apetag | 1152 | 1 | 105.241241 | 105.241241 | |
| l3-nonstandard-id3v1 | 1152 | 1 | 105.241241 | 105.241241 | |
| l3-nonstandard-id3v1-apetag | 1152 | 1 | 105.241241 | 105.241241 | |
| l3-nonstandard-id3v2 | 1152 | 1 | 105.241241 | 105.241241 | |
| M2L3_bitrate_16_all | 274176 | 1 | 111.060435 | 111.060435 | |
| M2L3_bitrate_22_all | 274176 | 1 | 111.407271 | 111.407271 | |
| M2L3_bitrate_24_all | 274176 | 1 | 111.577850 | 111.577850 | |
| M2L3_compl24 | 122112 | 1 | 114.902659 | 114.902659 | |
| M2L3_noise | 444672 | 1 | 117.053235 | 117.053235 | |
| ILL2_layer3 | 23040 | 1 | 113.251700 | 113.251700 | |
| l3-nonstandard-big-iscf | 2304 | | 99.000000 (no reference PCM) | 99.000000 | |
| ILL2_overalloc2, l3-nonstandard-id3v2-only, -sideinfo-size, -small, -vbrtag-corrupted, -vbrtag-empty, -vbrtag-noframes, -vbrtag-only | 0 | 0 | 99.000000 | 99.000000 | |
| l3-nonstandard-vbrtag-oob-read | 2304 | 0 | 99.000000 | 99.000000 | |

Every decoded vector is above 96 dB (lowest: l3-si_huff, 100.08 dB) and has exactly the PSNR of the C
program.

**The README thresholds cannot be reached**, by this port or by the C code with the required options.
I built `minimp3_test.c` in several configurations on the same machine: the default build (SIMD on x86-64,
16-bit output) reproduces the README (l3-compl 124.224582, l3-he_44khz 144.040797); any build with
`MINIMP3_NO_SIMD` gives 120.843569 and 132.137480, with or without `MINIMP3_FLOAT_OUTPUT`. The SIMD code
adds the terms of the polyphase filter and of the DCT in a different order, and that rounding happens to
be closer to the ISO reference. Since the specification says that the C code wins when they disagree,
the test requires the PSNR of the C program with the same options and more than 96 dB, and reports the
README values for comparison.

Skipped (Layer I and II, not decoded with `MINIMP3_ONLY_MP3`; 45 vectors): ILL2_center2, ILL2_dual,
ILL2_dynx22, ILL2_dynx31, ILL2_dynx32, ILL2_ext_switching, ILL2_layer1, ILL2_mono, ILL2_multilingual,
ILL2_overalloc1, ILL2_prediction, ILL2_samples, ILL2_scf63, ILL2_tca21, ILL2_tca30, ILL2_tca30_PC,
ILL2_tca31_PC, ILL2_tca31_mtx0, ILL2_tca31_mtx2, ILL2_tca32_PC, ILL2_wrongcrc, ILL4_ext_id1, ILL4_sync,
ILL4_wrong_length1, ILL4_wrong_length2, ILL4_wrongcrc, l1-fl1 to l1-fl8, l2-fl10 to l2-fl16, l2-test32,
l2-nonstandard-fl1_fl2_ff, l2-nonstandard-free_format, l2-nonstandard-test32-size. They are still part
of the comparison of 7.1: their frames are found and skipped exactly as in C. ILL2_layer3 starts with
Layer III frames and is scored. ILL2_overalloc2 is a Layer II vector, but none of its frames is accepted
by the frame search, so no layer is detected: it is scored as the C program scores it (0 samples, 99 dB).

### 7.4: robustness

`RobustnessTest` decodes every input to the end with the loop of the mod and checks that no exception is
thrown, that `frameBytes` always advances inside the range, and that nothing is written past
`MAX_SAMPLES_PER_FRAME` in `pcm`. Every input is decoded at the very end of an array of exactly its size
(any read past the range would throw `ArrayIndexOutOfBoundsException`) and, for most cases, also in the
middle of a larger array surrounded by random bytes or by valid-looking headers, which must give the same
output. Both with and without `pcm`. Each test has a JUnit timeout.

| Case | Inputs |
|---|---|
| empty, 1 to 4 bytes | all 11110 combinations of 10 interesting byte values; zeros; header prefixes |
| random | 1 MB of random bytes; the same with fake sync words every 1-64 bytes |
| truncation | 2 files (MPEG-1 with ID3v2 + ID3v1, MPEG-2.5 VBR) cut at 200 points each: 100 random, 100 at frame boundaries and ±1 |
| fuzzing | 10 000 variants of 4 files (MPEG-1 joint, MPEG-2 VBR stereo, MPEG-2.5 mono, ID3v2 + MPEG-2 mono): bit flips, byte substitutions, insertions, deletions, random blocks, header field changes, alone or combined, fixed seeds |
| reserved fields | bit rate 15, sample rate 3, layer 0, MPEG version "01", in the first, every, every other and random headers (nothing may be decoded when all headers are invalid) |
| generated frames | 4000 streams of frames with valid headers of every kind (versions, rates, modes, CRC, padding, free format) and random side information at and inside the edges of the legal ranges, followed by random main data |
| crafted | free-format frames that make `L3_huffman` read past `maindata` (4.2) |

All pass on JDK 21 and 25. Coverage (JaCoCo): the robustness tests alone execute 767 of the 783 lines of
`Mp3Decoder`; the whole suite executes all 783 and 435 of 442 branches. The branches never taken are
either impossible (the second half of C:1822, the `i + k + nextfb` test of C:1765 when it is reached) or
corners of tag detection. Time stays linear: 1 MB of random data with dense fake sync words is scanned in
about 0.2 s.

### 7.5: restart in the middle of a file

`ResyncTest`: 100 random start points (fixed seed) in each of 10 files covering the three MPEG versions,
CBR and VBR, mono and stereo, and a file with a large ID3v2 tag. A reset decoder starts at the point; the
frames found are compared with the decoding from the start of the file. Frame 0 is the first frame found.
Start points leave at least 40 frames before the end of the file (a quarter of the file for short files),
and each restart is compared up to 8 frames past the frame from which it must match.

| File | Version | Worst k0 | Worst "matches from" | Restarts matching from frame 3 | Distribution of "matches from" |
|---|---|---:|---:|---:|---|
| 44100_joint_cbr128 | MPEG-1 | 2 | 3 | 100/100 | {0=1, 2=18, 3=81} |
| tag_id3v2big_44100_joint_cbr128 | MPEG-1 | 2 | 3 | 100/100 | {0=77, 2=5, 3=18} |
| 48000_stereo_vbr0 | MPEG-1 | 3 | 4 | 98/100 | {1=2, 2=58, 3=38, 4=2} |
| 32000_mono_cbr32 | MPEG-1 | 5 | 6 | 9/100 | {0=1, 2=3, 3=5, 4=23, 5=59, 6=9} |
| 22050_joint_cbr160 | MPEG-2 | 1 | 3 | 100/100 | {0=1, 2=39, 3=60} |
| 24000_mono_vbr0 | MPEG-2 | 5 | 7 | 33/100 | {0=1, 2=10, 3=22, 4=49, 5=11, 6=6, 7=1} |
| 16000_stereo_vbr9 | MPEG-2 | 6 | 8 | 12/100 | {0=2, 3=10, 4=54, 5=16, 6=6, 7=4, 8=8} |
| 12000_mono_cbr160 (64 kbps) | MPEG-2.5 | 1 | 3 | 100/100 | {0=8, 2=61, 3=31} |
| 11025_stereo_vbr0 | MPEG-2.5 | 2 | 4 | 78/100 | {0=1, 3=77, 4=22} |
| 8000_joint_cbr8 | MPEG-2.5 | 1 | 3 | 100/100 | {0=21, 3=79} |

**At most 8 frames** here: in the worst case the output matches from the 8th frame after the first one
found. 730 of the 1000 restarts match from frame 3, as the specification asks; the others cannot, for the
C code either, and the test checks the bound that the format allows instead:

- A frame can be decoded only when the bit reservoir holds the `main_data_begin` bytes it refers to, up
  to 511 bytes (MPEG-1) or 255 (MPEG-2/2.5) of earlier frames. After a restart the reservoir is empty, so
  frames 0, 1, ... return 0 samples until enough earlier frames have been read: call k0 the first one that
  decodes. With small frames (32 kbps, VBR silence, low sample rates) k0 reaches 5-8 in these files, and
  more is possible: MPEG-2 frames of VBR silence at 24 kHz carry only 11 bytes of main data, so a full
  reservoir of 255 bytes spans more than 20 of them.
- Frame k0 is still wrong, because the MDCT overlap and the synthesis filter start from zero. With two
  granules per frame (MPEG-1), frame k0 + 1 is right; with one (MPEG-2 and 2.5) the synthesis filter keeps
  15 slots of the wrong granule, so only frame k0 + 2 is right.

`ResyncTest` requires every restart to be bit-exact from frame k0 + 1 (MPEG-1) or k0 + 2 (MPEG-2/2.5),
and it is. "Matches from" in the table uses the tolerance of 7.1, so it can be one frame earlier than the
bit-exact match when what is still wrong is too small to show. Results are identical on JDK 21 and 25.

The corpus is generated on each machine, and the files depend slightly on the ffmpeg build that
generates them: the numbers above come from ffmpeg 8.1 on Windows; the CI, with Ubuntu's ffmpeg 6.1,
decodes different files and gets different numbers (on its first run one restart needed more than 13
frames to fill the reservoir). The bound that the test checks holds for any file.

### 7.6: speed and allocation

`./gradlew benchmark` (class `Mp3Benchmark`): one thread, best of 7 runs after 5 warm-up runs, on the
two 4-minute synthetic files (44.1 kHz stereo).

| | 128 kbps | 320 kbps |
|---|---:|---:|
| JDK 21.0.7, P-core | 0.217 s, **1108× real time** | 0.272 s, **883× real time** |
| JDK 25.0.5, P-core | 0.224 s, 1073× real time | 0.266 s, 901× real time |
| JDK 21.0.7, E-core | 1.098 s, 219× real time | 1.311 s, 183× real time |
| JDK 25.0.5, E-core | 1.117 s, 215× real time | 1.404 s, 171× real time |

Processor: Intel Core i7-12700H (laptop, 6 performance + 8 efficiency cores), Windows 11, on AC power.
The minimum of 100× real time is met by a factor of 9-11 on a performance core and is still met on an
efficiency core. The benchmark was run pinned to one core (`start /affinity 4` and `start /affinity 10000`):
without pinning, Windows moves a long single-threaded run from a P-core to an E-core after a few seconds,
and the speed drops by 5× in the middle of the measurement. I first took that for a JIT problem; a
flight recording and the pinned runs showed that the compiled code is the same and only the core
changes.

Allocation: `AllocationTest` decodes 6 files 10 times, with and without `pcm`, after a warm-up, and
reads the bytes allocated by the thread from `com.sun.management.ThreadMXBean`: **0 bytes** for
14 135 040 samples, on JDK 21 and 25. The only allocation is the one-time initialization of the `Tables`
class (73 KB) on first use.

## 4. Parts of the original that look wrong (translated as they are)

### 4.1 MPEG-2.5 at 8 kHz with mixed blocks (C:523, C:1290, C:1020-1035)

`g_scf_mixed[1]` (C:523), the table for MPEG-2.5 at 8 kHz, starts with long bands `12,12,12,4,4,4`, which
cover 48 lines, but `L3_decode` treats the first `n_long_bands = 4` subbands, 72 lines, as long (C:1285).
`L3_reorder` (C:1290) then reorders the 528 short-block lines of the table starting at line 72, reading
and writing up to line 600 of a 576-line granule: 24 floats past the end. For the left channel they are
the first 24 lines of the right channel, which get overwritten; for the right channel they are `scf[0..23]`
(C:237). The table also has 39 bands while `n_long_sfb + n_short_sfb` is 36, so `L3_huffman` reads
`scf[36..38]` (C:814, C:851, C:889), which `L3_decode_scalefactors` did not compute for this granule:
uninitialized stack memory in C, the previous call's values in Java. The port keeps `grbuf`, `scf` and
`syn` in one array so that the overrun lands on the same data as in C. LAME never writes mixed blocks,
so real files are unlikely to hit this.

### 4.2 `L3_huffman` does not stop at `part2_3_length` in the big-values region (C:802-874)

Only the count1 loop checks the granule limit (C:885). A granule whose `big_values` asks for more bits
than `part2_3_length` gives keeps reading, past the granule and past the end of `maindata`: the crafted
stream of `RobustnessTest.huffmanReaderPastTheEndOfMainData` makes the C code read 26 bytes past
`maindata`, into the `gr_info` array that follows it in `mp3dec_scratch_t`. It stays inside the struct,
so it does not crash, but the samples then depend on pointer values. The Java port reads zeros there (2.2).
The sign bits of a count1 quadruple are read without the check too (C:890).

### 4.3 Intensity stereo is applied to mono frames (C:1274, C:1000-1018)

`L3_decode` tests the intensity stereo bit of the mode extension (C:1274) without checking that the
frame is joint stereo. For a mono MPEG-1 frame with that bit set, `L3_stereo_process` finds no right
channel and multiplies the whole spectrum by `g_pan[0] = 0`: **the frame decodes as silence**. I checked
it with the C reference: a mono file whose headers have the bit set decodes to 136 frames with peak 0
(1.57 without the bit). For MPEG-2 mono frames `L3_intensity_stereo` also reads `gr[1].scalefac_compress`
(C:1017), which for mono is `gr_info[1]`, never written: uninitialized in C, without effect on the
output because the shift it feeds is applied to 0.

### 4.4 The last frame before an ID3v1 tag is dropped (C:1713-1731)

When the fast path of `mp3dec_decode_frame` (C:1810) sees that the next header is not a frame header
(here the `TAG` of ID3v1), it searches again with `mp3d_find_frame`. `mp3d_match_frame` accepts a
candidate followed by an ID3v1 tag only if at least one more header matched before (`return nmatch > 0`,
C:1724), which is never the case for the last frame. So the last frame is skipped as garbage: every
synthetic file with an ID3v1 tag decodes one frame less than the same file without it (for example 135
instead of 136 frames), in C and in Java. The comment of the function says it should succeed at the end of
the data, which suggests that this case was not intended.

### 4.5 Rounding of `mp3dec_f32_to_s16` (C:1941-1942)

`s = (int16_t)(sample + .5f); s -= (s < 0);` truncates toward zero and then subtracts 1 from negative
results. Positive values round half away from zero, but every value in (-1.5, 0.5) becomes 0 and -1 is
never produced: -0.7 and -1.2 both give 0, -1.6 gives -2. The error is at most one unit, around zero only.
`ApiTest` documents this behavior; the same code is in the 16-bit output path (C:1465-1466), which is not
ported.

### 4.6 The PSNR table of the README (section 7.2)

It was measured with the SIMD code. The scalar code that the specification requires rounds differently
and scores 0.03 to 12 dB lower on the same vectors (still more than 96 dB). The README does not say so.

### 4.7 Minor

- `pcm == NULL` (C:1838) returns before the bit reservoir is updated, so calls without `pcm` in the
  middle of a stream lose the reservoir: the next frames decoded with `pcm` may return 0 until it refills.
  This is the original semantics, kept as they are; the mod should not mix the two modes on one stream.
- With fewer than 5 bytes left, `mp3dec_decode_frame` reports all of them as skipped (`frame_bytes` = the
  number of bytes, C:1821-1825); the next call returns `frame_bytes == 0`.
- With `pcm == NULL`, Layer I and II frames return 384 or 1152 although they are never decoded
  (C:1838-1841 come before the layer test of C:1870). The Javadoc says so.
- The free-format search (C:1759) compares up to about 2300 headers per candidate position: linear in the
  data, but with a large constant on hostile input.

## 5. Where the specification and the C code disagree

The specification says that the C code wins; these are the points where that decided something.

| Specification | Decision |
|---|---|
| 7.2: every vector at least as good as the README table | impossible with `MINIMP3_NO_SIMD`, for the C code too (3, 7.2). The test requires the PSNR of the C program and > 96 dB |
| 7.5: output equal from the third frame after the first one found | not always possible, for the C code too, when the bit reservoir spans many small frames (3, 7.5). The test requires the bound the format allows; 73% of the restarts meet frame 3, all meet frame 8 (in the corpus of 7.5) |
| 4: `info` "filled on every call" | as in C, only `frameBytes` is written when no frame is found; the other fields keep their values. Documented in the Javadoc of `Mp3FrameInfo` and `decodeFrame` |
| 4: result 0, 576 or 1152 | with `pcm == null`, 384 is also possible (Layer I), as in C (4.7) |
| 7.3: CBR maximum per version, 128 kbps | LAME writes at most 64 kbps for MPEG-2.5: those files are at 64 kbps (3, 7.1) |
| 7.6: "a recent desktop PC" | measured on a recent laptop processor, pinned to one core |

## 6. Running the tests

`./gradlew check` prepares the test data (`reference/prepare_testdata.py`: needs Python 3, git, gcc or
clang, ffmpeg with libmp3lame), then runs the tests on JDK 21 and on JDK 25. `./gradlew benchmark`
measures the speed. Reports with the numbers above are written to `build/reports/minimp3`. See
[README.md](README.md) and [reference/README.md](reference/README.md).

| Test class | Section |
|---|---|
| `ReferenceComparisonTest` | 7.1, 7.3 (480 tests: 240 files, with and without `pcm`) |
| `DifferentialFuzzTest` | 7.1 on corrupted input |
| `VectorConformanceTest` | 7.2 |
| `RobustnessTest` | 7.4, and the argument checks |
| `ResyncTest` | 7.5 |
| `AllocationTest`, `Mp3Benchmark` | 7.6 |
| `TablesTest`, `HuffmanTablesTest` | section 6: tables equal to the C source; Huffman lookups in bounds |
| `ApiTest` | `toPcm16`, `Mp3FrameInfo` semantics, `reset`, decoders on several threads, samples not clipped |

## Appendix: maximum difference per file (7.1, 7.3)

Calls = calls of `decodeFrame` until `frameBytes` is 0; frames = calls that returned samples. The
maximum float difference and the maximum 16-bit difference (after `toPcm16`) are 0 for every file, on
JDK 21 and JDK 25.

| File | Calls | Frames | Samples | Max float diff | Max 16-bit diff |
|---|---:|---:|---:|---:|---:|
| 11025_joint_cbr128 | 71 | 70 | 80640 | 0 | 0 |
| 11025_joint_cbr160 | 71 | 70 | 80640 | 0 | 0 |
| 11025_joint_cbr8 | 71 | 70 | 80640 | 0 | 0 |
| 11025_joint_vbr0 | 71 | 70 | 80640 | 0 | 0 |
| 11025_joint_vbr9 | 71 | 70 | 80640 | 0 | 0 |
| 11025_mono_cbr128 | 71 | 70 | 40320 | 0 | 0 |
| 11025_mono_cbr160 | 71 | 70 | 40320 | 0 | 0 |
| 11025_mono_cbr8 | 71 | 70 | 40320 | 0 | 0 |
| 11025_mono_vbr0 | 71 | 70 | 40320 | 0 | 0 |
| 11025_mono_vbr9 | 71 | 70 | 40320 | 0 | 0 |
| 11025_stereo_cbr128 | 71 | 70 | 80640 | 0 | 0 |
| 11025_stereo_cbr160 | 71 | 70 | 80640 | 0 | 0 |
| 11025_stereo_cbr8 | 71 | 70 | 80640 | 0 | 0 |
| 11025_stereo_vbr0 | 71 | 70 | 80640 | 0 | 0 |
| 11025_stereo_vbr9 | 71 | 70 | 80640 | 0 | 0 |
| 12000_joint_cbr128 | 77 | 76 | 87552 | 0 | 0 |
| 12000_joint_cbr160 | 77 | 76 | 87552 | 0 | 0 |
| 12000_joint_cbr8 | 77 | 76 | 87552 | 0 | 0 |
| 12000_joint_vbr0 | 77 | 76 | 87552 | 0 | 0 |
| 12000_joint_vbr9 | 77 | 76 | 87552 | 0 | 0 |
| 12000_mono_cbr128 | 77 | 76 | 43776 | 0 | 0 |
| 12000_mono_cbr160 | 77 | 76 | 43776 | 0 | 0 |
| 12000_mono_cbr8 | 77 | 76 | 43776 | 0 | 0 |
| 12000_mono_vbr0 | 77 | 76 | 43776 | 0 | 0 |
| 12000_mono_vbr9 | 77 | 76 | 43776 | 0 | 0 |
| 12000_stereo_cbr128 | 77 | 76 | 87552 | 0 | 0 |
| 12000_stereo_cbr160 | 77 | 76 | 87552 | 0 | 0 |
| 12000_stereo_cbr8 | 77 | 76 | 87552 | 0 | 0 |
| 12000_stereo_vbr0 | 77 | 76 | 87552 | 0 | 0 |
| 12000_stereo_vbr9 | 77 | 76 | 87552 | 0 | 0 |
| 16000_joint_cbr128 | 102 | 101 | 116352 | 0 | 0 |
| 16000_joint_cbr160 | 102 | 101 | 116352 | 0 | 0 |
| 16000_joint_cbr8 | 102 | 101 | 116352 | 0 | 0 |
| 16000_joint_vbr0 | 102 | 101 | 116352 | 0 | 0 |
| 16000_joint_vbr9 | 102 | 101 | 116352 | 0 | 0 |
| 16000_mono_cbr128 | 102 | 101 | 58176 | 0 | 0 |
| 16000_mono_cbr160 | 102 | 101 | 58176 | 0 | 0 |
| 16000_mono_cbr8 | 102 | 101 | 58176 | 0 | 0 |
| 16000_mono_vbr0 | 102 | 101 | 58176 | 0 | 0 |
| 16000_mono_vbr9 | 102 | 101 | 58176 | 0 | 0 |
| 16000_stereo_cbr128 | 102 | 101 | 116352 | 0 | 0 |
| 16000_stereo_cbr160 | 102 | 101 | 116352 | 0 | 0 |
| 16000_stereo_cbr8 | 102 | 101 | 116352 | 0 | 0 |
| 16000_stereo_vbr0 | 102 | 101 | 116352 | 0 | 0 |
| 16000_stereo_vbr9 | 102 | 101 | 116352 | 0 | 0 |
| 22050_joint_cbr128 | 138 | 137 | 157824 | 0 | 0 |
| 22050_joint_cbr160 | 138 | 137 | 157824 | 0 | 0 |
| 22050_joint_cbr8 | 138 | 137 | 157824 | 0 | 0 |
| 22050_joint_vbr0 | 138 | 137 | 157824 | 0 | 0 |
| 22050_joint_vbr9 | 138 | 137 | 157824 | 0 | 0 |
| 22050_mono_cbr128 | 138 | 137 | 78912 | 0 | 0 |
| 22050_mono_cbr160 | 138 | 137 | 78912 | 0 | 0 |
| 22050_mono_cbr8 | 138 | 137 | 78912 | 0 | 0 |
| 22050_mono_vbr0 | 138 | 137 | 78912 | 0 | 0 |
| 22050_mono_vbr9 | 138 | 137 | 78912 | 0 | 0 |
| 22050_stereo_cbr128 | 138 | 137 | 157824 | 0 | 0 |
| 22050_stereo_cbr160 | 138 | 137 | 157824 | 0 | 0 |
| 22050_stereo_cbr8 | 138 | 137 | 157824 | 0 | 0 |
| 22050_stereo_vbr0 | 138 | 137 | 157824 | 0 | 0 |
| 22050_stereo_vbr9 | 138 | 137 | 157824 | 0 | 0 |
| 24000_joint_cbr128 | 150 | 149 | 171648 | 0 | 0 |
| 24000_joint_cbr160 | 150 | 149 | 171648 | 0 | 0 |
| 24000_joint_cbr8 | 150 | 149 | 171648 | 0 | 0 |
| 24000_joint_vbr0 | 150 | 149 | 171648 | 0 | 0 |
| 24000_joint_vbr9 | 150 | 149 | 171648 | 0 | 0 |
| 24000_mono_cbr128 | 150 | 149 | 85824 | 0 | 0 |
| 24000_mono_cbr160 | 150 | 149 | 85824 | 0 | 0 |
| 24000_mono_cbr8 | 150 | 149 | 85824 | 0 | 0 |
| 24000_mono_vbr0 | 150 | 149 | 85824 | 0 | 0 |
| 24000_mono_vbr9 | 150 | 149 | 85824 | 0 | 0 |
| 24000_stereo_cbr128 | 150 | 149 | 171648 | 0 | 0 |
| 24000_stereo_cbr160 | 150 | 149 | 171648 | 0 | 0 |
| 24000_stereo_cbr8 | 150 | 149 | 171648 | 0 | 0 |
| 24000_stereo_vbr0 | 150 | 149 | 171648 | 0 | 0 |
| 24000_stereo_vbr9 | 150 | 149 | 171648 | 0 | 0 |
| 32000_joint_cbr128 | 101 | 100 | 230400 | 0 | 0 |
| 32000_joint_cbr32 | 101 | 100 | 230400 | 0 | 0 |
| 32000_joint_cbr320 | 101 | 100 | 230400 | 0 | 0 |
| 32000_joint_vbr0 | 101 | 100 | 230400 | 0 | 0 |
| 32000_joint_vbr9 | 101 | 100 | 230400 | 0 | 0 |
| 32000_mono_cbr128 | 101 | 100 | 115200 | 0 | 0 |
| 32000_mono_cbr32 | 101 | 100 | 115200 | 0 | 0 |
| 32000_mono_cbr320 | 101 | 100 | 115200 | 0 | 0 |
| 32000_mono_vbr0 | 101 | 100 | 115200 | 0 | 0 |
| 32000_mono_vbr9 | 101 | 100 | 115200 | 0 | 0 |
| 32000_stereo_cbr128 | 101 | 100 | 230400 | 0 | 0 |
| 32000_stereo_cbr32 | 101 | 100 | 230400 | 0 | 0 |
| 32000_stereo_cbr320 | 101 | 100 | 230400 | 0 | 0 |
| 32000_stereo_vbr0 | 101 | 100 | 230400 | 0 | 0 |
| 32000_stereo_vbr9 | 101 | 100 | 230400 | 0 | 0 |
| 44100_joint_cbr128 | 137 | 136 | 313344 | 0 | 0 |
| 44100_joint_cbr32 | 137 | 136 | 313344 | 0 | 0 |
| 44100_joint_cbr320 | 137 | 136 | 313344 | 0 | 0 |
| 44100_joint_vbr0 | 137 | 136 | 313344 | 0 | 0 |
| 44100_joint_vbr9 | 137 | 136 | 313344 | 0 | 0 |
| 44100_mono_cbr128 | 137 | 136 | 156672 | 0 | 0 |
| 44100_mono_cbr32 | 137 | 136 | 156672 | 0 | 0 |
| 44100_mono_cbr320 | 137 | 136 | 156672 | 0 | 0 |
| 44100_mono_vbr0 | 137 | 136 | 156672 | 0 | 0 |
| 44100_mono_vbr9 | 137 | 136 | 156672 | 0 | 0 |
| 44100_stereo_cbr128 | 137 | 136 | 313344 | 0 | 0 |
| 44100_stereo_cbr32 | 137 | 136 | 313344 | 0 | 0 |
| 44100_stereo_cbr320 | 137 | 136 | 313344 | 0 | 0 |
| 44100_stereo_vbr0 | 137 | 136 | 313344 | 0 | 0 |
| 44100_stereo_vbr9 | 137 | 136 | 313344 | 0 | 0 |
| 48000_joint_cbr128 | 149 | 148 | 340992 | 0 | 0 |
| 48000_joint_cbr32 | 149 | 148 | 340992 | 0 | 0 |
| 48000_joint_cbr320 | 149 | 148 | 340992 | 0 | 0 |
| 48000_joint_vbr0 | 149 | 148 | 340992 | 0 | 0 |
| 48000_joint_vbr9 | 149 | 148 | 340992 | 0 | 0 |
| 48000_mono_cbr128 | 149 | 148 | 170496 | 0 | 0 |
| 48000_mono_cbr32 | 149 | 148 | 170496 | 0 | 0 |
| 48000_mono_cbr320 | 149 | 148 | 170496 | 0 | 0 |
| 48000_mono_vbr0 | 149 | 148 | 170496 | 0 | 0 |
| 48000_mono_vbr9 | 149 | 148 | 170496 | 0 | 0 |
| 48000_stereo_cbr128 | 149 | 148 | 340992 | 0 | 0 |
| 48000_stereo_cbr32 | 149 | 148 | 340992 | 0 | 0 |
| 48000_stereo_cbr320 | 149 | 148 | 340992 | 0 | 0 |
| 48000_stereo_vbr0 | 149 | 148 | 340992 | 0 | 0 |
| 48000_stereo_vbr9 | 149 | 148 | 340992 | 0 | 0 |
| 8000_joint_cbr128 | 53 | 52 | 59904 | 0 | 0 |
| 8000_joint_cbr160 | 53 | 52 | 59904 | 0 | 0 |
| 8000_joint_cbr8 | 53 | 52 | 59904 | 0 | 0 |
| 8000_joint_vbr0 | 53 | 52 | 59904 | 0 | 0 |
| 8000_joint_vbr9 | 53 | 52 | 59904 | 0 | 0 |
| 8000_mono_cbr128 | 53 | 52 | 29952 | 0 | 0 |
| 8000_mono_cbr160 | 53 | 52 | 29952 | 0 | 0 |
| 8000_mono_cbr8 | 53 | 52 | 29952 | 0 | 0 |
| 8000_mono_vbr0 | 53 | 52 | 29952 | 0 | 0 |
| 8000_mono_vbr9 | 53 | 52 | 29952 | 0 | 0 |
| 8000_stereo_cbr128 | 53 | 52 | 59904 | 0 | 0 |
| 8000_stereo_cbr160 | 53 | 52 | 59904 | 0 | 0 |
| 8000_stereo_cbr8 | 53 | 52 | 59904 | 0 | 0 |
| 8000_stereo_vbr0 | 53 | 52 | 59904 | 0 | 0 |
| 8000_stereo_vbr9 | 53 | 52 | 59904 | 0 | 0 |
| ILL2_center2 | 2 | 0 | 0 | 0 | 0 |
| ILL2_dual | 2 | 0 | 0 | 0 | 0 |
| ILL2_dynx22 | 2 | 0 | 0 | 0 | 0 |
| ILL2_dynx31 | 2 | 0 | 0 | 0 | 0 |
| ILL2_dynx32 | 2 | 0 | 0 | 0 | 0 |
| ILL2_ext_switching | 10 | 0 | 0 | 0 | 0 |
| ILL2_layer1 | 4 | 0 | 0 | 0 | 0 |
| ILL2_layer3 | 11 | 10 | 23040 | 0 | 0 |
| ILL2_mono | 2 | 0 | 0 | 0 | 0 |
| ILL2_multilingual | 2 | 0 | 0 | 0 | 0 |
| ILL2_overalloc1 | 2 | 0 | 0 | 0 | 0 |
| ILL2_overalloc2 | 2 | 0 | 0 | 0 | 0 |
| ILL2_prediction | 2 | 0 | 0 | 0 | 0 |
| ILL2_samples | 2 | 0 | 0 | 0 | 0 |
| ILL2_scf63 | 2 | 0 | 0 | 0 | 0 |
| ILL2_tca21 | 2 | 0 | 0 | 0 | 0 |
| ILL2_tca30 | 2 | 0 | 0 | 0 | 0 |
| ILL2_tca30_PC | 2 | 0 | 0 | 0 | 0 |
| ILL2_tca31_PC | 2 | 0 | 0 | 0 | 0 |
| ILL2_tca31_mtx0 | 2 | 0 | 0 | 0 | 0 |
| ILL2_tca31_mtx2 | 2 | 0 | 0 | 0 | 0 |
| ILL2_tca32_PC | 2 | 0 | 0 | 0 | 0 |
| ILL2_wrongcrc | 2 | 0 | 0 | 0 | 0 |
| ILL4_ext_id1 | 2 | 0 | 0 | 0 | 0 |
| ILL4_sync | 2 | 0 | 0 | 0 | 0 |
| ILL4_wrong_length1 | 2 | 0 | 0 | 0 | 0 |
| ILL4_wrong_length2 | 2 | 0 | 0 | 0 | 0 |
| ILL4_wrongcrc | 2 | 0 | 0 | 0 | 0 |
| M2L3_bitrate_16_all | 477 | 476 | 274176 | 0 | 0 |
| M2L3_bitrate_22_all | 477 | 476 | 274176 | 0 | 0 |
| M2L3_bitrate_24_all | 477 | 476 | 274176 | 0 | 0 |
| M2L3_compl24 | 213 | 212 | 122112 | 0 | 0 |
| M2L3_noise | 387 | 386 | 444672 | 0 | 0 |
| l1-fl1 | 50 | 0 | 0 | 0 | 0 |
| l1-fl2 | 50 | 0 | 0 | 0 | 0 |
| l1-fl3 | 50 | 0 | 0 | 0 | 0 |
| l1-fl4 | 50 | 0 | 0 | 0 | 0 |
| l1-fl5 | 50 | 0 | 0 | 0 | 0 |
| l1-fl6 | 50 | 0 | 0 | 0 | 0 |
| l1-fl7 | 64 | 0 | 0 | 0 | 0 |
| l1-fl8 | 50 | 0 | 0 | 0 | 0 |
| l2-fl10 | 50 | 0 | 0 | 0 | 0 |
| l2-fl11 | 50 | 0 | 0 | 0 | 0 |
| l2-fl12 | 50 | 0 | 0 | 0 | 0 |
| l2-fl13 | 50 | 0 | 0 | 0 | 0 |
| l2-fl14 | 17 | 0 | 0 | 0 | 0 |
| l2-fl15 | 17 | 0 | 0 | 0 | 0 |
| l2-fl16 | 64 | 0 | 0 | 0 | 0 |
| l2-nonstandard-fl1_fl2_ff | 6 | 0 | 0 | 0 | 0 |
| l2-nonstandard-free_format | 50 | 0 | 0 | 0 | 0 |
| l2-nonstandard-test32-size | 64 | 0 | 0 | 0 | 0 |
| l2-test32 | 64 | 0 | 0 | 0 | 0 |
| l3-compl | 218 | 216 | 248832 | 0 | 0 |
| l3-he_32khz | 151 | 150 | 172800 | 0 | 0 |
| l3-he_44khz | 411 | 410 | 472320 | 0 | 0 |
| l3-he_48khz | 151 | 150 | 172800 | 0 | 0 |
| l3-he_free | 69 | 68 | 156672 | 0 | 0 |
| l3-he_mode | 129 | 128 | 262656 | 0 | 0 |
| l3-hecommon | 31 | 30 | 69120 | 0 | 0 |
| l3-nonstandard-apetag | 2 | 0 | 0 | 0 | 0 |
| l3-nonstandard-big-iscf | 7 | 2 | 2304 | 0 | 0 |
| l3-nonstandard-compl-sideinfo-bigvalues | 218 | 212 | 244224 | 0 | 0 |
| l3-nonstandard-compl-sideinfo-blocktype | 218 | 212 | 244224 | 0 | 0 |
| l3-nonstandard-compl-sideinfo-size | 218 | 212 | 244224 | 0 | 0 |
| l3-nonstandard-he_44_48khz | 560 | 559 | 643968 | 0 | 0 |
| l3-nonstandard-id3v1 | 2 | 0 | 0 | 0 | 0 |
| l3-nonstandard-id3v1-apetag | 2 | 0 | 0 | 0 | 0 |
| l3-nonstandard-id3v2 | 2 | 0 | 0 | 0 | 0 |
| l3-nonstandard-id3v2-only | 2 | 0 | 0 | 0 | 0 |
| l3-nonstandard-sideinfo-size | 3 | 0 | 0 | 0 | 0 |
| l3-nonstandard-sin1k0db_lame_vbrtag | 318 | 317 | 730368 | 0 | 0 |
| l3-nonstandard-small | 2 | 0 | 0 | 0 | 0 |
| l3-nonstandard-vbrtag-corrupted | 2 | 0 | 0 | 0 | 0 |
| l3-nonstandard-vbrtag-empty | 2 | 1 | 2304 | 0 | 0 |
| l3-nonstandard-vbrtag-noframes | 2 | 1 | 2304 | 0 | 0 |
| l3-nonstandard-vbrtag-only | 2 | 1 | 2304 | 0 | 0 |
| l3-nonstandard-vbrtag-oob-read | 2 | 1 | 2304 | 0 | 0 |
| l3-si | 119 | 118 | 135936 | 0 | 0 |
| l3-si_block | 65 | 64 | 73728 | 0 | 0 |
| l3-si_huff | 76 | 75 | 86400 | 0 | 0 |
| l3-sin1k0db | 319 | 315 | 725760 | 0 | 0 |
| l3-test45 | 823 | 822 | 946944 | 0 | 0 |
| l3-test46 | 251 | 250 | 288000 | 0 | 0 |
| special_12000_mono_vbr9_silence | 67 | 66 | 38016 | 0 | 0 |
| special_22050_joint_vbr0_noxing | 118 | 117 | 134784 | 0 | 0 |
| special_24000_joint_cbr160_noreservoir | 129 | 128 | 147456 | 0 | 0 |
| special_32000_mono_cbr32_impulses | 115 | 114 | 131328 | 0 | 0 |
| special_44100_joint_cbr128_silence | 233 | 232 | 534528 | 0 | 0 |
| special_44100_stereo_cbr320_noise | 157 | 156 | 359424 | 0 | 0 |
| tag_id3v1_16000_mono_vbr0 | 102 | 100 | 57600 | 0 | 0 |
| tag_id3v1_44100_joint_cbr128 | 137 | 135 | 311040 | 0 | 0 |
| tag_id3v1_48000_stereo_vbr9 | 149 | 147 | 338688 | 0 | 0 |
| tag_id3v1_8000_joint_cbr8 | 53 | 51 | 58752 | 0 | 0 |
| tag_id3v2big_16000_mono_vbr0 | 102 | 101 | 58176 | 0 | 0 |
| tag_id3v2big_44100_joint_cbr128 | 137 | 136 | 313344 | 0 | 0 |
| tag_id3v2big_48000_stereo_vbr9 | 149 | 148 | 340992 | 0 | 0 |
| tag_id3v2big_8000_joint_cbr8 | 53 | 52 | 59904 | 0 | 0 |
| tag_id3v2big_id3v1_16000_mono_vbr0 | 102 | 100 | 57600 | 0 | 0 |
| tag_id3v2big_id3v1_44100_joint_cbr128 | 137 | 135 | 311040 | 0 | 0 |
| tag_id3v2big_id3v1_48000_stereo_vbr9 | 149 | 147 | 338688 | 0 | 0 |
| tag_id3v2big_id3v1_8000_joint_cbr8 | 53 | 51 | 58752 | 0 | 0 |
| tag_id3v2small_16000_mono_vbr0 | 102 | 101 | 58176 | 0 | 0 |
| tag_id3v2small_44100_joint_cbr128 | 137 | 136 | 313344 | 0 | 0 |
| tag_id3v2small_48000_stereo_vbr9 | 149 | 148 | 340992 | 0 | 0 |
| tag_id3v2small_8000_joint_cbr8 | 53 | 52 | 59904 | 0 | 0 |
