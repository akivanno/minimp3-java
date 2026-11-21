#!/usr/bin/env python3
# To the extent possible under law, the author(s) have dedicated all copyright and related and
# neighboring rights to this software to the public domain worldwide. This software is distributed
# without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
"""Generate the synthetic MP3 test corpus of minimp3-java.

Every signal is synthetic (ffmpeg lavfi sources: sweeps, noise, impulses, silence) and is encoded with
LAME through ffmpeg's libmp3lame encoder, so the corpus is free of copyright. The tags are written by
this script; the cover image is generated noise.

Usage: make_corpus.py --out DIR [--ffmpeg PATH] [--jobs N]

DIR receives the .mp3 files and corpus.tsv, which describes them (one line per file, tab-separated):
name, file, group, sample rate, channel mode, rate mode, requested kbps, actual kbps of the first audio
frame, MPEG version, tags, seconds. The script only writes files that are missing, so it can be rerun.
"""
import argparse
import concurrent.futures
import os
import random
import struct
import subprocess
import sys
import zlib

SAMPLE_RATES = {
    "MPEG-1": [32000, 44100, 48000],
    "MPEG-2": [16000, 22050, 24000],
    "MPEG-2.5": [8000, 11025, 12000],
}
# CBR minimum and maximum of each MPEG version (ISO 11172-3 / 13818-3), plus 128 kbps.
# LAME caps MPEG-2.5 at 64 kbps: corpus.tsv records the bit rate that was actually written.
CBR = {"MPEG-1": (32, 320), "MPEG-2": (8, 160), "MPEG-2.5": (8, 160)}
CHANNEL_MODES = ["mono", "stereo", "joint"]
GEN_RATE = 48000  # the signals are generated at 48 kHz and resampled to the target rate

SWEEP_S, NOISE_S, IMPULSE_S, SILENCE_S = 1.5, 0.75, 0.75, 0.5
TOTAL_S = SWEEP_S + NOISE_S + IMPULSE_S + SILENCE_S
BENCH_S = 240


def version_of(sr):
    for v, rates in SAMPLE_RATES.items():
        if sr in rates:
            return v
    raise ValueError(sr)


def sweep_expr(f0, f1, d):
    # exponential sweep from f0 to f1 Hz in d seconds
    k = f1 / f0
    return "0.7*sin(2*PI*%g*%g/log(%g)*(pow(%g,t/%g)-1))" % (f0, d, k, k, d)


def content_graph(channels, seed):
    """filter_complex for sweep + full-scale white noise + impulses + silence, at GEN_RATE."""
    if channels == 1:
        sweep = sweep_expr(20, 20000, SWEEP_S)
        imp = "if(eq(mod(n,4800),0),1,if(eq(mod(n,4800),2400),-1,0))"
        g = ("aevalsrc=exprs='%s':s=%d:d=%g[sw];" % (sweep, GEN_RATE, SWEEP_S)
             + "anoisesrc=r=%d:a=1:c=white:seed=%d:d=%g[nz];" % (GEN_RATE, seed, NOISE_S)
             + "aevalsrc=exprs='%s':s=%d:d=%g[im];" % (imp, GEN_RATE, IMPULSE_S)
             + "aevalsrc=exprs='0':s=%d:d=%g[si];" % (GEN_RATE, SILENCE_S))
    else:
        sweep = sweep_expr(20, 20000, SWEEP_S) + "|" + sweep_expr(20000, 20, SWEEP_S)
        imp = ("if(eq(mod(n,4800),0),1,if(eq(mod(n,4800),2400),-1,0))"
               + "|if(lt(mod(t,0.25),0.02),0.95*sin(2*PI*1000*t),0)")
        g = ("aevalsrc=exprs='%s':s=%d:d=%g[sw];" % (sweep, GEN_RATE, SWEEP_S)
             + "anoisesrc=r=%d:a=1:c=white:seed=%d:d=%g[n1];" % (GEN_RATE, seed, NOISE_S)
             + "anoisesrc=r=%d:a=1:c=white:seed=%d:d=%g[n2];" % (GEN_RATE, seed + 1, NOISE_S)
             + "[n1][n2]amerge=inputs=2[nz];"
             + "aevalsrc=exprs='%s':s=%d:d=%g[im];" % (imp, GEN_RATE, IMPULSE_S)
             + "aevalsrc=exprs='0|0':s=%d:d=%g[si];" % (GEN_RATE, SILENCE_S))
    return g + "[sw][nz][im][si]concat=n=4:v=0:a=1[out]"


