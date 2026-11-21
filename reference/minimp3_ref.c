/*
 * minimp3_ref: the reference decoder for the minimp3-java tests.
 *
 * It decodes a file with minimp3.h, built with the same options as the Java port, and writes what
 * every call of mp3dec_decode_frame returned, so that the Java decoder can be compared with it.
 *
 * Usage: minimp3_ref [--nopcm] input.mp3 output.ref
 *
 *   --nopcm  pass pcm == NULL, so that only the frame information is read (no decoding)
 *
 * The caller works as the mod does: the whole file is in memory, mp3dec_decode_frame is called on the
 * remaining bytes and the position advances by frame_bytes until frame_bytes is 0. The same
 * mp3dec_frame_info_t is reused for every call, so fields that a call does not write keep their values.
 *
 * Output format (all integers are 32-bit little-endian, floats are IEEE 754 binary32 little-endian):
 *   "MP3REF01", or "MP3REFH1" with --nopcm       8 bytes, magic
 *   for every call:
 *     samples frame_bytes frame_offset channels hz layer bitrate_kbps    7 int32
 *     samples * channels floats                                          only if samples > 0
 *
 * To the extent possible under law, the author(s) have dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is distributed
 * without any warranty. See <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
#define MINIMP3_ONLY_MP3
#define MINIMP3_NO_SIMD
#define MINIMP3_FLOAT_OUTPUT
#define MINIMP3_IMPLEMENTATION
#include "minimp3.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void put_u32(FILE *f, uint32_t v)
{
    unsigned char b[4];
    b[0] = (unsigned char)(v);
    b[1] = (unsigned char)(v >> 8);
    b[2] = (unsigned char)(v >> 16);
    b[3] = (unsigned char)(v >> 24);
    if (fwrite(b, 1, 4, f) != 4)
    {
        fprintf(stderr, "error: write failed\n");
        exit(1);
    }
}

static void put_i32(FILE *f, int v)
{
    put_u32(f, (uint32_t)v);
}

static void put_f32(FILE *f, float v)
{
    uint32_t u;
    memcpy(&u, &v, 4);
    put_u32(f, u);
}

int main(int argc, char **argv)
{
    static mp3dec_t dec;
    mp3dec_frame_info_t info;
    mp3d_sample_t pcm[MINIMP3_MAX_SAMPLES_PER_FRAME];
    const char *in_name, *out_name;
    unsigned char *buf;
    long size, pos = 0;
    int nopcm = 0, argi = 1;
    FILE *in, *out;

    if (argc > 1 && !strcmp(argv[1], "--nopcm"))
    {
        nopcm = 1;
        argi++;
    }
    if (argc - argi != 2)
    {
        fprintf(stderr, "usage: minimp3_ref [--nopcm] input.mp3 output.ref\n");
        return 2;
    }
    in_name = argv[argi];
    out_name = argv[argi + 1];

    in = fopen(in_name, "rb");
    if (!in || fseek(in, 0, SEEK_END) || (size = ftell(in)) < 0 || fseek(in, 0, SEEK_SET))
    {
        fprintf(stderr, "error: cannot read %s\n", in_name);
        return 1;
    }
    buf = (unsigned char *)malloc(size ? (size_t)size : 1);
    if (!buf || (long)fread(buf, 1, (size_t)size, in) != size)
    {
        fprintf(stderr, "error: cannot read %s\n", in_name);
        return 1;
    }
    fclose(in);

    out = fopen(out_name, "wb");
    if (!out)
    {
        fprintf(stderr, "error: cannot write %s\n", out_name);
        return 1;
    }
    if (fwrite(nopcm ? "MP3REFH1" : "MP3REF01", 1, 8, out) != 8)
        return 1;

    memset(&info, 0, sizeof(info));
    mp3dec_init(&dec);
    for (;;)
    {
        int i, samples = mp3dec_decode_frame(&dec, buf + pos, (int)(size - pos), nopcm ? NULL : pcm, &info);
        put_i32(out, samples);
        put_i32(out, info.frame_bytes);
        put_i32(out, info.frame_offset);
        put_i32(out, info.channels);
        put_i32(out, info.hz);
        put_i32(out, info.layer);
        put_i32(out, info.bitrate_kbps);
        if (samples > 0 && !nopcm)
        {
            for (i = 0; i < samples * info.channels; i++)
                put_f32(out, pcm[i]);
        }
        if (info.frame_bytes <= 0)
            break;
        pos += info.frame_bytes;
    }
    if (fclose(out))
        return 1;
    free(buf);
    return 0;
}
