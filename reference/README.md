# Reference decoder and test data

The Java tests compare the port with the C original. This directory holds what produces the data they
need; the data itself (audio, reference outputs, the minimp3 checkout) is generated in
`build/testdata` and never committed.

| File | |
|---|---|
| `minimp3_ref.c` | the reference decoder: `minimp3.h` at the pinned commit, built with `MINIMP3_ONLY_MP3`, `MINIMP3_NO_SIMD` and `MINIMP3_FLOAT_OUTPUT`. It decodes a file with the loop the Java tests use and writes the result of every call of `mp3dec_decode_frame`, the frame information and the float samples |
| `make_corpus.py` | generates the synthetic corpus with ffmpeg (lavfi sources) and LAME (ffmpeg's libmp3lame) |
| `prepare_testdata.py` | does everything: fetches minimp3, builds the C programs, generates the corpus, runs the reference decoder and writes `build/testdata/manifest.tsv` |

## Requirements

- Python 3.8 or later
- git (unless `--minimp3` points to an existing checkout)
- a C compiler: gcc or clang (`--cc`, `$CC`, or found on `PATH`; on Windows also in the usual MSYS2
  locations, `C:\msys64` and `C:\tools\msys64`)
- ffmpeg built with libmp3lame (`ffmpeg -encoders | grep mp3` lists `libmp3lame`)

## Running

Gradle runs the script before the tests (`./gradlew test`). To run it by hand:

```
python3 reference/prepare_testdata.py --out build/testdata
```

Options: `--minimp3 DIR` (use an existing checkout), `--cc CC`, `--ffmpeg PATH`, `--jobs N`. The script
skips the steps whose results already exist, so it can be rerun cheaply.

It:

1. clones <https://github.com/lieff/minimp3> into `build/testdata/minimp3`, checks out commit
   `142823796ab8efa55e1bf11406028f3be77ecceb` (with `core.autocrlf=false`: the vectors are binary) and
   verifies the SHA-256 of `minimp3.h`;
2. builds `minimp3_ref.c` and the original test program `minimp3_test.c` with
   `-O2 -ffp-contract=off -fno-fast-math` and the three defines. `-ffp-contract=off` matters: a
   fused multiply-add rounds differently from Java's separate multiply and add;
3. generates the corpus in `build/testdata/corpus` (`corpus.tsv` describes every file);
4. runs `minimp3_ref` on every corpus file and every ISO vector (`ref/*.ref`, and `ref/*.nopcm.ref` with
   `pcm == NULL`), and `minimp3_test` on every vector (`vectors_c.tsv`: the PSNR the original computes);
5. writes `manifest.tsv`.

To decode a single file with the reference:

```
build/testdata/bin/minimp3_ref [--nopcm] input.mp3 output.ref
```

The output format is described at the top of `minimp3_ref.c`.

## The corpus

All signals are synthetic: 48 kHz sweeps from 20 Hz to 20 kHz, full-scale white noise (its peaks go past
full scale once decoded), impulses and tone bursts, and silence, resampled to the target rate and
encoded by LAME. The cover images of the tagged files are random pixels.

| Group | Files |
|---|---|
| `matrix` | every sample rate (MPEG-1: 32, 44.1, 48 kHz; MPEG-2: 16, 22.05, 24 kHz; MPEG-2.5: 8, 11.025, 12 kHz) × CBR minimum, CBR maximum, CBR 128 kbps, VBR `-V0`, VBR `-V9` (with Xing header) × mono, stereo, joint stereo; each file holds sweep, noise, impulses and silence (3.5 s) |
| `special` | single-content files and encoder options: silence only, full-scale noise at 320 kbps, impulses only, VBR without Xing header, no bit reservoir |
| `tags` | four files of the matrix with a large ID3v2 tag (a cover image of 117 KB), a small ID3v2 tag, an ID3v1 tag, and both |
| `bench` | 4 minutes, 44.1 kHz stereo, 128 and 320 kbps, for the benchmark |

LAME limits MPEG-2.5 to 64 kbps: the "128" and "160" kbps MPEG-2.5 files are written at 64 kbps.
`corpus.tsv` records the bit rate actually used.