def single_graph(kind, channels, seconds, seed):
    if kind == "silence":
        e = "|".join(["0"] * channels)
        return "aevalsrc=exprs='%s':s=%d:d=%g[out]" % (e, GEN_RATE, seconds)
    if kind == "noise":
        if channels == 1:
            return "anoisesrc=r=%d:a=1:c=white:seed=%d:d=%g[out]" % (GEN_RATE, seed, seconds)
        return ("anoisesrc=r=%d:a=1:c=white:seed=%d:d=%g[a];" % (GEN_RATE, seed, seconds)
                + "anoisesrc=r=%d:a=1:c=white:seed=%d:d=%g[b];[a][b]amerge=inputs=2[out]"
                % (GEN_RATE, seed + 1, seconds))
    if kind == "impulses":
        e = "|".join(["if(eq(mod(n,2400),0),1,0)"] * channels)
        return "aevalsrc=exprs='%s':s=%d:d=%g[out]" % (e, GEN_RATE, seconds)
    if kind == "music":
        # slowly changing tones with some noise: a stand-in for music in the benchmark
        e = ("0.25*sin(2*PI*(220+110*sin(0.1*t))*t)+0.2*sin(2*PI*660*t)*sin(0.5*t)"
             "+0.1*sin(2*PI*(3000+2000*sin(0.03*t))*t)+0.05*(random(0)-0.5)")
        e2 = e.replace("random(0)", "random(1)").replace("660", "550")
        exprs = e if channels == 1 else e + "|" + e2
        return "aevalsrc=exprs='%s':s=%d:d=%g[out]" % (exprs, GEN_RATE, seconds)
    raise ValueError(kind)


class Corpus:
    def __init__(self, out, ffmpeg):
        self.out = out
        self.ffmpeg = ffmpeg
        self.rows = []
        self.jobs = []

    def run(self, args):
        r = subprocess.run([self.ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y"] + args,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if r.returncode != 0:
            raise RuntimeError("ffmpeg failed: %s\n%s" % (" ".join(args), r.stderr.decode(errors="replace")))

    def source(self, wav, graph, sr, channels):
        """Render a filter graph to a float WAV at the target sample rate."""
        if os.path.exists(wav):
            return
        tmp = wav + ".tmp.wav"
        self.run(["-filter_complex", graph + ";[out]aresample=%d,aformat=sample_fmts=flt[o]" % sr,
                  "-map", "[o]", "-ac", str(channels), "-c:a", "pcm_f32le", tmp])
        os.replace(tmp, wav)

    def encode(self, wav, mp3, channel_mode, rate_mode, kbps, extra=()):
        if os.path.exists(mp3):
            return
        args = ["-i", wav, "-c:a", "libmp3lame"]
        if rate_mode == "cbr":
            args += ["-b:a", "%dk" % kbps]
        else:
            args += ["-q:a", str(kbps)]  # VBR -V<kbps>
        if channel_mode == "mono":
            args += ["-ac", "1"]
        else:
            args += ["-ac", "2", "-joint_stereo", "1" if channel_mode == "joint" else "0"]
        # no tags from ffmpeg: the tag variants are written by this script
        args += ["-id3v2_version", "0", "-write_id3v1", "0"] + list(extra)
        tmp = mp3 + ".tmp.mp3"
        self.run(args + ["-f", "mp3", tmp])
        os.replace(tmp, mp3)

    def add(self, name, group, sr, channel_mode, rate_mode, value, tags, seconds, make):
        self.rows.append((name, group, sr, channel_mode, rate_mode, value, tags, seconds))
        self.jobs.append(make)


def first_audio_kbps(path):
    """Bit rate of the first frame after the Xing/Info frame, read from its header."""
    br = {1: [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320],
          0: [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160]}
    data = open(path, "rb").read()
    i, found = 0, []
    while i < len(data) - 4 and len(found) < 2:
        if data[i] == 0xFF and (data[i + 1] & 0xE6) == 0xE2 and (data[i + 2] >> 4) not in (0, 15) \
                and (data[i + 2] >> 2) & 3 != 3:
            v = (data[i + 1] >> 3) & 3
            mpeg1 = v == 3
            kbps = br[1 if mpeg1 else 0][data[i + 2] >> 4]
            hz = {3: [44100, 48000, 32000], 2: [22050, 24000, 16000], 0: [11025, 12000, 8000]}[v][(data[i + 2] >> 2) & 3]
            size = (144000 if mpeg1 else 72000) * kbps // hz + ((data[i + 2] >> 1) & 1)
            found.append(kbps)
            i += size
        else:
            i += 1
    return found[-1] if found else 0


def id3v2_tag(big, seed):
    def frame(fid, payload):
        return fid.encode("ascii") + struct.pack(">I", len(payload)) + b"\0\0" + payload

    frames = [frame("TIT2", b"\0Synthetic test signal"), frame("TPE1", b"\0minimp3-java test corpus")]
    if big:
        frames.append(frame("APIC", b"\0image/bmp\0\x03cover\0" + bmp_noise(200, 200, seed)))
    body = b"".join(frames) + b"\0" * 1024  # padding
    n = len(body)
    size = bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F])
    return b"ID3\x03\x00\x00" + size + body


