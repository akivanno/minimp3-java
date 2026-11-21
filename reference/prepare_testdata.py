#!/usr/bin/env python3
# To the extent possible under law, the author(s) have dedicated all copyright and related and
# neighboring rights to this software to the public domain worldwide. This software is distributed
# without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
"""Prepare the test data of minimp3-java.

1. Fetch minimp3 at the commit the port was translated from (or use --minimp3 DIR) and check minimp3.h.
2. Build the C reference decoder (minimp3_ref.c) and the original test program (minimp3_test.c) with
   MINIMP3_ONLY_MP3, MINIMP3_NO_SIMD and MINIMP3_FLOAT_OUTPUT.
3. Generate the synthetic corpus (make_corpus.py).
4. Run the reference decoder on every corpus file and every ISO vector, and the original test program on
   every vector (its PSNR is recorded for comparison).
5. Write manifest.tsv, which the Java tests read.

Usage: prepare_testdata.py [--out DIR] [--minimp3 DIR] [--cc CC] [--ffmpeg FFMPEG] [--jobs N]

Requirements: Python 3.8+, git (unless --minimp3 is given), a C compiler (gcc or clang; --cc or $CC),
ffmpeg built with libmp3lame. Steps whose results exist are skipped, so the script can be rerun.
"""
import argparse
import concurrent.futures
import hashlib
import os
import platform
import re
import shutil
import subprocess
import sys

MINIMP3_URL = "https://github.com/lieff/minimp3.git"
MINIMP3_COMMIT = "142823796ab8efa55e1bf11406028f3be77ecceb"
# SHA-256 of minimp3.h at MINIMP3_COMMIT, with line endings normalized to LF
MINIMP3_H_SHA256 = "f9592be69e8b94044e9da6f45f993c9708000abae38cf6f44b70c699de55b509"
DEFINES = ["-DMINIMP3_ONLY_MP3", "-DMINIMP3_NO_SIMD", "-DMINIMP3_FLOAT_OUTPUT"]
# No FMA contraction and no fast-math: the reference must round every float operation like Java does.
CFLAGS = ["-O2", "-ffp-contract=off", "-fno-fast-math", "-fno-strict-aliasing"]

HERE = os.path.dirname(os.path.abspath(__file__))


def log(msg):
    print("[prepare_testdata] " + msg, flush=True)


def run(cmd, **kw):
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **kw)
    if r.returncode != 0:
        raise SystemExit("command failed (%d): %s\n%s" % (r.returncode, " ".join(cmd), r.stdout.decode(errors="replace")))
    return r.stdout.decode(errors="replace")


def normalized_sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read().replace(b"\r\n", b"\n")).hexdigest()


def fetch_minimp3(dest):
    if not os.path.isdir(os.path.join(dest, ".git")):
        if os.path.exists(dest):
            shutil.rmtree(dest)
        log("cloning %s" % MINIMP3_URL)
        # autocrlf=false: the vectors are binary files and must not be touched
        run(["git", "-c", "core.autocrlf=false", "clone", "--quiet", MINIMP3_URL, dest])
    head = run(["git", "-C", dest, "rev-parse", "HEAD"]).strip()
    if head != MINIMP3_COMMIT:
        log("checking out %s" % MINIMP3_COMMIT)
        try:
            run(["git", "-C", dest, "-c", "core.autocrlf=false", "checkout", "--quiet", MINIMP3_COMMIT])
        except SystemExit:
            run(["git", "-C", dest, "fetch", "--quiet", "origin", MINIMP3_COMMIT])
            run(["git", "-C", dest, "-c", "core.autocrlf=false", "checkout", "--quiet", MINIMP3_COMMIT])


def find_cc(requested):
    candidates = [requested, os.environ.get("CC"), "gcc", "clang", "cc"]
    if os.name == "nt":  # the usual MSYS2 locations
        for root in ("C:/msys64", "C:/tools/msys64"):
            for env in ("ucrt64", "mingw64", "clang64"):
                for name in ("gcc.exe", "clang.exe"):
                    candidates.append(os.path.join(root, env, "bin", name))
    for cc in candidates:
        if cc and shutil.which(cc):
            return shutil.which(cc)
    raise SystemExit("no C compiler found: install gcc or clang, or pass --cc")


def exe(path):
    return path + (".exe" if os.name == "nt" else "")