def bmp_noise(w, h, seed):
    """A 24-bit BMP of random pixels, with a few MPEG sync patterns hidden in it."""
    rnd = random.Random(seed)
    row = (w * 3 + 3) & ~3
    pixels = bytearray(rnd.getrandbits(8) for _ in range(row * h))
    for k in range(20):  # fake frame headers that the decoder must not lock on to
        p = rnd.randrange(0, len(pixels) - 4)
        pixels[p:p + 4] = b"\xff\xfb\x90\x64"
    header = b"BM" + struct.pack("<IHHI", 54 + len(pixels), 0, 0, 54)
    info = struct.pack("<IiiHHIIiiII", 40, w, h, 1, 24, 0, len(pixels), 2835, 2835, 0, 0)
    return header + info + bytes(pixels)


def id3v1_tag():
    def f(s, n):
        return s.encode("latin-1")[:n].ljust(n, b"\0")
    return b"TAG" + f("Synthetic test signal", 30) + f("minimp3-java", 30) + f("test corpus", 30) \
        + b"2026" + f("generated by make_corpus.py", 30) + b"\x0c"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True)
    ap.add_argument("--ffmpeg", default="ffmpeg")
    ap.add_argument("--jobs", type=int, default=os.cpu_count() or 4)
    args = ap.parse_args()
    out = os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    src = os.path.join(out, "src")
    os.makedirs(src, exist_ok=True)
    c = Corpus(out, args.ffmpeg)

    def mixed_source(sr, ch):
        wav = os.path.join(src, "mixed_%d_%d.wav" % (sr, ch))
        return wav, (lambda: c.source(wav, content_graph(ch, 1000 + sr % 977), sr, ch))

    # 1. every sample rate x rate mode x channel mode, with all the content types in each file
    sources = {}
    for version, rates in SAMPLE_RATES.items():
        lo, hi = CBR[version]
        for sr in rates:
            for cm in CHANNEL_MODES:
                ch = 1 if cm == "mono" else 2
                wav, mk = mixed_source(sr, ch)
                sources[wav] = mk
                for rate_mode, value in (("cbr", lo), ("cbr", hi), ("cbr", 128), ("vbr", 0), ("vbr", 9)):
                    tag = "cbr%d" % value if rate_mode == "cbr" else "vbr%d" % value
                    name = "%d_%s_%s" % (sr, cm, tag)
                    mp3 = os.path.join(out, name + ".mp3")
                    c.add(name, "matrix", sr, cm, rate_mode, value, "none", TOTAL_S,
                          (lambda w=wav, m=mp3, cm=cm, rm=rate_mode, v=value: c.encode(w, m, cm, rm, v)))

    # 2. single-content files and encoder options
    specials = [
        ("44100_joint_cbr128_silence", 44100, "joint", "cbr", 128, "silence", 6, ()),
        ("44100_stereo_cbr320_noise", 44100, "stereo", "cbr", 320, "noise", 4, ()),
        ("32000_mono_cbr32_impulses", 32000, "mono", "cbr", 32, "impulses", 4, ()),
        ("22050_joint_vbr0_noxing", 22050, "joint", "vbr", 0, "noise", 3, ("-write_xing", "0")),
        ("24000_joint_cbr160_noreservoir", 24000, "joint", "cbr", 160, "noise", 3, ("-reservoir", "0")),
        ("12000_mono_vbr9_silence", 12000, "mono", "vbr", 9, "silence", 3, ()),
    ]
    for name, sr, cm, rm, v, kind, secs, extra in specials:
        ch = 1 if cm == "mono" else 2
        wav = os.path.join(src, "%s_%d_%d_%g.wav" % (kind, sr, ch, secs))
        sources[wav] = (lambda w=wav, k=kind, ch=ch, s=secs, sr=sr: c.source(w, single_graph(k, ch, s, 7), sr, ch))
        mp3 = os.path.join(out, "special_" + name + ".mp3")
        c.add("special_" + name, "special", sr, cm, rm, v, "none", secs,
              (lambda w=wav, m=mp3, cm=cm, rm=rm, v=v, e=extra: c.encode(w, m, cm, rm, v, e)))

    # 3. tag variants of a few files of the matrix
    tag_bases = ["44100_joint_cbr128", "16000_mono_vbr0", "48000_stereo_vbr9", "8000_joint_cbr8"]
    tag_kinds = {
        "id3v2big": lambda base, seed: id3v2_tag(True, seed) + base,
        "id3v2small": lambda base, seed: id3v2_tag(False, seed) + base,
        "id3v1": lambda base, seed: base + id3v1_tag(),
        "id3v2big+id3v1": lambda base, seed: id3v2_tag(True, seed) + base + id3v1_tag(),
    }
    tag_rows = []
    for b in tag_bases:
        row = next(r for r in c.rows if r[0] == b)
        for kind, fn in tag_kinds.items():
            name = "tag_%s_%s" % (kind.replace("+", "_"), b)
            tag_rows.append((name, b, kind, fn))
            c.rows.append((name, "tags:" + b, row[2], row[3], row[4], row[5], kind, row[7]))

    # 4. benchmark files: 4 minutes, 44.1 kHz stereo (LAME's default joint stereo), 128 and 320 kbps
    bench_wav = os.path.join(src, "music_44100_2_%d.wav" % BENCH_S)
    sources[bench_wav] = lambda: c.source(bench_wav, single_graph("music", 2, BENCH_S, 3), 44100, 2)
    for kbps in (128, 320):
        name = "bench_44100_joint_cbr%d_%ds" % (kbps, BENCH_S)
        mp3 = os.path.join(out, name + ".mp3")
        c.add(name, "bench", 44100, "joint", "cbr", kbps, "none", BENCH_S,
              (lambda m=mp3, k=kbps: c.encode(bench_wav, m, "joint", "cbr", k)))

    with concurrent.futures.ThreadPoolExecutor(args.jobs) as ex:
        for f in [ex.submit(mk) for mk in sources.values()]:
            f.result()
        for f in [ex.submit(job) for job in c.jobs]:
            f.result()

    for name, base, kind, fn in tag_rows:
        path = os.path.join(out, name + ".mp3")
        if not os.path.exists(path):
            data = fn(open(os.path.join(out, base + ".mp3"), "rb").read(), zlib.crc32(name.encode()) & 0xFFFF)
            with open(path + ".tmp", "wb") as f:
                f.write(data)
            os.replace(path + ".tmp", path)

    tsv = os.path.join(out, "corpus.tsv")
    with open(tsv + ".tmp", "w", encoding="utf-8", newline="\n") as f:
        f.write("# name\tfile\tgroup\thz\tchannels\trate_mode\trequested\tactual_kbps\tversion\ttags\tseconds\n")
        for name, group, sr, cm, rm, v, tags, secs in c.rows:
            # the cover image of the tagged files may contain fake headers: read the untagged file
            base = group.split(":", 1)[1] if group.startswith("tags:") else name
            actual = first_audio_kbps(os.path.join(out, base + ".mp3"))
            group = group.split(":", 1)[0]
            f.write("\t".join(str(x) for x in (name, name + ".mp3", group, sr, cm, rm, v, actual,
                                               version_of(sr), tags, secs)) + "\n")
    os.replace(tsv + ".tmp", tsv)
    print("corpus: %d files in %s" % (len(c.rows), out))


if __name__ == "__main__":
    sys.exit(main())