def build(cc_request, source, deps, out, include):
    """Compiles source unless out is newer than it and deps. Returns the compiler's version line."""
    info = out + ".compiler.txt"
    newest = max(os.path.getmtime(s) for s in [source] + deps)
    if os.path.exists(out) and os.path.exists(info) and os.path.getmtime(out) >= newest:
        with open(info, encoding="utf-8") as f:
            return f.read().strip()
    cc = find_cc(cc_request)
    cmd = [cc] + CFLAGS + DEFINES + ["-I", include, source, "-o", out, "-lm"]
    if os.name == "nt":
        cmd.insert(1, "-static")  # MinGW: no runtime DLLs needed next to the executable
    log("building %s with %s" % (os.path.basename(out), cc))
    # the compiler may need its own directory on PATH (MSYS2)
    env = dict(os.environ, PATH=os.path.dirname(cc) + os.pathsep + os.environ.get("PATH", ""))
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env)
    if r.returncode != 0:
        raise SystemExit("compilation failed: %s%s%s" % (" ".join(cmd), os.linesep, r.stdout.decode(errors="replace")))
    version = subprocess.run([cc, "--version"], stdout=subprocess.PIPE, env=env).stdout.decode(errors="replace")
    version = version.splitlines()[0].strip() if version else cc
    with open(info, "w", encoding="utf-8") as f:
        f.write(version)
    return version


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.path.join(HERE, "..", "build", "testdata"))
    ap.add_argument("--minimp3", help="an existing checkout of minimp3 at the pinned commit")
    ap.add_argument("--cc")
    ap.add_argument("--ffmpeg", default="ffmpeg")
    ap.add_argument("--jobs", type=int, default=os.cpu_count() or 4)
    args = ap.parse_args()

    out = os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    stamp = os.path.join(out, "manifest.tsv")

    # 1. minimp3
    src = os.path.abspath(args.minimp3) if args.minimp3 else os.path.join(out, "minimp3")
    if not args.minimp3:
        fetch_minimp3(src)
    h = os.path.join(src, "minimp3.h")
    if normalized_sha256(h) != MINIMP3_H_SHA256:
        raise SystemExit("%s is not minimp3.h at commit %s" % (h, MINIMP3_COMMIT))
    log("minimp3.h at %s verified" % MINIMP3_COMMIT[:12])

    # 2. C programs
    bindir = os.path.join(out, "bin")
    os.makedirs(bindir, exist_ok=True)
    ref = exe(os.path.join(bindir, "minimp3_ref"))
    test = exe(os.path.join(bindir, "minimp3_test"))
    compiler = build(args.cc, os.path.join(HERE, "minimp3_ref.c"), [h], ref, src)
    build(args.cc, os.path.join(src, "minimp3_test.c"), [h, os.path.join(src, "minimp3_ex.h")], test, src)
    log("compiler: " + compiler)

    # 3. corpus
    corpus = os.path.join(out, "corpus")
    log("generating the corpus")
    run([sys.executable, os.path.join(HERE, "make_corpus.py"), "--out", corpus, "--ffmpeg", args.ffmpeg,
         "--jobs", str(args.jobs)])
    rows = []
    with open(os.path.join(corpus, "corpus.tsv"), encoding="utf-8") as f:
        for line in f:
            if not line.startswith("#") and line.strip():
                rows.append(line.rstrip("\n").split("\t"))

    # 4. reference outputs
    refdir = os.path.join(out, "ref")
    os.makedirs(refdir, exist_ok=True)
    vectors_dir = os.path.join(src, "vectors")
    vectors = sorted(n[:-4] for n in os.listdir(vectors_dir) if n.endswith(".bit"))
    jobs = []
    for r in rows:
        name, group = r[0], r[2]
        if group == "bench":
            continue  # the benchmark files are only decoded by Java
        jobs.append((os.path.join(corpus, r[1]), "corpus_" + name))
    for v in vectors:
        jobs.append((os.path.join(vectors_dir, v + ".bit"), "vector_" + v))

    def reference(job):
        mp3, key = job
        for flag, suffix in (([], ".ref"), (["--nopcm"], ".nopcm.ref")):
            target = os.path.join(refdir, key + suffix)
            if not os.path.exists(target) or os.path.getmtime(target) < max(os.path.getmtime(mp3), os.path.getmtime(ref)):
                run([ref] + flag + [mp3, target + ".tmp"])
                os.replace(target + ".tmp", target)

    def original_test(v):
        r = subprocess.run([test, os.path.join(vectors_dir, v + ".bit"), os.path.join(vectors_dir, v + ".pcm")],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, cwd=src)
        text = r.stdout.decode(errors="replace").strip().replace("\t", " ").replace("\n", " | ")
        m = re.search(r"PSNR=([0-9.]+)", text)
        return v, r.returncode, m.group(1) if m else "", text

    log("running the reference decoder on %d files" % len(jobs))
    with concurrent.futures.ThreadPoolExecutor(args.jobs) as ex:
        for f in [ex.submit(reference, j) for j in jobs]:
            f.result()
        results = [f.result() for f in [ex.submit(original_test, v) for v in vectors]]

    with open(os.path.join(out, "vectors_c.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("# vector\texit_code\tpsnr\toutput of minimp3_test (%s)\n" % " ".join(DEFINES))
        for v, code, psnr, text in results:
            f.write("%s\t%d\t%s\t%s\n" % (v, code, psnr, text))

    # 5. manifest
    with open(stamp + ".tmp", "w", encoding="utf-8", newline="\n") as f:
        f.write("# minimp3-java test data. kind\tname\tmp3\treference\tnopcm reference\tattributes\n")
        f.write("#commit\t%s\n" % MINIMP3_COMMIT)
        f.write("#compiler\t%s %s\n" % (compiler, " ".join(CFLAGS + DEFINES)))
        f.write("#host\t%s %s\n" % (platform.system(), platform.machine()))
        f.write("#minimp3\t%s\n" % src.replace(os.sep, "/"))
        for r in rows:
            name, group = r[0], r[2]
            attrs = "group=%s;hz=%s;channels=%s;rate=%s%s;kbps=%s;version=%s;tags=%s;seconds=%s" % (
                group, r[3], r[4], r[5], r[6], r[7], r[8], r[9], r[10])
            refs = ("", "") if group == "bench" else ("ref/corpus_%s.ref" % name, "ref/corpus_%s.nopcm.ref" % name)
            f.write("\t".join(["corpus", name, "corpus/" + r[1], refs[0], refs[1], attrs]) + "\n")
        for v in vectors:
            pcm = os.path.join(vectors_dir, v + ".pcm")
            attrs = "pcm=%s" % (pcm.replace(os.sep, "/") if os.path.exists(pcm) else "")
            f.write("\t".join(["vector", v, (os.path.join(vectors_dir, v + ".bit")).replace(os.sep, "/"),
                               "ref/vector_%s.ref" % v, "ref/vector_%s.nopcm.ref" % v, attrs]) + "\n")
    os.replace(stamp + ".tmp", stamp)
    log("done: %s" % stamp)


if __name__ == "__main__":
    main()
